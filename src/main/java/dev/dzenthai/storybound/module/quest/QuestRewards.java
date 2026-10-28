package dev.dzenthai.storybound.module.quest;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.dzenthai.storybound.core.logic.Action;
import dev.dzenthai.storybound.core.logic.Actions;
import dev.dzenthai.storybound.core.logic.LogicCodecs;
import dev.dzenthai.storybound.core.logic.OnFail;
import dev.dzenthai.storybound.core.logic.StoryContext;
import dev.dzenthai.storybound.core.logic.StoryTrace;
import dev.dzenthai.storybound.core.notify.Notifications;
import dev.dzenthai.storybound.core.notify.NotifyKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;

/**
 * A quest's rewards as in FTB Quests: items, one item of several to choose, a loot table, experience and actions,
 * waiting in the Journal for the player to claim them (or given at once). Never dropped on the ground: a claim
 * that does not fit into the inventory is refused with how many slots to free. The older form, a list of actions,
 * still works and runs at once as before.
 */
public final class QuestRewards {
    private QuestRewards() {}

    /**
     * An item reward: {@code { "item": "minecraft:emerald", "count": 5, "components": { ... } }}, also written as an
     * item stack ({@code "id"}) or just its id. A count above a stack is given as several stacks.
     */
    public record RewardItem(Holder<Item> item, int count, DataComponentPatch components) {
        private static final Codec<RewardItem> ITEM_FORM = RecordCodecBuilder.create(instance -> instance.group(
                ItemStack.ITEM_NON_AIR_CODEC.fieldOf("item").forGetter(RewardItem::item),
                Codec.intRange(1, 64 * 36).optionalFieldOf("count", 1).forGetter(RewardItem::count),
                DataComponentPatch.CODEC.optionalFieldOf("components", DataComponentPatch.EMPTY).forGetter(RewardItem::components)
        ).apply(instance, RewardItem::new));
        private static final Codec<RewardItem> STACK_FORM = Codec.withAlternative(ItemStack.CODEC, ItemStack.SIMPLE_ITEM_CODEC)
                .xmap(stack -> new RewardItem(stack.getItemHolder(), stack.getCount(), stack.getComponentsPatch()), RewardItem::stack);
        public static final Codec<RewardItem> CODEC = Codec.withAlternative(ITEM_FORM, STACK_FORM);

        /** One stack of it, for showing (its count may be above a stack). */
        public ItemStack stack() {
            ItemStack stack = new ItemStack(item, count);
            stack.applyComponents(components);
            return stack;
        }

        /** Stacks of at most the item's stack size. */
        public List<ItemStack> stacks() {
            List<ItemStack> stacks = new ArrayList<>();
            ItemStack one = new ItemStack(item, 1);
            one.applyComponents(components);
            int left = count;
            while (left > 0) {
                int size = Math.min(left, one.getMaxStackSize());
                stacks.add(one.copyWithCount(size));
                left -= size;
            }
            return stacks;
        }
    }

