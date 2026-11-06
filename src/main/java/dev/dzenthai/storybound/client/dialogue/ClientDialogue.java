package dev.dzenthai.storybound.client.dialogue;

import dev.dzenthai.storybound.client.StoryboundClientConfig;
import dev.dzenthai.storybound.client.camera.DialogueCamera;
import dev.dzenthai.storybound.core.text.ResolvedText;
import dev.dzenthai.storybound.module.dialogue.DialogueDefinition.Presentation;
import dev.dzenthai.storybound.module.dialogue.ThinkTime;
import dev.dzenthai.storybound.network.DialogueChoicesPayload;
import dev.dzenthai.storybound.network.DialogueLinePayload;
import dev.dzenthai.storybound.network.DialogueReplyPayload;
import java.util.List;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/**
 * The local player's dialogue as the server last sent it: one line and/or one set of options.
 * Replies name the session and step they answer; the server ignores stale ones.
 */
public final class ClientDialogue {
    /** How long a HUD subtitle stays after it is fully shown, per character, before it moves on by itself. */
    private static final long READ_MILLIS_PER_CHAR = 55;
    private static final long MIN_READ_MILLIS = 1800;
    public static final long CHOICE_HOLD_MILLIS = 0;
    public static final long CHOICE_FADE_MILLIS = 450;
    public static final long CHOICE_TOTAL_MILLIS = CHOICE_HOLD_MILLIS + CHOICE_FADE_MILLIS;

    private static int session = -1;
    private static Presentation presentation = Presentation.SCREEN;
    private static boolean closable = true;
    private static @Nullable Line line;
    private static @Nullable Choices choices;
    private static @Nullable Chosen chosen;
    /** The step already answered, so a double click sends one reply. */
    private static int answered = -1;
    /** When the player picked an option and how long its text was: an NPC answer waits for {@link ThinkTime}. */
    private static long choseAt;
    private static int choiceLength;
    /** The choice waits for the talk key (subtitles, bubbles): a prompt until its window is opened. */
    private static boolean asks;
    private static boolean choiceOpen;
    /** While its window is open, what its timer had left (0 to 1): it counts only the time to open it. */
    private static float pausedLeft = 1f;

    private ClientDialogue() {}

    public static boolean isActive() {
        return session >= 0;
    }

    public static Presentation presentation() {
        return presentation;
    }

    public static boolean closable() {
        return closable;
    }

    public static @Nullable Line line() {
        return line;
    }

    public static @Nullable Choices choices() {
        return choices;
    }

    public static @Nullable Chosen chosen() {
        Chosen current = chosen;
        if (current != null && Util.getMillis() - current.chosenAt() >= CHOICE_TOTAL_MILLIS) {
            chosen = null;
            return null;
        }
        return current;
    }

    public static void onLine(DialogueLinePayload payload) {
        if (payload.session() != session) {
            choices = null;
        }
        session = payload.session();
        presentation = payload.presentation();
        closable = payload.closable();
        DialogueLinePayload.Speaker speaker = payload.speaker();
        Component name = speaker.name().map(ResolvedText::toComponent).or(speaker::entityName).orElse(Component.empty());
        long now = Util.getMillis();
        long startAt = now;
        if (payload.think() != ThinkTime.NONE) {
            startAt = Math.max(startAt, (choseAt > 0 ? choseAt : now) + ThinkTime.resolve(payload.think(), choiceLength, StoryboundClientConfig.INSTANCE.thinkPause.get()));
        }
        line = new Line(payload.step(), speaker, name, payload.text().toComponent(), payload.timed(), startAt);
        choices = null;
        DialogueCamera.onLine(payload.camera(), speaker.entityId());
        Minecraft minecraft = Minecraft.getInstance();
        if (presentation == Presentation.SCREEN) {
            openScreen();
        } else if (minecraft.screen instanceof DialogueScreen) {
            // A HUD dialogue went back to subtitles after a choice.
            minecraft.setScreen(null);
        }
    }

