package dev.dzenthai.storybound.module.dialogue;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.compat.cutscene.Cutscenes;
import dev.dzenthai.storybound.config.StoryboundConfig;
import dev.dzenthai.storybound.core.StoryException;
import dev.dzenthai.storybound.core.event.NpcInteractEvent;
import dev.dzenthai.storybound.core.logic.EntitySelector;
import dev.dzenthai.storybound.core.logic.StoryContext;
import dev.dzenthai.storybound.core.logic.StoryTrace;
import dev.dzenthai.storybound.core.state.StoryScope;
import dev.dzenthai.storybound.core.state.StoryState;
import dev.dzenthai.storybound.core.state.StoryValue;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;

/**
 * Starts dialogues from NPCs: with the talk key (and right-click, if the server config allows it) and when a player
 * comes close. An entity's own binding (from {@code /storybound npc bind}) wins; otherwise the first binding file,
 * by id, whose selector matches.
 */
public final class NpcBindings {
    private static final int PROXIMITY_INTERVAL = 10;
    static final String ONCE_PREFIX = "npc_once/";
    /** Blocks beyond the player's interaction range still accepted for the talk key, like vanilla's leeway for clicks. */
    private static final double TALK_REACH_SLACK = 1.0;

    private static List<Map.Entry<ResourceLocation, NpcBinding>> files = List.of();
    private static double searchRadius = NpcBinding.Proximity.DEFAULT.radius();
    /** Player → entities within a proximity radius at the last check; a dialogue starts only on coming close. */
    private static final Map<UUID, Set<UUID>> NEAR = new HashMap<>();
    /** Player → entity → tick of the last proximity start, for the cooldown. */
    private static final Map<UUID, Map<UUID, Long>> LAST_START = new HashMap<>();
    /** The entity each player's trace last explained (see {@link #explainNoBinding}). */
    private static final Map<UUID, UUID> EXPLAINED = new HashMap<>();
    /** Players whose next check is their first since logging in: it only records who is near. */
    private static final Set<UUID> FRESH = new HashSet<>();

    private NpcBindings() {}

    /** Caches the loaded binding files in id order; called after every data load. */
    public static void rebuild() {
        List<Map.Entry<ResourceLocation, NpcBinding>> sorted = new ArrayList<>(DialogueRegistries.NPC_BINDINGS.serverStore().all().entrySet());
        sorted.sort(Map.Entry.comparingByKey(Comparator.comparing(ResourceLocation::toString)));
        files = List.copyOf(sorted);
        searchRadius = files.stream().flatMap(entry -> entry.getValue().proximity().stream())
                .mapToDouble(NpcBinding.Proximity::radius).max().orElse(0);
        searchRadius = Math.max(searchRadius, NpcBinding.Proximity.DEFAULT.radius());
    }

    /** The binding that applies to the entity; {@code id} is null for one stored on the entity. */
    public static Optional<Found> find(Entity entity) {
        if (entity.hasData(DialogueRegistries.NPC_BINDING)) {
            return Optional.of(new Found(null, entity.getData(DialogueRegistries.NPC_BINDING).toBinding(entity)));
        }
        for (Map.Entry<ResourceLocation, NpcBinding> entry : files) {
            if (entry.getValue().entity().matches(entity)) {
                return Optional.of(new Found(entry.getKey(), entry.getValue()));
            }
        }
        return Optional.empty();
    }

    /** Whether a binding file that matches the entity says it comes back (an entity's own binding never does). */
    public static boolean respawns(Entity entity) {
        return files.stream().anyMatch(entry -> entry.getValue().respawn() && entry.getValue().entity().matches(entity));
    }

    /**
     * The talk key, from a client: the entity must be in the player's level, alive and within reach, as for a
     * right-click; a spectator cannot talk. Anything else is dropped.
     */
    public static void talkRequested(ServerPlayer player, int entityId) {
        Entity target = reachable(player, entityId);
        if (target == null) {
            Storybound.LOGGER.debug("Storybound: dropped a talk request from {} for entity {}", player.getGameProfile().getName(), entityId);
            return;
        }
        talk(player, target);
    }

