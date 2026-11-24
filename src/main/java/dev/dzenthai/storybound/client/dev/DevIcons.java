package dev.dzenthai.storybound.client.dev;

import java.util.Map;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.jetbrains.annotations.Nullable;

/**
 * The dev mode's icons: 11 by 11 ({@link #SIZE}), a line one pixel wide, in the manner of Pixelarticons, drawn by hand
 * as pixel maps here and painted in the colour they are given ({@code #} is a pixel). They stand where the editor wrote
 * a glyph: a label beginning with one ("💾 Save", "🗑") shows the icon instead ({@link #GLYPHS}), so every button, menu
 * line and badge gets them without being changed; a glyph with no icon stays as it was.
 */
final class DevIcons {
    /**
     * The icons' size, in GUI pixels: odd, so an icon sits exactly in the middle of the editor's odd-width buttons, and
     * to the game's 7-pixel letters as Pixelarticons are to their text. A drawing smaller than the square is centred in
     * it.
     */
    static final int SIZE = 11;
    /** From an icon to the text after it. */
    static final int GAP = 3;

    private static final Map<String, String[]> ICONS = Map.ofEntries(
            Map.entry("back", new String[]{"...........", "...........", "....#......", "...#.......", "..#........", ".#########.", "..#........", "...#.......", "....#......", "...........", "..........."}),
            Map.entry("undo", new String[]{"...........", "...#.......", "..#........", ".#######...", "..#.....#..", "...#.....#.", ".........#.", ".........#.", "........#..", "..######...", "..........."}),
            Map.entry("redo", new String[]{"...........", ".......#...", "........#..", "...#######.", "..#.....#..", ".#.....#...", ".#.........", ".#.........", "..#........", "...######..", "..........."}),
            Map.entry("globe", new String[]{"...#####...", "..#..#..#..", ".#..#.#..#.", "#...#.#...#", "#...#.#...#", "###########", "#...#.#...#", "#...#.#...#", ".#..#.#..#.", "..#..#..#..", "...#####..."}),
            // The story's settings (⛭): a gear with square teeth, so it does not read as a sun.
            Map.entry("gear", new String[]{"....###....", ".##.#.#.##.", ".#.##.##.#.", "..#.....#..", "###.###.###", "#...#.#...#", "###.###.###", "..#.....#..", ".#.##.##.#.", ".##.#.#.##.", "....###...."}),
            // Scripts (⚙), so they are not taken for the settings.
            Map.entry("code", new String[]{".......#...", ".......#...", "..#...#.#..", ".#....#..#.", "#....#....#", ".#...#...#.", "..#.#...#..", "....#......", "...#......."}),
            Map.entry("search", new String[]{"..####.....", ".#....#....", "#......#...", "#......#...", "#......#...", "#......#...", ".#....#....", "..####.##..", "........##.", ".........##", "..........#"}),
            Map.entry("warning", new String[]{".....#.....", "....#.#....", "....#.#....", "...#...#...", "...#.#.#...", "..#..#..#..", "..#..#..#..", ".#.......#.", ".#...#...#.", "#.........#", "###########"}),
            Map.entry("check", new String[]{".........#.", "........#..", ".#.....#...", "..#...#....", "...#.#.....", "....#......"}),
            Map.entry("close", new String[]{"#.....#", ".#...#.", "..#.#..", "...#...", "..#.#..", ".#...#.", "#.....#"}),
            Map.entry("plus", new String[]{"...#...", "...#...", "...#...", "#######", "...#...", "...#...", "...#..."}),
            Map.entry("links", new String[]{"###.....###", "#.#.....#.#", "###.....###", ".#.......#.", ".#.......#.", "..#.....#..", "...#...#...", "....###....", "....#.#....", "....###...."}),
            Map.entry("user", new String[]{"....###....", "...#...#...", "...#...#...", "...#...#...", "....###....", "...........", "...#####...", "..#.....#..", ".#.......#.", ".#.......#.", ".#########."}),
            Map.entry("save", new String[]{"#########..", "#.#...#..#.", "#.#####...#", "#.........#", "#.........#", "#.#######.#", "#.#.....#.#", "#.#.....#.#", "#.#.....#.#", "#.#.....#.#", "###########"}),
            Map.entry("trash", new String[]{"...#####...", "###########", ".#.......#.", ".#..#.#..#.", ".#..#.#..#.", ".#..#.#..#.", ".#..#.#..#.", ".#..#.#..#.", ".#.......#.", "..#######.."}),
            Map.entry("edit", new String[]{"........##.", ".......#..#", "......#..#.", ".....#..#..", "....#..#...", "...#..#....", "..#..#.....", ".#..#......", ".#.#.......", ".##........"}),
            Map.entry("copy", new String[]{"....#######", "....#.....#", "....#.....#", "....#.....#", "#######...#", "#.....#...#", "#.....#####", "#.....#....", "#.....#....", "#.....#....", "#######...."}),
            Map.entry("open", new String[]{"......#####", ".........##", "........#.#", "####...#..#", "#.....#...#", "#....#.....", "#..........", "#......#...", "#......#...", "#......#...", "########..."}),
            Map.entry("folder", new String[]{"####.......", "#...#######", "#.........#", "#.........#", "#.........#", "#.........#", "#.........#", "#.........#", "###########"}),
            Map.entry("pin", new String[]{"...#####...", "..#.....#..", ".#..###..#.", ".#..#.#..#.", ".#..###..#.", ".#.......#.", "..#.....#..", "...#...#...", "....#.#....", ".....#....."}),
            Map.entry("target", new String[]{"...#####...", ".##.....##.", ".#.......#.", "#...###...#", "#..#...#..#", "#..#.#.#..#", "#..#...#..#", "#...###...#", ".#.......#.", ".##.....##.", "...#####..."}),
            Map.entry("eye", new String[]{"...#####...", ".##.....##.", "#...###...#", "#...###...#", "#...###...#", ".##.....##.", "...#####..."}),
            Map.entry("camera", new String[]{"###.###....", "#.#.#.#....", "#######....", "#.....#..##", "#.....#.#.#", "#.....##..#", "#.....#.#.#", "#.....#..##", "#######...."}),
            Map.entry("scene", new String[]{"......#####", "..####..#.#", "##.#..##..#", "###########", "#.........#", "#....#....#", "#....##...#", "#....#....#", "#.........#", "###########"}),
            Map.entry("bolt", new String[]{".......##..", "......##...", ".....##....", "....##.....", "...#######.", ".#######...", ".....##....", "....##.....", "...##......", "..##.......", "..#........"}),
            Map.entry("star", new String[]{".....#.....", ".....#.....", "....###....", "....###....", "###########", ".#########.", "..#######..", "...#####...", "...##.##...", "..##...##..", ".#.......#."}),
            Map.entry("scroll", new String[]{"###########", ".#.......#.", ".#.#####.#.", ".#.......#.", ".#.#####.#.", ".#.......#.", ".#.#####.#.", ".#.......#.", ".#.###...#.", ".#.......#.", "###########"}),
            Map.entry("dagger", new String[]{".....#.....", "....#.#....", "....#.#....", "....#.#....", "....#.#....", "....#.#....", ".#########.", ".....#.....", ".....#.....", "....###....", ".....#....."}),
            Map.entry("swords", new String[]{"#.........#", ".#.......#.", "..#.....#..", "...#...#...", "....#.#....", ".....#.....", "....#.#....", ".#.#...#.#.", "..#.....#..", ".#.#...#.#.", "#.........#"}),
            Map.entry("chat", new String[]{".#########.", "#.........#", "#.#######.#", "#.........#", "#.#####...#", "#.........#", ".#.#######.", ".##........", ".#........."}),
            Map.entry("bubble", new String[]{"..#######..", ".#.......#.", "#.........#", "#.........#", "#.........#", "#.........#", ".#.......#.", "..###.###..", "....#.#....", ".....#....."}),
            Map.entry("phrases", new String[]{".#########.", "#.........#", "#.........#", "#..#.#.#..#", "#.........#", "#.........#", ".#.#######.", ".##........", ".#........."}),
            Map.entry("book", new String[]{"###.....###", "#..##.##..#", "#....#....#", "#....#....#", "#....#....#", "#....#....#", "#....#....#", "###..#..###", "...##.##..."}),
            Map.entry("books", new String[]{"###........", "#.#.###....", "###.#.#.###", "#.#.###.#.#", "#.#.#.#.#.#", "#.#.#.#.#.#", "#.#.#.#.#.#", "#.#.#.#.#.#", "#.#.#.#.#.#", "#.#.#.#.#.#", "###########"}),
            Map.entry("skull", new String[]{"..#######..", ".#.......#.", "#.........#", "#.##...##.#", "#.##...##.#", "#....#....#", ".#.......#.", "..##.#.##..", "...#.#.#...", "...#####..."}),
            Map.entry("reload", new String[]{"...#####...", ".##.....#.#", ".#.......##", "#.......###", "#..........", "#.........#", "#.........#", "#.........#", ".#.......#.", ".##.....##.", "...#####..."}),
            Map.entry("heart", new String[]{".###...###.", "#...#.#...#", "#....#....#", "#.........#", ".#.......#.", "..#.....#..", "...#...#...", "....#.#....", ".....#....."}),
            Map.entry("dice", new String[]{".#########.", "#.........#", "#.#.....#.#", "#.........#", "#.........#", "#....#....#", "#.........#", "#.........#", "#.#.....#.#", "#.........#", ".#########."}),
            Map.entry("question", new String[]{".#####.", "#.....#", "#.....#", "......#", ".....#.", "....#..", "...#...", "...#...", ".......", "...#..."}),
            Map.entry("scale", new String[]{".....#.....", ".#########.", ".#...#...#.", "#.#..#..#.#", "###..#..###", ".....#.....", ".....#.....", ".....#.....", "...#####..."}),
            Map.entry("flag", new String[]{"#.........", "#########.", "#.#.#.#.#.", "##.#.#.##.", "#.#.#.#.#.", "#########.", "#.........", "#.........", "#.........", "#.........", "#........."}),
            Map.entry("call", new String[]{"###########", "#.........#", "#.........#", "#.....#...#", "#......#..#", "#.#######.#", "#......#..#", "#.....#...#", "#.........#", "#.........#", "###########"}),
            Map.entry("cut", new String[]{"#.........#", ".#.......#.", "..#.....#..", "...#...#...", "....#.#....", ".....#.....", "....#.#....", ".###...###.", "#...#.#...#", "#...#.#...#", ".###...###."}),
            Map.entry("clock", new String[]{"...#####...", ".##.....##.", ".#...#...#.", "#....#....#", "#....#....#", "#....###..#", "#.........#", "#.........#", ".#.......#.", ".##.....##.", "...#####..."}),
            Map.entry("file", new String[]{"######...", "#....##..", "#....#.#.", "#....####", "#.......#", "#.#####.#", "#.......#", "#.#####.#", "#.......#", "#.###...#", "#########"}),
            Map.entry("export", new String[]{".....#.....", "....###....", "...#.#.#...", "..#..#..#..", ".....#.....", ".....#.....", ".....#.....", "#.........#", "#.........#", "#.........#", "###########"}),
            Map.entry("unsorted", new String[]{"...#.#.#...", ".#.......#.", "...........", "#.........#", "...........", "#.........#", "...........", "#.........#", "...........", ".#.......#.", "...#.#.#..."}),
            Map.entry("mic", new String[]{"....###....", "...#...#...", "...#...#...", "...#...#...", ".#.#...#.#.", ".#..###..#.", "..#.....#..",
                    "...#####...", ".....#.....", ".....#.....", "...#####..."}),
            Map.entry("mic_set", new String[]{"..###......", ".#...#.....", ".#...#.....", ".#...#.....", ".#...#.....", "#.###.#....", ".#...#....#",
                    "..###....#.", "...#..#.#..", "...#...#...", ".#####....."}),
            Map.entry("music", new String[]{"....######.", "....#....#.", "....######.", "....#....#.", "....#....#.", "....#....#.", "..###..###.",
                    ".####.####.", ".####.####.", "..##...##..", "..........."}),
            Map.entry("play", new String[]{"#....", "##...", "#.#..", "#..#.", "#...#", "#..#.", "#.#..", "##...", "#...."}),
            Map.entry("stop", new String[]{"#######", "#######", "#######", "#######", "#######", "#######", "#######"}),
            Map.entry("bag", new String[]{"...#####...", "..#.....#..", ".#########.", ".#.......#.", ".#..###..#.", ".#..#.#..#.", ".#..###..#.", ".#.......#.", ".#.......#.", ".#########."}),
            Map.entry("hand", new String[]{"....#.#....", "...#.#.#...", "...#.#.#.#.", "...#.#.#.#.", "...#.#.#.#.", "#..#.....#.", "##.#.....#.", ".##......#.", "..#......#.", "...#....#..", "....####..."}),
            Map.entry("prev", new String[]{"....#", "...#.", "..#..", ".#...", "#....", ".#...", "..#..", "...#.", "....#"}),
            Map.entry("next", new String[]{"#....", ".#...", "..#..", "...#.", "....#", "...#.", "..#..", ".#...", "#...."}),
            Map.entry("play_all", new String[]{"#.....#....", "##....##...", "#.#...#.#..", "#..#..#..#.", "#...#.#...#", "#..#..#..#.", "#.#...#.#..", "##....##...", "#.....#...."}),
            Map.entry("swap", new String[]{".......#...", "........#..", ".#########.", "........#..", "...........", "..#........", ".#########.", "..#........", "...#......."}),
            Map.entry("photo", new String[]{"...#####...", "###.....###", "#.........#", "#...###...#", "#...#.#...#", "#...###...#", "#.........#", "###########"}));

