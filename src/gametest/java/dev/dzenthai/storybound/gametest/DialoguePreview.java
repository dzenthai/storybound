package dev.dzenthai.storybound.gametest;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.dialogue.ClientDialogue;
import dev.dzenthai.storybound.client.dialogue.DialogueScreen;
import dev.dzenthai.storybound.core.text.ResolvedText;
import dev.dzenthai.storybound.module.dialogue.CameraCue;
import dev.dzenthai.storybound.module.dialogue.DialogueDefinition;
import dev.dzenthai.storybound.module.dialogue.ThinkTime;
import dev.dzenthai.storybound.network.DialogueChoicesPayload;
import dev.dzenthai.storybound.network.DialogueLinePayload;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.lwjgl.glfw.GLFW;

/** Client-only synthetic conversation checks. No connection, saved world or reply packets. */
@EventBusSubscriber(modid = Storybound.MOD_ID, value = Dist.CLIENT)
public final class DialoguePreview {
    private static int ticks;
    private static int captures;
    private static boolean started;
    private static boolean ready;
    private static int session;

    private DialoguePreview() {}

    @SubscribeEvent
    public static void background(ScreenEvent.Render.Pre event) {
        if (!Boolean.getBoolean("storybound.dialoguePreview") || !(event.getScreen() instanceof DialogueScreen)) return;
        var graphics = event.getGuiGraphics();
        graphics.blit(ResourceLocation.withDefaultNamespace("textures/gui/title/background/panorama_0.png"),
                0, 0, graphics.guiWidth(), graphics.guiHeight(), 0, 0, 1024, 1024, 1024, 1024);
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("storybound.dialoguePreview")) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getOverlay() != null) return;
        if (!started) {
            started = true;
            language(minecraft, "en_us");
            return;
        }
        if (!ready) return;
        ticks++;
        if (ticks == 10) show(minecraft, 1, true, 3, false);
        if (ticks == 20) {
            screen(minecraft).keyPressed(GLFW.GLFW_KEY_TAB, 0, 0);
        }
        if (ticks == 25) capture(minecraft, "dialogue-en-gui-1.png");
        if (ticks == 35) {
            minecraft.options.guiScale().set(3);
            minecraft.resizeDisplay();
        }
        if (ticks == 50) capture(minecraft, "dialogue-en-gui-3.png");
        if (ticks == 60) show(minecraft, 3, false, 0, true);
        if (ticks == 75) capture(minecraft, "dialogue-long.png");
        if (ticks == 80) {
            if (!screen(minecraft).keyPressed(GLFW.GLFW_KEY_PAGE_DOWN, 0, 0)) throw new IllegalStateException("Page Down not handled");
        }
        if (ticks == 95) capture(minecraft, "dialogue-long-scrolled.png");
        if (ticks == 100) show(minecraft, 3, true, 9, false);
        if (ticks == 110) {
            for (int i = 0; i < 8; i++) screen(minecraft).keyPressed(GLFW.GLFW_KEY_TAB, 0, 0);
            if (!(screen(minecraft).getFocused() instanceof Button button) || !button.getMessage().getString().startsWith("9.")) {
                throw new IllegalStateException("Keyboard traversal did not reach the last offscreen enabled answer");
            }
        }
        if (ticks == 125) capture(minecraft, "dialogue-many-answers.png");
        if (ticks == 130) language(minecraft, "ru_ru");
        if (ticks == 140) show(minecraft, 0, true, 3, false);
        if (ticks == 155) capture(minecraft, "dialogue-ru.png");
        if (ticks == 160) {
            ClientDialogue.clear();
            ClientDialogue.onChoices(new DialogueChoicesPayload(++session, 1, true,
                    List.of(new DialogueChoicesPayload.Shown(text("answer_one"), true, Optional.empty())), 0, CameraCue.OFF));
        }
        if (ticks == 175) capture(minecraft, "dialogue-choices-only.png");
        if (ticks == 180) {
            show(minecraft, 2, false, 0, false);
        }
        if (ticks == 195) capture(minecraft, "dialogue-no-portrait.png");
        if (ticks == 205) {
            show(minecraft, 3, true, 3, false);
            ClientDialogue.onChoices(new DialogueChoicesPayload(session, 3, true,
                    List.of(new DialogueChoicesPayload.Shown(text("answer_one"), true, Optional.empty()),
                            new DialogueChoicesPayload.Shown(text("answer_locked"), false, Optional.of(text("locked_hint")))),
                    600, CameraCue.OFF));
        }
        if (ticks == 220) capture(minecraft, "dialogue-timed.png");
        if (ticks == 225) GLFW.glfwSetWindowSize(minecraft.getWindow().getWindow(), 854, 480);
        if (ticks == 235) show(minecraft, 0, true, 3, false);
        if (ticks == 250) capture(minecraft, "dialogue-small-window.png");
        if (ticks == 260) {
            GLFW.glfwSetWindowSize(minecraft.getWindow().getWindow(), 1280, 800);
            language(minecraft, "en_us");
        }
        if (ticks == 270) showLongAnswers(minecraft, false);
        if (ticks == 285) capture(minecraft, "dialogue-long-answers.png");
        if (ticks == 290) {
            for (int i = 0; i < 6; i++) screen(minecraft).keyPressed(GLFW.GLFW_KEY_TAB, 0, 0);
            if (!(screen(minecraft).getFocused() instanceof Button button) || !button.getMessage().getString().startsWith("6."))
                throw new IllegalStateException("Long answer traversal did not reach the sixth answer");
        }
        if (ticks == 305) capture(minecraft, "dialogue-long-answers-scrolled.png");
        if (ticks == 310) showLongAnswers(minecraft, true);
        if (ticks == 325) capture(minecraft, "dialogue-long-both.png");
        if (ticks == 340) {
            if (captures != 13) throw new IllegalStateException("Missing dialogue captures");
            ClientDialogue.clear();
            Storybound.LOGGER.info("Dialogue preview passed: thirteen captures, GUI scales, localization, long text, long answers, combined overflow, keyboard scrolling, locked answer, timed choices, small window and choices-only presentation");
            minecraft.stop();
        }
    }

    private static void showLongAnswers(Minecraft minecraft, boolean longBody) {
        show(minecraft, 1, true, 0, longBody);
        List<DialogueChoicesPayload.Shown> options = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String answer = (text("answer_long").toComponent().getString() + " ").repeat(i == 5 ? 10 : 3);
            options.add(new DialogueChoicesPayload.Shown(ResolvedText.literal(answer), true, Optional.empty()));
        }
        ClientDialogue.onChoices(new DialogueChoicesPayload(session, 2, true, options, 0, CameraCue.OFF));
    }

    private static ResolvedText text(String suffix) {
        return new ResolvedText("storybound.preview.dialogue." + suffix, false, List.of(), Optional.empty(), Optional.empty());
    }

    private static void show(Minecraft minecraft, int scale, boolean portrait, int answers, boolean longText) {
        minecraft.options.guiScale().set(scale);
        minecraft.resizeDisplay();
        ClientDialogue.clear();
        Component body = text("body").toComponent();
        if (longText) body = Component.literal((body.getString() + "\n\n").repeat(12));
        ClientDialogue.onLine(new DialogueLinePayload(++session, 1, DialogueDefinition.Presentation.SCREEN, true,
                new DialogueLinePayload.Speaker(Optional.of(text("name")), Optional.empty(), -1,
                        portrait ? Optional.of(ResourceLocation.fromNamespaceAndPath("storybound_preview", "textures/dialogue/portrait.png")) : Optional.empty()),
                ResolvedText.literal(body.getString()), false, ThinkTime.NONE, CameraCue.OFF));
        // The first press reveals text without dispatching an advance packet.
        screen(minecraft).keyPressed(GLFW.GLFW_KEY_SPACE, 0, 0);
        if (!ClientDialogue.line().isRevealed()) throw new IllegalStateException("Reveal key did not reveal the line");
        if (answers > 0) {
            List<DialogueChoicesPayload.Shown> options = new ArrayList<>();
            for (int i = 0; i < answers; i++) {
                options.add(new DialogueChoicesPayload.Shown(text(i == 0 ? "answer_one" : i == 2 ? "answer_locked" : "answer_long"),
                        i != 2, i == 2 ? Optional.of(text("locked_hint")) : Optional.empty()));
            }
            ClientDialogue.onChoices(new DialogueChoicesPayload(session, 2, true, options, 0, CameraCue.OFF));
            ClientDialogue.choose(2);
            // A disabled choice must be ignored; attempting a packet in this offline fixture would fail.
            if (ClientDialogue.choices().options().get(2).enabled()) throw new IllegalStateException("Disabled answer lost its state");
        }
    }

    private static DialogueScreen screen(Minecraft minecraft) {
        if (!(minecraft.screen instanceof DialogueScreen screen)) throw new IllegalStateException("Dialogue screen missing");
        return screen;
    }

    private static void capture(Minecraft minecraft, String file) {
        Screenshot.grab(minecraft.gameDirectory, file, minecraft.getMainRenderTarget(), message -> {});
        captures++;
    }

    private static void language(Minecraft minecraft, String code) {
        ready = false;
        minecraft.options.languageCode = code;
        minecraft.getLanguageManager().setSelected(code);
        minecraft.reloadResourcePacks().thenRun(() -> minecraft.execute(() -> ready = true));
    }
}
