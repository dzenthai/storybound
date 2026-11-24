package dev.dzenthai.storybound.module.scene;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.compat.cutscene.Cutscenes;
import dev.dzenthai.storybound.core.StoryException;
import dev.dzenthai.storybound.core.logic.Actions;
import dev.dzenthai.storybound.core.logic.OnFail;
import dev.dzenthai.storybound.core.logic.StoryContext;
import dev.dzenthai.storybound.core.logic.StoryTrace;
import dev.dzenthai.storybound.core.logic.StructureRef;
import dev.dzenthai.storybound.core.logic.action.ScreenFade;
import dev.dzenthai.storybound.core.logic.action.SceneActions;
import dev.dzenthai.storybound.core.logic.trigger.TriggerDispatcher;
import dev.dzenthai.storybound.core.state.StoryScope;
import dev.dzenthai.storybound.core.state.StoryState;
import dev.dzenthai.storybound.core.state.StoryValue;
import dev.dzenthai.storybound.module.dialogue.DialogueRegistries;
import dev.dzenthai.storybound.module.dialogue.Dialogues;
import dev.dzenthai.storybound.module.dialogue.NpcBinding;
import dev.dzenthai.storybound.module.dialogue.NpcBindings;
import dev.dzenthai.storybound.module.dialogue.NpcParts;
import dev.dzenthai.storybound.module.music.StoryMusic;
import dev.dzenthai.storybound.module.waypoint.ClientWaypoint;
import dev.dzenthai.storybound.module.waypoint.StructureLocator;
import dev.dzenthai.storybound.module.waypoint.Waypoints;
import dev.dzenthai.storybound.network.ScenePayloads;
import dev.dzenthai.storybound.network.StoryboundNetwork;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundClearTitlesPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityTeleportEvent;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Scenes as they play, on the server. A scene's places are worked out once, when it starts, for the player watching it
 * (a shot whose anchor is not found, is in another dimension or lies beyond what the player's client has loaded is left
 * out, and the trace says why); the client gets the shots and flies the camera, the server keeps the clock and plays
 * what happens at its seconds (titles, lines, sounds, fades, actions). One scene at a time: a scene asked for while the
 * player talks or watches one waits its turn. While it plays the player is kept safe ({@code invulnerable}), mobs near
 * leave them and the villagers alone and no monster spawns there ({@code world: calm}). A scene is remembered until it
 * ends, so a player who leaves the game (or a server that restarts) sees it again from the start on coming back.
 */
public final class Scenes {
    /** How near the watcher mobs are calmed and monsters kept from spawning. */
    static final double CALM_RADIUS = 64;
    /** How far a scene's places may be: what the client has loaded, a chunk short of the view distance. */
    static final int MARGIN_CHUNKS = 1;
    /** A fade between shots: down to black before the cut, back up after it, in ticks. */
    static final int FADE_TICKS = 8;
    /** Ticks after coming back before a scene left unfinished starts again: the world is drawn first. */
    static final int REPLAY_DELAY = 40;
    /** Ticks a line stays at least, and for each character of its words. */
    static final int LINE_MIN_TICKS = 50;
    static final double LINE_TICKS_PER_CHAR = 1.2;
    /** The title's fade in, stay and fade out, as a quest's on screen. */
    static final int TITLE_IN = 15;
    static final int TITLE_STAY = 70;
    static final int TITLE_OUT = 25;
    /** The watcher's unfinished scene, in the player's own scope. */
    static final ResourceLocation PENDING = Storybound.id("scene/pending");

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static final Map<UUID, Deque<ResourceLocation>> QUEUED = new HashMap<>();
    private static final Map<UUID, Integer> REPLAYS = new HashMap<>();
    private static int nextSession = 1;

    private Scenes() {}

    /** Something of the scene at its tick: a cue (a title, a line...), a fade between shots, or a talk. */
    record Timed(int tick, SceneDefinition.@Nullable Cue cue, @Nullable ScreenFade fade, @Nullable Talk talk) {}

    /** A talk of the scene: the dialogue, and who the player talks to (the shot's anchor), or nobody found. */
    record Talk(ResourceLocation dialogue, @Nullable Entity speaker) {}

