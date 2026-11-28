package dev.dzenthai.storybound.module.world;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.StoryException;
import dev.dzenthai.storybound.core.logic.StoryContext;
import dev.dzenthai.storybound.core.logic.StoryTrace;
import dev.dzenthai.storybound.core.logic.StructureRef;
import dev.dzenthai.storybound.core.state.StoryScope;
import dev.dzenthai.storybound.core.state.StoryState;
import dev.dzenthai.storybound.core.state.StoryValue;
import dev.dzenthai.storybound.module.document.DocumentDefinition;
import dev.dzenthai.storybound.module.world.WorldRuleDefinition.LootModifierRule;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
import net.neoforged.neoforge.common.loot.LootModifier;
import org.jetbrains.annotations.Nullable;

/**
 * Single dynamic NeoForge Global Loot Modifier backing all {@code type: "loot"} {@link WorldRuleDefinition} rules.
 * Supports adding, replacing, and removing any items across chests, specific loot tables, and mob drops, updating
 * immediately on {@code /reload} or Dev Mode saves with no extra datapack wiring.
 */
public final class WorldLootModifier extends LootModifier {
    public static final String ONCE_PREFIX = "world_loot/";

    public static final MapCodec<WorldLootModifier> CODEC = RecordCodecBuilder.mapCodec(
            instance -> codecStart(instance).apply(instance, WorldLootModifier::new));

    public WorldLootModifier(LootItemCondition[] conditions) {
        super(conditions);
    }

    public static ResourceLocation onceKey(ResourceLocation ruleId) {
        return Storybound.id(ONCE_PREFIX + ruleId.getNamespace() + "/" + ruleId.getPath());
    }

