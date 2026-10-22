package dev.dzenthai.storybound.client.dev;

import com.google.gson.JsonObject;
import dev.dzenthai.storybound.core.story.StoryLinks;
import dev.dzenthai.storybound.core.story.StoryLinks.NodeKey;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * "Inside the quest": what starts it on the left, then a column per stage — the stage with its log line and
 * objectives on top, below it what the stage touches ({@link QuestOutline}) — and what follows the quest on the right.
 * For reading; a click goes where the thing is edited: a dialogue opens its graph, a quest its own inside, anything
 * else is shown selected on the "All links" canvas.
 */
final class QuestView extends GraphView {
    private static final int COLUMN = StoryView.NODE_W + 44;
    private static final int GAP = 8;
    private static final int LINE = 10;

    /** A card on the canvas: a node, with how it relates. */
    private record Card(NodeKey key, String relation, int x, int y) {}

    /** A stage's box: its place and height. */
    private record StageBox(QuestOutline.Stage stage, int x, int y, int height) {}

    private @Nullable ResourceLocation id;
    private QuestOutline.@Nullable Outline outline;
    private final List<Card> cards = new ArrayList<>();
    private final List<StageBox> stages = new ArrayList<>();
    private int afterX;

    boolean isOpen() {
        return id != null;
    }

    void open(ResourceLocation quest) {
        id = quest;
        panX = 40;
        panY = 30;
        zoom = 1.0f;
        reload();
    }

    void close() {
        id = null;
        outline = null;
        cards.clear();
        stages.clear();
    }

    @Override
    Component backLabel() {
        return DevUi.text("back.story");
    }

    @Override
    void back(DevModeScreen screen) {
        screen.closeQuest();
    }

    @Override
    Component title() {
        return Component.literal("📜 " + (id != null ? id.getPath() : ""));
    }

    @Override
    int untranslated() {
        return 0;
    }

    @Override
    boolean showsIssues() {
        return true;
    }

    @Override
    boolean inspectorVisible(DevModeScreen screen) {
        return false;
    }

    @Override
    void addWidgets(DevModeScreen screen) {}

    /** Reads the quest and everything around it again; a quest deleted meanwhile closes the view. */
    @Override
    void reload() {
        if (id == null) {
            return;
        }
        if (ClientDevStore.getJson(StoryLinks.QUEST, id) == null) {
            close();
            return;
        }
        NodeKey quest = new NodeKey(StoryLinks.QUEST, id);
        outline = QuestOutline.of(quest, ClientDevStore::byType, ClientDevStore.collectEdges());
        layout();
    }

    /** Columns: the starts (and what asks for the quest while it runs), a column per stage, what follows. */
    private void layout() {
        cards.clear();
        stages.clear();
        QuestOutline.Outline shown = outline;
        if (shown == null) {
            return;
        }
        int y = 16;
        for (NodeKey start : shown.starts()) {
            cards.add(new Card(start, "start", 0, y));
            y += StoryView.NODE_H + GAP;
        }
        if (!shown.during().isEmpty()) {
            y += 16;
            for (QuestOutline.Attached item : shown.during()) {
                cards.add(new Card(item.key(), "during", 0, y));
                y += StoryView.NODE_H + GAP;
            }
        }
        int x = COLUMN;
        for (QuestOutline.Stage stage : shown.stages()) {
            int height = 28 + Math.max(1, stage.objectives().size()) * LINE;
            stages.add(new StageBox(stage, x, 16, height));
            int below = 16 + height + 24;
            for (QuestOutline.Attached item : stage.attached()) {
                cards.add(new Card(item.key(), item.relation(), x, below));
                below += StoryView.NODE_H + GAP;
            }
            x += COLUMN;
        }
        afterX = x;
        y = 16;
        for (QuestOutline.Attached item : shown.after()) {
            cards.add(new Card(item.key(), item.relation(), afterX, y));
            y += StoryView.NODE_H + GAP;
        }
    }

    @Override
    void saveEdits(DevModeScreen screen) {}

