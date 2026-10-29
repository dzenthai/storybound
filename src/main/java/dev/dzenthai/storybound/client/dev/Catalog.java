package dev.dzenthai.storybound.client.dev;

import dev.dzenthai.storybound.client.sound.StorySoundFiles;
import dev.dzenthai.storybound.client.sound.StorySounds;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * The searchable picker of structures, biomes, points of interest, mobs, items (the held one first) and of lists the
 * caller makes (logic types, story nodes): popular tags first, then every registry entry. What is typed can also be
 * used as an id as it is ("+ As id"), for modded or unusual ids.
 */
final class Catalog {
    static final int WIDTH = 320;
    static final int HEIGHT = 224;
    private static final int ROW_H = 19;

    /** What is picked; {@code LIST} is a list given by the caller (types, story nodes, dialogue nodes). */
    enum Kind { STRUCTURE, BIOME, POI, MOB, ITEM, SOUND, LIST }

    /** A file of the sounds folder that is not a sound, shown with why: picking it says how to fix it. */
    static final String PROBLEM = "@problem:";
    /**
     * A row that is not picked: a section's heading ({@code @section:}), or a muted note under it ({@code @note:}), as
     * the sound list's "The story's sounds" and "Minecraft's and mods' sounds" (October 30, 2026).
     */
    static final String SECTION = "@section:";
    static final String NOTE = "@note:";

    private static boolean pickable(Entry entry) {
        return !entry.id().startsWith(SECTION) && !entry.id().startsWith(NOTE);
    }

    /** @param held the stack in the player's hand, components and all (the first entry of the item catalog) */
    record Entry(String id, String label, ItemStack icon, boolean held) {
        Entry(String id, String label, ItemStack icon) {
            this(id, label, icon, false);
        }
    }

    @FunctionalInterface
    interface Callback {
        void picked(Kind kind, Entry entry);
    }

    private boolean open;
    private Component title = Component.empty();
    private Kind kind = Kind.STRUCTURE;
    private String filter = "";
    private int scroll;
    private List<Entry> entries = List.of();
    private @Nullable Callback callback;

    boolean isOpen() {
        return open;
    }

    void open(Component title, Kind kind, Callback callback) {
        show(title, kind, callback);
        this.entries = entries(kind, Minecraft.getInstance().level);
    }

    /** A list made by the caller: logic types, story nodes, a dialogue's nodes. */
    void openList(Component title, List<Entry> list, Callback callback) {
        show(title, Kind.LIST, callback);
        this.entries = List.copyOf(list);
    }

    private void show(Component title, Kind kind, Callback callback) {
        this.open = true;
        this.title = title;
        this.filter = "";
        this.callback = callback;
        this.kind = kind;
        this.scroll = 0;
    }

    void close() {
        open = false;
    }

    private int left(DevModeScreen screen) {
        return (screen.width - WIDTH) / 2;
    }

    private int top(DevModeScreen screen) {
        return (screen.height - HEIGHT) / 2;
    }

    private int listTop(DevModeScreen screen) {
        return top(screen) + 44;
    }

    private int rows() {
        return (HEIGHT - 44 - 8) / ROW_H;
    }

    void addWidgets(DevModeScreen screen) {
        int left = left(screen);
        int top = top(screen);
        screen.add(Button.builder(Component.literal("✕"), b -> {
            close();
            screen.rebuild();
        }).bounds(left + WIDTH - 22, top + 4, 18, 16).build());
        if (kind == Kind.SOUND) {
            soundButtons(screen, left, top);
        }

        int searchY = top + 24;
        EditBox search = new EditBox(screen.font(), left + 8, searchY, WIDTH - 84, 16, DevUi.text("catalog.search"));
        search.setMaxLength(128);
        search.setHint(DevUi.text("catalog.search_hint"));
        search.setValue(filter);
        search.setResponder(value -> {
            filter = value;
            scroll = 0;
        });
        screen.add(search);
        screen.focus(search);

        screen.add(Button.builder(DevUi.text("catalog.custom"), b -> {
            String raw = filter.trim();
            if (!raw.isEmpty()) {
                // Registry ids default to minecraft:, as in commands; a caller's list takes what was typed.
                String id = kind == Kind.LIST || raw.contains(":") || raw.startsWith("#") ? raw : "minecraft:" + raw;
                pick(screen, new Entry(id, id, new ItemStack(Items.NAME_TAG)));
            }
        }).bounds(left + WIDTH - 72, searchY - 1, 64, 18).build());
    }

