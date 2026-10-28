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
 * Also what each quest gave the player the last time, so the Journal can show it after the claim.
 */
public record QuestRewardData(List<Pending> pending, List<Received> received) {
    public static final QuestRewardData EMPTY = new QuestRewardData(List.of(), List.of());
    private static final Codec<QuestRewardData> OBJECT = RecordCodecBuilder.create(instance -> instance.group(
            Pending.CODEC.listOf().optionalFieldOf("pending", List.of()).forGetter(QuestRewardData::pending),
            Received.CODEC.listOf().optionalFieldOf("received", List.of()).forGetter(QuestRewardData::received)
    ).apply(instance, QuestRewardData::new));
    /** Saves from before October 28, 2026 hold only the waiting list. */
    public static final Codec<QuestRewardData> CODEC = Codec.withAlternative(OBJECT,
            Pending.CODEC.listOf().xmap(pending -> new QuestRewardData(pending, List.of()), QuestRewardData::pending));

    public QuestRewardData {
        pending = List.copyOf(pending);
        received = List.copyOf(received);
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

    /** @param items every stack given: the items, the one chosen, the loot rolled */
    public record Received(ResourceLocation quest, List<ItemStack> items, int xp) {
        public static final Codec<Received> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ResourceLocation.CODEC.fieldOf("quest").forGetter(Received::quest),
                ItemStack.CODEC.listOf().optionalFieldOf("items", List.of()).forGetter(Received::items),
                Codec.INT.optionalFieldOf("xp", 0).forGetter(Received::xp)
        ).apply(instance, Received::new));
        public static final StreamCodec<RegistryFriendlyByteBuf, Received> STREAM_CODEC = StreamCodec.composite(
                ResourceLocation.STREAM_CODEC, Received::quest,
                ItemStack.LIST_STREAM_CODEC, Received::items,
                ByteBufCodecs.VAR_INT, Received::xp,
                Received::new);

        public Received {
            items = List.copyOf(items);
        }
    }

    /** The waiting rewards as the quest sync sends them. */
    public static final StreamCodec<RegistryFriendlyByteBuf, List<Pending>> LIST_STREAM_CODEC = Pending.STREAM_CODEC.apply(ByteBufCodecs.list());
    /** The rewards received as the quest sync sends them. */
    public static final StreamCodec<RegistryFriendlyByteBuf, List<Received>> RECEIVED_STREAM_CODEC = Received.STREAM_CODEC.apply(ByteBufCodecs.list());

    public static QuestRewardData of(ServerPlayer player) {
        return player.getData(QuestRegistries.REWARDS);
    }

    static void add(ServerPlayer player, Pending pending) {
        List<Pending> next = new ArrayList<>(of(player).pending());
        next.add(pending);
        player.setData(QuestRegistries.REWARDS, new QuestRewardData(next, of(player).received()));
    }

    static Optional<Pending> first(ServerPlayer player, ResourceLocation quest) {
        return of(player).pending().stream().filter(pending -> pending.quest().equals(quest)).findFirst();
    }

    static void remove(ServerPlayer player, Pending pending) {
        List<Pending> next = new ArrayList<>(of(player).pending());
        next.remove(pending);
        player.setData(QuestRegistries.REWARDS, new QuestRewardData(next, of(player).received()));
    }

    /** Keeps what the quest gave, in place of what it gave before (a repeatable quest shows its last time). */
    static void receive(ServerPlayer player, Received received) {
        List<Received> next = new ArrayList<>(of(player).received());
        next.removeIf(old -> old.quest().equals(received.quest()));
        next.add(received);
        player.setData(QuestRegistries.REWARDS, new QuestRewardData(of(player).pending(), next));
    }

    public Optional<Received> received(ResourceLocation quest) {
        return received.stream().filter(entry -> entry.quest().equals(quest)).findFirst();
    }
}