    /**
     * For the client's "[H] Talk" hint: whether the talk key on this entity would do anything now, a dialogue whose
     * conditions pass or a {@code custom} binding for scripts. False for anything out of reach, so the answer tells a
     * client nothing about entities it could not talk to anyway.
     */
    public static boolean canTalk(ServerPlayer player, int entityId) {
        Entity target = reachable(player, entityId);
        if (target == null || !Dialogues.isEnabled() || Dialogues.state(player).isPresent()) {
            return false;
        }
        Found found = find(target).orElse(null);
        if (found == null) {
            explainNoBinding(player, target);
            return false;
        }
        if (found.binding().interact() && busyWith(found, target, player) != null) {
            // A busy NPC offers its busy line, or nothing.
            return found.binding().busyDialogue().isPresent() || found.binding().custom() && found.id() != null;
        }
        return (found.binding().custom() && found.id() != null)
                || (found.binding().interact() && found.binding().pick(new StoryContext(player, target)).isPresent());
    }

    /** The player an {@code exclusive} NPC talks with now, other than this one; null when it is free (or not exclusive). */
    private static @Nullable ServerPlayer busyWith(Found found, Entity npc, ServerPlayer player) {
        return found.binding().exclusive() ? Dialogues.talkingWith(npc, player) : null;
    }

    private static @Nullable Entity reachable(ServerPlayer player, int entityId) {
        Entity target = player.serverLevel().getEntity(entityId);
        if (target == null || !target.isAlive() || target == player || player.isSpectator()
                || !player.canInteractWithEntity(target, TALK_REACH_SLACK)) {
            return null;
        }
        return target;
    }

    /**
     * The player addresses an entity (talk key, or right-click with {@code dialogues.rightClickTalks}): a binding with
     * {@code interact} starts its dialogue, one with {@code custom} posts {@link NpcInteractEvent}. Public for tests.
     */
    public static void talk(ServerPlayer player, Entity target) {
        if (Dialogues.isEnabled() && Dialogues.state(player).isEmpty()) {
            find(target).ifPresentOrElse(found -> talk(player, target, found), () -> explainNoBinding(player, target));
        }
    }

    /**
     * A right-click talks like {@link #talk} when {@code dialogues.rightClickTalks} is on or the binding has
     * {@code override_interact}. Public for tests.
     *
     * @return true when the click must not do its usual thing (trading): the binding has {@code override_interact}
     *         and started a dialogue or posted {@link NpcInteractEvent}
     */
    public static boolean rightClick(ServerPlayer player, Entity target) {
        if (!Dialogues.isEnabled() || Dialogues.state(player).isPresent()) {
            return false;
        }
        Found found = find(target).orElse(null);
        if (found == null || !StoryboundConfig.COMMON.dialogueRightClickTalks.get() && !found.binding().overrideInteract()) {
            return false;
        }
        return talk(player, target, found) && found.binding().overrideInteract();
    }

    /** @return whether the binding handled the talk: posted {@link NpcInteractEvent} or started a dialogue */
    private static boolean talk(ServerPlayer player, Entity target, Found found) {
        if (Cutscenes.watching(player) != null) {
            // A cutscene is a scene of its own: no NPC talks into it.
            StoryTrace.note(player, bindingName(found, target), "talk: not during a cutscene");
            return false;
        }
        boolean handled = false;
        if (found.binding().custom() && found.id() != null) {
            NeoForge.EVENT_BUS.post(new NpcInteractEvent(player, target, found.id()));
            handled = true;
        }
        if (found.binding().interact() && Dialogues.state(player).isEmpty()) {
            ServerPlayer other = busyWith(found, target, player);
            if (other != null) {
                // Taken: the busy line, if the binding has one, instead of its dialogues.
                StoryTrace.note(player, bindingName(found, target), "talk: busy with " + other.getGameProfile().getName());
                return found.binding().busyDialogue().map(busy -> Dialogues.start(player, busy, target)).orElse(false) || handled;
            }
            Optional<ResourceLocation> dialogue = found.binding().pick(new StoryContext(player, target), bindingName(found, target));
            if (dialogue.isEmpty()) {
                StoryTrace.note(player, bindingName(found, target), "talk: no dialogue entry passed its conditions");
            }
            handled |= dialogue.isPresent() && Dialogues.start(player, dialogue.get(), target);
        }
        return handled;
    }