    /**
     * The sound list's own buttons, in its title bar (October 30, 2026; before, two rows of the list, and reloading
     * closed the window): reloading the sounds keeps the window open and lists them again once loaded; the folder
     * opens in the system's file manager.
     */
    private void soundButtons(DevModeScreen screen, int left, int top) {
        int folderW = 64;
        int reloadW = 76;
        screen.add(Button.builder(Component.literal("📂 ").append(DevUi.text("sound.folder")), b -> {
            java.nio.file.Path folder = StorySounds.folder();
            try {
                java.nio.file.Files.createDirectories(folder);
                net.minecraft.Util.getPlatform().openPath(folder);
            } catch (java.io.IOException e) {
                screen.status(Component.literal(e.getMessage()), DevUi.BAD);
            }
        }).tooltip(Tooltip.create(DevUi.text("sound.open_folder"))).bounds(left + WIDTH - 24 - folderW, top + 3, folderW, 16).build());
        screen.add(Button.builder(Component.literal("↻ ").append(DevUi.text("sound.refresh")), b -> {
            screen.status(DevUi.text("sound.reloading"), DevUi.NEUTRAL);
            Minecraft minecraft = Minecraft.getInstance();
            minecraft.reloadResourcePacks().thenRun(() -> minecraft.execute(() -> {
                if (open && kind == Kind.SOUND) {
                    entries = entries(kind, minecraft.level);
                    scroll = 0;
                }
                screen.status(DevUi.text("sound.reloaded", StorySounds.last().sounds().size()), DevUi.OK);
                screen.rebuild();
            }));
        }).tooltip(Tooltip.create(DevUi.text("sound.reload"))).bounds(left + WIDTH - 26 - folderW - reloadW, top + 3, reloadW, 16).build());
    }

    private void pick(DevModeScreen screen, Entry entry) {
        Callback picked = callback;
        close();
        if (picked != null) {
            picked.picked(kind, entry);
        }
        screen.rebuild();
    }

