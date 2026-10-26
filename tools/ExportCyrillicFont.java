import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import javax.imageio.ImageIO;

/** Exports a monochrome Cyrillic fallback atlas from the licensed Pixelify font. */
class ExportCyrillicFont {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length == 0 ? "." : args[0]);
        Path assets = root.resolve("src/main/resources/assets/storybound");
        Font font = Font.createFont(Font.TRUETYPE_FONT, assets.resolve("font/pixelify.ttf").toFile()).deriveFont(14f);
        int cell = 20;
        BufferedImage atlas = new BufferedImage(16 * cell, 19 * cell, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = atlas.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        graphics.setFont(font);
        graphics.setColor(Color.WHITE);
        StringBuilder rows = new StringBuilder();
        for (int row = 0; row < 19; row++) {
            if (row > 0) rows.append(",");
            rows.append('"');
            for (int col = 0; col < 16; col++) {
                char character = (char) (0x400 + row * 16 + col);
                if (font.canDisplay(character)) {
                    graphics.drawString(String.valueOf(character), col * cell, row * cell + 14);
                    rows.append(character);
                } else rows.append("\\u0000");
            }
            rows.append('"');
        }
        graphics.dispose();
        Path texture = assets.resolve("textures/font/field_cyrillic.png");
        Files.createDirectories(texture.getParent());
        ImageIO.write(atlas, "png", texture.toFile());
        for (String name : new String[]{"journal", "journal_heading", "journal_title"}) {
            boolean title = name.equals("journal_title");
            String json = "{\"providers\":[{\"type\":\"ttf\",\"file\":\"storybound:storybound_tiny.ttf\",\"size\":"
                    + (title ? 21 : 14) + ",\"oversample\":1,\"shift\":[0,2]},"
                    + "{\"type\":\"bitmap\",\"file\":\"storybound:font/field_cyrillic.png\",\"height\":"
                    + (title ? 40 : 20) + ",\"ascent\":" + (title ? 22 : 11) + ",\"chars\":[" + rows + "]},"
                    + "{\"type\":\"reference\",\"id\":\"minecraft:default\"}]}\n";
            Files.writeString(assets.resolve("font/" + name + ".json"), json, StandardCharsets.UTF_8);
        }
    }
}
