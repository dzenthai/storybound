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
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
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

    /** Something of the scene at its tick: a title, a line, a sound, a fade, actions. */
    record Timed(int tick, SceneDefinition.Cue cue, @Nullable ScreenFade fade) {}

    static final class Session {
        final int id;
        final ResourceLocation scene;
        final SceneDefinition definition;
        final int total;
        final List<Timed> timeline;
        int tick;
        int next;

        Session(int id, ResourceLocation scene, SceneDefinition definition, int total, List<Timed> timeline) {
            this.id = id;
            this.scene = scene;
            this.definition = definition;
            this.total = total;
            this.timeline = timeline;
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

    private static void start(ServerPlayer player, ResourceLocation scene) {
        SceneDefinition definition = SceneRegistries.SCENES.serverStore().getOrNull(scene);
        if (definition == null) {
            StoryTrace.note(player, "scene " + scene, "not played: no such scene is loaded");
            forgetPending(player);
            return;
        }
        List<ScenePayloads.Shot> shots = new ArrayList<>();
        List<Timed> timeline = new ArrayList<>();
        int at = 0;
        for (int index = 0; index < definition.shots().size(); index++) {
            SceneDefinition.Shot shot = definition.shots().get(index);
            Planned planned = plan(player, shot);
            if (planned.skipped() != null) {
                StoryTrace.note(player, "scene " + scene, "shot " + (index + 1) + " left out: " + planned.skipped());
                continue;
            }
            int ticks = Math.max(1, Math.round(shot.camera().seconds() * 20));
            boolean first = shots.isEmpty();
            shots.add(planned.shot().withTime(at, ticks));
            if (shot.transition() == SceneDefinition.Transition.FADE) {
                float edge = FADE_TICKS / 20f;
                // Down to black just before the cut, up after it; the first shot comes up out of black.
                timeline.add(new Timed(Math.max(0, at - (first ? 0 : FADE_TICKS)), null,
                        new ScreenFade(first ? 0 : edge, 0.05f, edge, ScreenFade.BLACK)));
            }
            for (SceneDefinition.Cue cue : shot.during()) {
                timeline.add(new Timed(at + Math.min(ticks, Math.round(cue.at() * 20)), cue, null));
            }
            at += ticks;
        }
        timeline.sort(Comparator.comparingInt(Timed::tick));
        Session session = new Session(nextSession++, scene, definition, at, timeline);
        StoryTrace.event(player, "scene_started", "scene", scene.toString(), "shots", shots.size() + " of " + definition.shots().size());
        setPending(player, scene);
        SESSIONS.put(player.getUUID(), session);
        if (shots.isEmpty()) {
            // Nothing could be shown: it still happened, so what waits for its end does not wait for ever.
            finish(player, session, SceneLogic.Reason.FINISH);
            return;
        }
        if (definition.world() == SceneDefinition.World.CALM) {
            calm(player);
        }
        StoryboundNetwork.sendToPlayer(player, new ScenePayloads.Play(session.id, definition.letterbox(), shots));
        runDue(player, session);
    }

    /** A shot placed for this player, or why it cannot be. */
    record Planned(@Nullable ScenePayloads.Shot shot, @Nullable String skipped) {
        static Planned skip(String why) {
            return new Planned(null, why);
        }
    }

    /** An anchor found: where it is, which way it faces, the entity it is (or none). */
    record Found(Vec3 pos, float yaw, @Nullable Entity entity) {
        Vec3 eye() {
            return entity != null ? entity.getEyePosition() : pos;
        }
    }

    private static Planned plan(ServerPlayer player, SceneDefinition.Shot shot) {
        Found anchor = find(player, shot.anchor());
        if (anchor == null) {
            return Planned.skip("its anchor " + describe(shot.anchor()) + " is not found near the player");
        }
        SceneDefinition.Camera camera = shot.camera();
        Vec3 from = place(anchor, camera.from());
        Vec3 to = place(anchor, camera.to());
        int look;
        Vec3 point;
        int entity = -1;
        switch (camera.look()) {
            case SceneDefinition.AlongPath path -> {
                look = ScenePayloads.LOOK_PATH;
                point = to.subtract(from).lengthSqr() > 1.0e-6 ? to.add(to.subtract(from)) : anchor.eye();
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
        for (Vec3 spot : List.of(from, to, anchor.pos())) {
            if (Math.hypot(spot.x - player.getX(), spot.z - player.getZ()) > reach) {
                return Planned.skip("it is " + (int) Math.hypot(spot.x - player.getX(), spot.z - player.getZ())
                        + " blocks away, beyond what the player's world has loaded (" + (int) reach + ")");
            }
        }
        return new Planned(new ScenePayloads.Shot(from, to, look, point, entity, 0, 1, camera.ease().ordinal(), shot.transition().ordinal()), null);
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
    static @Nullable Found find(ServerPlayer player, SceneDefinition.Anchor anchor) {
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
                session.tick++;
                if (session.definition.world() == SceneDefinition.World.CALM && session.tick % 20 == 0) {
                    calm(player);
                }
                runDue(player, session);
                if (session.tick >= session.total && SESSIONS.get(uuid) == session) {
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
        while (session.next < session.timeline.size() && session.timeline.get(session.next).tick() <= session.tick) {
            Timed due = session.timeline.get(session.next++);
            try {
                if (due.fade() != null) {
                    due.fade().send(player);
                } else {
                    cue(player, session, due.cue());
                }
            } catch (RuntimeException e) {
                Storybound.LOGGER.error("Storybound: scene {} failed at tick {}", session.scene, due.tick(), e);
            }
        }
    }

    private static void cue(ServerPlayer player, Session session, SceneDefinition.Cue cue) {
        cue.title().ifPresent(title -> {
            player.connection.send(new ClientboundSetTitlesAnimationPacket(TITLE_IN, TITLE_STAY, TITLE_OUT));
            player.connection.send(new ClientboundSetSubtitleTextPacket(title.subtitle()
                    .map(text -> text.resolve(player).toComponent()).orElse(net.minecraft.network.chat.Component.empty())));
            player.connection.send(new ClientboundSetTitleTextPacket(title.text().resolve(player).toComponent()));
        });
        cue.line().ifPresent(line -> {
            net.minecraft.network.chat.Component text = line.text().resolve(player).toComponent();
            int ticks = line.duration().map(seconds -> Math.round(seconds * 20))
                    .orElse(Math.max(LINE_MIN_TICKS, (int) Math.round(text.getString().length() * LINE_TICKS_PER_CHAR)));
            StoryboundNetwork.sendToPlayer(player, new ScenePayloads.Line(session.id,
                    line.speaker().map(speaker -> speaker.resolve(player).toComponent()), text, ticks));
            line.sound().ifPresent(sound -> player.playNotifySound(SoundEvent.createVariableRangeEvent(sound), SoundSource.VOICE, 1f, 1f));
        });
        cue.sound().ifPresent(sound -> player.playNotifySound(SoundEvent.createVariableRangeEvent(sound.sound()), sound.source(), sound.volume(), sound.pitch()));
        cue.fade().ifPresent(fade -> fade.send(player));
        if (!cue.actions().isEmpty()) {
            Actions.runAll(cue.actions(), StoryContext.of(player), "scene " + session.scene, OnFail.ABORT);
        }
    }

    private static void finish(ServerPlayer player, Session session, SceneLogic.Reason reason) {
        SESSIONS.remove(player.getUUID());
        forgetPending(player);
        StoryboundNetwork.sendToPlayer(player, new ScenePayloads.End(session.id));
        StoryTrace.event(player, "scene_finished", "scene", session.scene.toString(), "reason", reason.getSerializedName());
        if (reason == SceneLogic.Reason.FINISH && !session.definition.onEnd().isEmpty()) {
            Actions.runAll(session.definition.onEnd(), StoryContext.of(player), "scene " + session.scene + " on_end", OnFail.ABORT);
        }
        TriggerDispatcher.fire(SceneLogic.SCENE_FINISHED.get(), StoryContext.of(player), new SceneLogic.Finished(session.scene, reason));
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