    @Override
    void render(DevModeScreen screen, GuiGraphics graphics, int mouseX, int mouseY) {
        Font font = screen.font();
        beginCanvas(screen, graphics, 0xFF181C22);
        double worldX = worldX(mouseX);
        double worldY = worldY(mouseY);
        graphics.drawString(font, DevUi.text("quest.starts"), 0, 2, DevUi.LABEL, false);
        graphics.drawString(font, DevUi.text("quest.after"), afterX, 2, DevUi.LABEL, false);
        // Starts lead into the first stage, each stage into its next, the last one into what follows.
        StageBox first = stages.isEmpty() ? null : stages.getFirst();
        int index = 0;
        for (Card card : cards) {
            if (card.relation().equals("start") && first != null) {
                DevUi.edge(graphics, font, card.x() + StoryView.NODE_W, card.y() + StoryView.NODE_H / 2, first.x(), first.y() + 10, DevUi.LINK, "", index++);
            }
        }
        for (StageBox box : stages) {
            String next = box.stage().next();
            StageBox target = stages.stream().filter(other -> other.stage().id().equals(next)).findFirst().orElse(null);
            if (target != null) {
                DevUi.edge(graphics, font, box.x() + StoryView.NODE_W, box.y() + 10, target.x(), target.y() + 10, DevUi.NEUTRAL, "", index++);
            } else if (next.equals("@complete")) {
                DevUi.edge(graphics, font, box.x() + StoryView.NODE_W, box.y() + 10, afterX, 22, DevUi.OK, "✔", index++);
            } else if (next.equals("@fail")) {
                graphics.drawString(font, "✘", box.x() + StoryView.NODE_W + 4, box.y() + 6, DevUi.BAD, false);
            }
            renderStage(graphics, font, box);
        }
        for (Card card : cards) {
            // What a stage touches hangs under it on a line.
            stages.stream().filter(box -> box.x() == card.x()).findFirst().ifPresent(box -> {
                DevUi.vertical(graphics, box.x() - 6, box.y() + box.height(), card.y() + StoryView.NODE_H / 2, DevUi.CARD_EDGE);
                DevUi.horizontal(graphics, box.x() - 6, card.x(), card.y() + StoryView.NODE_H / 2, DevUi.CARD_EDGE);
            });
            JsonObject json = ClientDevStore.parseObject(ClientDevStore.getJson(card.key().type(), card.key().id()));
            boolean hovered = DevUi.inside(worldX, worldY, card.x(), card.y(), StoryView.NODE_W, StoryView.NODE_H);
            boolean missing = ClientDevStore.getJson(card.key().type(), card.key().id()) == null;
            DevUi.card(graphics, font, card.x(), card.y(), StoryView.NODE_W, StoryView.NODE_H, missing ? DevUi.BAD : hovered ? DevUi.NEUTRAL : DevUi.CARD_EDGE,
                    false, StoryView.color(card.key().type(), json), StoryView.glyph(card.key().type()) + " " + DevUi.str("quest.relation." + card.relation()),
                    false, StoryView.icon(card.key(), json), StoryView.displayTitle(card.key(), json), card.key().id().toString());
        }
        endCanvas(graphics);
        renderStageList(screen, graphics, mouseX, mouseY);
    }

    /** A stage: its id and log line on top, its objectives under it. */
    private static void renderStage(GuiGraphics graphics, Font font, StageBox box) {
        int x = box.x();
        int y = box.y();
        int w = StoryView.NODE_W;
        graphics.fill(x - 1, y - 1, x + w + 1, y + box.height() + 1, DevUi.ACCENT);
        graphics.fill(x, y, x + w, y + box.height(), DevUi.CARD);
        graphics.fill(x, y, x + w, y + 12, DevUi.ACCENT);
        graphics.drawString(font, font.plainSubstrByWidth("▣ " + box.stage().id(), w - 8), x + 4, y + 2, DevUi.DARK_TEXT, false);
        String log = translated(box.stage().log());
        graphics.drawString(font, font.plainSubstrByWidth(log.isEmpty() ? "—" : log, w - 8), x + 4, y + 15, DevUi.MUTED, false);
        int line = y + 15 + LINE;
        if (box.stage().objectives().isEmpty()) {
            graphics.drawString(font, font.plainSubstrByWidth(DevUi.str("quest.no_objectives"), w - 8), x + 4, line, DevUi.HINT, false);
        }
        for (QuestOutline.Objective objective : box.stage().objectives()) {
            String text = translated(objective.text());
            String label = "• " + (text.isEmpty() ? objective.id() : text);
            graphics.drawString(font, font.plainSubstrByWidth(label, w - 8), x + 4, line, DevUi.TEXT, false);
            line += LINE;
        }
    }

