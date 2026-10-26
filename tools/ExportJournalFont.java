import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import javax.imageio.ImageIO;

/** Exports one crisp Latin/Cyrillic face from the OFL-licensed Pixelify source. */
class ExportJournalFont {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length == 0 ? "." : args[0]);
        Path assets = root.resolve("src/main/resources/assets/storybound");
        Font font = Font.createFont(Font.TRUETYPE_FONT, assets.resolve("font/pixelify.ttf").toFile()).deriveFont(14f);
        var characters = new ArrayList<Character>();
        for (int code = 0x20; code < 0x100; code++) characters.add((char) code);
        for (int code = 0x400; code < 0x530; code++) characters.add((char) code);
        for (char character : "\u2013\u2014\u2018\u2019\u201c\u201d\u2022\u2026\u2713".toCharArray()) characters.add(character);
        int cell = 20;
        int rowCount = (characters.size() + 15) / 16;
        BufferedImage atlas = new BufferedImage(16 * cell, rowCount * cell, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = atlas.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        graphics.setFont(font);
        graphics.setColor(Color.WHITE);
        StringBuilder rows = new StringBuilder();
        for (int row = 0; row < rowCount; row++) {
            if (row > 0) rows.append(',');
            rows.append('"');
            for (int col = 0; col < 16; col++) {
                int index = row * 16 + col;
                char character = index < characters.size() ? characters.get(index) : 0;
                if (character != 0 && !Character.isISOControl(character) && font.canDisplay(character)) {
                    graphics.drawString(String.valueOf(character), col * cell, row * cell + 14);
                    rows.append(String.format("\\u%04x", (int) character));
                } else rows.append("\\u0000");
            }
            rows.append('"');
        }
        graphics.dispose();
        Path texture = assets.resolve("textures/font/journal_pixel.png");
        Files.createDirectories(texture.getParent());
        ImageIO.write(atlas, "png", texture.toFile());
        for (String name : new String[]{"journal", "journal_heading", "journal_title"}) {
            boolean title = name.equals("journal_title");
            String json = "{\"providers\":[{\"type\":\"space\",\"advances\":{\" \":" + (title ? 8 : 4)
                    + ",\"\\u00a0\":" + (title ? 8 : 4) + "}},"
                    + "{\"type\":\"bitmap\",\"file\":\"storybound:font/journal_pixel.png\",\"height\":"
                    + (title ? 40 : 20) + ",\"ascent\":" + (title ? 22 : 11) + ",\"chars\":[" + rows + "]},"
                    + "{\"type\":\"reference\",\"id\":\"minecraft:default\"}]}\n";
            Files.writeString(assets.resolve("font/" + name + ".json"), json, StandardCharsets.UTF_8);
        }
    }
}