    /** Proximity starts; every 10 ticks. Public for tests. */
    public static void tick(MinecraftServer server) {
        long now = server.getTickCount();
        if (now % PROXIMITY_INTERVAL != 0 || !Dialogues.isEnabled()) {
            return;
        }
        for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
            checkProximity(player, now);
        }
    }

    /** One proximity check for one player; public for tests. */
    public static void checkProximity(ServerPlayer player, long now) {
        UUID playerId = player.getUUID();
        if (player.isSpectator() || !player.isAlive()) {
            NEAR.remove(playerId);
            return;
        }
        boolean fresh = FRESH.remove(playerId);
        Set<UUID> previous = NEAR.getOrDefault(playerId, Set.of());
        Set<UUID> near = new HashSet<>();
        AABB box = player.getBoundingBox().inflate(searchRadius);
        for (Entity entity : player.level().getEntities(player, box, candidate -> candidate instanceof LivingEntity && candidate.isAlive())) {
            Found found = find(entity).orElse(null);
            NpcBinding.Proximity proximity = found != null ? found.binding().proximity().orElse(null) : null;
            if (proximity == null || entity.distanceToSqr(player) > proximity.radius() * proximity.radius()
                    || (proximity.lineOfSight() && !player.hasLineOfSight(entity))) {
                continue;
            }
            near.add(entity.getUUID());
            if (!fresh && !previous.contains(entity.getUUID())) {
                tryStart(player, entity, found, proximity, now);
            }
        }
        NEAR.put(playerId, near);
    }

    private static void tryStart(ServerPlayer player, Entity entity, Found found, NpcBinding.Proximity proximity, long now) {
        if (Dialogues.state(player).isPresent() || Cutscenes.watching(player) != null) {
            return;
        }
        Map<UUID, Long> starts = LAST_START.computeIfAbsent(player.getUUID(), id -> new HashMap<>());
        Long last = starts.get(entity.getUUID());
        if (last != null && now - last < proximity.cooldown() * 20L) {
            return;
        }
        ResourceLocation once = proximity.once() ? onceKey(found, entity) : null;
        if (once != null && StoryState.getStored(player, StoryScope.PLAYER, once).isPresent()) {
            return;
        }
        if (busyWith(found, entity, player) != null) {
            // Coming close to a busy NPC starts nothing, not even the busy line: only talking to it does.
            return;
        }
        Optional<ResourceLocation> dialogue = found.binding().pick(new StoryContext(player, entity), bindingName(found, entity) + " proximity");
        if (dialogue.isPresent() && Dialogues.start(player, dialogue.get(), entity)) {
            starts.put(entity.getUUID(), now);
            if (once != null) {
                try {
                    StoryState.set(player, StoryScope.PLAYER, once, new StoryValue.Bool(true));
                } catch (StoryException e) {
                    Storybound.LOGGER.warn("Storybound: could not update {}: {}", once, e.getMessage());
                }
            }
        }
    }

    /** The internal player flag of a {@code once} proximity binding. */
    public static ResourceLocation onceKey(Found found, Entity entity) {
        return found.id() != null
                ? Storybound.id(ONCE_PREFIX + found.id().getNamespace() + "/" + found.id().getPath())
                : Storybound.id(ONCE_PREFIX + "entity/" + entity.getUUID());
    }

    static void loggedIn(ServerPlayer player) {
        FRESH.add(player.getUUID());
    }

    static void forget(ServerPlayer player) {
        NEAR.remove(player.getUUID());
        LAST_START.remove(player.getUUID());
        EXPLAINED.remove(player.getUUID());
        FRESH.remove(player.getUUID());
    }

    static void reset() {
        NEAR.clear();
        LAST_START.clear();
        EXPLAINED.clear();
        FRESH.clear();
    }

    /** @param id the binding file, or null for a binding stored on the entity */
    /** How the trace names a binding: its id, or the entity whose own binding it is. */
    /**
     * For the trace, when the player looks at or talks to an entity no binding matches: what each binding of its type
     * (every binding, when none is of its type) wants that it lacks. Once per entity looked at, as the talk hint asks
     * again and again.
     */
    private static void explainNoBinding(ServerPlayer player, Entity target) {
        if (!StoryTrace.active(player) || target.getUUID().equals(EXPLAINED.put(player.getUUID(), target.getUUID()))) {
            return;
        }
        List<String> reasons = new ArrayList<>();
        for (Map.Entry<ResourceLocation, NpcBinding> entry : files) {
            EntitySelector selector = entry.getValue().entity();
            String mismatch = selector.mismatch(target);
            if (!mismatch.startsWith("type is ")) {
                reasons.add(entry.getKey() + ": " + mismatch);
            }
        }
        String entity = BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()) + " " + target.getUUID();
        StoryTrace.note(player, "npc " + entity, reasons.isEmpty() ? "no NPC binding is for this kind of entity"
                : "no NPC binding matches: " + String.join(" | ", reasons));
    }

    private static String bindingName(Found found, Entity entity) {
        return "npc binding " + (found.id() != null ? found.id().toString() : "of " + entity.getUUID());
    }

    public record Found(@Nullable ResourceLocation id, NpcBinding binding) {}
}