    /** The glyphs the editor writes and the icon each one is. */
    static final Map<String, String> GLYPHS = Map.ofEntries(
            Map.entry("←", "back"),
            Map.entry("↶", "undo"),
            Map.entry("↷", "redo"),
            Map.entry("🌐", "globe"),
            Map.entry("⚙", "code"),
            Map.entry("⛭", "gear"),
            Map.entry("🔍", "search"),
            Map.entry("🔎", "search"),
            Map.entry("⚠", "warning"),
            Map.entry("✓", "check"),
            Map.entry("✔", "check"),
            Map.entry("✕", "close"),
            Map.entry("✘", "close"),
            Map.entry("✗", "close"),
            Map.entry("+", "plus"),
            Map.entry("🕸", "links"),
            Map.entry("👤", "user"),
            Map.entry("🧑", "user"),
            Map.entry("💾", "save"),
            Map.entry("🗑", "trash"),
            Map.entry("✎", "edit"),
            Map.entry("✏", "edit"),
            Map.entry("📋", "copy"),
            Map.entry("⤢", "open"),
            Map.entry("↗", "open"),
            Map.entry("📁", "folder"),
            Map.entry("📂", "folder"),
            Map.entry("📌", "pin"),
            Map.entry("📍", "pin"),
            Map.entry("🎯", "target"),
            Map.entry("👁", "eye"),
            Map.entry("⚡", "bolt"),
            Map.entry("🎬", "scene"),
            Map.entry("🎥", "camera"),
            Map.entry("★", "star"),
            Map.entry("📜", "scroll"),
            Map.entry("🗡", "dagger"),
            Map.entry("⚔", "swords"),
            Map.entry("💬", "chat"),
            Map.entry("💭", "bubble"),
            Map.entry("🗨", "phrases"),
            Map.entry("📖", "book"),
            Map.entry("📚", "books"),
            Map.entry("🐉", "skull"),
            Map.entry("↻", "reload"),
            Map.entry("↺", "reload"),
            Map.entry("❤", "heart"),
            Map.entry("🎲", "dice"),
            Map.entry("❓", "question"),
            Map.entry("⚖", "scale"),
            Map.entry("🏁", "flag"),
            Map.entry("📞", "call"),
            Map.entry("✂", "cut"),
            Map.entry("🕒", "clock"),
            Map.entry("📄", "file"),
            Map.entry("📤", "export"),
            Map.entry("◌", "unsorted"),
            Map.entry("▶", "play"),
            Map.entry("⏹", "stop"),
            Map.entry("■", "stop"),
            Map.entry("🎤", "mic"),
            Map.entry("🎙", "mic_set"),
            Map.entry("🎵", "music"),
            Map.entry("🗣", "user"),
            Map.entry("🎒", "bag"),
            Map.entry("✋", "hand"),
            Map.entry("⏴", "prev"),
            Map.entry("⏵", "next"),
            Map.entry("⏩", "play_all"),
            Map.entry("⇄", "swap"),
            Map.entry("📷", "photo"));

