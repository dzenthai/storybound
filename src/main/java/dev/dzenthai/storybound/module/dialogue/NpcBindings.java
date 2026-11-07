package dev.dzenthai.storybound.module.dialogue;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.compat.cutscene.Cutscenes;
import dev.dzenthai.storybound.config.StoryboundConfig;
import dev.dzenthai.storybound.core.StoryException;
import dev.dzenthai.storybound.core.event.DialogueEvent;
import dev.dzenthai.storybound.core.event.NpcInteractEvent;
import dev.dzenthai.storybound.core.logic.StoryContext;
import dev.dzenthai.storybound.core.logic.StoryTrace;
import dev.dzenthai.storybound.core.state.StoryScope;
import dev.dzenthai.storybound.core.state.StoryState;
import dev.dzenthai.storybound.core.state.StoryValue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
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
 * comes close. An entity's own binding (from the removed {@code /storybound npc bind}) wins; otherwise the first binding file,
 * by id, whose selector matches.
 */
public final class NpcBindings {
    private static final int PROXIMITY_INTERVAL = 10;
    static final String ONCE_PREFIX = "npc_once/";
    /** Blocks beyond the player's interaction range still accepted for the talk key, like vanilla's leeway for clicks. */
    private static final double TALK_REACH_SLACK = 1.0;

    private static List<Map.Entry<ResourceLocation, NpcBinding>> files = List.of();
    private static double searchRadius = NpcBinding.Proximity.DEFAULT.radius();
    private static double chatterReach;
    /** Player → entities within a proximity radius at the last check; a dialogue starts only on coming close. */
    private static final Map<UUID, Set<UUID>> NEAR = new HashMap<>();
    /** Player → entity → tick of the last proximity start, for the cooldown. */
    private static final Map<UUID, Map<UUID, Long>> LAST_START = new HashMap<>();
    /** The entity each player's trace last explained (see {@link #explainNoBinding}). */
    private static final Map<UUID, UUID> EXPLAINED = new HashMap<>();
    /** Players whose next check is their first since logging in: it only records who is near. */
    private static final Set<UUID> FRESH = new HashSet<>();
    /** How long what an entity was worked out to be is kept: a second, so it does not flicker at a village's edge. */
    private static final int CACHE_TICKS = 20;
    /** Entity → what it was worked out to be (see {@link #find}). */
    private static final Map<UUID, Cached> CACHE = new HashMap<>();

    private NpcBindings() {}

    /** Caches the loaded binding files in id order; called after every data load. */
    public static void rebuild() {
        CACHE.clear();
        NpcParts.bindingsChanged();
        List<Map.Entry<ResourceLocation, NpcBinding>> sorted = new ArrayList<>(DialogueRegistries.NPC_BINDINGS.serverStore().all().entrySet());
        sorted.sort(Map.Entry.comparingByKey(Comparator.comparing(ResourceLocation::toString)));
        files = List.copyOf(sorted);
        searchRadius = files.stream().flatMap(entry -> entry.getValue().proximity().stream())
                .mapToDouble(NpcBinding.Proximity::radius).max().orElse(0);
        searchRadius = Math.max(searchRadius, NpcBinding.Proximity.DEFAULT.radius());
        chatterReach = files.stream().flatMap(entry -> entry.getValue().chatter().stream())
                .mapToDouble(NpcBinding.Chatter::radius).max().orElse(0);
    }

    /** The binding files, in id order. */
    static List<Map.Entry<ResourceLocation, NpcBinding>> files() {
        return files;
    }

    /** How far from a player an NPC with chatter may be heard: the largest chatter radius; 0 when none chatters. */
    static double chatterReach() {
        return chatterReach;
    }

    /**
     * The NPC the entity is: every binding matching it, closest first ({@link NpcBinding.Rank}), each setting from the
     * first that writes it (November 7, 2026, "layers" as in The Witcher; before, the closest binding alone applied, so
     * an elder tagged by hand lost what "every villager" gave). Empty when no binding matches. Worked out once per entity
     * and kept for {@link #CACHE_TICKS} (cleared on a reload; worked out again at once when its tags, its name or its own
     * binding change, as a story re-tagging an NPC and killing it in one go needs the new answer), as the talk hint,
     * proximity, chatter, names, hits and
     * respawns all ask it.
     */
    public static Optional<Found> find(Entity entity) {
        long now = entity.level().getGameTime();
        Cached cached = CACHE.get(entity.getUUID());
        if (cached != null && now >= cached.tick() && now - cached.tick() < CACHE_TICKS && cached.same(entity)) {
            return cached.found();
        }
        Optional<Found> found = Optional.ofNullable(resolve(entity));
        CACHE.put(entity.getUUID(), new Cached(now, Set.copyOf(entity.getTags()), entity.getCustomName(),
                entity.hasData(DialogueRegistries.NPC_BINDING), found));
        return found;
    }

