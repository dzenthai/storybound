package dev.dzenthai.storybound.module.world;

import dev.dzenthai.storybound.core.logic.Condition;
import dev.dzenthai.storybound.core.logic.StoryContext;
import dev.dzenthai.storybound.core.logic.StoryTrace;
import dev.dzenthai.storybound.core.logic.StructureRef;
import dev.dzenthai.storybound.core.text.ResolvedText;
import dev.dzenthai.storybound.core.text.StoryText;
import dev.dzenthai.storybound.module.boss.Bosses;
import dev.dzenthai.storybound.module.dialogue.DialogueRegistries;
import dev.dzenthai.storybound.module.dialogue.NpcBindings;
import dev.dzenthai.storybound.module.world.WorldRuleDefinition.HazardEffect;
import dev.dzenthai.storybound.module.world.WorldRuleDefinition.ItemLock;
import dev.dzenthai.storybound.module.world.WorldRuleDefinition.LootModifierRule;
import dev.dzenthai.storybound.module.world.WorldRuleDefinition.MobLock;
import dev.dzenthai.storybound.module.world.WorldRuleDefinition.PlaceLock;
import dev.dzenthai.storybound.module.world.WorldRuleDefinition.PlaceMode;
import dev.dzenthai.storybound.network.StoryboundNetwork;
import dev.dzenthai.storybound.network.WorldLocksPayload;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

/**
 * Runtime index and enforcement engine for {@link WorldRuleDefinition}. Caches resolved item and mob maps on
 * reload so hot gameplay events (interact, attack, pickup, mob spawn, server tick) short-circuit in O(1) when
 * no matching rule exists.
 */
public final class WorldRules {
    public static final double MOB_UNLOCK_RADIUS = 128.0;
    public static final String EXEMPT_TAG = "storybound:mob_lock_exempt";
    private static final double MOB_UNLOCK_RADIUS_SQR = MOB_UNLOCK_RADIUS * MOB_UNLOCK_RADIUS;
    private static final long NOTIFY_COOLDOWN_MS = 1500;
    private static final long HAZARD_NOTIFY_COOLDOWN_MS = 3000;
    private static final long BARRIER_FX_COOLDOWN_MS = 650;
    private static final long BARRIER_SETTLE_TICKS = 4;
    private static final DustParticleOptions BARRIER_DUST = new DustParticleOptions(new Vector3f(0.95f, 0.35f, 0.25f), 0.9f);
    private static final int[] BIOME_EXIT_RADII = {4, 8, 12, 18, 26, 36, 48, 64, 88, 112};
    private static final int BIOME_EXIT_DIRECTIONS = 16;

    public record Entry<T extends WorldRuleDefinition>(ResourceLocation id, T rule) {}

    public enum ItemAction {
        USE, ATTACK, EQUIP, CRAFT, PICKUP
    }

    private record ItemIndexEntry(ResourceLocation id, ItemLock rule, List<ResourceLocation> resolvedItemIds) {}

    private record SafePos(ResourceKey<Level> dimension, Vec3 pos) {}

    private record LastCheck(ResourceKey<Level> dimension, BlockPos blockPos, boolean blocked) {}

    private record MobSweepHit(Mob mob, Entry<MobLock> entry) {}

    private static volatile List<ItemIndexEntry> itemLocks = List.of();
    private static volatile Map<Item, List<Entry<ItemLock>>> itemLocksByItem = Map.of();
    private static volatile Map<EntityType<?>, List<Entry<MobLock>>> mobLocksByType = Map.of();
    private static volatile boolean hasDespawnMobLocks;
    private static volatile List<Entry<PlaceLock>> placeLocks = List.of();
    private static volatile boolean hasBarrierPlaceLocks;
    private static volatile boolean hasHazardPlaceLocks;
    private static volatile List<Entry<LootModifierRule>> lootRules = List.of();

    private static final Map<UUID, List<WorldLocksPayload.Entry>> LAST_SYNCED = new HashMap<>();
    private static final Map<UUID, SafePos> LAST_SAFE = new HashMap<>();
    private static final Map<UUID, LastCheck> LAST_CHECK = new HashMap<>();
    private static final Map<UUID, Long> BARRIER_COOLDOWN_UNTIL = new HashMap<>();
    private static final Map<UUID, Long> LAST_BARRIER_FX_MS = new HashMap<>();
    private static final Map<UUID, Long> LAST_NOTIFY_MS = new HashMap<>();
    private static final Map<UUID, Long> LAST_HAZARD_NOTIFY_MS = new HashMap<>();

    /** Client-side hook registered by {@code StoryboundClient} so {@code SlotMixin} stays server-safe. */
    private static volatile Predicate<ItemStack> clientCraftBlocker = stack -> false;

    private WorldRules() {}

    public static void setClientCraftBlocker(Predicate<ItemStack> blocker) {
        clientCraftBlocker = blocker;
    }

