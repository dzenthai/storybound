package dev.dzenthai.storybound.client.menu;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * A tab of the Adventure Menu, registered with {@link AdventureMenu#registerTab}.
 *
 * @param order     tabs are sorted by it; Storybound uses 100 (Quests), 200 (Journal), 300 (Codex), 400 (Bosses)
 * @param available hidden while false, e.g. when the server disabled the module
 * @param unseen    shows the "new" marker on the tab
 * @param view      creates the tab's content; called once per opened menu
 */
public record MenuTab(ResourceLocation id, Component title, int order, BooleanSupplier available, BooleanSupplier unseen,
                      Supplier<MenuView> view) {}