    /** Forgets what the entity was worked out to be, e.g. after a tag was put on it; public for tests and commands. */
    public static void forget(Entity entity) {
        CACHE.remove(entity.getUUID());
    }

    private static @Nullable Found resolve(Entity entity) {
        List<Layer> chain = new ArrayList<>();
        if (entity.hasData(DialogueRegistries.NPC_BINDING)) {
            chain.add(new Layer(null, entity.getData(DialogueRegistries.NPC_BINDING).toBinding(entity), new NpcBinding.Rank(NpcBinding.Rank.OWN, 0), false));
        }
        List<Layer> fromFiles = new ArrayList<>();
        ResourceLocation part = NpcParts.partOf(entity).orElse(null);
        for (Map.Entry<ResourceLocation, NpcBinding> entry : files) {
            // A "one" binding is for the entity holding its part, whatever its filters say now; for no other.
            NpcBinding.Rank rank = entry.getValue().isOne() ? (entry.getKey().equals(part) ? new NpcBinding.Rank(NpcBinding.Rank.PART, 0) : null)
                    : entry.getValue().rank(entity);
            Optional<Integer> share = entry.getValue().crowdShare();
            if (rank != null && share.isPresent()) {
                // Only a share of them ("30% of villagers are guards"): the roll decides; a share is a filter too, so
                // Guards come before Villagers.
                rank = NpcBinding.inShare(entity.getUUID(), entry.getKey(), share.get()) ? new NpcBinding.Rank(rank.by(), rank.filters() + 1) : null;
            }
            if (rank != null) {
                fromFiles.add(new Layer(entry.getKey(), entry.getValue(), rank, false));
            }
        }
        // A stable sort: equally close ones stay in id order, and are marked as a tie with the one before.
        fromFiles.sort(Comparator.comparing(Layer::rank));
        for (int i = 0; i < fromFiles.size(); i++) {
            Layer layer = fromFiles.get(i);
            boolean tie = i > 0 && fromFiles.get(i - 1).rank().equals(layer.rank());
            chain.add(tie ? new Layer(layer.id(), layer.binding(), layer.rank(), true) : layer);
        }
        return chain.isEmpty() ? null : Found.of(chain);
    }

    /**
     * Whether the entity comes back (see {@link NpcRespawns}): {@code respawn} of the closest binding that writes it.
     * Before (November 6, 2026), any matching file's did, so "every villager comes back" brought back an elder whose
     * own file said otherwise; now an elder whose file says nothing comes back with every villager (November 7, 2026).
     */
    public static boolean respawns(Entity entity) {
        return find(entity).map(Found::respawn).orElse(false);
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
        if (target == null || !Dialogues.isEnabled() || Dialogues.holdsTheScreen(player)) {
            return false;
        }
        Found found = find(target).orElse(null);
        if (found == null) {
            explainNoBinding(player, target);
            return false;
        }
        return wouldTalk(player, target, found);
    }

    /** Whether talking to the NPC would do anything now (see {@link #canTalk}), asked without writing to the trace. */
    private static boolean wouldTalk(ServerPlayer player, Entity target, Found found) {
        if (asleep(target)) {
            // In its bed: nobody talks to it until it wakes.
            return false;
        }
        if (NpcReactions.angryAt(target, player)) {
            // Angry at the player: no talk to offer them.
            return false;
        }
        if (found.interact() && busyWith(found, target, player) != null) {
            // A busy NPC offers its busy line, or nothing.
            return found.binding().busyDialogue().isPresent() || found.customId() != null;
        }
        return found.customId() != null || (found.interact() && found.pick(new StoryContext(player, target)).isPresent());
    }

    /** Whether a click would get phrases from the NPC now, which start no conversation. */
    private static boolean saysPhrases(ServerPlayer player, Entity target, Found found) {
        return found.interact() && found.pick(new StoryContext(player, target))
                .map(picked -> picked.entry().phrases().isPresent()).orElse(false);
    }