    /** Rebuilds all runtime lookup tables from the live {@code WORLD_RULES} server store. */
    public static void rebuild() {
        List<ItemIndexEntry> nextItems = new ArrayList<>();
        Map<Item, List<Entry<ItemLock>>> nextItemMap = new IdentityHashMap<>();
        Map<EntityType<?>, List<Entry<MobLock>>> nextMobMap = new IdentityHashMap<>();
        List<Entry<PlaceLock>> nextPlaces = new ArrayList<>();
        List<Entry<LootModifierRule>> nextLoot = new ArrayList<>();
        boolean despawnMobs = false;
        boolean barriers = false;
        boolean hazards = false;

        for (Map.Entry<ResourceLocation, WorldRuleDefinition> mapEntry : WorldRuleRegistries.WORLD_RULES.serverStore().all().entrySet()) {
            ResourceLocation id = mapEntry.getKey();
            WorldRuleDefinition def = mapEntry.getValue();
            if (!def.enabled()) {
                continue;
            }
            switch (def) {
                case ItemLock itemLock -> {
                    Set<Item> resolved = WorldRuleDefinition.resolveItems(itemLock.items());
                    if (resolved.isEmpty()) {
                        continue;
                    }
                    List<ResourceLocation> ids = new ArrayList<>(resolved.size());
                    Entry<ItemLock> entry = new Entry<>(id, itemLock);
                    for (Item item : resolved) {
                        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(item);
                        ids.add(itemId);
                        nextItemMap.computeIfAbsent(item, k -> new ArrayList<>()).add(entry);
                    }
                    nextItems.add(new ItemIndexEntry(id, itemLock, List.copyOf(ids)));
                }
                case MobLock mobLock -> {
                    Set<EntityType<?>> resolved = WorldRuleDefinition.resolveEntities(mobLock.entities());
                    if (resolved.isEmpty()) {
                        continue;
                    }
                    if (mobLock.despawnExisting()) {
                        despawnMobs = true;
                    }
                    Entry<MobLock> entry = new Entry<>(id, mobLock);
                    for (EntityType<?> entityType : resolved) {
                        nextMobMap.computeIfAbsent(entityType, k -> new ArrayList<>()).add(entry);
                    }
                }
                case PlaceLock placeLock -> {
                    nextPlaces.add(new Entry<>(id, placeLock));
                    if (placeLock.mode() == PlaceMode.BARRIER) {
                        barriers = true;
                    } else {
                        hazards = true;
                    }
                }
                case LootModifierRule lootRule -> nextLoot.add(new Entry<>(id, lootRule));
            }
        }

        itemLocks = List.copyOf(nextItems);
        itemLocksByItem = nextItemMap;
        mobLocksByType = nextMobMap;
        hasDespawnMobLocks = despawnMobs;
        placeLocks = List.copyOf(nextPlaces);
        hasBarrierPlaceLocks = barriers;
        hasHazardPlaceLocks = hazards;
        lootRules = List.copyOf(nextLoot);
        LAST_SYNCED.clear();
        LAST_CHECK.clear();
    }

    public static boolean hasItemLocks() {
        return !itemLocks.isEmpty();
    }

    public static boolean hasMobLocks() {
        return !mobLocksByType.isEmpty();
    }

    public static boolean hasDespawnMobLocks() {
        return hasDespawnMobLocks;
    }

    public static boolean hasPlaceLocks() {
        return !placeLocks.isEmpty();
    }

    public static boolean hasBarrierPlaceLocks() {
        return hasBarrierPlaceLocks;
    }

    public static boolean hasHazardPlaceLocks() {
        return hasHazardPlaceLocks;
    }

    public static List<Entry<LootModifierRule>> lootRules() {
        return lootRules;
    }

    // --- item locks

    /**
     * Checks creative/spectator bypass on the server via {@code gameMode} so GameTest mock players (whose
     * {@code isCreative()} is hardcoded to true) still respect {@code setGameMode(SURVIVAL)}.
     */
    private static boolean bypassesLocks(ServerPlayer player) {
        return player.gameMode.isCreative() || player.isSpectator();
    }

    /**
     * Returns the first active {@link ItemLock} blocking {@code action} on {@code stack} for {@code player}, or null.
     * Creative players always bypass item locks.
     */
    public static @Nullable Entry<ItemLock> findItemLock(ServerPlayer player, ItemStack stack, ItemAction action) {
        if (stack.isEmpty() || bypassesLocks(player) || itemLocksByItem.isEmpty()) {
            return null;
        }
        List<Entry<ItemLock>> candidates = itemLocksByItem.get(stack.getItem());
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        StoryContext context = null;
        for (Entry<ItemLock> entry : candidates) {
            if (!blocksAction(entry.rule(), action)) {
                continue;
            }
            if (entry.rule().unlock().isPresent()) {
                if (context == null) {
                    context = StoryContext.of(player);
                }
                if (entry.rule().unlock().get().test(context)) {
                    continue;
                }
            }
            return entry;
        }
        return null;
    }

    private static boolean blocksAction(ItemLock rule, ItemAction action) {
        return switch (action) {
            case USE -> rule.blockUse();
            case ATTACK -> rule.blockAttack();
            case EQUIP -> rule.blockEquip();
            case CRAFT -> rule.blockCraft();
            case PICKUP -> rule.blockPickup();
        };
    }

    /** Called from {@code SlotMixin} on both sides; notifies on the server when blocked. */
    public static boolean isCraftBlocked(Player player, ItemStack stack) {
        if (stack.isEmpty() || player.isSpectator()) {
            return false;
        }
        if (player instanceof ServerPlayer serverPlayer) {
            if (bypassesLocks(serverPlayer)) {
                return false;
            }
            Entry<ItemLock> lock = findItemLock(serverPlayer, stack, ItemAction.CRAFT);
            if (lock != null) {
                notifyItemBlocked(serverPlayer, lock.rule());
                return true;
            }
            return false;
        }
        return !player.isCreative() && player.level().isClientSide() && clientCraftBlocker.test(stack);
    }

    public static void notifyItemBlocked(ServerPlayer player, ItemLock rule) {
        notifyActionBar(player, LAST_NOTIFY_MS, NOTIFY_COOLDOWN_MS, resolveMessage(player, rule.message(), "storybound.world.item_locked"));
    }