    /**
     * @param actions run when the rewards are given (with the older list form, at once, and nothing else)
     * @param choice  one of them, picked by the player when claiming
     * @param loot    a loot table, rolled when the quest is completed (so claiming again cannot roll again)
     * @param xp      experience points
     * @param claim   whether they wait for the Journal's "Claim" button; otherwise given at once when they fit (and
     *                when nothing is to be chosen), else they wait too
     */
    public record Rewards(List<Action> actions, List<RewardItem> items, List<RewardItem> choice, Optional<ResourceKey<LootTable>> loot, int xp,
                          boolean claim) {
        public static final Rewards NONE = new Rewards(List.of(), List.of(), List.of(), Optional.empty(), 0, false);

        private static final Codec<Rewards> OBJECT = RecordCodecBuilder.create(instance -> instance.group(
                LogicCodecs.ACTIONS.optionalFieldOf("actions", List.of()).forGetter(Rewards::actions),
                RewardItem.CODEC.listOf().optionalFieldOf("items", List.of()).forGetter(Rewards::items),
                RewardItem.CODEC.listOf().optionalFieldOf("choice", List.of()).forGetter(Rewards::choice),
                ResourceKey.codec(Registries.LOOT_TABLE).optionalFieldOf("loot").forGetter(Rewards::loot),
                Codec.intRange(0, 1_000_000).optionalFieldOf("xp", 0).forGetter(Rewards::xp),
                Codec.BOOL.optionalFieldOf("claim", true).forGetter(Rewards::claim)
        ).apply(instance, Rewards::new));

        /** The older form, a list of actions, or the object; a list is given at once, as it always was. */
        public static final Codec<Rewards> CODEC = Codec.either(LogicCodecs.ACTIONS, OBJECT).comapFlatMap(
                either -> DataResult.success(either.map(actions -> new Rewards(actions, List.of(), List.of(), Optional.empty(), 0, false), rewards -> rewards)),
                rewards -> rewards.isActionList() ? Either.left(rewards.actions()) : Either.right(rewards));

        /** Only actions, given at once: what the older form says. */
        public boolean isActionList() {
            return !hasThings() && !claim;
        }

        /** Items, a choice, loot or experience: what the Journal shows and a claim gives. */
        public boolean hasThings() {
            return !items.isEmpty() || !choice.isEmpty() || loot.isPresent() || xp > 0;
        }

        /** What clients see: everything but the actions. */
        Rewards clientView() {
            return new Rewards(List.of(), items, choice, loot, xp, claim);
        }
    }

    /**
     * The quest was completed for the player: the rewards are given now, or wait in the Journal ({@link QuestRewardData}).
     */
    static void grant(ServerPlayer player, ResourceLocation quest, Rewards rewards) {
        String origin = "quest " + quest + " rewards";
        // Nothing to claim (an empty object the editor left): only the actions, at once; nothing waits in the Journal.
        if (rewards.isActionList() || !rewards.hasThings()) {
            Actions.runAll(rewards.actions(), StoryContext.of(player), origin, OnFail.ABORT);
            return;
        }
        List<ItemStack> loot = roll(player, rewards);
        if (!rewards.claim() && rewards.choice().isEmpty() && fits(player.getInventory(), things(rewards, loot, -1)) == 0) {
            give(player, quest, rewards, loot, -1);
            Quests.sync(player);
            return;
        }
        QuestRewardData.add(player, new QuestRewardData.Pending(quest, loot));
        StoryTrace.note(player, origin, "waiting in the Journal" + (rewards.claim() ? "" : " (they did not fit, or something is to be chosen)"));
        Quests.sync(player);
    }

    /**
     * Pays the party quests completed while the player was away ({@link QuestRewardsOwed}), with the completion toast
     * they missed; when they join. Nothing while the quests module is disabled: the debt waits. Public for tests.
     */
    public static void payOwed(ServerPlayer player) {
        if (!Quests.isEnabled()) {
            return;
        }
        List<ResourceLocation> owed = QuestRewardsOwed.get(player.server).take(player.getUUID());
        for (ResourceLocation quest : owed) {
            QuestDefinition definition = Quests.definition(quest);
            StoryTrace.note(player, "quest " + quest + " rewards", "completed by the party while the player was away: paid on joining");
            if (definition.announce()) {
                Notifications.send(player, NotifyKind.QUEST_COMPLETED, definition.title().resolve(player), Optional.empty());
            }
            grant(player, quest, definition.rewards());
        }
        if (!owed.isEmpty()) {
            Quests.sync(player);
        }
    }

    /**
     * The player claims the quest's rewards (its first waiting ones), with the item chosen.
     *
     * @param choice the index in {@link Rewards#choice()}, or -1 when there is nothing to choose
     * @return why not, or null when given
     */
    public static Component claim(ServerPlayer player, ResourceLocation quest, int choice) {
        QuestDefinition definition = Quests.definition(quest);
        QuestRewardData.Pending pending = QuestRewardData.first(player, quest).orElse(null);
        if (definition == null || pending == null) {
            return Component.translatable("storybound.quests.rewards.nothing");
        }
        Rewards rewards = definition.rewards();
        if (!rewards.choice().isEmpty() && (choice < 0 || choice >= rewards.choice().size())) {
            return Component.translatable("storybound.quests.rewards.choose");
        }
        int missing = fits(player.getInventory(), things(rewards, pending.loot(), choice));
        if (missing > 0) {
            StoryTrace.note(player, "quest " + quest + " rewards", "not claimed: " + missing + " more free slots are needed");
            return Component.translatable("storybound.quests.rewards.no_room", missing);
        }
        QuestRewardData.remove(player, pending);
        give(player, quest, rewards, pending.loot(), choice);
        Quests.sync(player);
        return null;
    }

