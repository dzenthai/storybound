package dev.dzenthai.storybound.client.dev;

import com.google.gson.JsonObject;
import dev.dzenthai.storybound.client.dev.DevModeScreen.MenuItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * The story editor's chapters, as FTB Quests shows them (November 16, 2026; the same day, a panel of three sections,
 * Chapters, Search and Problems, which the author found heavy): a narrow strip of icons on the left, the chapter's
 * item (its {@code icon}) or its first letter, ★ for the whole story, ◌ for the nodes in no chapter, + for a new one.
 * Hovering it slides the names out over the canvas; moving away folds them back. Search and the problems are buttons
 * of the top bar ({@link StoryFinder}).
 */
final class StoryPanel {
    /** The strip's width: where the map's canvas begins. */
    static final int STRIP_W = 24;
    /** The strip with the names slid out. */
    private static final int EXPANDED_W = 170;
    private static final int SLOT = 21;
    private static final int ROW_STEP = 23;
    private static final int TOP = DevUi.TOP_BAR_H + 4;

    /** A chapter to open (the whole story, a chapter, the nodes in none), or "+ Chapter" ({@code chapter} null). */
    private record ChapterRow(@Nullable ResourceLocation chapter, String name, String sign, ItemStack icon, int count) {}

    private final StoryView view;
    private int scroll;
    /** The names are slid out: the mouse came onto the strip and has not left the slid-out panel since. */
    private boolean expanded;

    StoryPanel(StoryView view) {
        this.view = view;
    }

    private List<ChapterRow> rows() {
        List<ChapterRow> list = new ArrayList<>();
        list.add(new ChapterRow(Chapters.quests(), DevUi.str("chapter.all_story"), "★", ItemStack.EMPTY, view.spineCount()));
        for (Map.Entry<ResourceLocation, JsonObject> entry : Chapters.sorted()) {
            String name = ClientDevStore.readStoryText(entry.getValue(), "title", entry.getKey().getPath());
            list.add(new ChapterRow(entry.getKey(), name, name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(java.util.Locale.ROOT),
                    icon(entry.getValue()), Chapters.positions(entry.getValue()).size()));
        }
        int unsorted = view.unsortedCount();
        if (unsorted > 0) {
            list.add(new ChapterRow(StoryView.UNSORTED, DevUi.str("chapter.unsorted"), "◌", ItemStack.EMPTY, unsorted));
        }
        list.add(new ChapterRow(null, DevUi.str("chapter.new"), "+", ItemStack.EMPTY, -1));
        return list;
    }

