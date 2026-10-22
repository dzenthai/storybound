package dev.dzenthai.storybound.core.logic;

import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stat;
import net.minecraft.stats.StatType;

/**
 * A vanilla statistic: {@code "stat": "minecraft:mob_kills"} (a custom one, the default {@code stat_type}), or with
 * {@code "stat_type": "minecraft:killed", "stat": "minecraft:zombie"} (zombies killed), {@code minecraft:mined} (a block),
 * {@code minecraft:used}, {@code minecraft:crafted} (an item) and the others of {@code /scoreboard}.
 */
public record StatRef(ResourceLocation statType, ResourceLocation stat) {
    private static final ResourceLocation CUSTOM = ResourceLocation.withDefaultNamespace("custom");
    public static final MapCodec<StatRef> MAP_CODEC = RecordCodecBuilder.<StatRef>mapCodec(instance -> instance.group(
            ResourceLocation.CODEC.optionalFieldOf("stat_type", CUSTOM).forGetter(StatRef::statType),
            ResourceLocation.CODEC.fieldOf("stat").forGetter(StatRef::stat)
    ).apply(instance, StatRef::new)).validate(ref -> ref.resolve().isPresent()
            ? DataResult.success(ref)
            : DataResult.error(() -> "Unknown statistic " + ref.statType() + " " + ref.stat()));

    public Optional<Stat<?>> resolve() {
        StatType<?> type = BuiltInRegistries.STAT_TYPE.get(statType);
        return type == null ? Optional.empty() : resolve(type);
    }

    private <T> Optional<Stat<?>> resolve(StatType<T> type) {
        return type.getRegistry().getOptional(stat).map(type::get);
    }

    /** The player's value; 0 for a statistic no longer known. */
    public int value(ServerPlayer player) {
        return resolve().map(resolved -> player.getStats().getValue(resolved)).orElse(0);
    }

    /** As the trace and the validator show it. */
    public String describe() {
        return CUSTOM.equals(statType) ? stat.toString() : statType + " " + stat;
    }
}