    public static void notifyPlaceBlocked(ServerPlayer player, PlaceLock rule, boolean hazard) {
        String fallbackKey = hazard ? "storybound.world.place_hazard" : "storybound.world.place_locked";
        notifyActionBar(player, hazard ? LAST_HAZARD_NOTIFY_MS : LAST_NOTIFY_MS,
                hazard ? HAZARD_NOTIFY_COOLDOWN_MS : NOTIFY_COOLDOWN_MS,
                resolveMessage(player, rule.message(), fallbackKey));
    }

    private static Component resolveMessage(ServerPlayer player, Optional<StoryText> text, String defaultKey) {
        if (text.isPresent()) {
            ResolvedText resolved = text.get().resolve(player);
            if (resolved.literal()) {
                return Component.literal(resolved.key());
            }
            return Component.translatableWithFallback(resolved.key(), resolved.fallback().orElse(null), resolved.args().toArray());
        }
        return Component.translatable(defaultKey);
    }

    private static void notifyActionBar(ServerPlayer player, Map<UUID, Long> cooldowns, long cooldownMs, Component message) {
        long now = System.currentTimeMillis();
        Long last = cooldowns.get(player.getUUID());
        if (last != null && now - last < cooldownMs) {
            return;
        }
        cooldowns.put(player.getUUID(), now);
        player.displayClientMessage(message.copy().withStyle(ChatFormatting.RED), true);
    }

    // --- client sync for item locks

    /** Computes active item locks for {@code player} and sends {@link WorldLocksPayload} if changed (or forced). */
    public static void syncPlayer(ServerPlayer player, boolean force) {
        List<WorldLocksPayload.Entry> active = computeClientLocks(player);
        List<WorldLocksPayload.Entry> previous = LAST_SYNCED.get(player.getUUID());
        if (!force && active.equals(previous)) {
            return;
        }
        LAST_SYNCED.put(player.getUUID(), active);
        StoryboundNetwork.sendToPlayer(player, new WorldLocksPayload(active));
    }