    /** The player an {@code exclusive} NPC talks with now, other than this one; null when it is free (or not exclusive). */
    private static @Nullable ServerPlayer busyWith(Found found, Entity npc, ServerPlayer player) {
        return found.exclusive() ? Dialogues.talkingWith(npc, player) : null;
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
        if (Dialogues.isEnabled() && freeToTalk(player, target)) {
            find(target).ifPresentOrElse(found -> talk(player, target, found), () -> explainNoBinding(player, target));
        }
    }

    /**
     * Whether the player may turn to this NPC: free, or in a dialogue of subtitles (a monologue) that gives way to the
     * player's own choice to talk; it is interrupted ({@code on_abort} runs) only when the NPC has something to say.
     * A dialogue on the screen, or one showing a choice, is never left this way.
     */
    private static boolean freeToTalk(ServerPlayer player, Entity target) {
        if (Dialogues.state(player).isEmpty()) {
            return true;
        }
        if (Dialogues.holdsTheScreen(player) || Dialogues.isTalkingTo(player, target)) {
            return false;
        }
        Found found = find(target).orElse(null);
        if (found == null || !wouldTalk(player, target, found)) {
            return false;
        }
        if (saysPhrases(player, target, found)) {
            // A phrase needs no conversation: the one in subtitles goes on (November 7, 2026; before, it was interrupted,
            // its on_abort running, for a line in a bubble).
            return true;
        }
        StoryTrace.note(player, bindingName(found, target), "talk: the dialogue in subtitles gives way");
        Dialogues.interruptShown(player, DialogueEvent.EndReason.LEFT);
        return true;
    }

    /**
     * A right-click talks like {@link #talk} when {@code dialogues.rightClickTalks} is on or the binding has
     * {@code override_interact}. Public for tests.
     *
     * @return true when the click must not do its usual thing (trading): the binding has {@code override_interact}
     *         and started a dialogue or posted {@link NpcInteractEvent}
     */
    public static boolean rightClick(ServerPlayer player, Entity target) {
        if (!Dialogues.isEnabled()) {
            return false;
        }
        Found found = find(target).orElse(null);
        if (found == null || !StoryboundConfig.COMMON.dialogueRightClickTalks.get() && !found.overrideInteract()
                || !freeToTalk(player, target)) {
            return false;
        }
        return talk(player, target, found) && found.overrideInteract();
    }

    /** @return whether the binding handled the talk: posted {@link NpcInteractEvent} or started a dialogue */
    private static boolean talk(ServerPlayer player, Entity target, Found found) {
        if (asleep(target)) {
            StoryTrace.note(player, bindingName(found, target), "talk: asleep");
            return false;
        }
        if (Cutscenes.watching(player) != null) {
            // A cutscene is a scene of its own: no NPC talks into it.
            StoryTrace.note(player, bindingName(found, target), "talk: not during a cutscene");
            return false;
        }
        if (NpcReactions.refuse(target, player, found.binding())) {
            // Angry at the player: a phrase instead, and the click is taken (no trading with someone chasing you either).
            return true;
        }
        noteTie(player, found, target);
        boolean handled = false;
        if (found.customId() != null) {
            // The binding that gave "custom" names the NPC to scripts.
            NeoForge.EVENT_BUS.post(new NpcInteractEvent(player, target, found.customId()));
            handled = true;
        }
        if (found.interact() && (Dialogues.state(player).isEmpty() || saysPhrases(player, target, found))) {
            ServerPlayer other = busyWith(found, target, player);
            if (other != null) {
                // Taken: the busy line, if the binding has one, instead of its dialogues.
                StoryTrace.note(player, bindingName(found, target), "talk: busy with " + other.getGameProfile().getName());
                return found.binding().busyDialogue().map(busy -> Dialogues.start(player, busy, target)).orElse(false) || handled;
            }
            Optional<Picked> picked = found.pick(new StoryContext(player, target), target, "");
            if (picked.isEmpty()) {
                StoryTrace.note(player, bindingName(found, target), "talk: no dialogue entry passed its conditions");
            }
            handled |= picked.isPresent() && startEntry(player, target, picked.get());
        }
        return handled;
    }

