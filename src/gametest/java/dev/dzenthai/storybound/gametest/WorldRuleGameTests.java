package dev.dzenthai.storybound.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.StoryException;
import dev.dzenthai.storybound.core.data.ValidationIssue;
import dev.dzenthai.storybound.core.data.ValidationService;
import dev.dzenthai.storybound.core.logic.Actions;
import dev.dzenthai.storybound.core.logic.LogicCodecs;
import dev.dzenthai.storybound.core.logic.OnFail;
import dev.dzenthai.storybound.core.logic.StoryContext;
import dev.dzenthai.storybound.core.state.StoryScope;
import dev.dzenthai.storybound.core.state.StoryState;
import dev.dzenthai.storybound.core.state.StoryValue;
import dev.dzenthai.storybound.module.world.WorldLootModifier;
import dev.dzenthai.storybound.module.world.WorldRuleDefinition;
import dev.dzenthai.storybound.module.world.WorldRuleRegistries;
import dev.dzenthai.storybound.module.world.WorldRules;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.event.entity.living.LivingEquipmentChangeEvent;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real-server verification for World Setup rules: item locks, mob locks, place locks, loot modifiers, and validation. */
@SuppressWarnings("removal")
@GameTestHolder(Storybound.MOD_ID)
@PrefixGameTestTemplate(false)
public final class WorldRuleGameTests {
    private WorldRuleGameTests() {}

    @GameTest(template = "empty")
    public static void itemLockBlocksActionsEquipCraftAndPickupUntilUnlocked(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.SURVIVAL);
        player.setInvulnerable(false);
        player.getAbilities().invulnerable = false;
        // Clear the 60-tick post-login spawn invulnerability so magic hazard damage can land immediately.
        for (int i = 0; i < 65; i++) {
            player.tick();
        }
        player.invulnerableTime = 0;
        ServerLevel level = helper.getLevel();
        Zombie target = helper.spawn(EntityType.ZOMBIE, new BlockPos(2, 1, 2));

        withRules(helper, Map.of(
                key("lock_diamond_sword"), """
                        {
                          "type": "item",
                          "items": "minecraft:diamond_sword",
                          "unlock": { "type": "flag", "key": "gametest:can_wield_diamond" },
                          "block_use": true,
                          "block_attack": true,
                          "block_equip": true,
                          "block_craft": true,
                          "block_pickup": true
                        }
                        """,
                key("disabled_iron_lock"), """
                        {
                          "type": "item",
                          "enabled": false,
                          "items": "minecraft:iron_sword",
                          "block_use": true,
                          "block_attack": true
                        }
                        """
        ), () -> {
            ItemStack diamondSword = new ItemStack(Items.DIAMOND_SWORD);
            player.setItemInHand(InteractionHand.MAIN_HAND, diamondSword);

            // Use blocked
            PlayerInteractEvent.RightClickItem rightClick = new PlayerInteractEvent.RightClickItem(player, InteractionHand.MAIN_HAND);
            NeoForge.EVENT_BUS.post(rightClick);
            helper.assertTrue(rightClick.isCanceled(), "locked item right-click is blocked");

            // Attack blocked
            AttackEntityEvent attack = new AttackEntityEvent(player, target);
            NeoForge.EVENT_BUS.post(attack);
            helper.assertTrue(attack.isCanceled(), "locked item attack is blocked");

            // Pickup blocked
            ItemEntity drop = new ItemEntity(level, player.getX(), player.getY(), player.getZ(), new ItemStack(Items.DIAMOND_SWORD));
            ItemEntityPickupEvent.Pre pickup = new ItemEntityPickupEvent.Pre(player, drop);
            NeoForge.EVENT_BUS.post(pickup);
            helper.assertTrue(pickup.canPickup() == TriState.FALSE, "locked item pickup is blocked");

            // Craft result slot & Smithing/Stonecutter ResultContainer slot blocked via SlotMixin
            CraftingContainer craftGrid = new TransientCraftingContainer(player.inventoryMenu, 2, 2);
            SimpleContainer resultContainer = new SimpleContainer(1);
            resultContainer.setItem(0, new ItemStack(Items.DIAMOND_SWORD));
            ResultSlot resultSlot = new ResultSlot(player, craftGrid, resultContainer, 0, 0, 0);
            helper.assertFalse(resultSlot.mayPickup(player), "locked item crafting result is blocked");

            ResultContainer smithingResult = new ResultContainer();
            smithingResult.setItem(0, new ItemStack(Items.DIAMOND_SWORD));
            Slot smithingSlot = new Slot(smithingResult, 0, 0, 0);
            helper.assertFalse(smithingSlot.mayPickup(player), "locked item smithing/stonecutter ResultContainer slot is blocked");

            // Equip blocked (offhand returns to inventory)
            player.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.DIAMOND_SWORD));
            NeoForge.EVENT_BUS.post(new LivingEquipmentChangeEvent(player, EquipmentSlot.OFFHAND, ItemStack.EMPTY, player.getOffhandItem()));
            helper.assertTrue(player.getOffhandItem().isEmpty(), "locked item is unequipped from offhand");

