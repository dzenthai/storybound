package dev.dzenthai.storybound.compat.jei;

import dev.dzenthai.storybound.client.ClientQuests;
import dev.dzenthai.storybound.core.logic.ItemMatcher;
import dev.dzenthai.storybound.module.quest.QuestDefinition;
import dev.dzenthai.storybound.module.quest.QuestRegistries;
import dev.dzenthai.storybound.module.quest.QuestRewards;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * One line of the "Storybound quests" JEI category: items a quest asks for (shown under an item's uses) or gives
 * (shown under how to get it). Only quests the player knows, so JEI spoils nothing the Journal does not show. No JEI
 * types here: the recipes are worked out without JEI and handed to it by {@link JeiQuests}.
 *
 * @param slots each slot's items (several for a tag or to cycle through)
 * @param count how many an objective asks for; 0 for rewards
 */
public record QuestRecipe(ResourceLocation quest, Component title, Kind kind, List<List<ItemStack>> slots, int count) {
    public enum Kind { BRING, HAND_IN, REWARD, CHOICE }

    /** The recipes of every quest the player knows, in the Journal's order. */
    public static List<QuestRecipe> forKnownQuests() {
        List<QuestRecipe> recipes = new ArrayList<>();
        Map<ResourceLocation, QuestDefinition> definitions = QuestRegistries.QUESTS.clientStore().all();
        ClientQuests.all().keySet().stream().sorted().forEach(quest -> {
            QuestDefinition definition = definitions.get(quest);
            if (definition != null) {
                recipes.addAll(of(quest, definition));
            }
        });
        return recipes;
    }

    /** What the quest asks for (objectives with items) and gives (reward items, one of the choice). */
    public static List<QuestRecipe> of(ResourceLocation quest, QuestDefinition definition) {
        Component title = definition.title().asComponent();
        List<QuestRecipe> recipes = new ArrayList<>();
        for (QuestDefinition.Stage stage : definition.stages()) {
            for (QuestDefinition.Objective objective : stage.objectives()) {
                objective.items().ifPresent(items -> {
                    List<ItemStack> stacks = stacks(items.matcher());
                    if (!stacks.isEmpty()) {
                        recipes.add(new QuestRecipe(quest, title, items.consume() ? Kind.HAND_IN : Kind.BRING, List.of(stacks), objective.count()));
                    }
                });
            }
        }
        QuestRewards.Rewards rewards = definition.rewards();
        List<List<ItemStack>> given = rewards.items().stream().map(item -> List.of(item.stack())).toList();
        if (!given.isEmpty()) {
            recipes.add(new QuestRecipe(quest, title, Kind.REWARD, given, 0));
        }
        List<List<ItemStack>> choice = rewards.choice().stream().map(item -> List.of(item.stack())).toList();
        if (!choice.isEmpty()) {
            recipes.add(new QuestRecipe(quest, title, Kind.CHOICE, choice, 0));
        }
        return recipes;
    }

    private static List<ItemStack> stacks(ItemMatcher matcher) {
        if (matcher.item().isPresent()) {
            return List.of(new ItemStack(matcher.item().get()));
        }
        return matcher.tag().flatMap(BuiltInRegistries.ITEM::getTag)
                .map(set -> set.stream().map(ItemStack::new).toList())
                .orElse(List.of());
    }
}
