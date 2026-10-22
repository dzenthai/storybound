package dev.dzenthai.storybound.module.quest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Quest rewards waiting for the player to claim them in the Journal (attachment {@code storybound:quest_rewards}):
 * personal even for a party quest, kept through death, one entry per completion (a repeatable quest may have several).
 */
public record QuestRewardData(List<Pending> pending) {
    public static final QuestRewardData EMPTY = new QuestRewardData(List.of());
    public static final Codec<QuestRewardData> CODEC = Pending.CODEC.listOf().xmap(QuestRewardData::new, QuestRewardData::pending);

    public QuestRewardData {
        pending = List.copyOf(pending);
    }

    /** @param loot what the quest's loot table gave when it was completed */
    public record Pending(ResourceLocation quest, List<ItemStack> loot) {
        public static final Codec<Pending> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ResourceLocation.CODEC.fieldOf("quest").forGetter(Pending::quest),
                ItemStack.CODEC.listOf().optionalFieldOf("loot", List.of()).forGetter(Pending::loot)
        ).apply(instance, Pending::new));
        public static final StreamCodec<RegistryFriendlyByteBuf, Pending> STREAM_CODEC = StreamCodec.composite(
                ResourceLocation.STREAM_CODEC, Pending::quest,
                ItemStack.LIST_STREAM_CODEC, Pending::loot,
                Pending::new);

        public Pending {
            loot = List.copyOf(loot);
        }
    }

    /** The waiting rewards as the quest sync sends them. */
    public static final StreamCodec<RegistryFriendlyByteBuf, List<Pending>> LIST_STREAM_CODEC = Pending.STREAM_CODEC.apply(ByteBufCodecs.list());

    public static QuestRewardData of(ServerPlayer player) {
        return player.getData(QuestRegistries.REWARDS);
    }

    static void add(ServerPlayer player, Pending pending) {
        List<Pending> next = new ArrayList<>(of(player).pending());
        next.add(pending);
        player.setData(QuestRegistries.REWARDS, new QuestRewardData(next));
    }

    static Optional<Pending> first(ServerPlayer player, ResourceLocation quest) {
        return of(player).pending().stream().filter(pending -> pending.quest().equals(quest)).findFirst();
    }

    static void remove(ServerPlayer player, Pending pending) {
        List<Pending> next = new ArrayList<>(of(player).pending());
        next.remove(pending);
        player.setData(QuestRegistries.REWARDS, new QuestRewardData(next));
    }
}
