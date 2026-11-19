package dev.dzenthai.storybound.client.dev;

import com.google.gson.JsonObject;
import dev.dzenthai.storybound.core.story.StoryLinks;
import dev.dzenthai.storybound.core.story.StoryLinks.NodeKey;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * "Inside the quest": what starts it on the left, then a column per stage — the stage with its log line and
 * objectives on top, below it what the stage touches ({@link QuestOutline}) — and what follows the quest on the right.
 * For reading; a click goes where the thing is edited: a dialogue opens its graph, a quest its own inside, anything
 * else is shown selected on the "All links" canvas.
 */
final class QuestView extends GraphView {
    /** A stage's width, and a column's: the stage and the gap to the next (November 18, 2026: before, a card's width). */
    private static final int STAGE_W = 6 * DevUi.GRID;
    private static final int COLUMN = STAGE_W + 2 * DevUi.GRID;
    /** From one node to the next under a stage: its box, its title, a little air. */
    private static final int NODE_STEP = StoryView.NODE_H + 16;
    private static final int LINE = 10;
    /** How wide a node's title is at most here. */
    private static final int TITLE_W = STAGE_W - 8;

    /**
     * A node on the canvas, with how it relates: its box's top left (the shape in its middle, as on the story canvas).
     */
    private record Card(NodeKey key, String relation, int x, int y, StoryView.Look look, ItemStack item, String title, boolean missing) {
        /**
         * A node and how it relates, read once when laid out (November 19, 2026: before, its definition was parsed
         * several times a frame, for its shape, its hit test, its links and its title).
         */
        static Card of(NodeKey key, String relation, int x, int y) {
            String raw = ClientDevStore.getJson(key.type(), key.id());
            JsonObject json = ClientDevStore.parseObject(raw);
            String title = raw == null ? key.id().getPath() : StoryView.displayTitle(key, json);
            return new Card(key, relation, x, y, StoryView.look(key.type(), json), StoryView.icon(key, json), title, raw == null);
        }

        int cx() {
            return x + StoryView.NODE_W / 2;
        }

        int cy() {
            return y + StoryView.NODE_H / 2;
        }
    }

    /** A stage's box: its place and height, and its lines (the log line and the objectives, wrapped to its width). */
    private record StageBox(QuestOutline.Stage stage, int x, int y, int height, List<Line> lines) {}