    public static void onChoices(DialogueChoicesPayload payload) {
        if (payload.session() != session) {
            line = null;
            presentation = Presentation.SCREEN;
        }
        session = payload.session();
        closable = payload.closable();
        List<Option> options = payload.options().stream()
                .map(shown -> new Option(shown.text().toComponent(), shown.enabled(), shown.hint().map(ResolvedText::toComponent).orElse(null)))
                .toList();
        choices = new Choices(payload.step(), options, payload.timeoutTicks(), Util.getMillis());
        chosen = null;
        asks = payload.asks();
        choiceOpen = false;
        if (asks && presentation == Presentation.SCREEN) {
            // A conversation in subtitles that begins with a choice.
            presentation = Presentation.HUD;
        }
        if (line != null) {
            line.revealAll();
        }
        DialogueCamera.onChoices(payload.camera());
        if (!asks) {
            openScreen();
        }
    }

    /**
     * A choice in subtitles or bubbles waits for the player (November 6, 2026): "[H] Answer" over the subtitles, its
     * timer running, until the talk key opens its window; before, the window opened by itself, even mid-fight.
     */
    public static boolean waitsForAnswer() {
        return choices != null && asks && !choiceOpen && answered != choices.step();
    }

    /** Whether the choice's timer runs now: not while a window opened from the prompt is open. */
    public static boolean timerRunning() {
        return !(asks && choiceOpen);
    }

    /** The talk key on the prompt: the window opens, its timer stops. */
    public static void openChoices() {
        Choices current = choices;
        if (current == null || !waitsForAnswer()) {
            return;
        }
        choiceOpen = true;
        pausedLeft = current.timeLeft();
        PacketDistributor.sendToServer(new DialogueReplyPayload(session, current.step(), DialogueReplyPayload.OPEN));
        openScreen();
    }

    /** Whether ESC in the window goes back to the prompt rather than leaving. */
    public static boolean backToPrompt() {
        return choices != null && asks && choiceOpen;
    }

