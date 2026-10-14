package dev.dzenthai.storybound.client.menu;

import dev.dzenthai.storybound.Storybound;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * The tab registry of the Adventure Menu. Storybound registers its own tabs during client setup;
 * other mods may add theirs the same way (on the main thread, e.g. in {@code FMLClientSetupEvent#enqueueWork}).
 */
public final class AdventureMenu {
    public static final ResourceLocation QUESTS = Storybound.id("quests");
    public static final ResourceLocation JOURNAL = Storybound.id("journal");

    private static final Map<ResourceLocation, MenuTab> TABS = new LinkedHashMap<>();
    private static @Nullable ResourceLocation lastTab;

    private AdventureMenu() {}

    public static void registerTab(MenuTab tab) {
        if (TABS.putIfAbsent(tab.id(), tab) != null) {
            throw new IllegalArgumentException("Duplicate Adventure Menu tab " + tab.id());
        }
    }

    /** Available tabs in display order. */
    public static List<MenuTab> availableTabs() {
        List<MenuTab> tabs = new ArrayList<>();
        for (MenuTab tab : TABS.values()) {
            if (tab.available().getAsBoolean()) {
                tabs.add(tab);
            }
        }
        tabs.sort(Comparator.comparingInt(MenuTab::order));
        return tabs;
    }

    /** Opens the menu on the given tab, or on the last one used when null. */
    public static void open(@Nullable ResourceLocation tab) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.setScreen(new AdventureMenuScreen(tab != null ? tab : lastTab));
        }
    }

    static void remember(ResourceLocation tab) {
        lastTab = tab;
    }

    /** Called on logout. */
    public static void reset() {
        lastTab = null;
    }
}
