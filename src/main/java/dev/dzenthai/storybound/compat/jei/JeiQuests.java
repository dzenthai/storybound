package dev.dzenthai.storybound.compat.jei;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.ClientQuests;
import dev.dzenthai.storybound.module.quest.QuestRegistries;
import java.util.List;
import java.util.TreeSet;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.runtime.IJeiRuntime;
import org.jetbrains.annotations.Nullable;

/**
 * Keeps JEI's "Storybound quests" recipes in step with the quests the player knows: called when JEI starts and on
 * every quest sync, it replaces the recipes only when the known quests or their definitions changed. Call it only
 * when JEI is installed; the client's main thread.
 */
public final class JeiQuests {
    static final RecipeType<QuestRecipe> TYPE = RecipeType.create(Storybound.MOD_ID, "quests", QuestRecipe.class);

    private static @Nullable IJeiRuntime runtime;
    private static List<QuestRecipe> shown = List.of();
    private static String shownFor = "";

    private JeiQuests() {}

    static void available(IJeiRuntime jei) {
        runtime = jei;
        shown = List.of();
        shownFor = "";
        refresh();
    }

    static void unavailable() {
        runtime = null;
        shown = List.of();
        shownFor = "";
    }

    public static void refresh() {
        IJeiRuntime jei = runtime;
        if (jei == null) {
            return;
        }
        // The known quests and the definitions they came from: progress alone changes nothing JEI shows.
        String signature = new TreeSet<>(ClientQuests.all().keySet()) + "@" + System.identityHashCode(QuestRegistries.QUESTS.clientStore().all());
        if (signature.equals(shownFor)) {
            return;
        }
        List<QuestRecipe> next = QuestRecipe.forKnownQuests();
        IRecipeManager recipes = jei.getRecipeManager();
        if (!shown.isEmpty()) {
            recipes.hideRecipes(TYPE, shown);
        }
        if (!next.isEmpty()) {
            recipes.addRecipes(TYPE, next);
        }
        shown = next;
        shownFor = signature;
    }
}