    /** A text key as the active language has it, else the key. */
    private static String translated(String key) {
        return key.isEmpty() ? "" : Language.getInstance().getOrDefault(key);
    }

    private void renderStageList(DevModeScreen screen, GuiGraphics graphics, int mouseX, int mouseY) {
        Font font = screen.font();
        DevUi.sidePanel(graphics, 0, DevUi.LEFT_PANEL_W, screen.height, true);
        graphics.drawString(font, DevUi.text("quest.stages", stages.size()), 8, DevUi.TOP_BAR_H + 8, DevUi.ACCENT, false);
        int y = DevUi.TOP_BAR_H + 24;
        for (StageBox box : stages) {
            if (y > screen.height - 40) {
                break;
            }
            DevUi.listRow(graphics, font, "▣ " + box.stage().id(), y, false, DevUi.inside(mouseX, mouseY, 4, y, DevUi.LEFT_PANEL_W - 8, 18));
            y += 20;
        }
        graphics.drawString(font, font.plainSubstrByWidth(DevUi.str("quest.hint"), DevUi.LEFT_PANEL_W - 12), 6, screen.height - 16, DevUi.HINT, false);
    }

    @Override
    boolean mouseClicked(DevModeScreen screen, double mouseX, double mouseY, int button) {
        if (mouseX < DevUi.LEFT_PANEL_W && mouseY >= DevUi.TOP_BAR_H + 24) {
            int row = ((int) mouseY - (DevUi.TOP_BAR_H + 24)) / 20;
            if (row >= 0 && row < stages.size()) {
                StageBox box = stages.get(row);
                centerOn(screen, box.x() + StoryView.NODE_W / 2.0, box.y() + box.height() / 2.0);
                return true;
            }
            return false;
        }
        if (!onCanvas(screen, mouseX, mouseY)) {
            return false;
        }
        double worldX = worldX(mouseX);
        double worldY = worldY(mouseY);
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            for (Card card : cards) {
                if (DevUi.inside(worldX, worldY, card.x(), card.y(), StoryView.NODE_W, StoryView.NODE_H)) {
                    go(screen, card.key());
                    return true;
                }
            }
        }
        startPan(mouseX, mouseY);
        return true;
    }

    /** Where a node is edited: a dialogue's graph, a quest's own inside, else the node selected among all links. */
    private void go(DevModeScreen screen, NodeKey key) {
        if (ClientDevStore.getJson(key.type(), key.id()) == null) {
            screen.status(DevUi.text("quest.missing", key.id().toString()), DevUi.BAD);
        } else if (key.type().equals(StoryLinks.DIALOGUE)) {
            screen.openDialogue(key.id());
        } else if (key.type().equals(StoryLinks.QUEST)) {
            screen.openQuest(key.id());
        } else {
            screen.closeQuest();
            screen.revealAmongLinks(key);
        }
    }

    @Override
    boolean mouseDragged(DevModeScreen screen, double mouseX, double mouseY) {
        return pan(mouseX, mouseY);
    }

    @Override
    boolean mouseReleased(DevModeScreen screen, double mouseX, double mouseY) {
        stopPan();
        return false;
    }

    @Override
    boolean keyPressed(DevModeScreen screen, int keyCode) {
        return false;
    }

    @Override
    void scrollInspector(double amount) {}
}
