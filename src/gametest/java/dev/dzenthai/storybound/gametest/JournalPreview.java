package dev.dzenthai.storybound.gametest;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.ClientJournal;
import dev.dzenthai.storybound.client.menu.AdventureMenu;
import dev.dzenthai.storybound.client.menu.AdventureMenuScreen;
import dev.dzenthai.storybound.core.text.ResolvedText;
import dev.dzenthai.storybound.module.journal.JournalNote;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Reproducible screenshots of the actual renderer; excluded from the release jar. */
@EventBusSubscriber(modid = Storybound.MOD_ID, value = Dist.CLIENT)
public final class JournalPreview {
    private static boolean started;
    private static boolean ready;
    private static int ticks;

    private JournalPreview() {}

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("storybound.uiPreview")) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getOverlay() != null) return;
        if (!started) {
            started = true;
            minecraft.options.languageCode = "ru_ru";
            minecraft.getLanguageManager().setSelected("ru_ru");
            minecraft.options.guiScale().set(2);
            minecraft.resizeDisplay();
            minecraft.reloadResourcePacks().thenRun(() -> minecraft.execute(() -> ready = true));
            return;
        }
        if (!ready) return;
        ticks++;
        if (ticks == 10) show(minecraft, false);
        if (ticks == 40) capture(minecraft, "journal-wide.png");
        if (ticks == 60) {
            minecraft.options.guiScale().set(3);
            minecraft.resizeDisplay();
            show(minecraft, false);
        }
        if (ticks == 90) capture(minecraft, "journal-compact.png");
        if (ticks == 110) show(minecraft, true);
        if (ticks == 140) capture(minecraft, "journal-long.png");
        if (ticks == 150 && minecraft.screen instanceof AdventureMenuScreen screen) {
            var layout = screen.bookLayout();
            for (int i = 0; i < 20; i++) {
                screen.mouseScrolled(screen.bookViewport().toGuiX(layout.detailLeft() + 10), screen.bookViewport().toGuiY(layout.content().y() + 20), 0, -1);
            }
            screen.mouseScrolled(screen.bookViewport().toGuiX(layout.content().x() + 10), screen.bookViewport().toGuiY(layout.content().y() + 20), 0, -1);
        }
        if (ticks == 170) capture(minecraft, "journal-scrolled.png");
        if (ticks == 180 && minecraft.screen instanceof AdventureMenuScreen screen) {
            var bounds = screen.bookLayout().bookmark(0, AdventureMenu.availableTabs().size());
            if (!screen.mouseClicked(screen.bookViewport().toGuiX(bounds.x() + bounds.width() / 2.0), screen.bookViewport().toGuiY(bounds.y() + 10), 0)) {
                throw new IllegalStateException("Bookmark did not handle its click");
            }
        }
        if (ticks == 200) capture(minecraft, "journal-tab-switch.png");
        if (ticks == 210) {
            ClientJournal.apply(true, List.of(), List.of());
            minecraft.setScreen(new AdventureMenuScreen(AdventureMenu.JOURNAL));
        }
        if (ticks == 240) capture(minecraft, "journal-empty.png");
        if (ticks == 250) showQuest(minecraft);
        if (ticks == 275) capture(minecraft, "journal-quest-controls.png");
        if (ticks == 280) {
            minecraft.options.guiScale().set(2);
            minecraft.resizeDisplay();
            showQuest(minecraft);
        }
        if (ticks == 305) capture(minecraft, "journal-quest-wide.png");
        if (ticks == 315) showDocument(minecraft);
        if (ticks == 340) capture(minecraft, "journal-document-controls.png");
        if (ticks == 360) {
            ready = false;
            minecraft.options.languageCode = "en_us";
            minecraft.getLanguageManager().setSelected("en_us");
            minecraft.reloadResourcePacks().thenRun(() -> minecraft.execute(() -> ready = true));
        }
        if (ticks == 380) showQuest(minecraft);
        if (ticks == 410) capture(minecraft, "dark-quest-english.png");
        if (ticks == 420 && minecraft.screen instanceof AdventureMenuScreen screen) {
            screen.setFocused(screen.children().get(1));
        }
        if (ticks == 421 || ticks == 423 || ticks == 426) capture(minecraft, "ribbon-focus-" + ticks + ".png");
        if (ticks == 430 && minecraft.screen instanceof AdventureMenuScreen screen) {
            var bounds = screen.bookLayout().bookmark(1, AdventureMenu.availableTabs().size());
            screen.mouseClicked(screen.bookViewport().toGuiX(bounds.x() + bounds.width() / 2.0), screen.bookViewport().toGuiY(bounds.y() + 10), 0);
        }
        if (ticks == 431 || ticks == 433 || ticks == 436) capture(minecraft, "ribbon-select-" + ticks + ".png");
        if (ticks == 450) {
            showQuest(minecraft);
            var preview = Storybound.id("preview_quest");
            var chapterQuest = ResourceLocation.fromNamespaceAndPath("demo", "harvest");
            var definition = dev.dzenthai.storybound.module.quest.QuestRegistries.QUESTS.clientStore().getOrNull(preview);
            dev.dzenthai.storybound.module.quest.QuestRegistries.QUESTS.clientStore().replace(java.util.Map.of(chapterQuest, definition));
            dev.dzenthai.storybound.client.ClientQuests.apply(java.util.Map.of(chapterQuest,
                    dev.dzenthai.storybound.core.state.QuestProgress.started(null)), List.of(), List.of());
            minecraft.setScreen(new AdventureMenuScreen(AdventureMenu.QUESTS));
        }
        if (ticks == 475) capture(minecraft, "dark-chapter-ford.png");
        if (ticks == 490 || ticks == 550) {
            minecraft.options.guiScale().set(ticks == 490 ? 1 : 0);
            minecraft.resizeDisplay();
            showQuest(minecraft);
        }
        if (ticks == 520) capture(minecraft, "book-gui-1.png");
        if (ticks == 580) capture(minecraft, "book-gui-auto.png");
        if (ticks == 590 && minecraft.screen instanceof AdventureMenuScreen screen) {
            minecraft.setLastInputType(net.minecraft.client.InputType.KEYBOARD_TAB);
            screen.setFocused(screen.children().get(1));
            var bounds = screen.bookLayout().bookmark(1, AdventureMenu.availableTabs().size());
            var window = minecraft.getWindow();
            double guiX = screen.bookViewport().toGuiX(bounds.x() + bounds.width() / 2.0);
            double guiY = screen.bookViewport().toGuiY(bounds.y() + 10);
            org.lwjgl.glfw.GLFW.glfwSetCursorPos(window.getWindow(),
                    guiX * window.getScreenWidth() / window.getGuiScaledWidth(),
                    guiY * window.getScreenHeight() / window.getGuiScaledHeight());
        }
        if (ticks == 610) capture(minecraft, "book-tooltip-auto.png");
        if (ticks == 620 && minecraft.screen instanceof AdventureMenuScreen screen) {
            var bounds = screen.bookLayout().bookmark(1, AdventureMenu.availableTabs().size());
            double x = screen.bookViewport().toGuiX(bounds.x() + bounds.width() / 2.0);
            double y = screen.bookViewport().toGuiY(bounds.y() + 10);
            if (!screen.mouseClicked(x, y, 0)) throw new IllegalStateException("Auto-scale bookmark missed its click");
            screen.mouseReleased(x, y, 0);
        }
        if (ticks == 645) capture(minecraft, "book-click-auto.png");
        if (ticks == 660) showQuest(minecraft, true);
        if (ticks == 685) capture(minecraft, "reward-choice-before.png");
        if (ticks == 700 && minecraft.screen instanceof AdventureMenuScreen screen) {
            screen.showTab(AdventureMenu.QUESTS);
            var claim = screen.children().stream()
                    .filter(widget -> widget instanceof net.minecraft.client.gui.components.Button)
                    .map(widget -> (net.minecraft.client.gui.components.Button) widget)
                    .filter(button -> button.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents text
                            && text.getKey().equals("storybound.quests.rewards.claim"))
                    .findFirst().orElseThrow(() -> new IllegalStateException("Claim control missing from reward fixture"));
            if (claim.active) throw new IllegalStateException("Claim enabled before choosing a reward");
            var layout = screen.bookLayout();
            boolean clicked = false;
            for (int y = layout.titleY(); y < layout.content().bottom() - 44; y++) {
                if (screen.mouseClicked(screen.bookViewport().toGuiX(layout.detailLeft() + 24), screen.bookViewport().toGuiY(y), 0)) {
                    clicked = true;
                    break;
                }
            }
            if (!clicked || !claim.active) throw new IllegalStateException("Reward choice did not enable Claim");
        }
        if (ticks == 720) capture(minecraft, "reward-choice-ready.png");
        if (ticks == 731) capture(minecraft, "reward-choice-particles.png");
        if (ticks == 750) {
            minecraft.getWindow().setWindowed(1920, 1080);
            minecraft.setScreen(new dev.dzenthai.storybound.client.menu.BookTextPreview());
        }
        if (ticks == 780) capture(minecraft, "bold-impression-1080.png");
        if (ticks == 800) minecraft.stop();
    }

    private static void showQuest(Minecraft minecraft) {
        showQuest(minecraft, false);
    }

    private static void showQuest(Minecraft minecraft, boolean choices) {
        var id = Storybound.id("preview_quest");
        var json = com.google.gson.JsonParser.parseString("""
                {"title":{"literal":"Preview quest"},
                 "description":{"literal":"Preview description"},
                 "difficulty":"easy","rewards":{"xp":10,"items":[{"item":"minecraft:bread","count":4}]}}
                """);
        {
            var object = json.getAsJsonObject();
            object.getAsJsonObject("title").addProperty("literal", previewText("quest.title"));
            object.getAsJsonObject("description").addProperty("literal", previewText("quest.description"));
        }
        if (choices) {
            var object = json.getAsJsonObject();
            object.getAsJsonObject("title").addProperty("literal", "Road supplies");
            object.remove("description");
            object.remove("difficulty");
            object.add("rewards", com.google.gson.JsonParser.parseString("""
                    {"choice":[{"item":"minecraft:bread","count":4},{"item":"minecraft:iron_ingot","count":2}]}
                    """));
        }
        var definition = dev.dzenthai.storybound.module.quest.QuestDefinition.CODEC
                .parse(com.mojang.serialization.JsonOps.INSTANCE, json).getOrThrow();
        dev.dzenthai.storybound.module.quest.QuestRegistries.QUESTS.clientStore().replace(java.util.Map.of(id, definition));
        dev.dzenthai.storybound.client.ClientQuests.apply(
                java.util.Map.of(id, choices ? new dev.dzenthai.storybound.core.state.QuestProgress(
                        dev.dzenthai.storybound.core.state.QuestStatus.COMPLETED, java.util.Optional.empty(), java.util.Map.of(), List.of())
                        : dev.dzenthai.storybound.core.state.QuestProgress.started(null)), List.of(id),
                List.of(new dev.dzenthai.storybound.module.quest.QuestRewardData.Pending(id, List.of())));
        minecraft.setScreen(new AdventureMenuScreen(AdventureMenu.QUESTS));
    }

    private static void showDocument(Minecraft minecraft) {
        var id = Storybound.id("preview_letter");
        var json = com.google.gson.JsonParser.parseString("""
                {"kind":"letter","title":"storybound.preview.document.title"}
                """);
        var definition = dev.dzenthai.storybound.module.document.DocumentDefinition.CODEC
                .parse(com.mojang.serialization.JsonOps.INSTANCE, json).getOrThrow();
        dev.dzenthai.storybound.module.document.DocumentRegistries.DOCUMENTS.clientStore().replace(java.util.Map.of(id, definition));
        ClientJournal.apply(true, List.of(id), List.of());
        minecraft.setScreen(new AdventureMenuScreen(AdventureMenu.JOURNAL));
    }

    private static void show(Minecraft minecraft, boolean longEntry) {
        List<JournalNote> notes = new ArrayList<>();
        String body = previewText("note.body");
        String title = previewText(longEntry ? "note.long_title" : "note.title");
        notes.add(note("night", "notes", title, longEntry ? body.repeat(16) : body));
        notes.add(note("elder", "notes", previewText("note.elder.title"), previewText("note.elder.body")));
        notes.add(note("tracks", "notes", previewText("note.tracks.title"), previewText("note.tracks.body")));
        notes.add(note("letter", "letters", previewText("document.title"), previewText("note.letter.body")));
        notes.add(note("chronicle", "letters", previewText("note.chronicle.title"), previewText("note.chronicle.body")));
        if (longEntry) {
            for (int i = 0; i < 40; i++) notes.add(note("extra_" + i, "notes", previewText("note.extra") + i, body));
        }
        ClientJournal.apply(true, List.of(), notes);
        minecraft.setScreen(new AdventureMenuScreen(AdventureMenu.JOURNAL));
    }

    private static JournalNote note(String id, String category, String title, String body) {
        ResourceLocation categoryId = Storybound.id(category);
        return new JournalNote(Storybound.id(id), categoryId, ResolvedText.literal(title), ResolvedText.literal(body));
    }

    private static String previewText(String key) {
        String translationKey = "storybound.preview." + key;
        if (!net.minecraft.client.resources.language.I18n.exists(translationKey)
                || !net.minecraft.client.resources.language.I18n.exists("storybound.journal.title")) {
            throw new IllegalStateException("Preview and production language resources must both be available");
        }
        return net.minecraft.client.resources.language.I18n.get(translationKey);
    }

    private static void capture(Minecraft minecraft, String name) {
        Screenshot.grab(minecraft.gameDirectory, name, minecraft.getMainRenderTarget(),
                message -> Storybound.LOGGER.info("UI preview: {}", message.getString()));
    }
}
