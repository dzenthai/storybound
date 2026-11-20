package dev.dzenthai.storybound.client.dev;

import dev.dzenthai.storybound.core.state.QuestProgress;
import dev.dzenthai.storybound.core.story.StoryLinks.NodeKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;

/**
 * The story editor's drawing ({@link StoryView}): the widgets, the canvas, its nodes, badges and arrows, the
 * players' progress on it.
 * <p>
 * One layer of the story editor, split by topic, each seeing the ones below it; the rest of the editor uses StoryView:
 * StoryCards &larr; StoryChapters &larr; StoryEditing &larr; StoryDrawing &larr; StoryView.
 */
abstract class StoryDrawing extends StoryEditing {
    /** Off, then each online player, then off again. */
    private void nextProgressPlayer(DevModeScreen screen) {
        List<String> names = new ArrayList<>();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            names.add(minecraft.player.getGameProfile().getName());
        }
        if (minecraft.getConnection() != null) {
            minecraft.getConnection().getOnlinePlayers().stream().map(info -> info.getProfile().getName()).sorted()
                    .filter(name -> !names.contains(name)).forEach(names::add);
        }
        int at = progressOf == null ? -1 : names.indexOf(progressOf);
        progressOf = at + 1 < names.size() ? names.get(at + 1) : null;
        if (progressOf != null) {
            DevRequests.requestProgress(progressOf);
        }
        progressTicks = 0;
        screen.rebuild();
    }

    @Override
    void tick(DevModeScreen screen) {
        if (progressOf != null && tab == Tab.STORY && ++progressTicks % PROGRESS_REFRESH == 0) {
            DevRequests.requestProgress(progressOf);
        }
    }

    /** Whether the progress layer shows a quest the player does not know yet: drawn dim. */
    private boolean unknownToPlayer(Card card) {
        String player = progressOf;
        return player != null && card.key().type().equals(ClientDevStore.TYPE_QUEST) && ClientDevStore.hasProgress(player)
                && ClientDevStore.progress(player, card.key().id()) == null;
    }

    /** A quest's mark on the progress layer, above its shape: its state and stage. */
    private void renderProgress(GuiGraphics graphics, Font font, Card card) {
        String player = progressOf;
        if (player == null || !card.key().type().equals(ClientDevStore.TYPE_QUEST) || !ClientDevStore.hasProgress(player)) {
            return;
        }
        QuestProgress progress = ClientDevStore.progress(player, card.key().id());
        if (progress == null) {
            return;
        }
        String mark = switch (progress.status()) {
            case COMPLETED -> "✔";
            case FAILED -> "✘";
            case ACTIVE -> "▶ " + progress.stage().orElse("");
        };
        int color = switch (progress.status()) {
            case COMPLETED -> DevUi.GOOD;
            case FAILED -> DevUi.ERROR;
            case ACTIVE -> DevUi.ACCENT;
        };
        String shown = font.plainSubstrByWidth(mark, TITLE_W - 6);
        int width = font.width(shown) + 6;
        int x = card.cx() - width / 2;
        int y = card.cy() - card.look().radius() - 13;
        graphics.fill(x, y, x + width, y + 10, color);
        graphics.drawString(font, shown, x + 3, y + 1, DevUi.DARK_TEXT, false);
    }

    @Override
    void addWidgets(DevModeScreen screen) {
        // The search, the progress and the problems are the top bar's (topLeft, topRight). In the middle of the top
        // bar, in words: what the map shows instead, the chapters or every link.
        Component toggle = DevUi.text(tab == Tab.STORY ? "canvas.show_all_links" : "canvas.show_chapters");
        int toggleW = DevButton.oddWidth(screen.font(), toggle) + 8;
        screen.add(Button.builder(toggle, b -> {
                    saveEdits(screen);
                    openTab(screen, tab == Tab.STORY ? Tab.ALL : Tab.STORY);
                }).tooltip(Tooltip.create(DevUi.text(tab == Tab.STORY ? "canvas.all_links.tooltip" : "canvas.back_to_chapters.tooltip")))
                .bounds((screen.width - toggleW) / 2, 3, toggleW, 19).build());
        if (inspectorVisible(screen)) {
            inspector.addWidgets(screen);
        }
    }

    /** After the stories' button: the search, and on the map whose progress it shows. */
    @Override
    List<DevModeScreen.TopButton> topLeft(DevModeScreen screen) {
        List<DevModeScreen.TopButton> buttons = new ArrayList<>();
        buttons.add(new DevModeScreen.TopButton(Component.literal("🔍"), DevUi.text("top.search.tooltip"),
                () -> screen.openFinder(StoryFinder.Mode.SEARCH)));
        if (tab == Tab.STORY) {
            Component who = progressOf == null ? Component.literal("👤") : Component.literal("👤 " + progressOf);
            buttons.add(new DevModeScreen.TopButton(who, DevUi.text("progress.tooltip"), () -> nextProgressPlayer(screen)));
        }
        return buttons;
    }

    /** Between the language and the settings: the story's problems, their count in their colour, or ✔ none. */
    @Override
    List<DevModeScreen.TopButton> topRight(DevModeScreen screen) {
        int problems = ClientDevStore.issues().size();
        boolean errors = ClientDevStore.issues().stream().anyMatch(line -> line.startsWith("[ERROR]"));
        Component label = problems == 0 ? Component.literal("✔") : Component.literal("⚠ " + problems)
                .withColor(errors ? DevUi.ERROR : DevUi.WARN);
        return List.of(new DevModeScreen.TopButton(label, DevUi.text(problems == 0 ? "top.problems_none.tooltip" : "top.problems.tooltip"),
                () -> screen.openFinder(StoryFinder.Mode.PROBLEMS)));
    }

    @Override
    void render(DevModeScreen screen, GuiGraphics graphics, int mouseX, int mouseY) {
        if (tab == Tab.STORY) {
            panel.hover(screen, mouseX, mouseY, dragging != null || linkingFrom != null || boxSelecting);
        }
        renderCanvas(screen, graphics, mouseX, mouseY);
        if (inspectorVisible(screen)) {
            inspector.render(screen, graphics);
        }
        if (tab == Tab.STORY) {
            panel.render(screen, graphics, mouseX, mouseY);
        }
    }

    /**
     * The chapter strip, only on the map: every link is shown on the whole width.
     */
    boolean stripHolds(DevModeScreen screen, double mouseX, double mouseY) {
        return tab == Tab.STORY && panel.holds(screen, mouseX, mouseY);
    }

    /** "On the map" from the search: a quest or a story event goes on the open chapter, in the middle of what is seen. */
    void putOnMap(DevModeScreen screen, NodeKey key) {
        if (tab != Tab.STORY) {
            openTab(screen, Tab.STORY);
        }
        dropOnCanvas(screen, key, (canvasLeft() + screen.canvasRight()) / 2.0, (DevUi.TOP_BAR_H + screen.height) / 2.0);
    }

    /** The badges under a card of the map: its own nodes, "+N" for the rest; a quest starting by itself gets ⚡ first. */
    static List<Badge> badges(Card card, Font font) {
        List<Badge> list = new ArrayList<>();
        // Under the node and its title, as large as the node is.
        int y = card.cy() + card.look().radius() + 18;
        // Centred under the node: their whole width first.
        int count = (card.selfStart() ? 1 : 0) + Math.min(card.own().size(), MAX_BADGES);
        boolean more = card.own().size() > MAX_BADGES;
        int total = count == 0 ? 0 : (more ? (count - 1) * (BADGE_W + 1) + font.width("+" + (card.own().size() - MAX_BADGES + 1)) + 4
                : count * (BADGE_W + 1) - 1);
        int x = card.cx() - total / 2;
        if (card.selfStart()) {
            list.add(new Badge(card, x, y, BADGE_W, null, List.of()));
            x += BADGE_W + 1;
        }
        List<NodeKey> own = card.own();
        int shown = own.size() > MAX_BADGES ? MAX_BADGES - 1 : own.size();
        for (int i = 0; i < shown; i++) {
            list.add(new Badge(card, x, y, BADGE_W, own.get(i), List.of()));
            x += BADGE_W + 1;
        }
        if (own.size() > shown) {
            List<NodeKey> rest = own.subList(shown, own.size());
            list.add(new Badge(card, x, y, font.width("+" + rest.size()) + 4, null, List.copyOf(rest)));
        }
        return list;
    }

    private static boolean hasProblem(NodeKey key) {
        return !ClientDevStore.issuesFor(key.type(), key.id()).isEmpty() || scriptBroken(key);
    }

    @Nullable Badge badgeAt(double worldX, double worldY, Font font) {
        if (tab != Tab.STORY) {
            return null;
        }
        for (int i = cards.size() - 1; i >= 0; i--) {
            for (Badge badge : badges(cards.get(i), font)) {
                if (DevUi.inside(worldX, worldY, badge.x(), badge.y(), badge.width(), BADGE_H)) {
                    return badge;
                }
            }
        }
        return null;
    }

    /** What a badge is, for its tooltip: the node's kind and title, or what the mark means. */
    private static Component badgeTooltip(Badge badge) {
        if (badge.node() == null) {
            return badge.rest().isEmpty() ? DevUi.text("badge.self_start") : DevUi.text("badge.more", badge.rest().size());
        }
        NodeKey key = badge.node();
        String line = glyph(key.type()) + " " + DevUi.str("kind." + key.type().getPath()) + ": " + title(key);
        List<String> issues = ClientDevStore.issuesFor(key.type(), key.id());
        return Component.literal(issues.isEmpty() ? line : line + "\n⚠ " + issues.getFirst());
    }

    private void renderCanvas(DevModeScreen screen, GuiGraphics graphics, int mouseX, int mouseY) {
        Font font = screen.font();
        beginCanvas(screen, graphics, DevUi.GRID_LINE);
        Map<NodeKey, Card> byKey = new HashMap<>();
        for (Card card : cards) {
            byKey.put(card.key(), card);
        }
        double worldX = worldX(mouseX);
        double worldY = worldY(mouseY);
        boolean idle = onCanvas(screen, mouseX, mouseY) && dragging == null && linkingFrom == null && !boxSelecting && !screen.modalOpen() && !stripHolds(screen, mouseX, mouseY);
        // Hovering a node lights its links and dims what is not linked with it.
        Card focus = idle ? cardAt(worldX, worldY) : null;
        Set<NodeKey> lit = new java.util.HashSet<>();
        if (focus != null) {
            lit.add(focus.key());
            for (Arrow arrow : arrows) {
                if (arrow.from().equals(focus.key())) {
                    lit.add(arrow.to());
                } else if (arrow.to().equals(focus.key())) {
                    lit.add(arrow.from());
                }
            }
        }
        List<Runnable> words = new ArrayList<>();
        for (Arrow arrow : arrows) {
            Card from = byKey.get(arrow.from());
            Card to = byKey.get(arrow.to());
            if (from != null && to != null) {
                boolean bright = focus != null ? arrow.from().equals(focus.key()) || arrow.to().equals(focus.key())
                        : selection.contains(arrow.from()) || selection.contains(arrow.to()) || arrow.from().equals(selected) || arrow.to().equals(selected);
                int color = bright ? GOLD_LIGHT : focus != null ? DevUi.blend(OCHRE_DARK, DevUi.BACKGROUND, 0.35f) : 0x8C775025;
                double[] a = from.towards(to.cx() + 0.5, to.cy() + 0.5, 4);
                double[] b = to.towards(from.cx() + 0.5, from.cy() + 0.5, 4);
                // Straight and quiet, always there; their words only while lit.
                NodeShapes.link(graphics, a[0], a[1], b[0], b[1], color);
                String label = bright ? arrow.litLabel() : "";
                if (!label.isEmpty()) {
                    int mx = (int) Math.round((a[0] + b[0]) / 2);
                    int my = (int) Math.round((a[1] + b[1]) / 2) + 6;
                    words.add(() -> backedText(graphics, font, label, mx, my, GOLD_LIGHT));
                }
            }
        }
        Card source = linkingFrom != null ? byKey.get(linkingFrom) : null;
        if (source != null) {
            double[] a = source.towards(worldX, worldY, 4);
            NodeShapes.link(graphics, a[0], a[1], worldX, worldY, GOLD_LIGHT);
        }
        Badge hoveredBadge = idle ? badgeAt(worldX, worldY, font) : null;
        for (Card card : cards) {
            boolean primary = card.key().equals(selected);
            boolean multi = selection.contains(card.key());
            boolean hovered = focus != null && focus.key().equals(card.key());
            boolean dim = focus != null && !lit.contains(card.key()) || tab == Tab.STORY && unknownToPlayer(card);
            boolean problem = card.hasError() || card.own().stream().anyMatch(StoryDrawing::hasProblem);
            renderNode(graphics, font, card, primary, multi, hovered, dim, problem);
            if (tab == Tab.STORY && !dim) {
                renderProgress(graphics, font, card);
            }
            if (tab == Tab.STORY) {
                renderBadges(graphics, font, card, hoveredBadge, dim);
            }
        }
        // The lit links' words over the nodes, so a node never hides them.
        words.forEach(Runnable::run);
        if (boxSelecting) {
            int x1 = (int) Math.min(boxStartX, boxEndX);
            int y1 = (int) Math.min(boxStartY, boxEndY);
            int x2 = (int) Math.max(boxStartX, boxEndX);
            int y2 = (int) Math.max(boxStartY, boxEndY);
            graphics.fill(x1, y1, x2, y2, 0x33E1B34D);
            DevUi.horizontal(graphics, x1, x2, y1, 0xFFE1B34D);
            DevUi.horizontal(graphics, x1, x2, y2, 0xFFE1B34D);
            DevUi.vertical(graphics, x1, y1, y2, 0xFFE1B34D);
            DevUi.vertical(graphics, x2, y1, y2, 0xFFE1B34D);
        }
        endCanvas(graphics);
        if (tab == Tab.STORY && cards.isEmpty()) {
            int left = canvasLeft() + 16;
            int y = DevUi.TOP_BAR_H + 26;
            for (FormattedCharSequence line : font.split(DevUi.text(Chapters.quests().equals(storyChapter) ? "map.empty" : "map.empty_chapter"),
                    screen.canvasRight() - left - 16)) {
                graphics.drawString(font, line, left, y, DevUi.HINT, false);
                y += 11;
            }
        }
        if (hoveredBadge != null) {
            graphics.renderTooltip(font, font.split(badgeTooltip(hoveredBadge), 240), mouseX, mouseY);
        } else if (focus != null) {
            graphics.renderComponentTooltip(font, nodeTooltip(focus), mouseX, mouseY);
        }
    }

    /**
     * A node: a dark shape in a thin bronze ring (a main quest's gold, red when it or one of its own nodes has a
     * problem), its gold icon, its title under it on a patch of the ground, so links pass under the words. Hovered: a
     * warm glow, the ring and the icon a step lighter, its link port. Selected: the shape gold, its icon dark. Dim
     * (another node hovered and this one not linked with it, or a quest the player does not know yet): ring, icon and
     * title faded.
     */
    private void renderNode(GuiGraphics graphics, Font font, Card card, boolean primary, boolean multi, boolean hovered, boolean dim,
                            boolean problem) {
        drawNode(graphics, font, card.look(), card.item(), card.cx(), card.cy(), card.title(), TITLE_W, primary, multi, hovered, dim,
                problem, card.missingTranslation());
        if (hovered || primary) {
            int px = card.rightX();
            graphics.fill(px - 3, card.cy() - 3, px + 4, card.cy() + 4, DevUi.DARK_TEXT);
            graphics.fill(px - 2, card.cy() - 2, px + 3, card.cy() + 3, GOLD_LIGHT);
        }
    }

    /**
     * A node as every canvas draws it (the story's, a quest's inside, a dialogue's): its shape centred on the pixel
     * {@code cx, cy}, dark, in a thin bronze ring (gold when selected or hovered, red when it or one of its own nodes
     * has a problem), its item in the middle, its title under it ({@code titleWidth} at most) on a patch of the ground.
     * Hovered: a warm glow and the shape lit over its item. Dim: the shape shaded over its item, its title faded.
     */
    static void drawNode(GuiGraphics graphics, Font font, Look look, ItemStack item, int cx, int cy, String title, int titleWidth,
                         boolean primary, boolean multi, boolean hovered, boolean dim, boolean problem, boolean missingTranslation) {
        int ringColor = problem ? DevUi.ERROR : primary ? GOLD_GLINT : hovered || multi ? GOLD_LIGHT : OCHRE;
        int glow = hovered ? 0x66E1B34D : 0;
        NodeShapes.draw(graphics, look.shape(), look.radius(), cx, cy, glow, primary ? SELECTED_FILL : NODE_FILL, ringColor);
        graphics.pose().pushPose();
        // The item behind the canvas's own overlays and words (an item is drawn far in front otherwise).
        // As large as the shape grew: a whole number of times, so its pixels stay even at every zoom step.
        graphics.pose().translate(cx + 0.5f, cy + 0.5f - NodeShapes.iconLift(look.shape(), look.radius()), -140);
        graphics.pose().scale(NodeShapes.SCALE, NodeShapes.SCALE, 1);
        graphics.renderItem(item, -8, -8);
        graphics.pose().popPose();
        if (hovered) {
            NodeShapes.overlay(graphics, look.shape(), look.radius(), cx, cy, 0x30FFF4C4);
        } else if (dim) {
            NodeShapes.overlay(graphics, look.shape(), look.radius(), cx, cy, 0x99140E0A);
        }
        if (missingTranslation) {
            int dot = cx + look.radius() - 2;
            graphics.fill(dot, cy - look.radius(), dot + 3, cy - look.radius() + 3, DevUi.WARN);
        }
        if (!title.isEmpty()) {
            String shown = font.plainSubstrByWidth(title, titleWidth);
            int titleColor = hovered || primary ? DevUi.TEXT : dim ? DevUi.blend(DevUi.LABEL, DevUi.BACKGROUND, 0.5f) : DevUi.LABEL;
            backedText(graphics, font, shown, cx, cy + look.radius() + 5, titleColor);
        }
    }

    /** Words centred on {@code cx}, on a patch of the canvas's ground (lines under them stop at its edge). */
    static void backedText(GuiGraphics graphics, Font font, String text, int cx, int y, int color) {
        int w = font.width(text);
        int x = cx - w / 2 + 1;
        graphics.fill(x - 2, y - 2, x + w + 1, y + 9, DevUi.BACKGROUND);
        graphics.drawString(font, text, x, y, color, false);
    }

    /** What a hovered node is: its whole title, its kind, its id, its first problem. */
    private static List<Component> nodeTooltip(Card card) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(card.title()).withColor(DevUi.TEXT));
        lines.add(Component.literal(card.badge()).withColor(card.color()));
        lines.add(Component.literal(card.key().id().toString()).withColor(DevUi.HINT));
        List<String> issues = ClientDevStore.issuesFor(card.key().type(), card.key().id());
        if (!issues.isEmpty()) {
            lines.add(Component.literal("⚠ " + issues.getFirst()).withColor(DevUi.ERROR));
        }
        return lines;
    }

    /** A card's badges: each node's sign, a red dot on one with a problem, the hovered one lit. */
    private void renderBadges(GuiGraphics graphics, Font font, Card card, @Nullable Badge hovered, boolean dim) {
        for (Badge badge : badges(card, font)) {
            boolean lit = hovered != null && hovered.card().equals(card) && hovered.x() == badge.x()
                    || badge.node() != null && (badge.node().equals(selected) || selection.contains(badge.node()));
            float k = dim ? 0.35f : 1f;
            graphics.fill(badge.x(), badge.y(), badge.x() + badge.width(), badge.y() + BADGE_H, lit ? DevUi.ROW_SELECTED : DevUi.CARD);
            graphics.fill(badge.x(), badge.y() + BADGE_H - 1, badge.x() + badge.width(), badge.y() + BADGE_H,
                    DevUi.blend(lit ? DevUi.WARN : DevUi.CARD_EDGE, DevUi.BACKGROUND, k));
            String sign = badge.node() != null ? glyph(badge.node().type()) : badge.rest().isEmpty() ? "⚡" : "+" + badge.rest().size();
            int signColor = DevUi.blend(badge.node() == null && badge.rest().isEmpty() ? DevUi.WARN : lit ? DevUi.ACCENT : DevUi.LABEL,
                    DevUi.CARD, k);
            String icon = DevIcons.split(sign).icon();
            if (icon != null) {
                DevIcons.draw(graphics, icon, badge.x() + (badge.width() - DevIcons.SIZE) / 2, badge.y() + 1, signColor);
            } else {
                graphics.drawString(font, sign, badge.x() + (badge.width() - font.width(sign)) / 2 + 1, badge.y() + (BADGE_H - 8) / 2, signColor, false);
            }
            boolean problem = badge.node() != null ? hasProblem(badge.node()) : badge.rest().stream().anyMatch(StoryDrawing::hasProblem);
            if (problem) {
                graphics.fill(badge.x() + badge.width() - 3, badge.y(), badge.x() + badge.width(), badge.y() + 3, DevUi.ERROR);
            }
        }
    }

    /**
     * A link in the inspector's words: what it is to the selected node, as seen from it ({@code outgoing}: the selected
     * node holds the link), else the link's own name.
     */
    static String linkPhrase(String kind, boolean outgoing) {
        String key = "storybound.dev.links.rel." + kind + (outgoing ? ".out" : ".in");
        return Language.getInstance().has(key) ? Component.translatable(key).getString() : edgeLabel(kind);
    }
}