    /** ESC in a window opened from the prompt: back to it, the timer going on from what it had left. */
    public static void hideChoices() {
        Choices current = choices;
        if (current == null || !backToPrompt()) {
            return;
        }
        choiceOpen = false;
        if (current.timeoutTicks() > 0) {
            long spent = Math.round((1f - pausedLeft) * current.timeoutTicks() * 50f);
            choices = new Choices(current.step(), current.options(), current.timeoutTicks(), Util.getMillis() - spent);
        }
        PacketDistributor.sendToServer(new DialogueReplyPayload(session, current.step(), DialogueReplyPayload.HIDE));
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof DialogueScreen) {
            minecraft.setScreen(null);
        }
    }

    public static void onClose(int closed) {
        if (closed != session) {
            return;
        }
        clear();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof DialogueScreen) {
            minecraft.setScreen(null);
        }
    }

    /** Moves past the current line, if it waits for that. */
    public static void advance() {
        if (line != null && choices == null && answered != line.step()) {
            answered = line.step();
            PacketDistributor.sendToServer(new DialogueReplyPayload(session, line.step(), DialogueReplyPayload.ADVANCE));
        }
    }

    public static void choose(int index) {
        choose(index, 0, 0, 240);
    }

    public static void choose(int index, int x, int y, int width) {
        if (choices != null && index >= 0 && index < choices.options().size() && choices.options().get(index).enabled() && answered != choices.step()) {
            answered = choices.step();
            choseAt = Util.getMillis();
            Component text = choices.options().get(index).text();
            choiceLength = text.getString().length();
            chosen = new Chosen(index + 1, text, choseAt, x, y, width);
            PacketDistributor.sendToServer(new DialogueReplyPayload(session, choices.step(), index));
        }
    }

    public static void leave() {
        if (isActive() && closable) {
            PacketDistributor.sendToServer(new DialogueReplyPayload(session, 0, DialogueReplyPayload.LEAVE));
        }
    }

    /** HUD subtitles move on by themselves once read; timed lines are moved on by the server. */
    public static void tick() {
        Line current = line;
        if (current == null || choices != null || (presentation != Presentation.HUD && presentation != Presentation.BUBBLE) || current.timed()) {
            return;
        }
        long revealedAt = current.revealedAt();
        if (revealedAt >= 0) {
            long readTime = Math.max(MIN_READ_MILLIS, current.text().getString().length() * READ_MILLIS_PER_CHAR);
            if (Util.getMillis() - revealedAt >= readTime) {
                advance();
            }
        }
    }

    public static void clear() {
        session = -1;
        line = null;
        choices = null;
        asks = false;
        choiceOpen = false;
        answered = -1;
        presentation = Presentation.SCREEN;
        choseAt = 0;
        DialogueCamera.stop();
        // The NPC may have nothing more to say, or something new: ask again.
        TalkHint.clear();
    }

    private static void openScreen() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.screen instanceof DialogueScreen)) {
            minecraft.setScreen(new DialogueScreen());
        }
    }

    /** A shown line with its typewriter state. */
    public static final class Line {
        private final int step;
        private final DialogueLinePayload.Speaker speaker;
        private final Component name;
        private final Component text;
        private final boolean timed;
        /** When printing starts: after the NPC's thinking pause, if any. */
        private final long startAt;
        private final int length;
        private long revealedAt = -1;

        Line(int step, DialogueLinePayload.Speaker speaker, Component name, Component text, boolean timed, long startAt) {
            this.step = step;
            this.speaker = speaker;
            this.name = name;
            this.text = text;
            this.timed = timed;
            this.startAt = startAt;
            this.length = text.getString().length();
        }

        public int step() {
            return step;
        }

        public DialogueLinePayload.Speaker speaker() {
            return speaker;
        }

        public Component name() {
            return name;
        }

        public Component text() {
            return text;
        }

        public boolean timed() {
            return timed;
        }

        /** Characters the typewriter has printed so far. */
        public int visibleChars() {
            if (revealedAt >= 0) {
                return Integer.MAX_VALUE;
            }
            if (isThinking()) {
                return 0;
            }
            int speed = StoryboundClientConfig.INSTANCE.textSpeed.get();
            long shown = speed <= 0 ? Integer.MAX_VALUE : (Util.getMillis() - startAt) * speed / 1000;
            if (shown >= length) {
                revealAll();
                return Integer.MAX_VALUE;
            }
            return (int) shown;
        }

        /** The NPC is still "thinking" about the player's option: dots instead of the line. */
        public boolean isThinking() {
            return revealedAt < 0 && Util.getMillis() < startAt;
        }

        public boolean isRevealed() {
            visibleChars();
            return revealedAt >= 0;
        }

        public void revealAll() {
            if (revealedAt < 0) {
                revealedAt = Util.getMillis();
            }
        }

        long revealedAt() {
            visibleChars();
            return revealedAt;
        }
    }

    public record Choices(int step, List<Option> options, int timeoutTicks, long shownAt) {
        /** 1 when just shown, 0 when the timer runs out; always 1 without a timer. */
        public float timeLeft() {
            if (timeoutTicks <= 0) {
                return 1f;
            }
            float elapsed = (Util.getMillis() - shownAt) / 50f;
            return Math.max(0f, 1f - elapsed / timeoutTicks);
        }
    }

    public record Option(Component text, boolean enabled, @Nullable Component hint) {}

    public record Chosen(int number, Component text, long chosenAt, int x, int y, int width) {
        public float alpha(long now) {
            long elapsed = now - chosenAt;
            if (elapsed < 0 || elapsed >= CHOICE_TOTAL_MILLIS) {
                return 0f;
            }
            if (elapsed <= CHOICE_HOLD_MILLIS) {
                return 1f;
            }
            float t = (elapsed - CHOICE_HOLD_MILLIS) / (float) CHOICE_FADE_MILLIS;
            return Math.max(0f, 1f - t * t * (3f - 2f * t));
        }
    }
}