    /** Gives the rewards and keeps what was given for the Journal; the caller syncs. */
    private static void give(ServerPlayer player, ResourceLocation quest, Rewards rewards, List<ItemStack> loot, int choice) {
        String origin = "quest " + quest + " rewards";
        List<ItemStack> given = things(rewards, loot, choice);
        QuestRewardData.receive(player, new QuestRewardData.Received(quest, given.stream().map(ItemStack::copy).toList(), rewards.xp()));
        for (ItemStack stack : given) {
            if (!player.getInventory().add(stack.copy())) {
                // Only when the inventory changed between the check and now (another mod): better at the feet than lost.
                player.drop(stack, false);
            }
        }
        if (rewards.xp() > 0) {
            player.giveExperiencePoints(rewards.xp());
        }
        StoryTrace.event(player, "rewards", "source", origin, "items", String.valueOf(given.size()), "xp", String.valueOf(rewards.xp()),
                "choice", String.valueOf(choice));
        Actions.runAll(rewards.actions(), StoryContext.of(player), origin, OnFail.ABORT);
    }

    /** Every stack a claim gives: the items, the one chosen, the loot rolled. */
    static List<ItemStack> things(Rewards rewards, List<ItemStack> loot, int choice) {
        List<ItemStack> stacks = new ArrayList<>();
        rewards.items().forEach(item -> stacks.addAll(item.stacks()));
        if (choice >= 0 && choice < rewards.choice().size()) {
            stacks.addAll(rewards.choice().get(choice).stacks());
        }
        loot.forEach(stack -> stacks.add(stack.copy()));
        return stacks;
    }

    private static List<ItemStack> roll(ServerPlayer player, Rewards rewards) {
        if (rewards.loot().isEmpty()) {
            return List.of();
        }
        LootTable table = player.server.reloadableRegistries().getLootTable(rewards.loot().get());
        if (table == LootTable.EMPTY) {
            StoryTrace.note(player, "rewards", "the loot table " + rewards.loot().get().location() + " is empty or unknown");
        }
        LootParams params = new LootParams.Builder(player.serverLevel())
                .withParameter(LootContextParams.ORIGIN, player.position())
                .withParameter(LootContextParams.THIS_ENTITY, player)
                .withLuck(player.getLuck())
                .create(LootContextParamSets.GIFT);
        return List.copyOf(table.getRandomItems(params));
    }

    /**
     * How many more free slots of the main inventory (hotbar included) the stacks need: they fill matching stacks
     * first, then empty slots. 0 when they fit.
     */
    static int fits(Inventory inventory, List<ItemStack> stacks) {
        List<ItemStack> slots = new ArrayList<>();
        for (ItemStack stack : inventory.items) {
            slots.add(stack.copy());
        }
        int missing = 0;
        for (ItemStack incoming : stacks) {
            int left = incoming.getCount();
            for (ItemStack slot : slots) {
                if (left > 0 && !slot.isEmpty() && ItemStack.isSameItemSameComponents(slot, incoming) && slot.getCount() < slot.getMaxStackSize()) {
                    int moved = Math.min(left, slot.getMaxStackSize() - slot.getCount());
                    slot.grow(moved);
                    left -= moved;
                }
            }
            if (left <= 0) {
                continue;
            }
            int free = -1;
            for (int i = 0; i < slots.size(); i++) {
                if (slots.get(i).isEmpty()) {
                    free = i;
                    break;
                }
            }
            if (free < 0) {
                missing++;
            } else {
                slots.set(free, incoming.copyWithCount(left));
            }
        }
        return missing;
    }
}