    static final class Session {
        final int id;
        final ResourceLocation scene;
        final SceneDefinition definition;
        final int total;
        final List<Timed> timeline;
        final @Nullable Runnable afterwards;
        int tick;
        int next;
        /** A talk of the scene is going on: its clock waits. */
        boolean talking;
        Optional<ResourceLocation> music;
        @Nullable ResourceLocation lastVoice;
        @Nullable ResourceLocation lastSound;
        @Nullable SoundSource lastSoundSource;

        Session(int id, ResourceLocation scene, SceneDefinition definition, int total, List<Timed> timeline, @Nullable Runnable afterwards) {
            this.id = id;
            this.scene = scene;
            this.definition = definition;
            this.total = total;
            this.timeline = timeline;
            this.afterwards = afterwards;
            this.music = definition.music();
        }
    }

    public static void register(IEventBus gameBus) {
        gameBus.addListener(Scenes::onServerTick);
        gameBus.addListener(Scenes::onDamage);
        gameBus.addListener(Scenes::onTarget);
        gameBus.addListener(Scenes::onSpawn);
        gameBus.addListener(Scenes::onDeath);
        gameBus.addListener(Scenes::onDimension);
        gameBus.addListener(Scenes::onCommandTeleport);
        gameBus.addListener(Scenes::onLoggedIn);
        gameBus.addListener(Scenes::onLoggedOut);
        gameBus.addListener((ServerStoppedEvent event) -> clear());
    }

    /** The scene the player watches now, or null. */
    public static @Nullable ResourceLocation watching(ServerPlayer player) {
        Session session = SESSIONS.get(player.getUUID());
        return session != null ? session.scene : null;
    }

    /** Plays the scene for the player now, or after what they talk or watch. */
    public static void play(ServerPlayer player, ResourceLocation scene) {
        if (busy(player)) {
            QUEUED.computeIfAbsent(player.getUUID(), uuid -> new ArrayDeque<>()).add(scene);
            StoryTrace.note(player, "scene " + scene, "waits its turn: the player " + (SESSIONS.containsKey(player.getUUID()) ? "watches another scene" : "talks"));
            return;
        }
        start(player, scene);
    }

    private static boolean busy(ServerPlayer player) {
        return SESSIONS.containsKey(player.getUUID()) || Dialogues.state(player).isPresent() || Cutscenes.watching(player) != null;
    }

    /**
     * A dialogue's scene node: the scene plays now, in the middle of the talk (which waits), and {@code afterwards} goes
     * on with the talk however the scene ends. An unknown scene goes on at once.
     */
    public static void playInDialogue(ServerPlayer player, ResourceLocation scene, Runnable afterwards) {
        if (SESSIONS.containsKey(player.getUUID()) || SceneRegistries.SCENES.serverStore().getOrNull(scene) == null) {
            StoryTrace.note(player, "scene " + scene, "not played in the dialogue: " + (SESSIONS.containsKey(player.getUUID())
                    ? "another scene plays" : "no such scene is loaded"));
            afterwards.run();
            return;
        }
        start(player, scene, afterwards);
    }

    /** Whether a dialogue may not start now: the player watches a scene that is not in one of its own talks. */
    public static boolean keepsFromTalking(ServerPlayer player) {
        Session session = SESSIONS.get(player.getUUID());
        return session != null && !session.talking;
    }

    /** The music of the scene the player watches, over every other track; empty when none plays or it has none. */
    public static Optional<ResourceLocation> music(ServerPlayer player) {
        Session session = SESSIONS.get(player.getUUID());
        return session != null ? session.music : Optional.empty();
    }

    /** Skips the rest of the scene the player watches, running any remaining cue actions and its on_end. */
    public static void skip(ServerPlayer player) {
        Session session = SESSIONS.get(player.getUUID());
        if (session != null) {
            skip(player, session.id);
        }
    }