            // Disabled rule does not block iron sword
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
            AttackEntityEvent ironAttack = new AttackEntityEvent(player, target);
            NeoForge.EVENT_BUS.post(ironAttack);
            helper.assertFalse(ironAttack.isCanceled(), "disabled item lock does not block");

            // Creative bypasses locks
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_SWORD));
            player.setGameMode(GameType.CREATIVE);
            AttackEntityEvent creativeAttack = new AttackEntityEvent(player, target);
            NeoForge.EVENT_BUS.post(creativeAttack);
            helper.assertFalse(creativeAttack.isCanceled(), "creative mode bypasses item lock");
            helper.assertTrue(resultSlot.mayPickup(player), "creative mode bypasses craft lock");

            // Unlock via flag in Survival
            player.setGameMode(GameType.SURVIVAL);
            setFlag(player, "can_wield_diamond", true);
            AttackEntityEvent unlockedAttack = new AttackEntityEvent(player, target);
            NeoForge.EVENT_BUS.post(unlockedAttack);
            helper.assertFalse(unlockedAttack.isCanceled(), "unlocked item attack passes");
            helper.assertTrue(resultSlot.mayPickup(player), "unlocked item crafting result passes");
            ItemEntityPickupEvent.Pre unlockedPickup = new ItemEntityPickupEvent.Pre(player, drop);
            NeoForge.EVENT_BUS.post(unlockedPickup);
            helper.assertTrue(unlockedPickup.canPickup() != TriState.FALSE, "unlocked item pickup passes");
        });

        remove(helper, player);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void mobLockCancelsNaturalSpawnAllowsSpawnersUnlessEnabledAndReplaces(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        ServerLevel level = helper.getLevel();
        BlockPos abs = helper.absolutePos(new BlockPos(3, 2, 3));
        player.teleportTo(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5);

        withRules(helper, Map.of(
                key("lock_zombies"), """
                        {
                          "type": "mob",
                          "entities": "minecraft:zombie",
                          "unlock": { "type": "flag", "key": "gametest:zombies_unlocked" },
                          "block_spawners": false,
                          "replace_with": "minecraft:skeleton"
                        }
                        """
        ), () -> {
            // Natural spawn blocked & replacement scheduled
            Zombie natural = EntityType.ZOMBIE.create(level);
            natural.moveTo(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5, 0, 0);
            FinalizeSpawnEvent naturalEvent = new FinalizeSpawnEvent(natural, level, abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5,
                    level.getCurrentDifficultyAt(abs), MobSpawnType.NATURAL, null, null);
            NeoForge.EVENT_BUS.post(naturalEvent);
            helper.assertTrue(naturalEvent.isSpawnCancelled(), "natural spawn is cancelled while locked");

            // Spawner allowed when block_spawners is false
            Zombie spawner = EntityType.ZOMBIE.create(level);
            FinalizeSpawnEvent spawnerEvent = new FinalizeSpawnEvent(spawner, level, abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5,
                    level.getCurrentDifficultyAt(abs), MobSpawnType.SPAWNER, null, null);
            NeoForge.EVENT_BUS.post(spawnerEvent);
            helper.assertFalse(spawnerEvent.isSpawnCancelled(), "spawner spawn allowed when block_spawners is false");

            // Command spawn always allowed
            Zombie command = EntityType.ZOMBIE.create(level);
            FinalizeSpawnEvent commandEvent = new FinalizeSpawnEvent(command, level, abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5,
                    level.getCurrentDifficultyAt(abs), MobSpawnType.COMMAND, null, null);
            NeoForge.EVENT_BUS.post(commandEvent);
            helper.assertFalse(commandEvent.isSpawnCancelled(), "command spawn is never blocked");

            // Unlock nearby player -> natural spawn passes
            setFlag(player, "zombies_unlocked", true);
            Zombie unlocked = EntityType.ZOMBIE.create(level);
            FinalizeSpawnEvent unlockedEvent = new FinalizeSpawnEvent(unlocked, level, abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5,
                    level.getCurrentDifficultyAt(abs), MobSpawnType.NATURAL, null, null);
            NeoForge.EVENT_BUS.post(unlockedEvent);
            helper.assertFalse(unlockedEvent.isSpawnCancelled(), "natural spawn passes once nearby player satisfies unlock");
        });

        // Verify replacement skeleton spawned cleanly on the server queue
        helper.runAfterDelay(2, () -> {
            AABB box = new AABB(abs).inflate(4.0);
            List<Skeleton> replacements = level.getEntitiesOfClass(Skeleton.class, box);
            helper.assertTrue(!replacements.isEmpty(), "replacement skeleton spawned after mob lock cancelled zombie");
            replacements.forEach(Skeleton::discard);
            remove(helper, player);
            helper.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void mobLockBlocksSpawnersWhenConfigured(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos abs = helper.absolutePos(new BlockPos(2, 2, 2));
        withRules(helper, Map.of(
                key("lock_spawner_spiders"), """
                        {
                          "type": "mob",
                          "entities": "minecraft:spider",
                          "block_spawners": true
                        }
                        """
        ), () -> {
            var spider = EntityType.SPIDER.create(level);
            FinalizeSpawnEvent spawnerEvent = new FinalizeSpawnEvent(spider, level, abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5,
                    level.getCurrentDifficultyAt(abs), MobSpawnType.SPAWNER, null, null);
            NeoForge.EVENT_BUS.post(spawnerEvent);
            helper.assertTrue(spawnerEvent.isSpawnCancelled(), "spawner spawn is cancelled when block_spawners is true");
        });
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void mobLockBlocksPositionCheckAndDespawnsExistingMobsSafely(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos abs = helper.absolutePos(new BlockPos(3, 2, 3));

        // Spawn three zombies BEFORE the rule activates: plain, custom-named, and exempt (simulating /summon)
        Zombie plain = helper.spawn(EntityType.ZOMBIE, new BlockPos(3, 2, 3));
        Zombie named = helper.spawn(EntityType.ZOMBIE, new BlockPos(4, 2, 3));
        named.setCustomName(Component.literal("Village Guard"));
        Zombie exempt = helper.spawn(EntityType.ZOMBIE, new BlockPos(5, 2, 3));
        exempt.addTag(WorldRules.EXEMPT_TAG);

        try {
            withRules(helper, Map.of(
                    key("lock_and_despawn_zombies"), """
                            {
                              "type": "mob",
                              "entities": "minecraft:zombie",
                              "despawn_existing": true,
                              "block_spawners": false,
                              "replace_with": "minecraft:skeleton"
                            }
                            """
            ), () -> {
                // 1. NaturalSpawner hook: MobSpawnEvent.PositionCheck must FAIL for NATURAL & CHUNK_GENERATION
                Zombie natural = EntityType.ZOMBIE.create(level);
                natural.moveTo(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5, 0, 0);
                MobSpawnEvent.PositionCheck naturalCheck = new MobSpawnEvent.PositionCheck(natural, level, MobSpawnType.NATURAL, null);
                NeoForge.EVENT_BUS.post(naturalCheck);
                helper.assertTrue(naturalCheck.getResult() == MobSpawnEvent.PositionCheck.Result.FAIL,
                        "PositionCheck fails natural spawn for locked mob");

                Zombie chunkGen = EntityType.ZOMBIE.create(level);
                chunkGen.moveTo(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5, 0, 0);
                MobSpawnEvent.PositionCheck chunkCheck = new MobSpawnEvent.PositionCheck(chunkGen, level, MobSpawnType.CHUNK_GENERATION, null);
                NeoForge.EVENT_BUS.post(chunkCheck);
                helper.assertTrue(chunkCheck.getResult() == MobSpawnEvent.PositionCheck.Result.FAIL,
                        "PositionCheck fails chunk-generation spawn for locked mob");

                // 2. Spawner allowed when block_spawners is false -> tagged exempt so sweep spares it
                Zombie spawner = EntityType.ZOMBIE.create(level);
                spawner.moveTo(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5, 0, 0);
                MobSpawnEvent.PositionCheck spawnerCheck = new MobSpawnEvent.PositionCheck(spawner, level, MobSpawnType.SPAWNER, null);
                NeoForge.EVENT_BUS.post(spawnerCheck);
                helper.assertTrue(spawnerCheck.getResult() != MobSpawnEvent.PositionCheck.Result.FAIL,
                        "PositionCheck allows spawner when block_spawners is false");
                helper.assertTrue(spawner.getTags().contains(WorldRules.EXEMPT_TAG),
                        "allowed spawner mob is tagged exempt from despawn_existing");

                // 3. EntityJoinLevelEvent (loadedFromDisk = true) cancels saved locked mob but spares custom-named mob
                Zombie diskPlain = EntityType.ZOMBIE.create(level);
                diskPlain.moveTo(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5, 0, 0);
                EntityJoinLevelEvent joinPlain = new EntityJoinLevelEvent(diskPlain, level, true);
                NeoForge.EVENT_BUS.post(joinPlain);
                helper.assertTrue(joinPlain.isCanceled(), "disk-loaded locked mob is cancelled when despawn_existing is true");

                Zombie diskNamed = EntityType.ZOMBIE.create(level);
                diskNamed.setCustomName(Component.literal("Boss Guard"));
                diskNamed.moveTo(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5, 0, 0);
                EntityJoinLevelEvent joinNamed = new EntityJoinLevelEvent(diskNamed, level, true);
                NeoForge.EVENT_BUS.post(joinNamed);
                helper.assertFalse(joinNamed.isCanceled(), "disk-loaded custom-named mob is protected");

                // 4. Sweeping existing mobs removes plain zombie + spawns replacement skeleton, spares named & exempt
                int swept = WorldRules.sweepExistingMobs(level.getServer());
                helper.assertTrue(swept >= 1, "sweepExistingMobs removed existing locked zombie");
                helper.assertTrue(plain.isRemoved(), "plain existing zombie was discarded");
                helper.assertFalse(named.isRemoved(), "custom-named zombie was preserved");
                helper.assertFalse(exempt.isRemoved(), "exempt zombie was preserved");

                AABB box = new AABB(abs).inflate(4.0);
                List<Skeleton> skeletons = level.getEntitiesOfClass(Skeleton.class, box);
                helper.assertTrue(!skeletons.isEmpty(), "replacement skeleton spawned for swept existing zombie");
                helper.assertTrue(skeletons.stream().allMatch(s -> s.getTags().contains(WorldRules.EXEMPT_TAG)),
                        "replacement skeleton is tagged exempt to avoid loop");
                skeletons.forEach(Skeleton::discard);
            });

            // 5. With despawn_existing: false, existing mobs stay alive while PositionCheck still blocks new spawns
            Zombie kept = helper.spawn(EntityType.ZOMBIE, new BlockPos(3, 2, 3));
            withRules(helper, Map.of(
                    key("lock_only_new_zombies"), """
                            {
                              "type": "mob",
                              "entities": "minecraft:zombie",
                              "despawn_existing": false
                            }
                            """
            ), () -> {
                WorldRules.sweepExistingMobs(level.getServer());
                helper.assertFalse(kept.isRemoved(), "existing zombie is kept when despawn_existing is false");

                Zombie fresh = EntityType.ZOMBIE.create(level);
                fresh.moveTo(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5, 0, 0);
                MobSpawnEvent.PositionCheck check = new MobSpawnEvent.PositionCheck(fresh, level, MobSpawnType.NATURAL, null);
                NeoForge.EVENT_BUS.post(check);
                helper.assertTrue(check.getResult() == MobSpawnEvent.PositionCheck.Result.FAIL,
                        "new natural spawn is still blocked when despawn_existing is false");
            });
            kept.discard();
        } finally {
            plain.discard();
            named.discard();
            exempt.discard();
        }
        helper.succeed();
    }

    /**
     * A mob lock never stops the story's own {@code spawn_entity} (its spawn type is EVENT, as a raid's) and never
     * removes what it spawned, riders written in the NBT included.
     */
    @GameTest(template = "empty")
    public static void mobLockSparesTheStorysOwnSpawns(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        ServerLevel level = helper.getLevel();
        BlockPos abs = helper.absolutePos(new BlockPos(3, 2, 3));
        player.teleportTo(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5);
        withRules(helper, Map.of(
                key("lock_story_zombies"), """
                        { "type": "mob", "entities": "minecraft:zombie", "despawn_existing": true }
                        """
        ), () -> {
            runActions(helper, player, """
                    [
                      { "type": "spawn_entity", "entity": "minecraft:zombie", "tag": "gametest_story_zombie" },
                      { "type": "spawn_entity", "entity": "minecraft:spider", "tag": "gametest_story_spider",
                        "nbt": { "Passengers": [ { "id": "minecraft:zombie", "Tags": ["gametest_story_rider"] } ] } }
                    ]
                    """);
            AABB box = new AABB(abs).inflate(8.0);
            List<Zombie> spawned = level.getEntitiesOfClass(Zombie.class, box, zombie -> zombie.getTags().contains("gametest_story_zombie"));
            helper.assertTrue(spawned.size() == 1, "the story's zombie spawned despite the lock");
            List<Zombie> riders = level.getEntitiesOfClass(Zombie.class, box, zombie -> zombie.getTags().contains("gametest_story_rider"));
            helper.assertTrue(riders.size() == 1, "the rider from the NBT spawned");
            WorldRules.sweepExistingMobs(level.getServer());
            helper.assertFalse(spawned.getFirst().isRemoved(), "the story's zombie is not removed by despawn_existing");
            helper.assertFalse(riders.getFirst().isRemoved(), "its rider is not removed either");
            level.getEntitiesOfClass(Mob.class, box,
                    mob -> mob.getTags().stream().anyMatch(tag -> tag.startsWith("gametest_story_"))).forEach(Entity::discard);
        });
        remove(helper, player);
        helper.succeed();
    }

    private static void runActions(GameTestHelper helper, ServerPlayer player, String json) {
        Actions.runAll(LogicCodecs.ACTIONS.parse(RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().registryAccess()),
                        JsonParser.parseString(json)).getOrThrow(GameTestAssertException::new),
                StoryContext.of(player), "gametest", OnFail.ABORT);
    }

    @GameTest(template = "empty")
    public static void placeLockBarrierPushNeverDependsOnLookDirection(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.SURVIVAL);
        ServerLevel level = helper.getLevel();
        BlockPos abs = helper.absolutePos(new BlockPos(3, 2, 3));
        player.teleportTo(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5);

        withRules(helper, Map.of(
                key("overworld_barrier"), """
                        {
                          "type": "place",
                          "dimension": "minecraft:overworld",
                          "mode": "barrier"
                        }
                        """
        ), () -> {
            // Check barrier facing South (yaw 0)
            WorldRules.forgetPlayer(player.getUUID());
            player.setYRot(0.0f);
            player.setDeltaMovement(Vec3.ZERO);
            WorldRules.checkPlaceLocks(player, true, false);
            Vec3 pushFacingSouth = player.getDeltaMovement();

            // Turn completely around (yaw 180) and check again: push direction must NOT invert
            WorldRules.forgetPlayer(player.getUUID());
            player.teleportTo(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5);
            player.setYRot(180.0f);
            player.setDeltaMovement(Vec3.ZERO);
            WorldRules.checkPlaceLocks(player, true, false);
            Vec3 pushFacingNorth = player.getDeltaMovement();

            helper.assertTrue(pushFacingSouth.horizontalDistanceSqr() > 1.0e-4, "barrier produced non-zero push");
            double alignment = pushFacingSouth.normalize().dot(pushFacingNorth.normalize());
            helper.assertTrue(alignment > 0.99,
                    "barrier push direction must stay identical regardless of look direction, dot=" + alignment);
        });

        remove(helper, player);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void placeLockBlocksDimensionTravelAndAppliesHazard(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.SURVIVAL);
        player.setInvulnerable(false);
        player.getAbilities().invulnerable = false;
        // Clear the 60-tick post-login spawn invulnerability so magic hazard damage can land immediately.
        for (int i = 0; i < 65; i++) {
            player.tick();
        }
        player.invulnerableTime = 0;

        withRules(helper, Map.of(
                key("nether_barrier"), """
                        {
                          "type": "place",
                          "dimension": "minecraft:the_nether",
                          "mode": "barrier",
                          "unlock": { "type": "flag", "key": "gametest:nether_unlocked" }
                        }
                        """,
                key("overworld_curse"), """
                        {
                          "type": "place",
                          "dimension": "minecraft:overworld",
                          "mode": "hazard",
                          "effects": [{ "effect": "minecraft:weakness", "amplifier": 1, "duration": 5 }],
                          "damage": 2.0,
                          "unlock": { "type": "flag", "key": "gametest:curse_lifted" }
                        }
                        """
        ), () -> {
            // Barrier blocks travel to Nether
            EntityTravelToDimensionEvent blockedTravel = new EntityTravelToDimensionEvent(player, Level.NETHER);
            NeoForge.EVENT_BUS.post(blockedTravel);
            helper.assertTrue(blockedTravel.isCanceled(), "dimension barrier cancels travel while locked");

            // Unlock Nether -> travel passes
            setFlag(player, "nether_unlocked", true);
            EntityTravelToDimensionEvent openTravel = new EntityTravelToDimensionEvent(player, Level.NETHER);
            NeoForge.EVENT_BUS.post(openTravel);
            helper.assertFalse(openTravel.isCanceled(), "dimension barrier allows travel once unlocked");

            // Hazard applies effect + magic damage in Overworld
            float beforeHp = player.getHealth();
            WorldRules.checkPlaceLocks(player, true);
            helper.assertTrue(player.hasEffect(MobEffects.WEAKNESS), "hazard applies configured potion effect");
            helper.assertTrue(player.getHealth() < beforeHp, "hazard deals configured damage");

            // Lift curse -> hazard stops applying
            player.removeAllEffects();
            float hpAfter = player.getHealth();
            setFlag(player, "curse_lifted", true);
            WorldRules.checkPlaceLocks(player, true);
            helper.assertFalse(player.hasEffect(MobEffects.WEAKNESS), "unlocked hazard no longer applies effect");
            helper.assertTrue(player.getHealth() == hpAfter, "unlocked hazard no longer deals damage");
        });

        remove(helper, player);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void worldLootModifierAddsReplacesAndRemovesItems(GameTestHelper helper) {
        ServerPlayer playerA = helper.makeMockServerPlayerInLevel();
        ServerPlayer playerB = helper.makeMockServerPlayerInLevel();
        ResourceLocation addRule = key("loot_add_emerald");
        ResourceLocation worldOnceRule = key("loot_add_diamond_world");
        ResourceLocation replaceRule = key("loot_replace_flesh");
        ResourceLocation removeRule = key("loot_remove_gold");

        try {
            withRules(helper, Map.of(
                    addRule, """
                            {
                              "type": "loot",
                              "operation": "add",
                              "tables": "minecraft:chests/simple_dungeon",
                              "item": "minecraft:emerald",
                              "min_count": 3,
                              "max_count": 3,
                              "once": "player",
                              "conditions": { "type": "flag", "key": "gametest:loot_perk" },
                              "components": { "minecraft:custom_name": "\\"Story Emerald\\"" }
                            }
                            """,
                    worldOnceRule, """
                            {
                              "type": "loot",
                              "operation": "add",
                              "tables": "minecraft:chests/simple_dungeon",
                              "item": "minecraft:diamond",
                              "once": "world"
                            }
                            """,
                    replaceRule, """
                            {
                              "type": "loot",
                              "operation": "replace",
                              "entities": "minecraft:zombie",
                              "target": "minecraft:rotten_flesh",
                              "item": "minecraft:gold_ingot",
                              "min_count": 2,
                              "max_count": 2
                            }
                            """
            ), () -> {
                // Chest roll before flag: diamond drops (world once), emerald does not (flag false)
                List<ItemStack> roll1A = rollChestLoot(helper, playerA, BuiltInLootTables.SIMPLE_DUNGEON);
                helper.assertTrue(roll1A.stream().anyMatch(s -> s.is(Items.DIAMOND)), "once:world diamond added on first roll");
                helper.assertFalse(roll1A.stream().anyMatch(s -> s.is(Items.EMERALD) && s.has(DataComponents.CUSTOM_NAME)),
                        "conditional emerald skipped before flag");

                // Enable flag for playerA -> emerald drops with count 3 and custom name; diamond does not repeat
                setFlag(playerA, "loot_perk", true);
                List<ItemStack> roll2A = rollChestLoot(helper, playerA, BuiltInLootTables.SIMPLE_DUNGEON);
                ItemStack emerald = roll2A.stream().filter(s -> s.is(Items.EMERALD) && s.has(DataComponents.CUSTOM_NAME)).findFirst().orElse(ItemStack.EMPTY);
                helper.assertFalse(emerald.isEmpty(), "conditional add rule drops emerald with components");
                helper.assertTrue(emerald.getCount() == 3, "emerald count matches min/max 3, got " + emerald.getCount());

                // Second roll for playerA: once:player prevents repeat
                List<ItemStack> roll3A = rollChestLoot(helper, playerA, BuiltInLootTables.SIMPLE_DUNGEON);
                helper.assertFalse(roll3A.stream().anyMatch(s -> s.is(Items.EMERALD) && s.has(DataComponents.CUSTOM_NAME)),
                        "once:player emerald does not repeat for playerA");

                // Roll for playerB: world once stays exhausted
                setFlag(playerB, "loot_perk", true);
                List<ItemStack> roll1B = rollChestLoot(helper, playerB, BuiltInLootTables.SIMPLE_DUNGEON);
                helper.assertTrue(roll1B.stream().anyMatch(s -> s.is(Items.EMERALD) && s.has(DataComponents.CUSTOM_NAME)),
                        "once:player emerald still drops for playerB");

                // Mob drop replace: zombie rotten_flesh -> gold_ingot (run several rolls to guarantee flesh appears)
                boolean replacedFlesh = false;
                for (int i = 0; i < 12; i++) {
                    List<ItemStack> drops = rollZombieLoot(helper, playerA);
                    helper.assertFalse(drops.stream().anyMatch(s -> s.is(Items.ROTTEN_FLESH)), "replace stripped all rotten flesh");
                    if (drops.stream().anyMatch(s -> s.is(Items.GOLD_INGOT) && s.getCount() == 2)) {
                        replacedFlesh = true;
                    }
                }
                helper.assertTrue(replacedFlesh, "zombie rotten flesh replaced with 2 gold ingots");
            });

            // Test remove operation on mob drops, non-death entity context guard, and non-stackable count split
            ResourceLocation nonStackableRule = key("loot_add_three_swords");
            ResourceLocation nonDeathGuardRule = key("loot_mob_death_only");
            withRules(helper, Map.of(
                    removeRule, """
                            {
                              "type": "loot",
                              "operation": "remove",
                              "entities": "minecraft:zombie",
                              "target": "minecraft:rotten_flesh"
                            }
                            """,
                    nonStackableRule, """
                            {
                              "type": "loot",
                              "operation": "add",
                              "tables": "minecraft:chests/simple_dungeon",
                              "item": "minecraft:iron_sword",
                              "min_count": 3,
                              "max_count": 3
                            }
                            """,
                    nonDeathGuardRule, """
                            {
                              "type": "loot",
                              "operation": "add",
                              "entities": "minecraft:zombie",
                              "item": "minecraft:nether_star"
                            }
                            """
            ), () -> {
                for (int i = 0; i < 12; i++) {
                    List<ItemStack> drops = rollZombieLoot(helper, playerA);
                    helper.assertFalse(drops.stream().anyMatch(s -> s.is(Items.ROTTEN_FLESH)), "remove stripped rotten flesh");
                    helper.assertTrue(drops.stream().anyMatch(s -> s.is(Items.NETHER_STAR)), "mob death drop applies entity rule");
                }
                // Non-stackable min_count: 3 splits into 3 separate iron swords
                List<ItemStack> chest = rollChestLoot(helper, playerA, BuiltInLootTables.SIMPLE_DUNGEON);
                long swordCount = chest.stream().filter(s -> s.is(Items.IRON_SWORD)).mapToInt(ItemStack::getCount).sum();
                helper.assertTrue(swordCount >= 3, "non-stackable loot rule splits count 3 across 3 stacks, got " + swordCount);

                // Non-death loot context (no DAMAGE_SOURCE, e.g. shearing/bartering) must NOT trigger mob-drop rules
                ServerLevel level = helper.getLevel();
                Zombie aliveZombie = EntityType.ZOMBIE.create(level);
                Vec3 pos = Vec3.atCenterOf(helper.absolutePos(new BlockPos(1, 1, 1)));
                LootTable giftTable = level.getServer().reloadableRegistries().getLootTable(BuiltInLootTables.PIGLIN_BARTERING);
                LootParams nonDeathParams = new LootParams.Builder(level)
                        .withParameter(LootContextParams.THIS_ENTITY, aliveZombie)
                        .create(LootContextParamSets.PIGLIN_BARTER);
                List<ItemStack> barter = giftTable.getRandomItems(nonDeathParams);
                helper.assertFalse(barter.stream().anyMatch(s -> s.is(Items.NETHER_STAR)),
                        "mob-drop loot rule does not fire on non-death loot tables without DAMAGE_SOURCE");
            });
        } finally {
            StoryState.clearWorld(helper.getLevel().getServer(), WorldLootModifier.onceKey(worldOnceRule));
            remove(helper, playerA);
            remove(helper, playerB);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void worldRuleCodecsValidateAllFourTypesAndRejectInvalid(GameTestHelper helper) {
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().registryAccess());
        // Valid rules of each kind
        for (String valid : List.of(
                """
                { "type": "item", "items": ["#minecraft:swords", "minecraft:trident"], "block_craft": true }
                """,
                """
                { "type": "mob", "entities": "minecraft:creeper", "block_spawners": true, "replace_with": "minecraft:zombie" }
                """,
                """
                { "type": "place", "dimension": "minecraft:the_end", "mode": "hazard", "damage": 2.5,
                  "effects": [{ "effect": "minecraft:wither", "amplifier": 1, "duration": 5 }] }
                """,
                """
                { "type": "loot", "operation": "replace", "target": "#minecraft:swords", "item": "minecraft:stick", "min_count": 1, "max_count": 3 }
                """
        )) {
            helper.assertTrue(WorldRuleDefinition.CODEC.parse(ops, JsonParser.parseString(valid)).isSuccess(),
                    "valid world rule parses: " + valid);
        }
        // Invalid rules must be rejected by the codec
        for (String invalid : List.of(
                "{ \"type\": \"item\", \"items\": [] }",
                "{ \"type\": \"mob\", \"entities\": [] }",
                "{ \"type\": \"place\", \"mode\": \"barrier\" }",
                "{ \"type\": \"loot\", \"operation\": \"add\" }",
                "{ \"type\": \"loot\", \"operation\": \"replace\", \"item\": \"minecraft:stick\" }",
                "{ \"type\": \"loot\", \"operation\": \"replace\", \"target\": \"minecraft:stick\" }",
                "{ \"type\": \"loot\", \"operation\": \"remove\" }",
                "{ \"type\": \"loot\", \"operation\": \"add\", \"item\": \"minecraft:stick\", \"min_count\": 5, \"max_count\": 2 }"
        )) {
            helper.assertTrue(WorldRuleDefinition.CODEC.parse(ops, JsonParser.parseString(invalid)).isError(),
                    "invalid world rule rejected: " + invalid);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void worldRuleValidationReportsImpossiblePlacesAndUnknownQuests(GameTestHelper helper) {
        ResourceLocation badRule = key("impossible_place");
        withRules(helper, Map.of(
                badRule, """
                        {
                          "type": "place",
                          "dimension": "minecraft:the_nether",
                          "biome": "minecraft:plains",
                          "unlock": { "type": "quest", "quest": "gametest:no_such_quest", "state": "completed" }
                        }
                        """
        ), () -> {
            List<String> issues = ValidationService.validate().issues().stream()
                    .filter(issue -> badRule.equals(issue.entry()))
                    .map(ValidationIssue::describe)
                    .toList();
            helper.assertTrue(issues.stream().anyMatch(s -> s.contains("plains") && s.contains("the_nether")),
                    "validator reports impossible biome in Nether: " + issues);
            helper.assertTrue(issues.stream().anyMatch(s -> s.contains("no_such_quest")),
                    "validator reports unknown quest in unlock: " + issues);
        });
        helper.succeed();
    }

    // --- helpers

    private static ResourceLocation key(String path) {
        return ResourceLocation.fromNamespaceAndPath("gametest", path);
    }

    private static void setFlag(ServerPlayer player, String path, boolean value) {
        try {
            StoryState.set(player, StoryScope.PLAYER, key(path), new StoryValue.Bool(value));
        } catch (StoryException e) {
            throw new GameTestAssertException(e.getMessage());
        }
    }

    private static List<ItemStack> rollChestLoot(GameTestHelper helper, ServerPlayer player, ResourceKey<LootTable> tableKey) {
        LootTable table = helper.getLevel().getServer().reloadableRegistries().getLootTable(tableKey);
        LootParams params = new LootParams.Builder(helper.getLevel())
                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(helper.absolutePos(new BlockPos(1, 1, 1))))
                .withParameter(LootContextParams.THIS_ENTITY, player)
                .create(LootContextParamSets.CHEST);
        return table.getRandomItems(params);
    }

    private static List<ItemStack> rollZombieLoot(GameTestHelper helper, ServerPlayer killer) {
        ServerLevel level = helper.getLevel();
        Zombie zombie = EntityType.ZOMBIE.create(level);
        Vec3 pos = Vec3.atCenterOf(helper.absolutePos(new BlockPos(1, 1, 1)));
        zombie.moveTo(pos.x, pos.y, pos.z, 0, 0);
        LootTable table = level.getServer().reloadableRegistries().getLootTable(EntityType.ZOMBIE.getDefaultLootTable());
        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, pos)
                .withParameter(LootContextParams.THIS_ENTITY, zombie)
                .withParameter(LootContextParams.DAMAGE_SOURCE, level.damageSources().playerAttack(killer))
                .withParameter(LootContextParams.ATTACKING_ENTITY, killer)
                .withParameter(LootContextParams.LAST_DAMAGE_PLAYER, killer)
                .create(LootContextParamSets.ENTITY);
        return table.getRandomItems(params);
    }

    private static void withRules(GameTestHelper helper, Map<ResourceLocation, String> rules, Runnable body) {
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().registryAccess());
        Map<ResourceLocation, WorldRuleDefinition> previous = WorldRuleRegistries.WORLD_RULES.serverStore().all();
        Map<ResourceLocation, WorldRuleDefinition> installed = new LinkedHashMap<>(previous);
        rules.forEach((id, json) -> installed.put(id, WorldRuleDefinition.CODEC
                .parse(ops, JsonParser.parseString(json)).getOrThrow(GameTestAssertException::new)));
        WorldRuleRegistries.WORLD_RULES.serverStore().replace(installed);
        WorldRules.rebuild();
        try {
            body.run();
        } finally {
            WorldRuleRegistries.WORLD_RULES.serverStore().replace(previous);
            WorldRules.rebuild();
        }
    }

    private static void remove(GameTestHelper helper, ServerPlayer player) {
        helper.getLevel().getServer().getPlayerList().remove(player);
    }
}

