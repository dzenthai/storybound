package dev.dzenthai.storybound.module.world;

import dev.dzenthai.storybound.core.data.ValidationIssue;
import dev.dzenthai.storybound.core.data.ValidationService;
import dev.dzenthai.storybound.core.event.BossDefeatedEvent;
import dev.dzenthai.storybound.core.event.CodexUnlockedEvent;
import dev.dzenthai.storybound.core.event.QuestChangedEvent;
import dev.dzenthai.storybound.core.event.StoryValueChangedEvent;
import dev.dzenthai.storybound.core.logic.LogicOwners;
import dev.dzenthai.storybound.core.state.StoryState;
import dev.dzenthai.storybound.module.world.WorldRuleDefinition.EntryRef;
import dev.dzenthai.storybound.module.world.WorldRuleDefinition.ItemLock;
import dev.dzenthai.storybound.module.world.WorldRuleDefinition.LootModifierRule;
import dev.dzenthai.storybound.module.world.WorldRuleDefinition.MobLock;
import dev.dzenthai.storybound.module.world.WorldRuleDefinition.PlaceLock;
import dev.dzenthai.storybound.module.world.WorldRuleDefinition.PlaceMode;
import dev.dzenthai.storybound.module.world.WorldRules.Entry;
import dev.dzenthai.storybound.module.world.WorldRules.ItemAction;
import java.util.function.Consumer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.event.entity.living.LivingEquipmentChangeEvent;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * NeoForge event wiring, reference walking, and validation for {@link WorldRuleDefinition}.
 */
public final class WorldRuleEvents {
    private static long tickCounter;

    private WorldRuleEvents() {}

    public static void register(IEventBus gameBus) {
        gameBus.addListener(EventPriority.HIGH, WorldRuleEvents::onTagsUpdated);
        gameBus.addListener(WorldRuleEvents::onDatapackSync);
        gameBus.addListener(WorldRuleEvents::onPlayerLoggedOut);
        gameBus.addListener(WorldRuleEvents::onPlayerChangeGameMode);
        gameBus.addListener(WorldRuleEvents::onServerStopped);
        gameBus.addListener(WorldRuleEvents::onServerTick);

        // Item locks
        gameBus.addListener(EventPriority.HIGH, WorldRuleEvents::onRightClickItem);
        gameBus.addListener(EventPriority.HIGH, WorldRuleEvents::onRightClickBlock);
        gameBus.addListener(EventPriority.HIGH, WorldRuleEvents::onLeftClickBlock);
        gameBus.addListener(EventPriority.HIGH, WorldRuleEvents::onBlockBreak);
        gameBus.addListener(EventPriority.HIGH, WorldRuleEvents::onEntityInteract);
        gameBus.addListener(EventPriority.HIGH, WorldRuleEvents::onEntityInteractSpecific);
        gameBus.addListener(EventPriority.HIGH, WorldRuleEvents::onAttackEntity);
        gameBus.addListener(EventPriority.HIGH, WorldRuleEvents::onItemPickup);
        gameBus.addListener(WorldRuleEvents::onEquipmentChange);

        // Mob spawn locks & existing mob removal
        gameBus.addListener(EventPriority.HIGH, WorldRuleEvents::onMobPositionCheck);
        gameBus.addListener(EventPriority.HIGH, WorldRuleEvents::onFinalizeSpawn);
        gameBus.addListener(EventPriority.HIGH, WorldRuleEvents::onEntityJoinLevel);

        // Place locks
        gameBus.addListener(EventPriority.HIGH, WorldRuleEvents::onDimensionTravel);

        // Immediate client item-lock sync when story state changes
        gameBus.addListener(WorldRuleEvents::onStoryValueChanged);
        gameBus.addListener(WorldRuleEvents::onQuestChanged);
        gameBus.addListener(WorldRuleEvents::onBossDefeated);
        gameBus.addListener(WorldRuleEvents::onCodexUnlocked);

        LogicOwners.register(WorldRuleRegistries.WORLD_RULES, (rule, references) -> {
            switch (rule) {
                case ItemLock itemLock -> itemLock.unlock().ifPresent(c -> c.collectReferences(references));
                case MobLock mobLock -> {
                    references.place(mobLock.place());
                    mobLock.unlock().ifPresent(c -> c.collectReferences(references));
                }
                case PlaceLock placeLock -> {
                    references.place(placeLock.place());
                    placeLock.unlock().ifPresent(c -> c.collectReferences(references));
                }
                case LootModifierRule lootRule -> {
                    references.place(lootRule.place());
                    lootRule.conditions().ifPresent(c -> c.collectReferences(references));
                }
            }
        });
        ValidationService.registerValidator(WorldRuleEvents::validate);
    }