    /** Skips the rest of the given scene session when the player is watching it (and not in one of its talks). */
    public static void skip(ServerPlayer player, int sessionId) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null || session.id != sessionId || session.talking) {
            return;
        }
        if (player.connection != null) {
            player.connection.send(new ClientboundClearTitlesPacket(true));
        }
        while (session.next < session.timeline.size() && SESSIONS.get(player.getUUID()) == session) {
            Timed due = session.timeline.get(session.next++);
            if (due.cue() != null && !due.cue().actions().isEmpty()) {
                try {
                    Actions.runAll(due.cue().actions(), StoryContext.of(player), "scene " + session.scene, OnFail.ABORT);
                } catch (RuntimeException e) {
                    Storybound.LOGGER.error("Storybound: scene {} failed at tick {}", session.scene, due.tick(), e);
                }
            }
        }
        if (SESSIONS.get(player.getUUID()) == session) {
            finish(player, session, SceneLogic.Reason.FINISH, true);
        }
    }

    private static void start(ServerPlayer player, ResourceLocation scene) {
        start(player, scene, null);
    }

    private static void start(ServerPlayer player, ResourceLocation scene, @Nullable Runnable afterwards) {
        SceneDefinition definition = SceneRegistries.SCENES.serverStore().getOrNull(scene);
        if (definition == null) {
            StoryTrace.note(player, "scene " + scene, "not played: no such scene is loaded");
            forgetPending(player);
            if (afterwards != null) {
                afterwards.run();
            }
            return;
        }
        List<ScenePayloads.Shot> shots = new ArrayList<>();
        List<Timed> timeline = new ArrayList<>();
        int at = 0;
        boolean talks = false;
        for (int index = 0; index < definition.shots().size(); index++) {
            SceneDefinition.Shot shot = definition.shots().get(index);
            if (shot.talk()) {
                // A talk takes no time of the scene's clock: the clock waits while it goes on.
                Found anchor = shot.anchor() instanceof SceneDefinition.PlayerAnchor ? null : find(player, shot.anchor());
                timeline.add(new Timed(at, null, null, new Talk(shot.dialogue().orElseThrow(), anchor != null ? anchor.entity() : null)));
                talks = true;
                continue;
            }
            SceneDefinition.Camera camera = shot.camera().orElseThrow();
            Planned planned = plan(player, shot.anchor(), camera, shot.transition());
            if (planned.skipped() != null) {
                StoryTrace.note(player, "scene " + scene, "shot " + (index + 1) + " left out: " + planned.skipped());
                continue;
            }
            int ticks = Math.max(1, Math.round(camera.seconds() * 20));
            boolean first = shots.isEmpty();
            shots.add(planned.shot().withTime(at, ticks));
            if (shot.transition() == SceneDefinition.Transition.FADE) {
                float edge = FADE_TICKS / 20f;
                // Down to black just before the cut, up after it; the first shot comes up out of black.
                timeline.add(new Timed(Math.max(0, at - (first ? 0 : FADE_TICKS)), null,
                        new ScreenFade(first ? 0 : edge, 0.05f, edge, ScreenFade.BLACK), null));
            }
            for (SceneDefinition.Cue cue : shot.during()) {
                timeline.add(new Timed(at + Math.min(ticks, Math.round(cue.at() * 20)), cue, null, null));
            }
            at += ticks;
        }
        timeline.sort(Comparator.comparingInt(Timed::tick));
        Session session = new Session(nextSession++, scene, definition, at, timeline, afterwards);
        StoryTrace.event(player, "scene_started", "scene", scene.toString(), "shots", shots.size() + " of " + definition.shots().size());
        setPending(player, scene);
        SESSIONS.put(player.getUUID(), session);
        if (shots.isEmpty() && !talks) {
            // Nothing could be shown: it still happened, so what waits for its end does not wait for ever.
            finish(player, session, SceneLogic.Reason.FINISH);
            return;
        }
        if (definition.world() == SceneDefinition.World.CALM) {
            calm(player);
        }
        if (!shots.isEmpty()) {
            // Send any instant-black opening fade (tick 0, in == 0) before switching the camera so frame 0 never flashes.
            ScreenFade openingFade = null;
            for (Timed entry : timeline) {
                if (entry.tick() > 0) {
                    break;
                }
                if (entry.fade() != null && entry.fade().in() <= 0f) {
                    openingFade = entry.fade();
                } else if (entry.cue() != null && entry.cue().fade().isPresent() && entry.cue().fade().get().in() <= 0f) {
                    openingFade = entry.cue().fade().get();
                }
            }
            if (openingFade != null) {
                openingFade.send(player);
            }
            StoryboundNetwork.sendToPlayer(player, new ScenePayloads.Play(session.id, definition.letterbox(), shots));
        }
        runDue(player, session);
        if (SESSIONS.get(player.getUUID()) == session) {
            StoryMusic.sync(player);
        }
    }

    /** A shot placed for this player, or why it cannot be. */
    record Planned(@Nullable ScenePayloads.Shot shot, @Nullable String skipped) {
        static Planned skip(String why) {
            return new Planned(null, why);
        }
    }

    /** An anchor found: where it is, which way it faces, the entity it is (or none). */
    public record Found(Vec3 pos, float yaw, @Nullable Entity entity) {
        public Vec3 eye() {
            return entity != null ? entity.getEyePosition() : pos;
        }
    }

    private static Planned plan(ServerPlayer player, SceneDefinition.Anchor anchorOf, SceneDefinition.Camera camera, SceneDefinition.Transition transition) {
        Found anchor = find(player, anchorOf);
        if (anchor == null) {
            return Planned.skip("its anchor " + describe(anchorOf) + " is not found near the player");
        }
        Vec3 from = place(anchor, camera.from());
        List<Vec3> via = camera.via().stream().map(point -> place(anchor, point)).toList();
        Vec3 to = place(anchor, camera.to());
        int look;
        Vec3 point;
        int entity = -1;
        switch (camera.look()) {
            case SceneDefinition.AlongPath path -> {
                look = ScenePayloads.LOOK_PATH;
                Vec3 dir = ScenePath.of(from, via, to).tangentAt(0.0);
                point = dir.lengthSqr() > 1.0e-6 ? from.add(dir) : anchor.eye();
            }
            case SceneDefinition.AtOther other -> {
                Found target = find(player, other.anchor());
                Found seen = target != null ? target : anchor;
                look = seen.entity() != null ? ScenePayloads.LOOK_ENTITY : ScenePayloads.LOOK_POINT;
                point = seen.eye();
                entity = seen.entity() != null ? seen.entity().getId() : -1;
            }
            default -> {
                look = anchor.entity() != null ? ScenePayloads.LOOK_ENTITY : ScenePayloads.LOOK_POINT;
                point = anchor.eye();
                entity = anchor.entity() != null ? anchor.entity().getId() : -1;
            }
        }
        double reach = Math.max(2, player.server.getPlayerList().getViewDistance() - MARGIN_CHUNKS) * 16.0;
        List<Vec3> spots = new ArrayList<>(via.size() + 3);
        spots.add(from);
        spots.addAll(via);
        spots.add(to);
        spots.add(anchor.pos());
        for (Vec3 spot : spots) {
            if (Math.hypot(spot.x - player.getX(), spot.z - player.getZ()) > reach) {
                return Planned.skip("it is " + (int) Math.hypot(spot.x - player.getX(), spot.z - player.getZ())
                        + " blocks away, beyond what the player's world has loaded (" + (int) reach + ")");
            }
        }
        return new Planned(new ScenePayloads.Shot(from, via, to, look, point, entity, 0, 1, camera.ease().ordinal(),
                transition.ordinal(), camera.fov().orElse(0f)), null);
    }

    /** A point given as right, up, forward of the anchor, turned the way it faces. */
    static Vec3 place(Found anchor, Vec3 offset) {
        double yaw = Math.toRadians(anchor.yaw());
        Vec3 forward = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
        Vec3 right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
        return anchor.pos().add(right.scale(offset.x)).add(0, offset.y, 0).add(forward.scale(offset.z));
    }

    private static String describe(SceneDefinition.Anchor anchor) {
        return switch (anchor) {
            case SceneDefinition.PlayerAnchor player -> "player";
            case SceneDefinition.NpcAnchor npc -> "npc " + npc.npc();
            case SceneDefinition.EntityAnchor entity -> "entity " + entity.entity();
            case SceneDefinition.StructureAnchor structure -> "structure " + structure.structure();
            case SceneDefinition.WaypointAnchor waypoint -> "waypoint " + waypoint.waypoint();
        };
    }

    /** Where the anchor is for this player, in their dimension; null when it is not there. */
    public static @Nullable Found find(ServerPlayer player, SceneDefinition.Anchor anchor) {
        ServerLevel level = player.serverLevel();
        return switch (anchor) {
            case SceneDefinition.PlayerAnchor own -> new Found(player.position(), player.getYRot(), player);
            case SceneDefinition.EntityAnchor selector -> {
                Entity entity = SceneActions.nearest(StoryContext.of(player), selector.entity());
                yield entity != null ? new Found(entity.position(), entity.getYRot(), entity) : null;
            }
            case SceneDefinition.NpcAnchor npc -> {
                Entity entity = npc(player, npc.npc());
                yield entity != null ? new Found(entity.position(), entity.getYRot(), entity) : null;
            }
            case SceneDefinition.StructureAnchor structure -> {
                Vec3 middle = structure(player, structure.structure());
                yield middle != null ? new Found(middle, 0, null) : null;
            }
            case SceneDefinition.WaypointAnchor waypoint -> {
                ClientWaypoint shown = Waypoints.visible(player).get(waypoint.waypoint().toString());
                yield shown != null && shown.placed() && shown.dimension().equals(level.dimension().location())
                        ? new Found(shown.pos(), 0, shown.entity() >= 0 ? level.getEntity(shown.entity()) : null) : null;
            }
        };
    }

    /** The NPC of a binding near the player: the one the mod picked, or the nearest entity the binding is for. */
    private static @Nullable Entity npc(ServerPlayer player, ResourceLocation id) {
        NpcBinding binding = DialogueRegistries.NPC_BINDINGS.serverStore().all().get(id);
        if (binding == null) {
            return null;
        }
        if (binding.isOne()) {
            NpcParts.Part part = NpcParts.holding(player.server, id);
            Entity holder = part != null ? player.serverLevel().getEntity(part.holder()) : null;
            return holder != null && holder.isAlive() && holder.distanceTo(player) <= CALM_RADIUS ? holder : null;
        }
        Entity best = null;
        double bestDistance = CALM_RADIUS * CALM_RADIUS;
        for (Entity candidate : player.serverLevel().getEntities(player, player.getBoundingBox().inflate(CALM_RADIUS), Entity::isAlive)) {
            double distance = candidate.distanceToSqr(player);
            if (distance <= bestDistance && NpcBindings.find(candidate)
                    .map(found -> found.chain().stream().anyMatch(layer -> id.equals(layer.id()))).orElse(false)) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }

    /**
     * The middle of such a structure on the ground: the one the player stands in, else the nearest one a search has
     * found already (the waypoints'); null while none is known. A search is started for next time, never waited for.
     */
    private static @Nullable Vec3 structure(ServerPlayer player, StructureRef ref) {
        ServerLevel level = player.serverLevel();
        BlockPos here = player.blockPosition();
        BlockPos at = null;
        if (ref.contains(level, here)) {
            at = here;
        } else {
            Optional<BlockPos> found = StructureLocator.result(player.server,
                    new StructureLocator.Search(ref, level.dimension(), here, StructureRef.DEFAULT_SEARCH_RADIUS));
            if (found != null && found.isPresent()) {
                at = found.get();
            }
        }
        if (at == null) {
            return null;
        }
        Vec3 middle = ref.markerPos(level, at);
        BlockPos column = BlockPos.containing(middle);
        return new Vec3(middle.x, StructureRef.groundY(level, column), middle.z);
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
            UUID uuid = player.getUUID();
            Integer replay = REPLAYS.get(uuid);
            if (replay != null) {
                if (replay > 0) {
                    REPLAYS.put(uuid, replay - 1);
                } else {
                    REPLAYS.remove(uuid);
                    StoryState.getStored(player, StoryScope.PLAYER, PENDING).ifPresent(value -> {
                        ResourceLocation scene = value instanceof StoryValue.Str text ? ResourceLocation.tryParse(text.value()) : null;
                        if (scene != null) {
                            play(player, scene);
                        } else {
                            forgetPending(player);
                        }
                    });
                }
            }
            Session session = SESSIONS.get(uuid);
            if (session != null) {
                if (session.talking) {
                    if (Dialogues.state(player).isPresent()) {
                        // The scene's clock waits for its talk; the world stays calm meanwhile.
                        if (session.definition.world() == SceneDefinition.World.CALM && server.getTickCount() % 20 == 0) {
                            calm(player);
                        }
                        continue;
                    }
                    session.talking = false;
                    StoryboundNetwork.sendToPlayer(player, new ScenePayloads.Resume(session.id));
                } else {
                    session.tick++;
                }
                if (session.definition.world() == SceneDefinition.World.CALM && session.tick % 20 == 0) {
                    calm(player);
                }
                runDue(player, session);
                if (!session.talking && session.tick >= session.total && session.next >= session.timeline.size() && SESSIONS.get(uuid) == session) {
                    finish(player, session, SceneLogic.Reason.FINISH);
                }
            } else {
                Deque<ResourceLocation> queue = QUEUED.get(uuid);
                if (queue != null && !busy(player)) {
                    ResourceLocation next = queue.poll();
                    if (queue.isEmpty()) {
                        QUEUED.remove(uuid);
                    }
                    if (next != null) {
                        start(player, next);
                    }
                }
            }
        }
    }

    private static void runDue(ServerPlayer player, Session session) {
        while (!session.talking && session.next < session.timeline.size() && session.timeline.get(session.next).tick() <= session.tick) {
            Timed due = session.timeline.get(session.next++);
            try {
                if (due.talk() != null) {
                    talk(player, session, due.talk());
                } else if (due.fade() != null) {
                    due.fade().send(player);
                } else if (due.cue() != null) {
                    cue(player, session, due.cue());
                }
            } catch (RuntimeException e) {
                Storybound.LOGGER.error("Storybound: scene {} failed at tick {}", session.scene, due.tick(), e);
            }
        }
    }

    /**
     * A talk of the scene: the client lets go of the scene's camera and screen, the dialogue plays with its own, and the
     * scene's clock waits until it is over. A dialogue that does not start (unknown, its NPC not found) is gone past.
     */
    private static void talk(ServerPlayer player, Session session, Talk talk) {
        stopCueAudio(player, session);
        session.talking = true;
        StoryboundNetwork.sendToPlayer(player, new ScenePayloads.Pause(session.id));
        if (!Dialogues.start(player, talk.dialogue(), talk.speaker() != null && talk.speaker().isAlive() ? talk.speaker() : null)) {
            StoryTrace.note(player, "scene " + session.scene, "its talk " + talk.dialogue() + " did not start; going on");
            session.talking = false;
            StoryboundNetwork.sendToPlayer(player, new ScenePayloads.Resume(session.id));
        }
    }

    private static void stopCueAudio(ServerPlayer player, Session session) {
        if (player.connection != null) {
            if (session.lastVoice != null) {
                player.connection.send(new ClientboundStopSoundPacket(session.lastVoice, SoundSource.VOICE));
            }
            if (session.lastSound != null && session.lastSoundSource != null) {
                player.connection.send(new ClientboundStopSoundPacket(session.lastSound, session.lastSoundSource));
            }
        }
        session.lastVoice = null;
        session.lastSound = null;
        session.lastSoundSource = null;
    }

    private static void cue(ServerPlayer player, Session session, SceneDefinition.Cue cue) {
        cue.title().ifPresent(title -> {
            if (player.connection != null) {
                player.connection.send(new ClientboundSetTitlesAnimationPacket(TITLE_IN, TITLE_STAY, TITLE_OUT));
                player.connection.send(new ClientboundSetSubtitleTextPacket(title.subtitle()
                        .map(text -> text.resolve(player).toComponent()).orElse(net.minecraft.network.chat.Component.empty())));
                player.connection.send(new ClientboundSetTitleTextPacket(title.text().resolve(player).toComponent()));
            }
        });
        cue.line().ifPresent(line -> {
            if (session.lastVoice != null && player.connection != null) {
                player.connection.send(new ClientboundStopSoundPacket(session.lastVoice, SoundSource.VOICE));
            }
            session.lastVoice = null;
            net.minecraft.network.chat.Component text = line.text().resolve(player).toComponent();
            int ticks = line.duration().map(seconds -> Math.round(seconds * 20))
                    .orElse(Math.max(LINE_MIN_TICKS, (int) Math.round(text.getString().length() * LINE_TICKS_PER_CHAR)));
            StoryboundNetwork.sendToPlayer(player, new ScenePayloads.Line(session.id,
                    line.speaker().map(speaker -> speaker.resolve(player).toComponent()), text, ticks));
            line.sound().ifPresent(sound -> {
                player.playNotifySound(SoundEvent.createVariableRangeEvent(sound), SoundSource.VOICE, 1f, 1f);
                session.lastVoice = sound;
            });
        });
        cue.caption().ifPresent(caption -> {
            net.minecraft.network.chat.Component text = caption.text().resolve(player).toComponent();
            int ticks = caption.duration().map(seconds -> Math.round(seconds * 20))
                    .orElse(Math.max(LINE_MIN_TICKS, (int) Math.round(text.getString().length() * LINE_TICKS_PER_CHAR)));
            StoryboundNetwork.sendToPlayer(player, new ScenePayloads.Caption(session.id, text, caption.position().ordinal(), caption.black(), ticks));
        });
        cue.music().ifPresent(track -> {
            session.music = Optional.of(track);
            StoryMusic.sync(player);
        });
        cue.sound().ifPresent(sound -> {
            if (session.lastSound != null && session.lastSoundSource != null && player.connection != null) {
                player.connection.send(new ClientboundStopSoundPacket(session.lastSound, session.lastSoundSource));
            }
            player.playNotifySound(SoundEvent.createVariableRangeEvent(sound.sound()), sound.source(), sound.volume(), sound.pitch());
            session.lastSound = sound.sound();
            session.lastSoundSource = sound.source();
        });
        cue.fade().ifPresent(fade -> fade.send(player));
        if (!cue.actions().isEmpty()) {
            Actions.runAll(cue.actions(), StoryContext.of(player), "scene " + session.scene, OnFail.ABORT);
        }
    }

    private static void finish(ServerPlayer player, Session session, SceneLogic.Reason reason) {
        finish(player, session, reason, false);
    }

    private static void finish(ServerPlayer player, Session session, SceneLogic.Reason reason, boolean skipped) {
        SESSIONS.remove(player.getUUID());
        forgetPending(player);
        stopCueAudio(player, session);
        if (reason != SceneLogic.Reason.FINISH && player.connection != null) {
            player.connection.send(new ClientboundClearTitlesPacket(true));
        }
        if (skipped) {
            // Cut scene music immediately while the client still knows it is exiting a scene.
            StoryMusic.sync(player);
            StoryboundNetwork.sendToPlayer(player, new ScenePayloads.End(session.id));
        } else {
            // End the scene first so post-scene music transitions smoothly.
            StoryboundNetwork.sendToPlayer(player, new ScenePayloads.End(session.id));
            StoryMusic.sync(player);
        }
        StoryTrace.event(player, "scene_finished", "scene", session.scene.toString(), "reason", reason.getSerializedName());
        if (reason == SceneLogic.Reason.FINISH && !session.definition.onEnd().isEmpty()) {
            Actions.runAll(session.definition.onEnd(), StoryContext.of(player), "scene " + session.scene + " on_end", OnFail.ABORT);
        }
        TriggerDispatcher.fire(SceneLogic.SCENE_FINISHED.get(), StoryContext.of(player), new SceneLogic.Finished(session.scene, reason));
        if (session.afterwards != null) {
            // The dialogue that played it goes on.
            session.afterwards.run();
        }
    }

    private static void setPending(ServerPlayer player, ResourceLocation scene) {
        try {
            StoryState.set(player, StoryScope.PLAYER, PENDING, new StoryValue.Str(scene.toString()));
        } catch (StoryException e) {
            Storybound.LOGGER.warn("Storybound: could not remember the scene {} for {}: {}", scene, player.getGameProfile().getName(), e.getMessage());
        }
    }

    private static void forgetPending(ServerPlayer player) {
        try {
            StoryState.clear(player, StoryScope.PLAYER, PENDING);
        } catch (StoryException e) {
            Storybound.LOGGER.warn("Storybound: could not forget the scene of {}: {}", player.getGameProfile().getName(), e.getMessage());
        }
    }

    /** Mobs near the watcher let go of them and of the villagers. */
    private static void calm(ServerPlayer player) {
        for (Mob mob : player.serverLevel().getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(CALM_RADIUS))) {
            LivingEntity target = mob.getTarget();
            if (target != null && calmed(target)) {
                mob.setTarget(null);
            }
        }
    }

    /** A watcher of a calm scene, or a villager near one: no mob may go for it. */
    private static boolean calmed(Entity entity) {
        if (entity instanceof ServerPlayer player) {
            Session session = SESSIONS.get(player.getUUID());
            return session != null && session.definition.world() == SceneDefinition.World.CALM;
        }
        return entity instanceof AbstractVillager && nearCalmWatcher(entity);
    }

    private static boolean nearCalmWatcher(Entity entity) {
        for (Map.Entry<UUID, Session> entry : SESSIONS.entrySet()) {
            if (entry.getValue().definition.world() != SceneDefinition.World.CALM) {
                continue;
            }
            MinecraftServer server = entity.getServer();
            ServerPlayer watcher = server != null ? server.getPlayerList().getPlayer(entry.getKey()) : null;
            if (watcher != null && watcher.level() == entity.level() && watcher.distanceToSqr(entity) <= CALM_RADIUS * CALM_RADIUS) {
                return true;
            }
        }
        return false;
    }

    private static void onDamage(LivingIncomingDamageEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && !event.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            Session session = SESSIONS.get(player.getUUID());
            if (session != null && session.definition.invulnerable()) {
                event.setCanceled(true);
            }
        }
    }

    private static void onTarget(LivingChangeTargetEvent event) {
        LivingEntity target = event.getNewAboutToBeSetTarget();
        if (!SESSIONS.isEmpty() && target != null && calmed(target)) {
            event.setCanceled(true);
        }
    }

    private static void onSpawn(FinalizeSpawnEvent event) {
        if (SESSIONS.isEmpty() || !(event.getEntity() instanceof Enemy)) {
            return;
        }
        MobSpawnType type = event.getSpawnType();
        if ((type == MobSpawnType.NATURAL || type == MobSpawnType.PATROL || type == MobSpawnType.REINFORCEMENT) && nearCalmWatcher(event.getEntity())) {
            event.setSpawnCancelled(true);
        }
    }

    private static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            Session session = SESSIONS.get(player.getUUID());
            if (session != null) {
                finish(player, session, SceneLogic.Reason.DIED);
            }
        }
    }

    private static void onDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            Session session = SESSIONS.get(player.getUUID());
            if (session != null) {
                finish(player, session, SceneLogic.Reason.MOVED);
            }
        }
    }

    private static void onCommandTeleport(EntityTeleportEvent.TeleportCommand event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            Session session = SESSIONS.get(player.getUUID());
            if (session != null) {
                finish(player, session, SceneLogic.Reason.MOVED);
            }
        }
    }

    private static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && StoryState.getStored(player, StoryScope.PLAYER, PENDING).isPresent()) {
            REPLAYS.put(player.getUUID(), REPLAY_DELAY);
        }
    }

    /** Left mid-scene: no end, no trigger; the scene stays remembered and starts again when they come back. */
    private static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID uuid = event.getEntity().getUUID();
        SESSIONS.remove(uuid);
        QUEUED.remove(uuid);
        REPLAYS.remove(uuid);
    }

    /** Everything forgotten; for tests and a stopping server. */
    public static void clear() {
        SESSIONS.clear();
        QUEUED.clear();
        REPLAYS.clear();
    }

    /** Ticks the scene the player watches has run, or -1; for tests. */
    public static int tickOf(ServerPlayer player) {
        Session session = SESSIONS.get(player.getUUID());
        return session != null ? session.tick : -1;
    }

    /** How long the scene the player watches lasts in ticks, or -1; for tests. */
    public static int lengthOf(ServerPlayer player) {
        Session session = SESSIONS.get(player.getUUID());
        return session != null ? session.total : -1;
    }

    /** Ends the player's scene as if they had left: for tests of coming back. */
    public static void leaveForTests(ServerPlayer player) {
        SESSIONS.remove(player.getUUID());
    }

    /** Asks for the replay of an unfinished scene at once, as a login would after its delay; for tests. */
    public static void comeBackForTests(ServerPlayer player) {
        REPLAYS.put(player.getUUID(), 0);
    }
}