    /** For the trace: two bindings equally close to the entity, of which the first by id goes first. */
    private static void noteTie(ServerPlayer player, Found found, Entity target) {
        List<Layer> chain = found.chain();
        for (int i = 1; i < chain.size(); i++) {
            if (chain.get(i).tie()) {
                StoryTrace.note(player, bindingName(found, target), "tie: " + chain.get(i - 1).id() + " and " + chain.get(i).id()
                        + " name it equally closely (" + chain.get(i).rank().describe() + "), " + chain.get(i - 1).id()
                        + " first by id; a filter tells them apart");
            }
        }
    }

    /**
     * What a click entry names: its dialogue started, or its phrases said over the NPC in a bubble, with no
     * conversation (November 6, 2026). Phrases count as handled even when they come too soon after the last ones (a
     * quick second click): the click is still the NPC's, so it does not open trading. Each entry goes through its
     * phrases on its own: "thank you" lines after a quest start at their first, wherever the ones before it stood.
     */
    private static boolean startEntry(ServerPlayer player, Entity target, Picked picked) {
        NpcBinding.Entry entry = picked.entry();
        if (entry.dialogue().isPresent()) {
            return Dialogues.start(player, entry.dialogue().get(), target);
        }
        if (target instanceof LivingEntity living) {
            NpcPhrases.say(living, entry.phrases().orElseThrow(), NpcPhrases.clickCounter(target, player, picked.layer().id(), picked.index()),
                    player, player.server.getTickCount(), true);
            return true;
        }
        return false;
    }

