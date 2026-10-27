package dev.dzenthai.storybound.gametest;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.document.DocumentScreen;
import dev.dzenthai.storybound.client.document.DocumentStyles;
import dev.dzenthai.storybound.client.menu.BookViewport;
import dev.dzenthai.storybound.module.document.DocumentDefinition;
import dev.dzenthai.storybound.module.document.DocumentKind;
import dev.dzenthai.storybound.module.document.DocumentRef;
import dev.dzenthai.storybound.module.document.DocumentRegistries;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.lwjgl.glfw.GLFW;

/** Isolated reading-screen checks, excluded from the release jar. */
@EventBusSubscriber(modid = Storybound.MOD_ID, value = Dist.CLIENT)
public final class DocumentPreview {
    private static boolean started;
    private static boolean ready;
    private static int ticks;
    private static int captures;

    private DocumentPreview() {}

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("storybound.documentPreview")) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getOverlay() != null) return;
        if (!started) {
            started = true;
            language(minecraft, "en_us");
            return;
        }
        if (!ready) return;
        ticks++;
        if (ticks == 10) show(minecraft, 1, false);
        if (ticks == 30) capture(minecraft, "reader-en-gui-1.png");
        if (ticks == 40) show(minecraft, 3, false);
        if (ticks == 60) capture(minecraft, "reader-en-gui-3.png");
        if (ticks == 70) {
            DocumentScreen screen = screen(minecraft);
            if (!screen.keyPressed(GLFW.GLFW_KEY_RIGHT, 0, 0)) throw new IllegalStateException("Next page not handled");
        }
        if (ticks == 90) capture(minecraft, "reader-next-spread.png");
        if (ticks == 100) {
            var window = minecraft.getWindow();
            var viewport = BookViewport.fit(window.getWidth(), window.getHeight(), window.getGuiScale());
            var style = DocumentStyles.INSTANCE.get(Storybound.id("book_leather"));
            if (!screen(minecraft).mouseClicked(viewport.toGuiX(32 + style.padding().left() + 11),
                    viewport.toGuiY(8 + style.height() - style.padding().bottom() + 2 + 6), 0)) {
                throw new IllegalStateException("Page arrow missed its click");
            }
        }
        if (ticks == 120) capture(minecraft, "reader-back-spread.png");
        if (ticks == 130) language(minecraft, "ru_ru");
        if (ticks == 140) show(minecraft, 0, false);
        if (ticks == 160) capture(minecraft, "reader-ru-gui-auto.png");
        if (ticks == 170) {
            screen(minecraft).mouseScrolled(0, 0, 0, -1);
        }
        if (ticks == 190) capture(minecraft, "reader-ru-next.png");
        if (ticks == 200) show(minecraft, 2, false, true);
        if (ticks == 220) capture(minecraft, "reader-long-title.png");
        if (ticks == 240) show(minecraft, 2, true);
        if (ticks == 260) capture(minecraft, "reader-custom-style.png");
        if (ticks == 270) {
            if (captures != 8) throw new IllegalStateException("Missing document captures");
            screen(minecraft).onClose();
            if (minecraft.screen instanceof DocumentScreen) throw new IllegalStateException("Reader did not close");
            language(minecraft, "en_us");
        }
        if (ticks == 280) showScroll(minecraft, 1, false, false);
        if (ticks == 300) capture(minecraft, "scroll-en-gui-1.png");
        if (ticks == 310) {
            minecraft.options.guiScale().set(3);
            minecraft.resizeDisplay();
        }
        if (ticks == 330) capture(minecraft, "scroll-en-gui-3.png");
        if (ticks == 340) screen(minecraft).mouseScrolled(0, 0, 0, -4);
        if (ticks == 360) capture(minecraft, "scroll-wheel.png");
        if (ticks == 370 && !screen(minecraft).keyPressed(GLFW.GLFW_KEY_PAGE_DOWN, 0, 0)) {
            throw new IllegalStateException("Scroll Page Down not handled");
        }
        if (ticks == 390) capture(minecraft, "scroll-paged.png");
        if (ticks == 400) language(minecraft, "ru_ru");
        if (ticks == 410) showScroll(minecraft, 0, false, false);
        if (ticks == 430) capture(minecraft, "scroll-ru.png");
        if (ticks == 440) showScroll(minecraft, 2, false, true);
        if (ticks == 460) capture(minecraft, "scroll-short.png");
        if (ticks == 470) showScroll(minecraft, 2, true, false);
        if (ticks == 490) capture(minecraft, "scroll-long-title.png");
        if (ticks == 510) {
            if (captures != 15) throw new IllegalStateException("Missing book or scroll captures");
            screen(minecraft).onClose();
            if (minecraft.screen instanceof DocumentScreen) throw new IllegalStateException("Scroll did not close");
            language(minecraft, "en_us");
        }
        if (ticks == 520) showLetter(minecraft, 1, false);
        if (ticks == 540) capture(minecraft, "letter-en-gui-1.png");
        if (ticks == 550) {
            minecraft.options.guiScale().set(3);
            minecraft.resizeDisplay();
        }
        if (ticks == 570) capture(minecraft, "letter-en-gui-3.png");
        if (ticks == 580) showLetter(minecraft, 3, true);
        if (ticks == 600) capture(minecraft, "letter-long.png");
        if (ticks == 610) screen(minecraft).mouseScrolled(0, 0, 0, -3);
        if (ticks == 630) capture(minecraft, "letter-wheel.png");
        if (ticks == 640 && !screen(minecraft).keyPressed(GLFW.GLFW_KEY_PAGE_DOWN, 0, 0)) {
            throw new IllegalStateException("Letter Page Down not handled");
        }
        if (ticks == 660) capture(minecraft, "letter-paged.png");
        if (ticks == 670) language(minecraft, "ru_ru");
        if (ticks == 680) showLetter(minecraft, 0, false);
        if (ticks == 700) capture(minecraft, "letter-ru.png");
        if (ticks == 710) {
            showLetter(minecraft, 2, true);
            screen(minecraft).mouseScrolled(0, 0, 0, -100000);
        }
        if (ticks == 730) capture(minecraft, "letter-end.png");
        if (ticks == 750) {
            if (captures != 22) throw new IllegalStateException("Missing book, scroll or letter captures");
            screen(minecraft).onClose();
            if (minecraft.screen instanceof DocumentScreen) throw new IllegalStateException("Letter did not close");
            language(minecraft, "en_us");
        }
        if (ticks == 760) showNote(minecraft, 1, false, false);
        if (ticks == 780) capture(minecraft, "note-en-gui-1.png");
        if (ticks == 790) {
            minecraft.options.guiScale().set(3);
            minecraft.resizeDisplay();
        }
        if (ticks == 810) capture(minecraft, "note-en-gui-3.png");
        if (ticks == 820) showNote(minecraft, 3, true, false);
        if (ticks == 840) capture(minecraft, "note-long.png");
        if (ticks == 850) screen(minecraft).mouseScrolled(0, 0, 0, -3);
        if (ticks == 870) capture(minecraft, "note-wheel.png");
        if (ticks == 880 && !screen(minecraft).keyPressed(GLFW.GLFW_KEY_PAGE_DOWN, 0, 0)) {
            throw new IllegalStateException("Note Page Down not handled");
        }
        if (ticks == 900) capture(minecraft, "note-paged.png");
        if (ticks == 910) language(minecraft, "ru_ru");
        if (ticks == 920) showNote(minecraft, 0, false, false);
        if (ticks == 940) capture(minecraft, "note-ru.png");
        if (ticks == 950) {
            showNote(minecraft, 2, true, false);
            screen(minecraft).mouseScrolled(0, 0, 0, -100000);
        }
        if (ticks == 970) capture(minecraft, "note-end.png");
        if (ticks == 980) showNote(minecraft, 2, false, true);
        if (ticks == 1000) capture(minecraft, "note-legacy-style.png");
        if (ticks == 1020) {
            if (captures != 30) throw new IllegalStateException("Missing reading-screen captures");
            screen(minecraft).onClose();
            if (minecraft.screen instanceof DocumentScreen) throw new IllegalStateException("Note did not close");
            language(minecraft, "en_us");
        }
        if (ticks == 1030) showContract(minecraft, 1, false, false, false);
        if (ticks == 1050) capture(minecraft, "contract-en-gui-1.png");
        if (ticks == 1060) {
            minecraft.options.guiScale().set(3);
            minecraft.resizeDisplay();
        }
        if (ticks == 1080) capture(minecraft, "contract-en-gui-3.png");
        if (ticks == 1090) showContract(minecraft, 3, true, false, false);
        if (ticks == 1110) capture(minecraft, "contract-long.png");
        if (ticks == 1120) screen(minecraft).mouseScrolled(0, 0, 0, -3);
        if (ticks == 1140) capture(minecraft, "contract-wheel.png");
        if (ticks == 1150 && !screen(minecraft).keyPressed(GLFW.GLFW_KEY_PAGE_DOWN, 0, 0)) {
            throw new IllegalStateException("Contract Page Down not handled");
        }
        if (ticks == 1170) capture(minecraft, "contract-paged.png");
        if (ticks == 1180) language(minecraft, "ru_ru");
        if (ticks == 1190) showContract(minecraft, 0, false, false, false);
        if (ticks == 1210) capture(minecraft, "contract-ru.png");
        if (ticks == 1220) {
            showContract(minecraft, 2, true, false, false);
            screen(minecraft).mouseScrolled(0, 0, 0, -100000);
        }
        if (ticks == 1240) capture(minecraft, "contract-end.png");
        if (ticks == 1250) showContract(minecraft, 2, false, false, true);
        if (ticks == 1270) capture(minecraft, "contract-long-title.png");
        if (ticks == 1280) showContract(minecraft, 2, false, true, false);
        if (ticks == 1300) capture(minecraft, "contract-legacy-style.png");
        if (ticks == 1320) {
            if (captures != 39) throw new IllegalStateException("Missing contract captures");
            screen(minecraft).onClose();
            if (minecraft.screen instanceof DocumentScreen) throw new IllegalStateException("Contract did not close");
            showItems(minecraft);
        }
        if (ticks == 1340) capture(minecraft, "document-items.png");
        if (ticks == 1360) {
            if (captures != 40) throw new IllegalStateException("Missing inventory capture");
            Storybound.LOGGER.info("Document preview passed: forty captures, five item models, all five reading surfaces, navigation, title wrapping and legacy styles");
            minecraft.stop();
        }
    }

    private static void showItems(Minecraft minecraft) {
        minecraft.options.guiScale().set(2);
        minecraft.resizeDisplay();
        DocumentKind[] kinds = {DocumentKind.BOOK, DocumentKind.SCROLL, DocumentKind.LETTER, DocumentKind.NOTE, DocumentKind.CONTRACT};
        Map<ResourceLocation, DocumentDefinition> definitions = new LinkedHashMap<>();
        ItemStack[] items = new ItemStack[kinds.length];
        for (int i = 0; i < kinds.length; i++) {
            var id = Storybound.id("preview_item_" + kinds[i].getSerializedName());
            var json = JsonParser.parseString("{\"kind\":\"" + kinds[i].getSerializedName()
                    + "\",\"title\":{\"literal\":\"" + kinds[i].getSerializedName() + "\"}}");
            definitions.put(id, DocumentDefinition.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
            items[i] = new ItemStack(DocumentRegistries.DOCUMENT_ITEM.get());
            items[i].set(DocumentRegistries.DOCUMENT_COMPONENT.get(), new DocumentRef(id));
        }
        DocumentRegistries.DOCUMENTS.clientStore().replace(definitions);
        minecraft.setScreen(new Screen(Component.literal("Document item material review")) {
            @Override
            public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
                graphics.fill(0, 0, width, height, 0xFF30342B);
                graphics.drawCenteredString(font, "Document items / actual item models", width / 2, 36, 0xF3D8AF);
                int left = (width - 500) / 2;
                for (int i = 0; i < items.length; i++) {
                    int center = left + i * 100 + 50;
                    graphics.drawCenteredString(font, kinds[i].getSerializedName(), center, 78, 0xF3D8AF);
                    graphics.pose().pushPose();
                    graphics.pose().translate(center - 32, 106, 0);
                    graphics.pose().scale(4, 4, 1);
                    graphics.renderItem(items[i], 0, 0);
                    graphics.pose().popPose();
                    graphics.fill(center - 11, 215, center + 11, 237, 0xFF79533F);
                    graphics.fill(center - 9, 217, center + 9, 235, 0xFFDFB88B);
                    graphics.renderItem(items[i], center - 8, 218);
                }
                graphics.drawCenteredString(font, "4x detail above / inventory size below", width / 2, 274, 0xF3D8AF);
            }
        });
    }

    private static DocumentScreen screen(Minecraft minecraft) {
        if (!(minecraft.screen instanceof DocumentScreen screen)) throw new IllegalStateException("Reader is not open");
        return screen;
    }

    private static void language(Minecraft minecraft, String language) {
        ready = false;
        minecraft.options.languageCode = language;
        minecraft.getLanguageManager().setSelected(language);
        minecraft.reloadResourcePacks().thenRun(() -> minecraft.execute(() -> ready = true));
    }

    private static void show(Minecraft minecraft, int scale, boolean custom) {
        show(minecraft, scale, custom, false);
    }

    private static void show(Minecraft minecraft, int scale, boolean custom, boolean longTitle) {
        minecraft.options.guiScale().set(scale);
        minecraft.resizeDisplay();
        var id = Storybound.id("preview_reader");
        String json = custom
                ? "{\"kind\":\"book\",\"title\":\"storybound.preview.reader.title\",\"style\":\"storybound_preview:reader_legacy\"}"
                : "{\"kind\":\"book\",\"title\":\"storybound.preview.reader.title\"}";
        if (longTitle) json = json.replace("storybound.preview.reader.title", "storybound.preview.reader.long_title");
        var definition = DocumentDefinition.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
        DocumentRegistries.DOCUMENTS.clientStore().replace(Map.of(id, definition));
        String key = "storybound.preview.reader.body";
        if (!I18n.exists(key)) throw new IllegalStateException("Reader fixture translation missing");
        String chapter = I18n.get(key).replace("\\n", "\n");
        String source = chapter + "\n---page---\n" + chapter + "\n---page---\n" + chapter
                + "\n---page---\n" + chapter;
        minecraft.setScreen(new DocumentScreen(id, null, source));
    }

    private static void capture(Minecraft minecraft, String name) {
        Screenshot.grab(minecraft.gameDirectory, name, minecraft.getMainRenderTarget(), message -> {});
        captures++;
    }

    private static void showScroll(Minecraft minecraft, int scale, boolean longTitle, boolean shortText) {
        minecraft.options.guiScale().set(scale);
        minecraft.resizeDisplay();
        var id = Storybound.id("preview_scroll");
        var json = JsonParser.parseString("{\"kind\":\"scroll\",\"title\":\"storybound.preview.scroll.title\"}").getAsJsonObject();
        if (longTitle) json.addProperty("title", "storybound.preview.scroll.long_title");
        var definition = DocumentDefinition.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        DocumentRegistries.DOCUMENTS.clientStore().replace(Map.of(id, definition));
        String source = shortText
                ? I18n.get("storybound.preview.scroll.short")
                : (I18n.get("storybound.preview.reader.body").replace("\\n", "\n") + "\n\n").repeat(3);
        minecraft.setScreen(new DocumentScreen(id, null, source));
    }

    private static void showNote(Minecraft minecraft, int scale, boolean longText, boolean legacy) {
        minecraft.options.guiScale().set(scale);
        minecraft.resizeDisplay();
        var id = Storybound.id("preview_note_reader");
        String style = legacy ? ",\"style\":\"storybound:note_torn\"" : "";
        var json = JsonParser.parseString("{\"kind\":\"note\",\"title\":\"storybound.preview.note.title\"" + style + "}");
        var definition = DocumentDefinition.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        if (!legacy && !definition.styleId().equals(Storybound.id("note"))) {
            throw new IllegalStateException("Default note style missing");
        }
        var contract = DocumentDefinition.CODEC.parse(JsonOps.INSTANCE,
                JsonParser.parseString("{\"kind\":\"contract\",\"title\":\"Contract\"}")).getOrThrow();
        if (!contract.styleId().equals(Storybound.id("contract"))) {
            throw new IllegalStateException("Default contract style missing");
        }
        DocumentRegistries.DOCUMENTS.clientStore().replace(Map.of(id, definition));
        String source = "**" + I18n.get("storybound.preview.note.title") + "**\n\n"
                + I18n.get("storybound.preview.note.body").replace("\\n", "\n");
        if (longText) source = (source + "\n\n").repeat(4);
        minecraft.setScreen(new DocumentScreen(id, null, source));
    }

    private static void showContract(Minecraft minecraft, int scale, boolean longText, boolean legacy, boolean longTitle) {
        minecraft.options.guiScale().set(scale);
        minecraft.resizeDisplay();
        var id = Storybound.id("preview_contract_reader");
        var json = JsonParser.parseString("""
                {
                  "kind": "contract",
                  "title": "storybound.preview.contract.title",
                  "contract": {
                    "conditions": "storybound.preview.contract.conditions",
                    "reward": "storybound.preview.contract.reward",
                    "hint": "storybound.preview.contract.hint"
                  }
                }
                """).getAsJsonObject();
        if (legacy) json.addProperty("style", "storybound:note_torn");
        if (longTitle) json.addProperty("title", "storybound.preview.contract.long_title");
        var definition = DocumentDefinition.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        DocumentRegistries.DOCUMENTS.clientStore().replace(Map.of(id, definition));
        String key = "storybound.preview.contract.body";
        if (!I18n.exists(key)) throw new IllegalStateException("Contract fixture translation missing");
        String source = I18n.get(key).replace("\\n", "\n");
        if (longText) source = (source + "\n\n").repeat(6);
        minecraft.setScreen(new DocumentScreen(id, null, source));
    }

    private static void showLetter(Minecraft minecraft, int scale, boolean longText) {
        minecraft.options.guiScale().set(scale);
        minecraft.resizeDisplay();
        var id = Storybound.id("preview_letter_reader");
        var json = JsonParser.parseString("{\"kind\":\"letter\",\"title\":\"storybound.preview.letter.title\"}");
        var definition = DocumentDefinition.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        DocumentRegistries.DOCUMENTS.clientStore().replace(Map.of(id, definition));
        String key = "storybound.preview.letter.body";
        if (!I18n.exists(key)) throw new IllegalStateException("Letter fixture translation missing");
        String source = I18n.get(key).replace("\\n", "\n");
        if (longText) source = (source + "\n\n").repeat(4);
        minecraft.setScreen(new DocumentScreen(id, null, source));
    }
}