    private static void onTagsUpdated(TagsUpdatedEvent event) {
        if (event.getUpdateCause() == TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) {
            WorldRules.rebuild();
        }
    }

    private static void onDatapackSync(OnDatapackSyncEvent event) {
        event.getRelevantPlayers().forEach(player -> WorldRules.syncPlayer(player, true));
    }

    private static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        WorldRules.forgetPlayer(event.getEntity().getUUID());
    }

    private static void onPlayerChangeGameMode(PlayerEvent.PlayerChangeGameModeEvent event) {
        if (!WorldRules.hasItemLocks() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        // Defer one tick so player.gameMode already holds the new mode before computing client locks.
        player.server.execute(() -> WorldRules.syncPlayer(player, false));
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        WorldRules.clearRuntime();
        tickCounter = 0;
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        tickCounter++;
        boolean checkBarriers = WorldRules.hasBarrierPlaceLocks() && (tickCounter % 2 == 0);
        boolean checkHazards = WorldRules.hasHazardPlaceLocks() && (tickCounter % 20 == 0);
        boolean syncItems = WorldRules.hasItemLocks() && (tickCounter % 20 == 0);
        boolean sweepMobs = WorldRules.hasDespawnMobLocks() && (tickCounter % 20 == 0);
        MinecraftServer server = event.getServer();
        if (sweepMobs) {
            WorldRules.sweepExistingMobs(server);
        }
        if (!checkBarriers && !checkHazards && !syncItems) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (checkBarriers || checkHazards) {
                WorldRules.checkPlaceLocks(player, checkBarriers, checkHazards);
            }
            if (syncItems) {
                WorldRules.syncPlayer(player, false);
            }
        }
    }

    // --- item lock listeners

    private static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (!WorldRules.hasItemLocks() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ItemStack stack = event.getItemStack();
        Entry<ItemLock> lock = WorldRules.findItemLock(player, stack, ItemAction.USE);
        if (lock == null && stack.getItem() instanceof Equipable) {
            lock = WorldRules.findItemLock(player, stack, ItemAction.EQUIP);
        }
        if (lock != null) {
            event.setCancellationResult(InteractionResult.FAIL);
            event.setCanceled(true);
            WorldRules.notifyItemBlocked(player, lock.rule());
        }
    }

    private static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!WorldRules.hasItemLocks() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Entry<ItemLock> lock = WorldRules.findItemLock(player, event.getItemStack(), ItemAction.USE);
        if (lock != null) {
            // Block using/placing the locked item while still allowing opening chests, doors, or crafting tables.
            event.setUseItem(TriState.FALSE);
            if (player.isSecondaryUseActive()
                    || player.serverLevel().getBlockState(event.getPos()).getMenuProvider(player.serverLevel(), event.getPos()) == null) {
                WorldRules.notifyItemBlocked(player, lock.rule());
            }
        }
    }

    private static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (!WorldRules.hasItemLocks() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Entry<ItemLock> lock = WorldRules.findItemLock(player, event.getItemStack(), ItemAction.USE);
        if (lock != null) {
            event.setCanceled(true);
            WorldRules.notifyItemBlocked(player, lock.rule());
        }
    }

    private static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!WorldRules.hasItemLocks() || !(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        Entry<ItemLock> lock = WorldRules.findItemLock(player, player.getMainHandItem(), ItemAction.USE);
        if (lock != null) {
            event.setCanceled(true);
            WorldRules.notifyItemBlocked(player, lock.rule());
        }
    }

    private static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!WorldRules.hasItemLocks() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Entry<ItemLock> lock = WorldRules.findItemLock(player, event.getItemStack(), ItemAction.USE);
        if (lock != null) {
            event.setCancellationResult(InteractionResult.FAIL);
            event.setCanceled(true);
            WorldRules.notifyItemBlocked(player, lock.rule());
        }
    }

    private static void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (!WorldRules.hasItemLocks() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Entry<ItemLock> lock = WorldRules.findItemLock(player, event.getItemStack(), ItemAction.USE);
        if (lock != null) {
            event.setCancellationResult(InteractionResult.FAIL);
            event.setCanceled(true);
            WorldRules.notifyItemBlocked(player, lock.rule());
        }
    }

    private static void onAttackEntity(AttackEntityEvent event) {
        if (!WorldRules.hasItemLocks() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Entry<ItemLock> lock = WorldRules.findItemLock(player, player.getMainHandItem(), ItemAction.ATTACK);
        if (lock != null) {
            event.setCanceled(true);
            WorldRules.notifyItemBlocked(player, lock.rule());
        }
    }

    private static void onItemPickup(ItemEntityPickupEvent.Pre event) {
        if (!WorldRules.hasItemLocks() || !(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        Entry<ItemLock> lock = WorldRules.findItemLock(player, event.getItemEntity().getItem(), ItemAction.PICKUP);
        if (lock != null) {
            event.setCanPickup(TriState.FALSE);
            WorldRules.notifyItemBlocked(player, lock.rule());
        }
    }

    private static void onEquipmentChange(LivingEquipmentChangeEvent event) {
        if (!WorldRules.hasItemLocks() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        EquipmentSlot slot = event.getSlot();
        if (slot == EquipmentSlot.MAINHAND || event.getTo().isEmpty()) {
            return;
        }
        Entry<ItemLock> lock = WorldRules.findItemLock(player, event.getTo(), ItemAction.EQUIP);
        if (lock != null) {
            ItemStack blocked = event.getTo().copy();
            player.setItemSlot(slot, ItemStack.EMPTY);
            ItemHandlerHelper.giveItemToPlayer(player, blocked);
            player.containerMenu.broadcastChanges();
            WorldRules.notifyItemBlocked(player, lock.rule());
        }
    }

    // --- mob spawn locks & existing mob removal

    /**
     * NaturalSpawner and BaseSpawner check {@link MobSpawnEvent.PositionCheck} (NaturalSpawner never fires
     * {@link FinalizeSpawnEvent} in NeoForge 1.21.1). Failing here blocks natural, chunk-gen, and spawner spawns
     * before the mob is ever finalized or added to the world.
     */
    private static void onMobPositionCheck(MobSpawnEvent.PositionCheck event) {
        if (!WorldRules.hasMobLocks() || event.getResult() == MobSpawnEvent.PositionCheck.Result.FAIL) {
            return;
        }
        ServerLevel level = event.getLevel().getLevel();
        Mob mob = event.getEntity();
        Entry<MobLock> lock = WorldRules.checkMobSpawn(level, mob, event.getSpawnType(),
                event.getX(), event.getY(), event.getZ());
        if (lock == null) {
            if (WorldRules.isExemptibleSpawn(event.getSpawnType())) {
                mob.addTag(WorldRules.EXEMPT_TAG);
            }
            return;
        }
        event.setResult(MobSpawnEvent.PositionCheck.Result.FAIL);
        lock.rule().replaceWith().ifPresent(replacement -> {
            // Only replace on positions where the blocked mob actually passed vanilla spawn rules and obstruction
            boolean wouldSpawn = (event.getSpawner() != null
                    || mob.checkSpawnRules(event.getLevel(), event.getSpawnType()))
                    && mob.checkSpawnObstruction(event.getLevel());
            if (wouldSpawn) {
                WorldRules.scheduleMobReplacement(level, replacement.value(),
                        event.getX(), event.getY(), event.getZ(), mob.getYRot());
            }
        });
    }

    private static void onFinalizeSpawn(FinalizeSpawnEvent event) {
        if (!WorldRules.hasMobLocks() || event.isSpawnCancelled()) {
            return;
        }
        ServerLevel level = event.getLevel().getLevel();
        Mob mob = event.getEntity();
        Entry<MobLock> lock = WorldRules.checkMobSpawn(level, mob, event.getSpawnType(),
                event.getX(), event.getY(), event.getZ());
        if (lock == null) {
            if (WorldRules.isExemptibleSpawn(event.getSpawnType())) {
                mob.addTag(WorldRules.EXEMPT_TAG);
            }
            return;
        }
        event.setSpawnCancelled(true);
        event.setCanceled(true);
        lock.rule().replaceWith().ifPresent(replacement -> WorldRules.scheduleMobReplacement(
                level, replacement.value(), event.getX(), event.getY(), event.getZ(), mob.getYRot()));
    }

    private static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (!event.loadedFromDisk() || !WorldRules.hasDespawnMobLocks()
                || !(event.getLevel() instanceof ServerLevel level)
                || !(event.getEntity() instanceof Mob mob)) {
            return;
        }
        Entry<MobLock> lock = WorldRules.findDespawnMobLock(level, mob);
        if (lock == null) {
            return;
        }
        event.setCanceled(true);
        lock.rule().replaceWith().ifPresent(replacement -> WorldRules.scheduleMobReplacement(
                level, replacement.value(), mob.getX(), mob.getY(), mob.getZ(), mob.getYRot()));
    }

    // --- place locks

    private static void onDimensionTravel(EntityTravelToDimensionEvent event) {
        if (!WorldRules.hasPlaceLocks() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Entry<PlaceLock> lock = WorldRules.checkDimensionTravel(player, event.getDimension());
        if (lock != null) {
            event.setCanceled(true);
            WorldRules.notifyPlaceBlocked(player, lock.rule(), false);
        }
    }

    // --- instant sync triggers

    private static void onStoryValueChanged(StoryValueChangedEvent event) {
        if (!WorldRules.hasItemLocks()) {
            return;
        }
        for (ServerPlayer player : StoryState.onlinePlayersOf(event.getServer(), event.getScope(), event.getOwner())) {
            WorldRules.syncPlayer(player, false);
        }
    }

    private static void onQuestChanged(QuestChangedEvent event) {
        if (!WorldRules.hasItemLocks()) {
            return;
        }
        for (ServerPlayer player : StoryState.onlinePlayersOf(event.getServer(), event.getScope(), event.getOwner())) {
            WorldRules.syncPlayer(player, false);
        }
    }

    private static void onBossDefeated(BossDefeatedEvent event) {
        if (WorldRules.hasItemLocks()) {
            WorldRules.syncPlayer(event.getPlayer(), false);
        }
    }

    private static void onCodexUnlocked(CodexUnlockedEvent event) {
        if (WorldRules.hasItemLocks()) {
            WorldRules.syncPlayer(event.getPlayer(), false);
        }
    }

    // --- validation

    private static void validate(Consumer<ValidationIssue> sink) {
        ResourceLocation typeId = WorldRuleRegistries.WORLD_RULES.id();
        WorldRuleRegistries.WORLD_RULES.serverStore().all().forEach((id, def) -> {
            switch (def) {
                case ItemLock itemLock -> {
                    for (EntryRef ref : itemLock.items()) {
                        if (!ref.existsIn(BuiltInRegistries.ITEM, Registries.ITEM)) {
                            sink.accept(ValidationIssue.warning(typeId, id, "Unknown item or tag " + ref));
                        }
                    }
                    if (!itemLock.blockUse() && !itemLock.blockAttack() && !itemLock.blockEquip()
                            && !itemLock.blockCraft() && !itemLock.blockPickup() && !itemLock.hideInJei()) {
                        sink.accept(ValidationIssue.warning(typeId, id, "Item lock has all restriction options turned off"));
                    }
                }
                case MobLock mobLock -> {
                    for (EntryRef ref : mobLock.entities()) {
                        if (!ref.existsIn(BuiltInRegistries.ENTITY_TYPE, Registries.ENTITY_TYPE)) {
                            sink.accept(ValidationIssue.warning(typeId, id, "Unknown entity or tag " + ref));
                        }
                    }
                }
                case PlaceLock placeLock -> {
                    if (placeLock.mode() == PlaceMode.HAZARD && placeLock.effects().isEmpty() && placeLock.damage() <= 0.0f) {
                        sink.accept(ValidationIssue.warning(typeId, id, "Hazard place lock has no effects and 0 damage"));
                    }
                }
                case LootModifierRule lootRule -> {
                    if (lootRule.chance() <= 0.0f) {
                        sink.accept(ValidationIssue.warning(typeId, id, "chance is 0, so this loot modifier never applies"));
                    }
                    for (EntryRef ref : lootRule.target()) {
                        if (!ref.existsIn(BuiltInRegistries.ITEM, Registries.ITEM)) {
                            sink.accept(ValidationIssue.warning(typeId, id, "Unknown target item or tag " + ref));
                        }
                    }
                    for (EntryRef ref : lootRule.entities()) {
                        if (!ref.existsIn(BuiltInRegistries.ENTITY_TYPE, Registries.ENTITY_TYPE)) {
                            sink.accept(ValidationIssue.warning(typeId, id, "Unknown entity or tag " + ref));
                        }
                    }
                }
            }
        });
    }
}