    /** Proximity starts; every 10 ticks. Public for tests. */
    public static void tick(MinecraftServer server) {
        long now = server.getTickCount();
        if (now % 200 == 0) {
            // What entities long out of sight were: not kept.
            long time = server.overworld().getGameTime();
            CACHE.values().removeIf(cached -> time < cached.tick() || time - cached.tick() >= CACHE_TICKS);
        }
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
            // Near and not yet greeted during this stay: tried again on every check while it cannot start yet (the
            // player is talking, the quest is not at that stage yet), so standing there does not miss it. Just after a
            // login, what an NPC would say right now is not said (nobody is greeted for logging in), but what it
            // only gets to say later still is.
            boolean settled = previous.contains(entity.getUUID())
                    || (fresh ? found.pick(new StoryContext(player, entity)).isPresent() : tryStart(player, entity, found, proximity, now));
            if (settled) {
                near.add(entity.getUUID());
            }
        }
        NEAR.put(playerId, near);
    }

    /**
     * @return whether this stay near the NPC is settled: a dialogue started, or it never will this time (its cooldown,
     *         {@code once} spent); false while it may yet (the player is talking or watching a cutscene, the NPC is
     *         busy, no dialogue passes its conditions yet), so the next check tries again. Those checks test the
     *         conditions quietly: the trace gets the start, not a line every half second.
     */
    private static boolean tryStart(ServerPlayer player, Entity entity, Found found, NpcBinding.Proximity proximity, long now) {
        if (asleep(entity) || Dialogues.state(player).isPresent() || Cutscenes.watching(player) != null || NpcReactions.angryAt(entity, player)) {
            return false;
        }
        Map<UUID, Long> starts = LAST_START.computeIfAbsent(player.getUUID(), id -> new HashMap<>());
        Long last = starts.get(entity.getUUID());
        if (last != null && now - last < proximity.cooldown() * 20L) {
            return true;
        }
        ResourceLocation once = proximity.once() ? onceKey(found, entity) : null;
        if (once != null && StoryState.getStored(player, StoryScope.PLAYER, once).isPresent()) {
            return true;
        }
        if (busyWith(found, entity, player) != null) {
            // Coming close to a busy NPC starts nothing, not even the busy line: only talking to it does.
            return false;
        }
        if (found.pick(new StoryContext(player, entity)).isEmpty()) {
            return false;
        }
        Optional<Picked> picked = found.pick(new StoryContext(player, entity), entity, " proximity");
        if (picked.isPresent() && startEntry(player, entity, picked.get())) {
            starts.put(entity.getUUID(), now);
            if (once != null) {
                try {
                    StoryState.set(player, StoryScope.PLAYER, once, new StoryValue.Bool(true));
                } catch (StoryException e) {
                    Storybound.LOGGER.warn("Storybound: could not update {}: {}", once, e.getMessage());
                }
            }
            return true;
        }
        return false;
    }

    /**
     * Asleep in its bed (November 7, 2026): it is not talked to, by key, click or coming close, until it wakes; a hit
     * still wakes it, as in vanilla.
     */
    private static boolean asleep(Entity entity) {
        return entity instanceof LivingEntity living && living.isSleeping();
    }

    /**
     * Whether the NPC is talking, so it does not go to bed (asked by vanilla's {@code SleepInBed}; November 7, 2026):
     * in a dialogue, or its phrase still over its head. It lies down once it is done; before, it went to bed in the
     * middle of a conversation.
     */
    public static boolean keepsAwake(LivingEntity npc) {
        return Dialogues.isEnabled() && npc.level() instanceof net.minecraft.server.level.ServerLevel level
                && (Dialogues.inDialogue(npc) || NpcPhrases.speaking(npc, level.getServer().getTickCount()));
    }

    /** The internal player flag of a {@code once} proximity: of the binding that gave the proximity. */
    public static ResourceLocation onceKey(Found found, Entity entity) {
        ResourceLocation id = found.source("proximity").map(Layer::id).orElse(null);
        return id != null
                ? Storybound.id(ONCE_PREFIX + id.getNamespace() + "/" + id.getPath())
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
        CACHE.clear();
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
            NpcBinding binding = entry.getValue();
            String mismatch = binding.mismatch(target);
            if (mismatch.isEmpty() && binding.crowdShare().isPresent()) {
                mismatch = "not in its share of " + binding.crowdShare().get() + "%";
            }
            if (!mismatch.startsWith("type is ")) {
                reasons.add(entry.getKey() + ": " + mismatch);
            }
        }
        String entity = BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()) + " " + target.getUUID();
        StoryTrace.note(player, "npc " + entity, reasons.isEmpty() ? "no NPC binding is for this kind of entity"
                : "no NPC binding matches: " + String.join(" | ", reasons));
    }

    private static String bindingName(Found found, Entity entity) {
        return found.chain().getFirst().name(entity);
    }

    /**
     * One binding of an entity's chain.
     *
     * @param id   the binding file, or null for the one stored on the entity
     * @param rank how closely it names the entity
     * @param tie  as close as the one before it, which goes first by id
     */
    public record Layer(@Nullable ResourceLocation id, NpcBinding binding, NpcBinding.Rank rank, boolean tie) {
        /** How the trace names it: its id, or the entity whose own binding it is. */
        public String name(Entity entity) {
            return "npc binding " + (id != null ? id.toString() : "of " + entity.getUUID());
        }
    }

    /** What a click gets: an entry of one binding of the chain, at its place in that binding's list. */
    public record Picked(Layer layer, NpcBinding.Entry entry) {
        public int index() {
            return layer.binding().dialogues().indexOf(entry);
        }
    }

    /**
     * The NPC an entity is (see {@link #find}).
     *
     * @param chain   every binding matching it, closest first; never empty
     * @param binding what it ends up with: each setting from the first binding of the chain that writes it, the on/off
     *                ones always written (the default when none does); its {@code dialogues} are all of the chain's in
     *                order, for showing (a click goes by {@link #pick})
     * @param sources setting (its file key, {@code "chatter"}) → the binding it came from; absent: the default
     */
    public record Found(List<Layer> chain, NpcBinding binding, Map<String, Layer> sources) {
        static Found of(List<Layer> chain) {
            Map<String, Layer> sources = new LinkedHashMap<>();
            List<NpcBinding.Entry> dialogues = new ArrayList<>();
            for (Layer layer : chain) {
                dialogues.addAll(layer.binding().dialogues());
                if (!layer.binding().dialogues().isEmpty()) {
                    sources.putIfAbsent("dialogues", layer);
                }
            }
            NpcBinding merged = new NpcBinding(chain.getFirst().binding().entities(), List.copyOf(dialogues),
                    Optional.of(first(chain, sources, "interact", NpcBinding::interact).orElse(true)),
                    first(chain, sources, "proximity", NpcBinding::proximity),
                    Optional.of(first(chain, sources, "custom", NpcBinding::custom).orElse(false)),
                    Optional.of(first(chain, sources, "override_interact", NpcBinding::overrideInteract).orElse(false)),
                    Optional.of(first(chain, sources, "exclusive", NpcBinding::exclusive).orElse(false)),
                    first(chain, sources, "busy_dialogue", NpcBinding::busyDialogue),
                    // One picked by the mod comes back unless its binding says otherwise: a story NPC, not one of a crowd.
                    Optional.of(first(chain, sources, "respawn", binding -> binding.isOne() ? Optional.of(binding.respawn().orElse(true)) : binding.respawn())
                            .orElse(false)),
                    Optional.of(first(chain, sources, "invulnerable", NpcBinding::invulnerable).orElse(false)),
                    first(chain, sources, "when_attacked", NpcBinding::whenAttacked),
                    first(chain, sources, "chatter", NpcBinding::chatter),
                    Optional.of(first(chain, sources, "show_name", NpcBinding::showName).orElse(true)),
                    // A name only from a binding that names one NPC: one for every entity of a kind gives names instead.
                    first(byHand(chain), sources, "display_name", NpcBinding::displayName),
                    first(dynamic(chain), sources, "names", binding -> binding.names().isEmpty() ? Optional.empty() : Optional.of(binding.names()))
                            .orElse(List.of()), false, Optional.empty());
            return new Found(List.copyOf(chain), merged, Collections.unmodifiableMap(sources));
        }

        /** The bindings by hand (and the one stored on the entity), which name one NPC each. */
        private static List<Layer> byHand(List<Layer> chain) {
            return chain.stream().filter(layer -> layer.rank().by() >= NpcBinding.Rank.TAG).toList();
        }

        /** The bindings for every entity of a kind. */
        private static List<Layer> dynamic(List<Layer> chain) {
            return chain.stream().filter(layer -> layer.rank().by() == NpcBinding.Rank.KIND).toList();
        }

        /** The value of the first binding of the chain that writes the setting, noting where it came from. */
        private static <T> Optional<T> first(List<Layer> chain, Map<String, Layer> sources, String key, Function<NpcBinding, Optional<T>> field) {
            for (Layer layer : chain) {
                Optional<T> value = field.apply(layer.binding());
                if (value.isPresent()) {
                    sources.put(key, layer);
                    return value;
                }
            }
            return Optional.empty();
        }

        /** The closest binding: its id (null for one stored on the entity). */
        public @Nullable ResourceLocation id() {
            return chain.getFirst().id();
        }

        /** The binding a setting came from; empty when it is the default. */
        public Optional<Layer> source(String key) {
            return Optional.ofNullable(sources.get(key));
        }

        public boolean interact() {
            return binding.interact().orElse(true);
        }

        /** The binding that gave {@code custom: true}, named to scripts on a click; null when off (or one stored on the entity). */
        public @Nullable ResourceLocation customId() {
            return binding.custom().orElse(false) ? source("custom").map(Layer::id).orElse(null) : null;
        }

        public boolean overrideInteract() {
            return binding.overrideInteract().orElse(false);
        }

        public boolean exclusive() {
            return binding.exclusive().orElse(false);
        }

        public boolean respawn() {
            return binding.respawn().orElse(false);
        }

        public boolean invulnerable() {
            return binding.invulnerable().orElse(false);
        }

        public boolean showName() {
            return binding.showName().orElse(true);
        }

        /** What a click gets from this player, asked quietly: see {@link #pick(StoryContext, Entity, String)}. */
        public Optional<Picked> pick(StoryContext context) {
            return pick(context, null, "");
        }

        /**
         * What a click gets from this player: the dialogues entries binding by binding, closest first (each binding's by
         * priority), the first whose conditions pass. So an elder's own dialogue comes before what every villager says.
         *
         * @param traced the NPC, to write each binding's checks to the trace; null to ask quietly
         * @param what   added to each binding's name in the trace, " proximity"
         */
        public Optional<Picked> pick(StoryContext context, @Nullable Entity traced, String what) {
            for (Layer layer : chain) {
                Optional<NpcBinding.Entry> entry = layer.binding().pick(context, traced == null ? null : layer.name(traced) + what);
                if (entry.isPresent()) {
                    return Optional.of(new Picked(layer, entry.get()));
                }
            }
            return Optional.empty();
        }
    }

    /**
     * What an entity was worked out to be, at a game tick, with what it was known by then: the tags, the name and its own
     * binding, which a story may change at any moment (its kind never changes; its profession or place may lag a second).
     */
    private record Cached(long tick, Set<String> tags, @Nullable Component name, boolean own, Optional<Found> found) {
        boolean same(Entity entity) {
            return tags.equals(entity.getTags()) && Objects.equals(name, entity.getCustomName())
                    && own == entity.hasData(DialogueRegistries.NPC_BINDING);
        }
    }
}