    private DevIcons() {}

    /** A label split into its icon (null when it does not begin with a glyph that has one) and the text after it. */
    record Split(@Nullable String icon, String text) {}

    /** A glyph that has an icon, at the label's start, followed by a space or nothing: "💾 Save", "🗑", "+ Add" (not "+3"). */
    static Split split(String label) {
        if (label.isEmpty()) {
            return new Split(null, label);
        }
        int end = label.offsetByCodePoints(0, 1);
        String icon = GLYPHS.get(label.substring(0, end));
        if (icon == null || end < label.length() && label.charAt(end) != ' ') {
            return new Split(null, label);
        }
        return new Split(icon, end < label.length() ? label.substring(end + 1) : "");
    }

    static boolean has(String icon) {
        return ICONS.containsKey(icon);
    }

    /**
     * Paints the icon in the {@link #SIZE} square whose top left is {@code x, y}, its drawing centred in it (each map
     * is drawn centred, and this keeps one that is not from showing it).
     */
    static void draw(GuiGraphics graphics, String icon, int x, int y, int color) {
        String[] rows = ICONS.get(icon);
        if (rows == null) {
            return;
        }
        int[] box = OFFSETS.computeIfAbsent(icon, name -> offset(rows));
        x += box[0];
        y += box[1];
        for (int row = 0; row < rows.length; row++) {
            String line = rows[row];
            int start = -1;
            // Runs of pixels as one fill each.
            for (int column = 0; column <= line.length(); column++) {
                boolean on = column < line.length() && line.charAt(column) == '#';
                if (on && start < 0) {
                    start = column;
                } else if (!on && start >= 0) {
                    graphics.fill(x + start, y + row, x + column, y + row + 1, color);
                    start = -1;
                }
            }
        }
    }