    /** What the search matches; a section's heading stays over what it found there, and goes with nothing found. */
    private List<Entry> filtered() {
        String query = filter.trim().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) {
            return entries;
        }
        List<Entry> out = new ArrayList<>();
        Entry heading = null;
        for (Entry entry : entries) {
            if (entry.id().startsWith(SECTION)) {
                heading = entry;
            } else if (pickable(entry) && (entry.label().toLowerCase(Locale.ROOT).contains(query) || entry.id().toLowerCase(Locale.ROOT).contains(query))) {
                if (heading != null) {
                    out.add(heading);
                    heading = null;
                }
                out.add(entry);
            }
        }
        return out;
    }

    /** The window under the widgets; the screen draws the widgets on top. */
    void render(DevModeScreen screen, GuiGraphics graphics, int mouseX, int mouseY) {
        Font font = screen.font();
        int left = left(screen);
        int top = top(screen);
        graphics.fill(0, 0, screen.width, screen.height, 0xAA0A0C10);
        graphics.fill(left - 1, top - 1, left + WIDTH + 1, top + HEIGHT + 1, DevUi.ACCENT);
        graphics.fill(left, top, left + WIDTH, top + HEIGHT, 0xFF181C24);
        graphics.fill(left, top, left + WIDTH, top + 20, 0xFF222834);
        graphics.drawString(font, title, left + 8, top + 6, DevUi.ACCENT, false);

        int listTop = listTop(screen);
        List<Entry> shown = filtered();
        scroll = Mth.clamp(scroll, 0, Math.max(0, shown.size() - rows()));
        graphics.fill(left + 6, listTop - 1, left + WIDTH - 6, top + HEIGHT - 6, 0xFF12151B);
        if (shown.isEmpty()) {
            graphics.drawString(font, DevUi.text("catalog.empty"), left + 14, listTop + 12, DevUi.MUTED, false);
            return;
        }
        for (int i = 0; i < rows() && scroll + i < shown.size(); i++) {
            Entry entry = shown.get(scroll + i);
            int y = listTop + i * ROW_H;
            if (entry.id().startsWith(SECTION)) {
                graphics.drawString(font, entry.label(), left + 12, y + 7, DevUi.ACCENT, false);
                graphics.fill(left + 12, y + ROW_H - 3, left + WIDTH - 12, y + ROW_H - 2, 0xFF37474F);
                continue;
            }
            if (entry.id().startsWith(NOTE)) {
                graphics.drawString(font, font.plainSubstrByWidth(entry.label(), WIDTH - 40), left + 30, y + 5, DevUi.MUTED, false);
                continue;
            }
            boolean hover = DevUi.inside(mouseX, mouseY, left + 8, y, WIDTH - 16, ROW_H);
            if (hover) {
                graphics.fill(left + 7, y, left + WIDTH - 7, y + ROW_H - 1, DevUi.ROW_SELECTED);
            }
            graphics.renderItem(entry.icon(), left + 10, y + 1);
            graphics.drawString(font, font.plainSubstrByWidth(entry.label(), 155), left + 30, y + 5, hover ? DevUi.WARN : DevUi.TEXT, false);
            String id = font.plainSubstrByWidth(entry.id(), 118);
            graphics.drawString(font, id, left + WIDTH - 12 - font.width(id), y + 5, 0xFF78909C, false);
        }
    }

    /** Called after the widgets had their chance; a click outside the window closes it. */
    boolean mouseClicked(DevModeScreen screen, double mouseX, double mouseY) {
        int left = left(screen);
        int top = top(screen);
        if (!DevUi.inside(mouseX, mouseY, left, top, WIDTH, HEIGHT)) {
            close();
            screen.rebuild();
            return true;
        }
        List<Entry> shown = filtered();
        int listTop = listTop(screen);
        for (int i = 0; i < rows() && scroll + i < shown.size(); i++) {
            if (DevUi.inside(mouseX, mouseY, left + 8, listTop + i * ROW_H, WIDTH - 16, ROW_H)) {
                if (pickable(shown.get(scroll + i))) {
                    pick(screen, shown.get(scroll + i));
                }
                return true;
            }
        }
        return true;
    }

    /** ESC closes, Enter picks the first match; other keys go to the search box. */
    boolean keyPressed(DevModeScreen screen, int keyCode) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            close();
            screen.rebuild();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            Entry first = filtered().stream().filter(Catalog::pickable).findFirst().orElse(null);
            if (first != null) {
                pick(screen, first);
                return true;
            }
        }
        return false;
    }

    void scroll(double amount) {
        scroll = Math.max(0, scroll - (int) Math.signum(amount) * 3);
    }

    private static List<Entry> entries(Kind kind, @Nullable ClientLevel level) {
        List<Entry> list = new ArrayList<>();
        switch (kind) {
            case STRUCTURE -> {
                popular(list, "#minecraft:village", "village", Items.BELL);
                popular(list, "#minecraft:mineshaft", "mineshaft", Items.RAIL);
                popular(list, "#minecraft:ruined_portal", "ruined_portal", Items.OBSIDIAN);
                popular(list, "#minecraft:shipwreck", "shipwreck", Items.OAK_BOAT);
                popular(list, "#minecraft:ocean_ruin", "ocean_ruin", Items.PRISMARINE);
                popular(list, "#minecraft:eye_of_ender_located", "stronghold", Items.ENDER_EYE);
                popular(list, "minecraft:pillager_outpost", "pillager_outpost", Items.CROSSBOW);
                popular(list, "minecraft:ancient_city", "ancient_city", Items.SCULK_SHRIEKER);
                popular(list, "minecraft:trial_chambers", "trial_chambers", Items.TRIAL_KEY);
                popular(list, "minecraft:mansion", "mansion", Items.TOTEM_OF_UNDYING);
                popular(list, "minecraft:monument", "monument", Items.SEA_LANTERN);
                popular(list, "minecraft:fortress", "fortress", Items.BLAZE_ROD);
                popular(list, "minecraft:bastion_remnant", "bastion_remnant", Items.GILDED_BLACKSTONE);
                popular(list, "minecraft:end_city", "end_city", Items.PURPUR_BLOCK);
                popular(list, "minecraft:desert_pyramid", "desert_pyramid", Items.CHISELED_SANDSTONE);
                popular(list, "minecraft:jungle_pyramid", "jungle_pyramid", Items.MOSSY_COBBLESTONE);
                popular(list, "minecraft:swamp_hut", "swamp_hut", Items.CAULDRON);
                popular(list, "minecraft:igloo", "igloo", Items.SNOW_BLOCK);
                popular(list, "minecraft:trail_ruins", "trail_ruins", Items.BRUSH);
                if (level != null) {
                    Set<String> listed = new LinkedHashSet<>(list.stream().map(Entry::id).toList());
                    level.registryAccess().registry(Registries.STRUCTURE).ifPresent(registry -> sortedIds(registry.keySet()).forEach(id -> {
                        if (listed.add(id.toString())) {
                            list.add(new Entry(id.toString(), DevUi.humanize(id.getPath()), new ItemStack(Items.FILLED_MAP)));
                        }
                    }));
                }
            }
            case BIOME -> {
                popular(list, "#minecraft:is_forest", "forest", Items.OAK_SAPLING);
                popular(list, "#minecraft:is_taiga", "taiga", Items.SPRUCE_SAPLING);
                popular(list, "#minecraft:is_mountain", "mountain", Items.GOAT_HORN);
                popular(list, "#minecraft:is_jungle", "jungle", Items.JUNGLE_SAPLING);
                popular(list, "#minecraft:is_ocean", "ocean", Items.KELP);
                popular(list, "#minecraft:is_badlands", "badlands", Items.TERRACOTTA);
                popular(list, "#minecraft:is_nether", "nether", Items.NETHERRACK);
                if (level != null) {
                    level.registryAccess().registry(Registries.BIOME).ifPresent(registry -> sortedIds(registry.keySet()).forEach(id -> {
                        String key = "biome." + id.getNamespace() + "." + id.getPath();
                        String label = Language.getInstance().has(key) ? Component.translatable(key).getString() : DevUi.humanize(id.getPath());
                        list.add(new Entry(id.toString(), label, new ItemStack(Items.GRASS_BLOCK)));
                    }));
                }
            }
            case POI -> sortedIds(BuiltInRegistries.POINT_OF_INTEREST_TYPE.keySet()).forEach(id -> {
                // Vanilla points of interest are named with the block that makes them; others by their id.
                String key = "storybound.dev.catalog.poi." + id.getPath();
                String label = id.getNamespace().equals("minecraft") && Language.getInstance().has(key)
                        ? Component.translatable(key).getString() : DevUi.humanize(id.getPath());
                list.add(new Entry(id.toString(), label, new ItemStack(Items.COMPASS)));
            });
            case MOB -> {
                popular(list, "#minecraft:raiders", "raiders", Items.CROSSBOW);
                popular(list, "#minecraft:skeletons", "skeletons", Items.BONE);
                popular(list, "#minecraft:zombies", "zombies", Items.ROTTEN_FLESH);
                popular(list, "#minecraft:illager", "illagers", Items.EMERALD);
                BuiltInRegistries.ENTITY_TYPE.entrySet().stream()
                        .filter(entry -> entry.getValue().getCategory() != MobCategory.MISC || isNpc(entry.getValue()))
                        .sorted(Comparator.comparing(entry -> entry.getKey().location().toString()))
                        .forEach(entry -> list.add(new Entry(entry.getKey().location().toString(),
                                entry.getValue().getDescription().getString(), new ItemStack(Items.SPAWNER))));
            }
            case ITEM -> {
                Player player = Minecraft.getInstance().player;
                ItemStack held = player != null ? player.getMainHandItem() : ItemStack.EMPTY;
                if (!held.isEmpty()) {
                    list.add(new Entry(BuiltInRegistries.ITEM.getKey(held.getItem()).toString(),
                            "✋ " + DevUi.str("catalog.held", held.getHoverName().getString(), held.getCount()), held.copy(), true));
                }
                BuiltInRegistries.ITEM.entrySet().stream()
                        .filter(entry -> entry.getValue() != Items.AIR)
                        .sorted(Comparator.comparing(entry -> entry.getKey().location().toString()))
                        .forEach(entry -> list.add(new Entry(entry.getKey().location().toString(),
                                entry.getValue().getDescription().getString(), new ItemStack(entry.getValue()))));
            }
            case SOUND -> {
                // The story's own sounds first (config/storybound/sounds), then every sound the game knows.
                // The files that did not make it, and why, so a file dropped in is never just missing.
                for (StorySoundFiles.Problem problem : StorySounds.last().problems()) {
                    if (!problem.kind().equals("stereo")) {
                        list.add(new Entry(PROBLEM + problem.kind(), "⚠ " + problem.file() + " — " + DevUi.str("sound.short." + problem.kind()),
                                new ItemStack(Items.BARRIER)));
                    }
                }
                Set<String> listed = new LinkedHashSet<>();
                list.add(new Entry(SECTION + "story", DevUi.str("sound.section.story"), ItemStack.EMPTY));
                for (StorySoundFiles.Sound sound : StorySounds.last().sounds()) {
                    if (listed.add(sound.id())) {
                        list.add(new Entry(sound.id(), sound.name(), new ItemStack(Items.NOTE_BLOCK)));
                    }
                }
                if (listed.isEmpty()) {
                    list.add(new Entry(NOTE + "story", DevUi.str("sound.section.story_empty"), ItemStack.EMPTY));
                }
                list.add(new Entry(SECTION + "game", DevUi.str("sound.section.game"), ItemStack.EMPTY));
                sortedIds(new java.util.HashSet<>(Minecraft.getInstance().getSoundManager().getAvailableSounds())).forEach(id -> {
                    if (listed.add(id.toString())) {
                        list.add(new Entry(id.toString(), id.getPath(), new ItemStack(Items.JUKEBOX)));
                    }
                });
            }
            case LIST -> {}
        }
        return list;
    }

    /** Villagers, traders and golems are MISC, but stories talk to them. */
    private static boolean isNpc(EntityType<?> type) {
        return type == EntityType.VILLAGER || type == EntityType.WANDERING_TRADER || type == EntityType.IRON_GOLEM || type == EntityType.SNOW_GOLEM;
    }

    private static void popular(List<Entry> list, String id, String name, Item icon) {
        list.add(new Entry(id, DevUi.str("catalog.popular." + name), new ItemStack(icon)));
    }

    private static List<ResourceLocation> sortedIds(Set<ResourceLocation> ids) {
        return ids.stream().sorted(Comparator.comparing(ResourceLocation::toString)).toList();
    }
}
