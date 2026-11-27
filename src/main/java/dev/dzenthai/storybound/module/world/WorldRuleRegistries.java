package dev.dzenthai.storybound.module.world;

import com.mojang.serialization.MapCodec;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.data.DefinitionType;
import dev.dzenthai.storybound.core.data.DefinitionTypes;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * Registers the {@code world_rule} definition type ({@code storybound/world_rules}) and the dynamic
 * {@code storybound:world_loot} Global Loot Modifier serializer.
 */
public final class WorldRuleRegistries {
    public static final DefinitionType<WorldRuleDefinition> WORLD_RULES = DefinitionTypes.register(
            DefinitionType.serverOnly(Storybound.id("world_rule"), "storybound/world_rules", WorldRuleDefinition.CODEC));

    private static final DeferredRegister<MapCodec<? extends IGlobalLootModifier>> LOOT_MODIFIERS =
            DeferredRegister.create(NeoForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS, Storybound.MOD_ID);

    public static final DeferredHolder<MapCodec<? extends IGlobalLootModifier>, MapCodec<WorldLootModifier>> WORLD_LOOT =
            LOOT_MODIFIERS.register("world_loot", () -> WorldLootModifier.CODEC);

    private WorldRuleRegistries() {}

    public static void register(IEventBus modBus) {
        LOOT_MODIFIERS.register(modBus);
    }
}