    private static final Map<String, int[]> OFFSETS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * How far the drawing moves to look centred in its square: first by its edges (equal empty columns and rows), then
     * by its mass (an arrow's head or a play sign's wide side weighs on one side) as far as the square lets it, by a
     * whole pixel once the mass is off by a third of one.
     */
    private static int[] offset(String[] rows) {
        int left = SIZE;
        int right = -1;
        int top = SIZE;
        int bottom = -1;
        int count = 0;
        double sumX = 0;
        double sumY = 0;
        for (int row = 0; row < rows.length; row++) {
            for (int column = 0; column < rows[row].length(); column++) {
                if (rows[row].charAt(column) == '#') {
                    left = Math.min(left, column);
                    right = Math.max(right, column);
                    top = Math.min(top, row);
                    bottom = Math.max(bottom, row);
                    sumX += column;
                    sumY += row;
                    count++;
                }
            }
        }
        if (count == 0) {
            return new int[]{0, 0};
        }
        int dx = Math.floorDiv(SIZE - 1 - right - left, 2);
        int dy = Math.floorDiv(SIZE - 1 - bottom - top, 2);
        double middle = (SIZE - 1) / 2.0;
        int ox = (int) Math.floor(middle - (sumX / count + dx) + 0.7);
        int oy = (int) Math.floor(middle - (sumY / count + dy) + 0.7);
        ox = Math.max(-(left + dx), Math.min(SIZE - 1 - (right + dx), ox));
        oy = Math.max(-(top + dy), Math.min(SIZE - 1 - (bottom + dy), oy));
        return new int[]{dx + ox, dy + oy};
    }

    /** The label's width as {@link #drawLabel} draws it. */
    static int width(Font font, String label) {
        Split split = split(label);
        if (split.icon() == null) {
            return font.width(label);
        }
        return SIZE + (split.text().isEmpty() ? 0 : GAP + font.width(split.text()));
    }

    /**
     * Draws a label as text, its glyph as the icon (text {@code y} as {@code drawString} takes it; the icon two pixels
     * higher, the letters in its middle); returns the width drawn.
     */
    static int drawLabel(GuiGraphics graphics, Font font, String label, int x, int y, int iconColor, int textColor) {
        Split split = split(label);
        if (split.icon() == null) {
            graphics.drawString(font, label, x, y, textColor, false);
            return font.width(label);
        }
        draw(graphics, split.icon(), x, y - 2, iconColor);
        if (split.text().isEmpty()) {
            return SIZE;
        }
        graphics.drawString(font, split.text(), x + SIZE + GAP, y, textColor, false);
        return SIZE + GAP + font.width(split.text());
    }
}
