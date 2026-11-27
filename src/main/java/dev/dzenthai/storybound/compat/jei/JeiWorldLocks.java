package dev.dzenthai.storybound.compat.jei;

import dev.dzenthai.storybound.client.world.ClientWorldRules;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Hides and restores locked items in JEI dynamically as the player's story progression changes.
 * Only ever loaded when JEI is installed; runs on the client main thread.
 */
public final class JeiWorldLocks {
    private static @Nullable IJeiRuntime runtime;
    private static Set<Item> hidden = Set.of();

    private JeiWorldLocks() {}

    static void available(IJeiRuntime jei) {
        runtime = jei;
        hidden = Set.of();
        refresh();
    }

    static void unavailable() {
        runtime = null;
        hidden = Set.of();
    }

    public static void refresh() {
        IJeiRuntime jei = runtime;
        if (jei == null) {
            return;
        }
        Set<Item> desired = ClientWorldRules.hiddenInJei();
        if (desired.equals(hidden)) {
            return;
        }
        IIngredientManager manager = jei.getIngredientManager();
        List<ItemStack> toShow = new ArrayList<>();
        for (Item item : hidden) {
            if (!desired.contains(item)) {
                toShow.add(new ItemStack(item));
            }
        }
        List<ItemStack> toHide = new ArrayList<>();
        for (Item item : desired) {
            if (!hidden.contains(item)) {
                toHide.add(new ItemStack(item));
            }
        }
        if (!toShow.isEmpty()) {
            manager.addIngredientsAtRuntime(VanillaTypes.ITEM_STACK, toShow);
        }
        if (!toHide.isEmpty()) {
            manager.removeIngredientsAtRuntime(VanillaTypes.ITEM_STACK, toHide);
        }
        hidden = new LinkedHashSet<>(desired);
    }
}

