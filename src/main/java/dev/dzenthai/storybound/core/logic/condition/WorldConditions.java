package dev.dzenthai.storybound.core.logic.condition;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.dzenthai.storybound.core.logic.Area;
import dev.dzenthai.storybound.core.logic.Condition;
import dev.dzenthai.storybound.core.logic.ConditionType;
import dev.dzenthai.storybound.core.logic.ItemMatcher;
import dev.dzenthai.storybound.core.logic.StoryContext;
import dev.dzenthai.storybound.core.logic.StructureRef;
import dev.dzenthai.storybound.core.state.PartyData;
import java.util.Optional;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryCodecs;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

/** Checks against the player's inventory, position and party. */
public final class WorldConditions {
    private WorldConditions() {}

    /** Counts matching items across the whole inventory, armor and offhand included. */
    public static int countItems(ServerPlayer player, ItemMatcher matcher) {
        Inventory inventory = player.getInventory();
        int count = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (matcher.test(inventory.getItem(slot))) {
                count += inventory.getItem(slot).getCount();
            }
        }
        return count;
    }

    /** {@code { "type": "has_item", "item": "minecraft:emerald", "count": 5 }} or with {@code "tag"}. */
    public record HasItem(ItemMatcher matcher, int count) implements Condition {
        public static final MapCodec<HasItem> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                ItemMatcher.MAP_CODEC.forGetter(HasItem::matcher),
                Codec.intRange(1, Integer.MAX_VALUE).optionalFieldOf("count", 1).forGetter(HasItem::count)
        ).apply(instance, HasItem::new));

        @Override
        public ConditionType<?> type() {
            return BuiltinConditions.HAS_ITEM.get();
        }

        @Override
        public boolean test(StoryContext context) {
            return countItems(context.player(), matcher) >= count;
        }
    }

    /**
     * Every given part must match: {@code dimension}, {@code biome} (id, #tag or list), {@code area} and {@code structure}
     * (id or #tag: the player is within such a structure).
     */
    public record Location(Optional<ResourceKey<Level>> dimension, Optional<HolderSet<Biome>> biome, Optional<Area> area,
                           Optional<StructureRef> structure) implements Condition {
        public static final MapCodec<Location> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Level.RESOURCE_KEY_CODEC.optionalFieldOf("dimension").forGetter(Location::dimension),
                RegistryCodecs.homogeneousList(Registries.BIOME).optionalFieldOf("biome").forGetter(Location::biome),
                Area.CODEC.optionalFieldOf("area").forGetter(Location::area),
                StructureRef.CODEC.optionalFieldOf("structure").forGetter(Location::structure)
        ).apply(instance, Location::new));

        @Override
        public ConditionType<?> type() {
            return BuiltinConditions.LOCATION.get();
        }

        @Override
        public boolean test(StoryContext context) {
            ServerPlayer player = context.player();
            return dimension.map(player.level().dimension()::equals).orElse(true)
                    && biome.map(set -> set.contains(player.level().getBiome(player.blockPosition()))).orElse(true)
                    && area.map(sphere -> sphere.contains(player)).orElse(true)
                    && structure.map(ref -> ref.contains(player.serverLevel(), player.blockPosition())).orElse(true);
        }
    }

    /** True when the player is in a real party (not the implicit solo one) of at least {@code min_size} members. */
    public record InParty(int minSize) implements Condition {
        public static final MapCodec<InParty> CODEC = Codec.intRange(1, Integer.MAX_VALUE).optionalFieldOf("min_size", 1)
                .xmap(InParty::new, InParty::minSize);

        @Override
        public ConditionType<?> type() {
            return BuiltinConditions.IN_PARTY.get();
        }

        @Override
        public boolean test(StoryContext context) {
            return PartyData.get(context.server()).partyOf(context.player().getUUID())
                    .map(party -> party.members().size() >= minSize)
                    .orElse(false);
        }
    }
}