    /** A line of a stage's box, in its colour. */
    private record Line(FormattedCharSequence text, int color) {}

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
        setZoom(1.0f);
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
        // A node sits in the middle of its column.
        int inColumn = (STAGE_W - StoryView.NODE_W) / 2;
        int y = 16;
        for (NodeKey start : shown.starts()) {
            cards.add(Card.of(start, "start", inColumn, y));
            y += NODE_STEP;
        }
        if (!shown.during().isEmpty()) {
            y += 16;
            for (QuestOutline.Attached item : shown.during()) {
                cards.add(Card.of(item.key(), "during", inColumn, y));
                y += NODE_STEP;
            }
        }
        int x = COLUMN;
        for (QuestOutline.Stage stage : shown.stages()) {
            List<Line> lines = stageLines(stage);
            int height = 18 + lines.size() * LINE;
            stages.add(new StageBox(stage, x, 16, height, lines));
            int below = 16 + height + 20;
            for (QuestOutline.Attached item : stage.attached()) {
                cards.add(Card.of(item.key(), item.relation(), x + inColumn, below));
                below += NODE_STEP;
            }
            x += COLUMN;
        }
        afterX = x;
        y = 16;
        for (QuestOutline.Attached item : shown.after()) {
            cards.add(Card.of(item.key(), item.relation(), afterX + inColumn, y));
            y += NODE_STEP;
        }
    }

    @Override
    void saveEdits(DevModeScreen screen) {}

    private static boolean holds(Card card, double worldX, double worldY) {
        StoryView.Look look = card.look();
        return NodeShapes.contains(look.shape(), look.radius(), worldX - card.cx() - 0.5, worldY - card.cy() - 0.5);
    }

    /** The point {@code gap} pixels out of a node's edge towards {@code x, y}. */
    private static double[] towards(Card card, double x, double y, double gap) {
        double dx = x - card.cx() - 0.5;
        double dy = y - card.cy() - 0.5;
        double length = Math.max(1e-6, Math.hypot(dx, dy));
        double reach = card.look().radius() + gap;
        return new double[]{card.cx() + 0.5 + dx / length * reach, card.cy() + 0.5 + dy / length * reach};
    }

    @Override
    void render(DevModeScreen screen, GuiGraphics graphics, int mouseX, int mouseY) {
        Font font = screen.font();
        beginCanvas(screen, graphics, DevUi.GRID_LINE);
        double worldX = worldX(mouseX);
        double worldY = worldY(mouseY);
        boolean idle = onCanvas(screen, mouseX, mouseY) && !screen.modalOpen();
        Card hovered = null;
        for (Card card : cards) {
            if (idle && holds(card, worldX, worldY)) {
                hovered = card;
            }
        }
        StoryView.backedText(graphics, font, DevUi.str("quest.starts"), STAGE_W / 2, 2, DevUi.LABEL);
        StoryView.backedText(graphics, font, DevUi.str("quest.after"), afterX + STAGE_W / 2, 2, DevUi.LABEL);
        // An empty column says what could be there, so it does not look like something failed to show.
        boolean anyStart = cards.stream().anyMatch(card -> card.x() < COLUMN);
        boolean anyAfter = cards.stream().anyMatch(card -> card.x() >= afterX);
        if (!anyStart) {
            hint(graphics, font, "quest.starts_none", 0);
        }
        if (!anyAfter) {
            hint(graphics, font, "quest.after_none", afterX);
        }
        int quiet = 0xCCB08031;
        // Starts lead into the first stage, each stage into its next, the last one into what follows.
        StageBox first = stages.isEmpty() ? null : stages.getFirst();
        for (Card card : cards) {
            if (card.relation().equals("start") && first != null) {
                double[] a = towards(card, first.x(), first.y() + 10, 4);
                NodeShapes.link(graphics, a[0], a[1], first.x() - 3, first.y() + 10.5, quiet);
            }
        }
        for (StageBox box : stages) {
            String next = box.stage().next();
            StageBox target = stages.stream().filter(other -> other.stage().id().equals(next)).findFirst().orElse(null);
            double fromX = box.x() + STAGE_W + 3;
            if (target != null) {
                NodeShapes.link(graphics, fromX, box.y() + 10.5, target.x() - 3, target.y() + 10.5, quiet);
            } else if (next.equals("@complete")) {
                NodeShapes.link(graphics, fromX, box.y() + 10.5, afterX - 3, box.y() + 10.5, 0xCCA5B574);
                StoryView.backedText(graphics, font, "✔ " + DevUi.str("quest.completed"), (int) (fromX + afterX) / 2, box.y() + 16, DevUi.OK);
            } else if (next.equals("@fail")) {
                graphics.drawString(font, "✘ " + DevUi.str("quest.failed"), (int) fromX + 4, box.y() + 6, DevUi.BAD, false);
            }
            // What the stage touches hangs under it, one under the other.
            double lastY = box.y() + box.height() + 3;
            for (Card card : cards) {
                if (card.x() == box.x() + (STAGE_W - StoryView.NODE_W) / 2) {
                    double top = card.cy() + 0.5 - card.look().radius() - 4;
                    NodeShapes.link(graphics, card.cx() + 0.5, lastY, card.cx() + 0.5, top, 0x80775025);
                    lastY = card.cy() + card.look().radius() + 17;
                }
            }
            renderStage(graphics, font, box);
        }
        for (Card card : cards) {
            StoryView.drawNode(graphics, font, card.look(), card.item(), card.cx(), card.cy(), card.title(), TITLE_W,
                    false, false, card == hovered, false, card.missing(), false);
        }
        endCanvas(graphics);
        renderStageList(screen, graphics, mouseX, mouseY);
        if (hovered != null) {
            graphics.renderComponentTooltip(font, tooltip(hovered), mouseX, mouseY);
        }
    }

    /** A quiet line under an empty column's heading, wrapped to the column. */
    private static void hint(GuiGraphics graphics, Font font, String key, int columnX) {
        int y = 20;
        for (FormattedCharSequence line : font.split(DevUi.text(key), STAGE_W - 8)) {
            graphics.drawString(font, line, columnX + (STAGE_W - font.width(line)) / 2, y, DevUi.HINT, false);
            y += LINE;
        }
    }

    /** A hovered node: how it relates to the quest, its title, its id. */
    private static List<Component> tooltip(Card card) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(card.title()).withColor(DevUi.TEXT));
        lines.add(Component.literal(StoryView.glyph(card.key().type()) + " " + DevUi.str("quest.relation." + card.relation())).withColor(DevUi.LABEL));
        lines.add(Component.literal(card.key().id().toString()).withColor(DevUi.HINT));
        if (card.missing()) {
            lines.add(DevUi.text("quest.missing", card.key().id().toString()).copy().withColor(DevUi.ERROR));
        }
        return lines;
    }

    /**
     * A stage's lines, wrapped to its box (November 19, 2026; before, cut at its edge: "No objectives: an action m"):
     * the log line, then each objective, or that there is none.
     */
    private static List<Line> stageLines(QuestOutline.Stage stage) {
        Font font = Minecraft.getInstance().font;
        List<Line> lines = new ArrayList<>();
        String log = translated(stage.log());
        font.split(Component.literal(log.isEmpty() ? "—" : log), STAGE_W - 8).forEach(line -> lines.add(new Line(line, DevUi.MUTED)));
        if (stage.objectives().isEmpty()) {
            font.split(DevUi.text("quest.no_objectives"), STAGE_W - 8).forEach(line -> lines.add(new Line(line, DevUi.HINT)));
        }
        for (QuestOutline.Objective objective : stage.objectives()) {
            String text = translated(objective.text());
            font.split(Component.literal("• " + (text.isEmpty() ? objective.id() : text)), STAGE_W - 8)
                    .forEach(line -> lines.add(new Line(line, DevUi.TEXT)));
        }
        return lines;
    }

    /** A stage: a quiet frame, its id on top, its log line, its objectives under it. */
    private static void renderStage(GuiGraphics graphics, Font font, StageBox box) {
        int x = box.x();
        int y = box.y();
        int w = STAGE_W;
        graphics.fill(x - 1, y - 1, x + w + 1, y + box.height() + 1, StoryView.OCHRE);
        graphics.fill(x, y, x + w, y + box.height(), StoryView.NODE_FILL);
        graphics.drawString(font, font.plainSubstrByWidth("▣ " + box.stage().id(), w - 8), x + 4, y + 3, StoryView.GOLD, false);
        int line = y + 15;
        for (Line text : box.lines()) {
            graphics.drawString(font, text.text(), x + 4, line, text.color(), false);
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
                centerOn(screen, box.x() + STAGE_W / 2.0, box.y() + box.height() / 2.0);
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
                if (holds(card, worldX, worldY)) {
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
