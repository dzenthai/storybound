package dev.dzenthai.storybound.core.logic.condition;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
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
import net.minecraft.util.StringRepresentable;
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

    public record Time(Optional<Period> period, Optional<Integer> min, Optional<Integer> max) implements Condition {
        public static final MapCodec<Time> CODEC = RecordCodecBuilder.<Time>mapCodec(instance -> instance.group(
                Period.CODEC.optionalFieldOf("period").forGetter(Time::period),
                Codec.intRange(0, 24000).optionalFieldOf("min").forGetter(Time::min),
                Codec.intRange(0, 24000).optionalFieldOf("max").forGetter(Time::max)
        ).apply(instance, Time::new)).validate(time -> time.period().isEmpty() && time.min().isEmpty() && time.max().isEmpty()
                ? DataResult.error(() -> "Time condition requires 'period', 'min', or 'max'")
                : DataResult.success(time));

        @Override
        public ConditionType<?> type() {
            return BuiltinConditions.TIME.get();
        }

        @Override
        public boolean test(StoryContext context) {
            long tick = Math.floorMod(context.player().level().getDayTime(), 24000L);
            if (period.isPresent() && !period.get().contains(tick)) {
                return false;
            }
            if (min.isPresent() || max.isPresent()) {
                int from = min.orElse(0);
                int to = max.orElse(24000);
                return from <= to ? (tick >= from && tick <= to) : (tick >= from || tick <= to);
            }
            return true;
        }

        public enum Period implements StringRepresentable {
            DAY("day", 0, 12000),
            SUNSET("sunset", 12000, 13800),
            NIGHT("night", 13800, 22200),
            SUNRISE("sunrise", 22200, 24000);

            public static final Codec<Period> CODEC = StringRepresentable.fromEnum(Period::values);

            private final String name;
            private final int startTick;
            private final int endTick;

            Period(String name, int startTick, int endTick) {
                this.name = name;
                this.startTick = startTick;
                this.endTick = endTick;
            }

            public boolean contains(long tick) {
                return tick >= startTick && tick < endTick;
            }

            @Override
            public String getSerializedName() {
                return name;
            }
        }
    }

    public record Weather(State weather) implements Condition {
        public static final MapCodec<Weather> CODEC = State.CODEC.fieldOf("weather").xmap(Weather::new, Weather::weather);

        @Override
        public ConditionType<?> type() {
            return BuiltinConditions.WEATHER.get();
        }

        @Override
        public boolean test(StoryContext context) {
            Level level = context.player().level();
            return switch (weather) {
                case CLEAR -> !level.isRaining() && !level.isThundering();
                case RAIN -> level.isRaining();
                case THUNDER -> level.isThundering();
            };
        }

        public enum State implements StringRepresentable {
            CLEAR("clear"),
            RAIN("rain"),
            THUNDER("thunder");

            public static final Codec<State> CODEC = StringRepresentable.fromEnum(State::values);

            private final String name;

            State(String name) {
                this.name = name;
            }

            @Override
            public String getSerializedName() {
                return name;
            }
        }
    }
}