    public static void syncAll(MinecraftServer server, boolean force) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            syncPlayer(player, force);
        }
    }

    private static List<WorldLocksPayload.Entry> computeClientLocks(ServerPlayer player) {
        if (itemLocks.isEmpty() || bypassesLocks(player)) {
            return List.of();
        }
        List<WorldLocksPayload.Entry> result = new ArrayList<>();
        StoryContext context = StoryContext.of(player);
        for (ItemIndexEntry entry : itemLocks) {
            ItemLock rule = entry.rule();
            if (rule.unlock().isPresent() && rule.unlock().get().test(context)) {
                continue;
            }
            byte flags = packFlags(rule);
            if (flags == 0) {
                continue;
            }
            Optional<ResolvedText> message = rule.message().map(text -> text.resolve(player));
            result.add(new WorldLocksPayload.Entry(entry.resolvedItemIds(), flags, message));
        }
        return List.copyOf(result);
    }

    private static byte packFlags(ItemLock rule) {
        byte flags = 0;
        if (rule.blockUse()) flags |= WorldLocksPayload.FLAG_USE;
        if (rule.blockAttack()) flags |= WorldLocksPayload.FLAG_ATTACK;
        if (rule.blockEquip()) flags |= WorldLocksPayload.FLAG_EQUIP;
        if (rule.blockCraft()) flags |= WorldLocksPayload.FLAG_CRAFT;
        if (rule.blockPickup()) flags |= WorldLocksPayload.FLAG_PICKUP;
        if (rule.hideInJei()) flags |= WorldLocksPayload.FLAG_HIDE_JEI;
        return flags;
    }

    // --- mob spawn locks & existing mob removal

    /**
     * Checks whether a mob spawn should be blocked. Returns the blocking {@link Entry} if blocked, or null if allowed.
     */
    public static @Nullable Entry<MobLock> checkMobSpawn(ServerLevel level, Entity entity, MobSpawnType spawnType, double x, double y, double z) {
        if (mobLocksByType.isEmpty() || entity.getTags().contains(EXEMPT_TAG)) {
            return null;
        }
        List<Entry<MobLock>> candidates = mobLocksByType.get(entity.getType());
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        boolean natural = isNaturalSpawn(spawnType);
        boolean spawner = isSpawnerSpawn(spawnType);
        if (!natural && !spawner) {
            return null;
        }
        return matchMobCandidates(level, candidates, spawner, false, x, y, z);
    }

    /**
     * Returns the first active {@link MobLock} with {@code despawn_existing} that should remove {@code mob}, or null
     * if allowed or protected.
     */
    public static @Nullable Entry<MobLock> findDespawnMobLock(ServerLevel level, Mob mob) {
        if (!hasDespawnMobLocks || mob.getTags().contains(EXEMPT_TAG)) {
            return null;
        }
        List<Entry<MobLock>> candidates = mobLocksByType.get(mob.getType());
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        Entry<MobLock> match = matchMobCandidates(level, candidates, false, true, mob.getX(), mob.getY(), mob.getZ());
        if (match == null || isProtectedFromDespawn(mob)) {
            return null;
        }
        return match;
    }

    private static @Nullable Entry<MobLock> matchMobCandidates(ServerLevel level, List<Entry<MobLock>> candidates,
                                                               boolean spawner, boolean requireDespawnExisting,
                                                               double x, double y, double z) {
        BlockPos pos = BlockPos.containing(x, y, z);
        Holder<Biome> biome = null;
        for (Entry<MobLock> entry : candidates) {
            MobLock rule = entry.rule();
            if (requireDespawnExisting && !rule.despawnExisting()) {
                continue;
            }
            if (spawner && !rule.blockSpawners()) {
                continue;
            }
            if (rule.dimension().isPresent() && !level.dimension().equals(rule.dimension().get())) {
                continue;
            }
            if (rule.biome().isPresent()) {
                if (biome == null) {
                    biome = level.getBiome(pos);
                }
                if (!rule.biome().get().contains(biome)) {
                    continue;
                }
            }
            if (rule.structure().isPresent()
                    && rule.structure().get().stream().noneMatch(ref -> ref.contains(level, pos))) {
                continue;
            }
            if (rule.unlock().isPresent() && anyNearbyPlayerUnlocked(level, x, y, z, rule.unlock().get())) {
                continue;
            }
            return entry;
        }
        return null;
    }

    /**
     * Entities never removed by {@code despawn_existing}: custom-named mobs, tamed pets, leashed mobs,
     * player-ridden mounts, Storybound NPCs/bosses, and entities tagged {@link #EXEMPT_TAG} (summoned, bred,
     * bucketed, replacement, or allowed spawner mobs).
     */
    public static boolean isProtectedFromDespawn(Mob mob) {
        if (mob.getTags().contains(EXEMPT_TAG) || mob.hasCustomName() || mob.isLeashed()) {
            return true;
        }
        if (mob instanceof TamableAnimal tamable && tamable.isTame()) {
            return true;
        }
        if (mob.getControllingPassenger() instanceof Player) {
            return true;
        }
        for (Entity passenger : mob.getPassengers()) {
            if (passenger instanceof Player) {
                return true;
            }
        }
        if (mob.hasData(DialogueRegistries.NPC_BINDING) || NpcBindings.find(mob).isPresent()) {
            return true;
        }
        return Bosses.bossOf(mob).isPresent();
    }

    /**
     * Removes (and optionally replaces) already-spawned unprotected mobs matching active {@code despawn_existing}
     * rules across all loaded levels.
     */
    public static int sweepExistingMobs(MinecraftServer server) {
        if (!hasDespawnMobLocks) {
            return 0;
        }
        int removed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            List<MobSweepHit> hits = null;
            for (Entity entity : level.getAllEntities()) {
                if (!(entity instanceof Mob mob) || !mob.isAlive() || mob.isRemoved()) {
                    continue;
                }
                if (!mobLocksByType.containsKey(mob.getType())) {
                    continue;
                }
                Entry<MobLock> match = findDespawnMobLock(level, mob);
                if (match != null) {
                    if (hits == null) {
                        hits = new ArrayList<>();
                    }
                    hits.add(new MobSweepHit(mob, match));
                }
            }
            if (hits == null) {
                continue;
            }
            for (MobSweepHit hit : hits) {
                Mob mob = hit.mob();
                if (!mob.isAlive() || mob.isRemoved()) {
                    continue;
                }
                double x = mob.getX();
                double y = mob.getY();
                double z = mob.getZ();
                float yRot = mob.getYRot();
                float halfH = mob.getBbHeight() * 0.5f;
                mob.discard();
                removed++;
                Optional<Holder<EntityType<?>>> replacement = hit.entry().rule().replaceWith();
                if (replacement.isPresent()) {
                    spawnMobReplacement(level, replacement.get().value(), x, y, z, yRot);
                } else {
                    level.sendParticles(ParticleTypes.POOF, x, y + halfH, z, 4, 0.25, 0.25, 0.25, 0.02);
                }
            }
        }
        return removed;
    }

    /** Schedules spawning the replacement mob on the server tick so chunk generation never deadlocks or recurses. */
    public static void scheduleMobReplacement(ServerLevel level, EntityType<?> replacementType, double x, double y, double z, float yRot) {
        level.getServer().execute(() -> spawnMobReplacement(level, replacementType, x, y, z, yRot));
    }

    @SuppressWarnings("deprecation")
    private static void spawnMobReplacement(ServerLevel level, EntityType<?> replacementType, double x, double y, double z, float yRot) {
        BlockPos pos = BlockPos.containing(x, y, z);
        if (!level.isLoaded(pos)) {
            return;
        }
        Entity spawned = replacementType.create(level);
        if (spawned == null) {
            return;
        }
        spawned.addTag(EXEMPT_TAG);
        spawned.moveTo(x, y, z, yRot, 0.0f);
        if (spawned instanceof Mob mob) {
            mob.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.CONVERSION, null);
        }
        spawned.getSelfAndPassengers().forEach(part -> part.addTag(EXEMPT_TAG));
        level.addFreshEntityWithPassengers(spawned);
    }

    /** Spawn types explicitly spared from {@code despawn_existing} once allowed to spawn. */
    public static boolean isExemptibleSpawn(MobSpawnType type) {
        return type == MobSpawnType.COMMAND
                || type == MobSpawnType.BREEDING
                || type == MobSpawnType.BUCKET
                || type == MobSpawnType.CONVERSION
                || isSpawnerSpawn(type);
    }

    private static boolean isNaturalSpawn(MobSpawnType type) {
        return type == MobSpawnType.NATURAL
                || type == MobSpawnType.CHUNK_GENERATION
                || type == MobSpawnType.PATROL
                || type == MobSpawnType.REINFORCEMENT
                || type == MobSpawnType.STRUCTURE
                || type == MobSpawnType.JOCKEY
                || type == MobSpawnType.EVENT
                || type == MobSpawnType.TRIGGERED;
    }

    private static boolean isSpawnerSpawn(MobSpawnType type) {
        return type == MobSpawnType.SPAWNER || type == MobSpawnType.TRIAL_SPAWNER;
    }

    private static boolean anyNearbyPlayerUnlocked(ServerLevel level, double x, double y, double z, Condition unlock) {
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) {
                continue;
            }
            if (player.distanceToSqr(x, y, z) <= MOB_UNLOCK_RADIUS_SQR && unlock.test(StoryContext.of(player))) {
                return true;
            }
        }
        return false;
    }

    // --- place locks

    /** Returns the first dimension-wide barrier rule blocking travel to {@code targetDimension}, or null. */
    public static @Nullable Entry<PlaceLock> checkDimensionTravel(ServerPlayer player, ResourceKey<Level> targetDimension) {
        if (placeLocks.isEmpty() || bypassesLocks(player)) {
            return null;
        }
        StoryContext context = null;
        for (Entry<PlaceLock> entry : placeLocks) {
            PlaceLock rule = entry.rule();
            if (rule.mode() != PlaceMode.BARRIER || !rule.isDimensionWide()) {
                continue;
            }
            if (!rule.dimension().get().equals(targetDimension)) {
                continue;
            }
            if (rule.unlock().isPresent()) {
                if (context == null) {
                    context = StoryContext.of(player);
                }
                if (rule.unlock().get().test(context)) {
                    continue;
                }
            }
            return entry;
        }
        return null;
    }

    /** Convenience overload checking both barriers and (when {@code applyHazard}) hazards. */
    public static void checkPlaceLocks(ServerPlayer player, boolean applyHazard) {
        checkPlaceLocks(player, true, applyHazard);
    }

    /**
     * Periodic check for biome, structure, and dimension place locks. Barrier checks run every 2 ticks so players
     * meet a firm invisible wall without penetrating deep into locked areas; hazard checks run every 20 ticks.
     */
    public static void checkPlaceLocks(ServerPlayer player, boolean checkBarrier, boolean applyHazard) {
        if (placeLocks.isEmpty() || !player.isAlive() || bypassesLocks(player)) {
            return;
        }
        ServerLevel level = player.serverLevel();
        BlockPos pos = player.blockPosition();
        UUID uuid = player.getUUID();

        if (checkBarrier && hasBarrierPlaceLocks) {
            long gameTime = level.getGameTime();
            Long cooldownUntil = BARRIER_COOLDOWN_UNTIL.get(uuid);
            if (cooldownUntil == null || gameTime >= cooldownUntil) {
                LastCheck last = LAST_CHECK.get(uuid);
                boolean unchangedOutside = last != null && !last.blocked()
                        && last.dimension().equals(level.dimension())
                        && last.blockPos().equals(pos);
                if (!unchangedOutside) {
                    Entry<PlaceLock> barrierHit = findActiveBarrier(level, player, pos);
                    if (barrierHit != null) {
                        LAST_CHECK.put(uuid, new LastCheck(level.dimension(), pos, true));
                        expelFromBarrier(player, level, barrierHit);
                    } else {
                        LAST_CHECK.put(uuid, new LastCheck(level.dimension(), pos, false));
                        recordSafePosition(player, level.dimension());
                    }
                }
            }
        }

        if (applyHazard && hasHazardPlaceLocks) {
            applyHazards(player, level, player.blockPosition());
        }
    }

    private static void recordSafePosition(ServerPlayer player, ResourceKey<Level> dimension) {
        UUID uuid = player.getUUID();
        Vec3 current = player.position();
        SafePos prev = LAST_SAFE.get(uuid);
        if (prev == null || !prev.dimension().equals(dimension)) {
            LAST_SAFE.put(uuid, new SafePos(dimension, current));
            return;
        }
        // Prefer grounded/water/flying spots with >= 0.75 block spacing so sprint-jumping returns to solid ground.
        boolean stableFooting = player.onGround() || player.isInWater() || player.getAbilities().flying;
        if (stableFooting && prev.pos().distanceToSqr(current) >= 0.75 * 0.75) {
            LAST_SAFE.put(uuid, new SafePos(dimension, current));
        }
    }

    private static @Nullable Entry<PlaceLock> findActiveBarrier(ServerLevel level, ServerPlayer player, BlockPos pos) {
        Holder<Biome> biome = null;
        StoryContext context = null;
        for (Entry<PlaceLock> entry : placeLocks) {
            PlaceLock rule = entry.rule();
            if (rule.mode() != PlaceMode.BARRIER) {
                continue;
            }
            if (rule.dimension().isPresent() && !level.dimension().equals(rule.dimension().get())) {
                continue;
            }
            if (rule.biome().isPresent()) {
                if (biome == null) {
                    biome = level.getBiome(pos);
                }
                if (!rule.biome().get().contains(biome)) {
                    continue;
                }
            }
            if (rule.structure().isPresent()
                    && rule.structure().get().stream().noneMatch(ref -> ref.contains(level, pos))) {
                continue;
            }
            if (rule.unlock().isPresent()) {
                if (context == null) {
                    context = StoryContext.of(player);
                }
                if (rule.unlock().get().test(context)) {
                    continue;
                }
            }
            return entry;
        }
        return null;
    }

    private static void applyHazards(ServerPlayer player, ServerLevel level, BlockPos pos) {
        Holder<Biome> biome = null;
        StoryContext context = null;
        for (Entry<PlaceLock> entry : placeLocks) {
            PlaceLock rule = entry.rule();
            if (rule.mode() != PlaceMode.HAZARD) {
                continue;
            }
            if (rule.dimension().isPresent() && !level.dimension().equals(rule.dimension().get())) {
                continue;
            }
            if (rule.biome().isPresent()) {
                if (biome == null) {
                    biome = level.getBiome(pos);
                }
                if (!rule.biome().get().contains(biome)) {
                    continue;
                }
            }
            if (rule.structure().isPresent()
                    && rule.structure().get().stream().noneMatch(ref -> ref.contains(level, pos))) {
                continue;
            }
            if (rule.unlock().isPresent()) {
                if (context == null) {
                    context = StoryContext.of(player);
                }
                if (rule.unlock().get().test(context)) {
                    continue;
                }
            }
            for (HazardEffect effect : rule.effects()) {
                player.addEffect(effect.createInstance());
            }
            if (rule.damage() > 0.0f) {
                player.hurt(level.damageSources().magic(), rule.damage());
            }
            notifyPlaceBlocked(player, rule, true);
            StoryTrace.note(player, "world_rule " + entry.id(), "hazard applied");
        }
    }

    /**
     * Smoothly moves a player back outside a barrier using purely geometric outward vectors (structure bounding box
     * edges, verified safe outside breadcrumbs, or expanding ring search for biomes). Never uses the player's look
     * direction, so turning around cannot bypass or invert the barrier.
     */
    private static void expelFromBarrier(ServerPlayer player, ServerLevel level, Entry<PlaceLock> entry) {
        PlaceLock rule = entry.rule();
        UUID uuid = player.getUUID();
        Vec3 current = player.position();
        SafePos safe = LAST_SAFE.get(uuid);

        // 1. Dimension-wide barrier when already inside the locked dimension
        if (rule.isDimensionWide()) {
            if (safe != null && !safe.dimension().equals(level.dimension())) {
                ServerLevel safeLevel = player.server.getLevel(safe.dimension());
                if (safeLevel != null && findActiveBarrier(safeLevel, player, BlockPos.containing(safe.pos())) == null) {
                    player.stopRiding();
                    player.teleportTo(safeLevel, safe.pos().x, safe.pos().y, safe.pos().z, player.getYRot(), player.getXRot());
                    player.resetFallDistance();
                    BARRIER_COOLDOWN_UNTIL.put(uuid, safeLevel.getGameTime() + BARRIER_SETTLE_TICKS);
                    notifyPlaceBlocked(player, rule, false);
                    StoryTrace.note(player, "world_rule " + entry.id(), "returned from locked dimension");
                    return;
                }
            }
            ServerLevel overworld = player.server.overworld();
            if (!level.dimension().equals(overworld.dimension())) {
                BlockPos spawn = overworld.getSharedSpawnPos();
                if (findActiveBarrier(overworld, player, spawn) == null) {
                    Vec3 target = findSafeStandingPos(overworld, spawn.getX(), spawn.getY(), spawn.getZ());
                    player.stopRiding();
                    player.teleportTo(overworld, target.x, target.y, target.z, player.getYRot(), player.getXRot());
                    player.resetFallDistance();
                    LAST_SAFE.put(uuid, new SafePos(overworld.dimension(), target));
                    BARRIER_COOLDOWN_UNTIL.put(uuid, overworld.getGameTime() + BARRIER_SETTLE_TICKS);
                    notifyPlaceBlocked(player, rule, false);
                    StoryTrace.note(player, "world_rule " + entry.id(), "returned to overworld spawn");
                    return;
                }
            }
        }

        // 2. Normal approach from outside: use verified LAST_SAFE if it is still outside all active barriers
        Vec3 exitPos = null;
        Vec3 outward = null;
        if (safe != null
                && safe.dimension().equals(level.dimension())
                && safe.pos().distanceToSqr(current) < 48.0 * 48.0
                && findActiveBarrier(level, player, BlockPos.containing(safe.pos())) == null) {
            outward = computeOutwardNormal(level, rule, current, safe.pos());
            exitPos = safe.pos();
            // Add a small extra buffer if LAST_SAFE sits very close to the boundary line
            if (exitPos.distanceToSqr(current) < 0.85 * 0.85 && outward.lengthSqr() > 1.0e-4) {
                Vec3 stepped = exitPos.add(outward.scale(0.65));
                BlockPos steppedBlock = BlockPos.containing(stepped);
                if (hasFreeStandSpace(level, steppedBlock) && findActiveBarrier(level, player, steppedBlock) == null) {
                    exitPos = stepped;
                }
            }
        }

        // 3. Player started inside the locked area (no valid LAST_SAFE): compute a deterministic geometric exit
        if (exitPos == null) {
            exitPos = findGeometricExit(level, player, rule, current);
            if (exitPos != null) {
                outward = horizontalNormalize(exitPos.subtract(current));
            }
        }

        if (exitPos != null) {
            if (outward == null || outward.lengthSqr() < 1.0e-4) {
                outward = horizontalNormalize(exitPos.subtract(current));
            }
            movePlayerSmoothly(player, exitPos, outward);
            LAST_SAFE.put(uuid, new SafePos(level.dimension(), exitPos));
            LAST_CHECK.put(uuid, new LastCheck(level.dimension(), BlockPos.containing(exitPos), false));
            BARRIER_COOLDOWN_UNTIL.put(uuid, level.getGameTime() + BARRIER_SETTLE_TICKS);
            Vec3 wallPoint = exitPos.distanceToSqr(current) <= 16.0 ? current.lerp(exitPos, 0.5) : exitPos.subtract(outward.scale(1.2));
            playBarrierFeedback(player, level, wallPoint, outward);
        } else {
            // Ultimate fallback if the entire loaded region is locked: nudge horizontally toward world spawn (never look angle)
            BlockPos spawn = level.getSharedSpawnPos();
            Vec3 toSpawn = horizontalNormalize(new Vec3(spawn.getX() + 0.5 - current.x, 0.0, spawn.getZ() + 0.5 - current.z));
            if (toSpawn.lengthSqr() < 1.0e-4) {
                toSpawn = new Vec3(1.0, 0.0, 0.0);
            }
            Entity mover = player.getRootVehicle();
            mover.setDeltaMovement(toSpawn.scale(0.35));
            mover.hurtMarked = true;
            BARRIER_COOLDOWN_UNTIL.put(uuid, level.getGameTime() + BARRIER_SETTLE_TICKS);
        }

        notifyPlaceBlocked(player, rule, false);
        StoryTrace.note(player, "world_rule " + entry.id(), "blocked by barrier");
    }

    /** Moves the player (or their mount) to {@code target} with gentle outward velocity and no vertical bounce. */
    private static void movePlayerSmoothly(ServerPlayer player, Vec3 target, Vec3 outward) {
        Vec3 gentleVelocity = outward.lengthSqr() > 1.0e-4 ? outward.scale(0.18) : Vec3.ZERO;
        if (player.isFallFlying()) {
            player.stopFallFlying();
        }
        if (player.isPassenger()) {
            Entity vehicle = player.getRootVehicle();
            vehicle.teleportTo(target.x, target.y, target.z);
            vehicle.setDeltaMovement(gentleVelocity.x, 0.0, gentleVelocity.z);
            vehicle.hurtMarked = true;
            vehicle.resetFallDistance();
        } else {
            player.teleportTo(target.x, target.y, target.z);
            player.setDeltaMovement(gentleVelocity.x, 0.0, gentleVelocity.z);
            player.hurtMarked = true;
            player.resetFallDistance();
        }
    }

    /** Prefers the nearest structure bounding-box face normal when inside a locked structure; else {@code safe - current}. */
    private static Vec3 computeOutwardNormal(ServerLevel level, PlaceLock rule, Vec3 current, Vec3 safePos) {
        BoundingBox box = findContainingStructureBox(level, rule, BlockPos.containing(current));
        if (box != null) {
            Vec3 wallNormal = nearestBoxOutwardNormal(box, current.x, current.z);
            // Only use wall normal if it generally agrees with the side the player came from
            Vec3 toSafe = horizontalNormalize(safePos.subtract(current));
            if (toSafe.lengthSqr() < 1.0e-4 || wallNormal.dot(toSafe) > 0.2) {
                return wallNormal;
            }
        }
        return horizontalNormalize(safePos.subtract(current));
    }

    private static @Nullable BoundingBox findContainingStructureBox(ServerLevel level, PlaceLock rule, BlockPos pos) {
        if (rule.structure().isEmpty()) {
            return null;
        }
        for (StructureRef ref : rule.structure().get()) {
            Optional<BoundingBox> box = ref.boundingBox(level, pos);
            if (box.isPresent()) {
                return box.get();
            }
        }
        return null;
    }

    /** Computes the horizontal unit outward vector pointing toward the closest face of {@code box}. */
    public static Vec3 nearestBoxOutwardNormal(BoundingBox box, double x, double z) {
        double dWest = Math.abs(x - box.minX());
        double dEast = Math.abs((box.maxX() + 1.0) - x);
        double dNorth = Math.abs(z - box.minZ());
        double dSouth = Math.abs((box.maxZ() + 1.0) - z);
        double best = dWest;
        Vec3 normal = new Vec3(-1.0, 0.0, 0.0);
        if (dEast < best) {
            best = dEast;
            normal = new Vec3(1.0, 0.0, 0.0);
        }
        if (dNorth < best) {
            best = dNorth;
            normal = new Vec3(0.0, 0.0, -1.0);
        }
        if (dSouth < best) {
            normal = new Vec3(0.0, 0.0, 1.0);
        }
        return normal;
    }

    /**
     * Finds a deterministic safe standing position outside a locked structure (shortest bounding-box wall + margin)
     * or biome (expanding compass ring search).
     */
    private static @Nullable Vec3 findGeometricExit(ServerLevel level, ServerPlayer player, PlaceLock rule, Vec3 current) {
        BlockPos origin = BlockPos.containing(current);
        int preferredY = origin.getY();

        // 1. Structure exit: test the 4 horizontal bounding-box faces, nearest wall first
        BoundingBox box = findContainingStructureBox(level, rule, origin);
        if (box != null) {
            int margin = 3;
            List<int[]> wallCandidates = new ArrayList<>(4);
            wallCandidates.add(new int[]{box.minX() - margin, origin.getZ(), Math.abs(origin.getX() - box.minX())});
            wallCandidates.add(new int[]{box.maxX() + margin, origin.getZ(), Math.abs(box.maxX() - origin.getX())});
            wallCandidates.add(new int[]{origin.getX(), box.minZ() - margin, Math.abs(origin.getZ() - box.minZ())});
            wallCandidates.add(new int[]{origin.getX(), box.maxZ() + margin, Math.abs(box.maxZ() - origin.getZ())});
            wallCandidates.sort(Comparator.comparingInt(a -> a[2]));

            for (int[] candidate : wallCandidates) {
                int cx = candidate[0];
                int cz = candidate[1];
                BlockPos probe = new BlockPos(cx, preferredY, cz);
                if (!level.isLoaded(probe)) {
                    continue;
                }
                Vec3 standing = findSafeStandingPos(level, cx, preferredY, cz);
                if (findActiveBarrier(level, player, BlockPos.containing(standing)) == null) {
                    return standing;
                }
            }
        }

        // 2. Biome / overlapping barrier exit: expanding 16-direction ring search in loaded chunks
        for (int radius : BIOME_EXIT_RADII) {
            for (int i = 0; i < BIOME_EXIT_DIRECTIONS; i++) {
                double angle = (Math.PI * 2.0 * i) / BIOME_EXIT_DIRECTIONS;
                int cx = origin.getX() + (int) Math.round(Math.cos(angle) * radius);
                int cz = origin.getZ() + (int) Math.round(Math.sin(angle) * radius);
                BlockPos probe = new BlockPos(cx, preferredY, cz);
                if (!level.isLoaded(probe)) {
                    continue;
                }
                if (findActiveBarrier(level, player, probe) != null) {
                    continue;
                }
                Vec3 standing = findSafeStandingPos(level, cx, preferredY, cz);
                if (findActiveBarrier(level, player, BlockPos.containing(standing)) == null) {
                    return standing;
                }
            }
        }

        // 3. World spawn if unlocked
        BlockPos spawn = level.getSharedSpawnPos();
        if (findActiveBarrier(level, player, spawn) == null) {
            return findSafeStandingPos(level, spawn.getX(), spawn.getY(), spawn.getZ());
        }
        return null;
    }

    /** Finds safe standing coordinates at (x, z), preferring near {@code preferredY} before falling back to surface. */
    private static Vec3 findSafeStandingPos(ServerLevel level, int x, int preferredY, int z) {
        int minY = level.getMinBuildHeight() + 1;
        int maxY = level.getMaxBuildHeight() - 2;
        int base = Math.max(minY, Math.min(maxY, preferredY));

        // First search vertically around preferredY (preserves caves, Nether shelves, and indoor floors)
        for (int dy = 0; dy <= 12; dy++) {
            int up = base + dy;
            if (up <= maxY && isSafeGround(level, new BlockPos(x, up, z))) {
                return new Vec3(x + 0.5, up, z + 0.5);
            }
            if (dy > 0) {
                int down = base - dy;
                if (down >= minY && isSafeGround(level, new BlockPos(x, down, z))) {
                    return new Vec3(x + 0.5, down, z + 0.5);
                }
            }
        }

        // Fall back to surface height in open-sky dimensions
        if (!level.dimensionType().hasCeiling()) {
            int surfaceY = Math.max(minY, Math.min(maxY, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)));
            BlockPos surface = new BlockPos(x, surfaceY, z);
            if (!isDangerousBlock(level.getBlockState(surface.below())) && hasFreeStandSpace(level, surface)) {
                return new Vec3(x + 0.5, surfaceY, z + 0.5);
            }
            // Check 3x3 neighbors if exact surface spot is hazardous
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    int sy = Math.max(minY, Math.min(maxY, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x + dx, z + dz)));
                    BlockPos alt = new BlockPos(x + dx, sy, z + dz);
                    if (!isDangerousBlock(level.getBlockState(alt.below())) && hasFreeStandSpace(level, alt)) {
                        return new Vec3(x + dx + 0.5, sy, z + dz + 0.5);
                    }
                }
            }
            return new Vec3(x + 0.5, surfaceY, z + 0.5);
        }

        return new Vec3(x + 0.5, base, z + 0.5);
    }

    private static boolean isSafeGround(ServerLevel level, BlockPos feet) {
        BlockState below = level.getBlockState(feet.below());
        if (below.getCollisionShape(level, feet.below()).isEmpty() || isDangerousBlock(below)) {
            return false;
        }
        return hasFreeStandSpace(level, feet);
    }

    private static boolean hasFreeStandSpace(ServerLevel level, BlockPos feet) {
        BlockState atFeet = level.getBlockState(feet);
        BlockState atHead = level.getBlockState(feet.above());
        return atFeet.getCollisionShape(level, feet).isEmpty()
                && atHead.getCollisionShape(level, feet.above()).isEmpty()
                && !isDangerousBlock(atFeet)
                && !isDangerousBlock(atHead);
    }

    private static boolean isDangerousBlock(BlockState state) {
        return state.is(Blocks.LAVA) || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)
                || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CACTUS) || state.is(Blocks.SWEET_BERRY_BUSH);
    }

    private static void playBarrierFeedback(ServerPlayer player, ServerLevel level, Vec3 wallPoint, Vec3 outward) {
        long now = System.currentTimeMillis();
        Long lastFx = LAST_BARRIER_FX_MS.get(player.getUUID());
        if (lastFx != null && now - lastFx < BARRIER_FX_COOLDOWN_MS) {
            return;
        }
        LAST_BARRIER_FX_MS.put(player.getUUID(), now);

        level.playSound(null, player.blockPosition(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, 0.5f, 0.85f);
        if (player.connection == null) {
            return;
        }
        // Render a shimmering vertical curtain of ward particles perpendicular to the barrier normal
        double ox = outward.lengthSqr() > 1.0e-4 ? outward.x : 0.0;
        double oz = outward.lengthSqr() > 1.0e-4 ? outward.z : 1.0;
        double tx = -oz;
        double tz = ox;
        for (int iu = -2; iu <= 2; iu++) {
            double u = iu * 0.55;
            for (int iv = 0; iv <= 2; iv++) {
                double px = wallPoint.x + tx * u;
                double py = wallPoint.y + 0.35 + iv * 0.7;
                double pz = wallPoint.z + tz * u;
                level.sendParticles(player, BARRIER_DUST, false, px, py, pz, 1, 0.04, 0.04, 0.04, 0.0);
            }
        }
    }

    private static Vec3 horizontalNormalize(Vec3 v) {
        Vec3 flat = new Vec3(v.x, 0.0, v.z);
        return flat.lengthSqr() > 1.0e-4 ? flat.normalize() : Vec3.ZERO;
    }

    public static void forgetPlayer(UUID uuid) {
        LAST_SYNCED.remove(uuid);
        LAST_SAFE.remove(uuid);
        LAST_CHECK.remove(uuid);
        BARRIER_COOLDOWN_UNTIL.remove(uuid);
        LAST_BARRIER_FX_MS.remove(uuid);
        LAST_NOTIFY_MS.remove(uuid);
        LAST_HAZARD_NOTIFY_MS.remove(uuid);
    }

    public static void clearRuntime() {
        LAST_SYNCED.clear();
        LAST_SAFE.clear();
        LAST_CHECK.clear();
        BARRIER_COOLDOWN_UNTIL.clear();
        LAST_BARRIER_FX_MS.clear();
        LAST_NOTIFY_MS.clear();
        LAST_HAZARD_NOTIFY_MS.clear();
    }
}
