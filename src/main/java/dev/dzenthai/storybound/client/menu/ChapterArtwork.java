package dev.dzenthai.storybound.client.menu;

import com.google.gson.JsonParser;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.quest.ClientChapterMap;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** Resource-pack presentation metadata, independent of server quest definitions. */
public final class ChapterArtwork {
    public record Art(ResourceLocation texture, Optional<Component> caption) {}
    private static final ResourceLocation CONFIG = Storybound.id("storybound/menu/chapter_art.json");
    public static final ResourceLocation DEFAULT = Storybound.id("textures/gui/journal/chapter_default.png");

    private ChapterArtwork() {}

    public static Art forQuest(ResourceLocation quest) {
        var chapter = ClientChapterMap.get().chapters().stream()
                .filter(value -> value.nodes().stream().anyMatch(node -> node.quest().equals(quest))).findFirst();
        String id = chapter.map(value -> value.id().toString()).orElse("");
        Optional<Component> caption = chapter.map(value -> value.title().asComponent());
        var resource = Minecraft.getInstance().getResourceManager().getResource(CONFIG);
        if (resource.isPresent()) {
            try (var reader = resource.get().openAsReader()) {
                var config = JsonParser.parseReader(reader).getAsJsonObject();
                var quests = config.getAsJsonObject("quests");
                if (id.isEmpty() && quests != null && quests.has(quest.toString())) id = quests.get(quest.toString()).getAsString();
                var chapters = config.getAsJsonObject("chapters");
                if (chapters != null && chapters.has(id)) {
                    var art = chapters.getAsJsonObject(id);
                    var texture = ResourceLocation.parse(art.get("texture").getAsString());
                    if (caption.isEmpty() && art.has("title")) {
                        String key = art.get("title").getAsString();
                        if (net.minecraft.locale.Language.getInstance().has(key)) caption = Optional.of(Component.translatable(key));
                    }
                    if (Minecraft.getInstance().getResourceManager().getResource(texture).isPresent()) return new Art(texture, caption);
                }
            } catch (java.io.IOException | RuntimeException error) {
                Storybound.LOGGER.warn("Could not read chapter artwork: {}", error.getMessage());
            }
        }
        return new Art(DEFAULT, caption);
    }

    public static void append(ListDetailView.Detail detail, Art art) {
        int imageHeight = Math.max(24, Math.min(32, detail.width() / 4));
        detail.block(imageHeight + 5, (graphics, x, y, width, mouseX, mouseY, partialTick) -> {
            com.mojang.blaze3d.systems.RenderSystem.enableBlend();
            int sourceHeight = Math.min(887, 1774 * imageHeight / Math.max(1, width));
            graphics.blit(art.texture(), x, y, width, imageHeight, 0, (887 - sourceHeight) / 2,
                    1774, sourceHeight, 1774, 887);
            graphics.renderOutline(x - 1, y - 1, width + 2, imageHeight + 2, MenuThemes.INSTANCE.theme().divider());
        });
        art.caption().ifPresent(caption -> detail.text(caption, MenuThemes.INSTANCE.theme().muted()).gap(3));
    }
}