    /** The chapter's item, when it names one that exists. */
    private static ItemStack icon(JsonObject chapter) {
        if (chapter.get("icon") instanceof com.google.gson.JsonPrimitive primitive) {
            ResourceLocation id = ResourceLocation.tryParse(primitive.getAsString());
            if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
                return new ItemStack(BuiltInRegistries.ITEM.get(id));
            }
        }
        return ItemStack.EMPTY;
    }

    private int visibleRows(DevModeScreen screen) {
        return Math.max(1, (screen.height - TOP - 4) / ROW_STEP);
    }

    /** The strip, or the panel slid out, holds the point. */
    boolean holds(DevModeScreen screen, double mouseX, double mouseY) {
        int width = expanded ? EXPANDED_W : STRIP_W;
        return mouseX >= 0 && mouseX < width && mouseY >= DevUi.TOP_BAR_H && mouseY < screen.height;
    }

    /** Slides the names out while the mouse is on the strip or the slid-out panel; never while something is dragged. */
    void hover(DevModeScreen screen, double mouseX, double mouseY, boolean busy) {
        if (busy || screen.modalOpen()) {
            expanded = false;
        } else if (mouseX >= 0 && mouseX < STRIP_W && mouseY >= DevUi.TOP_BAR_H) {
            expanded = true;
        } else if (expanded && !holds(screen, mouseX, mouseY)) {
            expanded = false;
        }
    }

    void render(DevModeScreen screen, GuiGraphics graphics, int mouseX, int mouseY) {
        Font font = screen.font();
        List<ChapterRow> list = rows();
        int visible = visibleRows(screen);
        scroll = Mth.clamp(scroll, 0, Math.max(0, list.size() - visible));
        int width = expanded ? EXPANDED_W : STRIP_W;
        graphics.pose().pushPose();
        if (expanded) {
            // Over the canvas and its cards.
            graphics.pose().translate(0, 0, 200);
        }
        DevUi.sidePanel(graphics, 0, width, screen.height, true);
        for (int i = scroll; i < Math.min(list.size(), scroll + visible); i++) {
            ChapterRow row = list.get(i);
            int y = TOP + (i - scroll) * ROW_STEP;
            boolean open = row.chapter() != null && view.isOpenChapter(row.chapter());
            boolean hover = DevUi.inside(mouseX, mouseY, 0, y - 1, width, ROW_STEP);
            if (expanded && (open || hover)) {
                graphics.fill(1, y - 1, width - 2, y + SLOT + 1, open ? DevUi.ROW_SELECTED : DevUi.ROW_HOVER);
            }
            // One pixel on each side within the strip (its edge is its last column).
            int slotX = 1;
            graphics.fill(slotX, y, slotX + SLOT, y + SLOT, open ? DevUi.ROW_SELECTED : DevUi.CARD);
            int edge = open ? DevUi.ACCENT : hover ? DevUi.NEUTRAL : DevUi.CARD_EDGE;
            DevUi.frame(graphics, slotX, y, SLOT, SLOT, edge);
            if (!row.icon().isEmpty()) {
                graphics.renderItem(row.icon(), slotX + 2, y + 2);
            } else {
                int color = row.chapter() == null ? DevUi.LINK : open ? DevUi.ACCENT : hover ? DevUi.TEXT : DevUi.LABEL;
                String icon = DevIcons.split(row.sign()).icon();
                if (icon != null) {
                    DevIcons.draw(graphics, icon, slotX + (SLOT - DevIcons.SIZE) / 2, y + (SLOT - DevIcons.SIZE) / 2, color);
                } else {
                    graphics.drawString(font, row.sign(), slotX + (SLOT - font.width(row.sign())) / 2 + 1, y + 7, color, false);
                }
            }
            if (expanded) {
                String count = row.count() >= 0 ? String.valueOf(row.count()) : "";
                int countW = font.width(count);
                int nameW = width - SLOT - 14 - countW - 6;
                graphics.drawString(font, font.plainSubstrByWidth(row.name(), nameW), SLOT + 8, y + 7,
                        row.chapter() == null ? DevUi.LINK : open ? DevUi.WARN : hover ? DevUi.TEXT : DevUi.LABEL, false);
                graphics.drawString(font, count, width - 8 - countW, y + 7, DevUi.MUTED, false);
            }
        }
        graphics.pose().popPose();
    }

    boolean mouseClicked(DevModeScreen screen, double mouseX, double mouseY, int button) {
        List<ChapterRow> list = rows();
        int index = scroll + (int) Math.floor((mouseY - TOP + 1) / ROW_STEP);
        if (mouseY < TOP - 1 || index < 0 || index >= list.size()) {
            return true;
        }
        ChapterRow row = list.get(index);
        if (row.chapter() == null) {
            view.createChapter(screen);
            screen.rebuild();
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && !Chapters.hidden(row.chapter()) && !StoryView.UNSORTED.equals(row.chapter())) {
            ResourceLocation id = row.chapter();
            screen.openMenu((int) mouseX, (int) mouseY, List.of(
                    MenuItem.of(Component.literal("✏ ").append(DevUi.text("chapter.rename")), () -> view.renameChapter(screen, id)),
                    MenuItem.danger(Component.literal("🗑 ").append(DevUi.text("chapter.delete", row.name())), () -> view.deleteChapter(screen, id))));
            return true;
        }
        view.showChapter(screen, row.chapter());
        return true;
    }

    /** The wheel over the strip scrolls a long list of chapters. */
    boolean scroll(DevModeScreen screen, double mouseX, double mouseY, double amount) {
        if (!holds(screen, mouseX, mouseY)) {
            return false;
        }
        scroll = Math.max(0, scroll - (int) Math.signum(amount));
        return true;
    }
}