    @Override
    protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> generatedLoot, LootContext context) {
        if (WorldRules.lootRules().isEmpty()) {
            return generatedLoot;
        }
        ResourceLocation tableId = context.getQueriedLootTableId();
        List<WorldRules.Entry<LootModifierRule>> tableRules = WorldRules.lootRulesForTable(tableId);
        Entity killedEntity = findKilledEntity(context);
        List<WorldRules.Entry<LootModifierRule>> entityRules = killedEntity != null ? WorldRules.lootRulesForEntity(killedEntity.getType()) : null;
        List<WorldRules.Entry<LootModifierRule>> chestRules = WorldRules.lootChestRules();
        boolean checkChests = !chestRules.isEmpty() && DocumentDefinition.LootRule.isChestTable(tableId);
        if (tableRules == null && entityRules == null && !checkChests) {
            return generatedLoot;
        }
        ServerLevel level = context.getLevel();
        Vec3 origin = context.getParamOrNull(LootContextParams.ORIGIN);
        BlockPos pos = origin != null ? BlockPos.containing(origin) : null;
        ServerPlayer player = findPlayer(context);
        RandomSource random = context.getRandom();

        if (tableRules != null) {
            applyBucket(generatedLoot, tableRules, tableId, killedEntity, level, pos, player, random);
        }
        if (entityRules != null) {
            applyBucket(generatedLoot, entityRules, tableId, killedEntity, level, pos, player, random);
        }
        if (checkChests) {
            applyBucket(generatedLoot, chestRules, tableId, killedEntity, level, pos, player, random);
        }
        return generatedLoot;
    }

    private static void applyBucket(ObjectArrayList<ItemStack> generatedLoot, List<WorldRules.Entry<LootModifierRule>> rules,
                                    ResourceLocation tableId, @Nullable Entity killedEntity, ServerLevel level,
                                    @Nullable BlockPos pos, @Nullable ServerPlayer player, RandomSource random) {
        for (int i = 0; i < rules.size(); i++) {
            WorldRules.Entry<LootModifierRule> entry = rules.get(i);
            ResourceLocation ruleId = entry.id();
            LootModifierRule rule = entry.rule();
            if (!matchesContext(ruleId, rule, tableId, killedEntity, level, pos, player)) {
                continue;
            }
            boolean applied = switch (rule.operation()) {
                case ADD -> applyAdd(generatedLoot, rule, random);
                case REPLACE -> applyReplace(generatedLoot, rule, random);
                case REMOVE -> applyRemove(generatedLoot, rule, random);
            };
            if (applied) {
                recordOnce(ruleId, rule.once(), level, player);
                if (player != null) {
                    StoryTrace.note(player, "world_rule " + ruleId, "loot " + rule.operation().getSerializedName() + " on " + tableId);
                }
            }
        }
    }

    private static boolean applyAdd(ObjectArrayList<ItemStack> loot, LootModifierRule rule, RandomSource random) {
        if (rule.chance() < 1.0f && random.nextFloat() >= rule.chance()) {
            return false;
        }
        addSplitStacks(loot, rule, rule.rollCount(random));
        return true;
    }

    private static boolean applyReplace(ObjectArrayList<ItemStack> loot, LootModifierRule rule, RandomSource random) {
        boolean changed = false;
        List<ItemStack> extras = null;
        for (int i = 0; i < loot.size(); i++) {
            ItemStack stack = loot.get(i);
            if (!rule.matchesTarget(stack)) {
                continue;
            }
            if (rule.chance() < 1.0f && random.nextFloat() >= rule.chance()) {
                continue;
            }
            int remaining = rule.rollCount(random);
            ItemStack first = rule.createStack(remaining);
            loot.set(i, first);
            remaining -= first.getCount();
            while (remaining > 0) {
                ItemStack extra = rule.createStack(remaining);
                if (extras == null) {
                    extras = new java.util.ArrayList<>();
                }
                extras.add(extra);
                remaining -= extra.getCount();
            }
            changed = true;
        }
        if (extras != null) {
            loot.addAll(extras);
        }
        return changed;
    }

    /** Splits counts larger than the item's max stack size (e.g. non-stackable tools/potions) into multiple stacks. */
    private static void addSplitStacks(ObjectArrayList<ItemStack> loot, LootModifierRule rule, int totalCount) {
        int remaining = totalCount;
        while (remaining > 0) {
            ItemStack stack = rule.createStack(remaining);
            loot.add(stack);
            remaining -= stack.getCount();
        }
    }

    private static boolean applyRemove(ObjectArrayList<ItemStack> loot, LootModifierRule rule, RandomSource random) {
        boolean changed = false;
        for (int i = loot.size() - 1; i >= 0; i--) {
            ItemStack stack = loot.get(i);
            if (!rule.matchesTarget(stack)) {
                continue;
            }
            if (rule.chance() < 1.0f && random.nextFloat() >= rule.chance()) {
                continue;
            }
            loot.remove(i);
            changed = true;
        }
        return changed;
    }

    private static boolean matchesContext(ResourceLocation ruleId, LootModifierRule rule, ResourceLocation tableId,
                                          @Nullable Entity killedEntity, ServerLevel level, @Nullable BlockPos pos,
                                          @Nullable ServerPlayer player) {
        if (rule.chance() <= 0.0f) {
            return false;
        }
        boolean hasTables = !rule.tables().isEmpty();
        boolean hasEntities = !rule.entities().isEmpty();
        if (hasTables && !rule.tables().contains(tableId)) {
            return false;
        }
        if (hasEntities && (killedEntity == null || !rule.matchesEntity(killedEntity.getType()))) {
            return false;
        }
        if (!hasTables && !hasEntities && !DocumentDefinition.LootRule.isChestTable(tableId)) {
            return false;
        }
        if (rule.dimension().isPresent() && !level.dimension().equals(rule.dimension().get())) {
            return false;
        }
        if (rule.biome().isPresent() && (pos == null || !rule.biome().get().contains(level.getBiome(pos)))) {
            return false;
        }
        if (rule.structure().isPresent()
                && (pos == null || !StructureRef.containsAny(level, pos, rule.structure().get()))) {
            return false;
        }
        if (rule.once() == DocumentDefinition.LootRule.Once.WORLD) {
            if (StoryState.getWorldStored(level.getServer(), onceKey(ruleId)).isPresent()) {
                return false;
            }
        } else if (rule.once() == DocumentDefinition.LootRule.Once.PLAYER) {
            if (player == null || StoryState.getStored(player, StoryScope.PLAYER, onceKey(ruleId)).isPresent()) {
                return false;
            }
        }
        if (rule.conditions().isPresent()) {
            return player != null && rule.conditions().get().test(StoryContext.of(player));
        }
        return true;
    }

    private static void recordOnce(ResourceLocation ruleId, DocumentDefinition.LootRule.Once once,
                                   ServerLevel level, @Nullable ServerPlayer player) {
        if (once == DocumentDefinition.LootRule.Once.WORLD) {
            StoryState.setWorld(level.getServer(), onceKey(ruleId), new StoryValue.Bool(true));
        } else if (once == DocumentDefinition.LootRule.Once.PLAYER && player != null) {
            try {
                StoryState.set(player, StoryScope.PLAYER, onceKey(ruleId), new StoryValue.Bool(true));
            } catch (StoryException ignored) {
                // Internal marker key is never declared with a conflicting type.
            }
        }
    }

    private static @Nullable ServerPlayer findPlayer(LootContext context) {
        Entity direct = context.getParamOrNull(LootContextParams.THIS_ENTITY);
        if (direct instanceof ServerPlayer player) {
            return player;
        }
        Entity lastDamage = context.getParamOrNull(LootContextParams.LAST_DAMAGE_PLAYER);
        if (lastDamage instanceof ServerPlayer player) {
            return player;
        }
        Entity attacker = context.getParamOrNull(LootContextParams.ATTACKING_ENTITY);
        if (attacker instanceof ServerPlayer player) {
            return player;
        }
        return null;
    }

    private static @Nullable Entity findKilledEntity(LootContext context) {
        if (!context.hasParam(LootContextParams.DAMAGE_SOURCE)) {
            return null;
        }
        Entity subject = context.getParamOrNull(LootContextParams.THIS_ENTITY);
        return subject != null && !(subject instanceof Player) ? subject : null;
    }

    @Override
    public MapCodec<? extends IGlobalLootModifier> codec() {
        return CODEC;
    }
}

