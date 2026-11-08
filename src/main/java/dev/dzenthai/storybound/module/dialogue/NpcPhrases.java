package dev.dzenthai.storybound.module.dialogue;

import dev.dzenthai.storybound.core.logic.StoryTrace;
import dev.dzenthai.storybound.core.text.StoryText;
import dev.dzenthai.storybound.network.SpeechBubblePayload;
import dev.dzenthai.storybound.network.StoryboundNetwork;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;

/**
 * An NPC's voice for its phrases (November 5, 2026): which of a {@link PhrasesDefinition}'s lines comes next. Chatter
 * waits until the NPC has said nothing for {@link #LINE_GAP_TICKS}; a hit's phrase cuts in at once (before, it waited
 * for the chatter's bubble to fade), only not more often than every {@link #HIT_GAP_TICKS}, so quick hits do not fill
 * the air with bubbles. Where the list stands is kept per counter: an NPC's chatter has one,
 * each player who hits it another, so the same phrases given to five NPCs go on for each on its own.
 */
public final class NpcPhrases {
    /** Chatter speaks only after the NPC has said nothing for this long. */
    static final int LINE_GAP_TICKS = 60;
    /** Two phrases of the same counter (a player's hits) at least this far apart. */
    static final int HIT_GAP_TICKS = 20;
    /** A counter unused this long is forgotten. */
    private static final int FORGET_TICKS = 20 * 60 * 10;

    /** Per counter: the next line in order, or the last one said at random. */
    private static final Map<String, Integer> POSITION = new HashMap<>();
    private static final Map<String, Long> USED = new HashMap<>();
    private static final Map<UUID, Long> LAST_SPOKE = new HashMap<>();
    /** NPC → the tick its last bubble fades: until then it is still speaking (it does not go to bed). */
    private static final Map<UUID, Long> SPEAKS_UNTIL = new HashMap<>();

    private NpcPhrases() {}

    /**
     * Says the next of the phrases over the NPC, to every player within bubble range, each in their own language.
     * {@code cutIn} (a hit, a click): it speaks at once, whatever it said before, only not more often than every
     * {@link #HIT_GAP_TICKS} for the same counter; otherwise (chatter) only after the NPC has been quiet for
     * {@link #LINE_GAP_TICKS}.
     *
     * @param player who the story texts are resolved for, and in whose trace it is noted
     * @return whether something was said
     */
    static boolean say(LivingEntity npc, PhrasesRef ref, String counter, ServerPlayer player, long now, boolean cutIn) {
        PhrasesDefinition phrases = ref.resolve();
        boolean tooSoon = cutIn ? now - USED.getOrDefault(counter, Long.MIN_VALUE / 2) < HIT_GAP_TICKS : now - lastSpoke(npc) < LINE_GAP_TICKS;
        if (phrases == null || phrases.lines().isEmpty() || tooSoon) {
            return false;
        }
        List<StoryText> lines = phrases.lines();
        int index;
        if (phrases.order() == PhrasesDefinition.Order.SEQUENCE) {
            int next = POSITION.getOrDefault(counter, 0);
            index = Math.min(next, lines.size() - 1);
            POSITION.put(counter, next + 1);
        } else if (phrases.order() == PhrasesDefinition.Order.LOOP) {
            // Round: after the last, the first again (kept within the lines, so a list made shorter goes on from its top).
            index = POSITION.getOrDefault(counter, 0) % lines.size();
            POSITION.put(counter, (index + 1) % lines.size());
        } else {
            int last = POSITION.getOrDefault(counter, -1);
            index = npc.getRandom().nextInt(lines.size());
            if (lines.size() > 1 && index == last) {
                index = (index + 1 + npc.getRandom().nextInt(lines.size() - 1)) % lines.size();
            }
            POSITION.put(counter, index);
        }
        USED.put(counter, now);
        StoryText text = lines.get(index);
        for (ServerPlayer viewer : ((ServerLevel) npc.level()).players()) {
            if (viewer.distanceToSqr(npc) <= Dialogues.BUBBLE_RANGE * Dialogues.BUBBLE_RANGE) {
                StoryboundNetwork.sendToPlayer(viewer, new SpeechBubblePayload(npc.getId(), text.resolve(viewer), -1, ThinkTime.NONE));
            }
        }
        StoryTrace.note(player, ref.describe(), "line " + (index + 1) + " said by " + npc.getName().getString());
        LAST_SPOKE.put(npc.getUUID(), now);
        SPEAKS_UNTIL.put(npc.getUUID(), now + bubbleTicks(text.resolve(player).toComponent().getString()));
        return true;
    }

    /**
     * How long a bubble with this text stays over a head, in ticks: as the client shows it ({@code SpeechBubbles}, 2.5 s
     * and 60 ms a letter, 3 to 10 s). The server does not translate: a translation key counts by its own length, near
     * enough.
     */
    static long bubbleTicks(String text) {
        return Math.clamp(2500L + 60L * text.length(), 3000L, 10000L) / 50L;
    }

    /** Whether the NPC is still saying a phrase: its bubble has not faded yet. */
    public static boolean speaking(LivingEntity npc, long now) {
        return now < SPEAKS_UNTIL.getOrDefault(npc.getUUID(), Long.MIN_VALUE);
    }

    /** The tick the NPC last said something of its phrases. */
    static long lastSpoke(LivingEntity npc) {
        return LAST_SPOKE.getOrDefault(npc.getUUID(), Long.MIN_VALUE / 2);
    }

    /** Starts a counter over: the next phrase in order is the first again. */
    static void forget(String counter) {
        POSITION.remove(counter);
        USED.remove(counter);
    }

    /** Where a counter stands: the next line in order, or the last said at random; -1 when unused. For tests. */
    public static int position(String counter) {
        return POSITION.getOrDefault(counter, -1);
    }

    static String hitCounter(LivingEntity npc, ServerPlayer player) {
        return "hit:" + npc.getUUID() + ":" + player.getUUID();
    }

    static String warningCounter(LivingEntity npc, ServerPlayer player) {
        return "warning:" + npc.getUUID() + ":" + player.getUUID();
    }

    /** For tests: the counter of the warnings to a player. */
    public static String warningCounterFor(LivingEntity npc, ServerPlayer player) {
        return warningCounter(npc, player);
    }

    static String chatterCounter(LivingEntity npc) {
        return "chatter:" + npc.getUUID();
    }

    /** A chatter variant's own place in its list (each variant goes on by itself); -1 is the chatter's own phrases. */
    static String chatterCounter(LivingEntity npc, int variant) {
        return variant < 0 ? chatterCounter(npc) : chatterCounter(npc) + ":" + variant;
    }

    /**
     * Phrases said on a click (November 6, 2026): per player and per click entry (its binding and its place in that
     * binding's list; the binding since November 7, 2026, as an NPC's entries now come from several), so each player
     * goes through each entry's lines in order on their own.
     *
     * @param binding the binding of the entry; null for the one stored on the entity
     */
    static String clickCounter(Entity npc, ServerPlayer player, @Nullable ResourceLocation binding, int entry) {
        return "click:" + npc.getUUID() + ":" + player.getUUID() + ":" + (binding != null ? binding : "own") + ":" + entry;
    }

    /**
     * The counter of an NPC's busy line for a player (November 9, 2026): the binding that gave {@code busy}, its own
     * order of lines; public for tests.
     */
    public static String busyCounter(Entity npc, ServerPlayer player, @Nullable ResourceLocation binding) {
        return "busy:" + npc.getUUID() + ":" + player.getUUID() + ":" + (binding != null ? binding : "own");
    }

    /** For tests: the counter of the phrases a player gets on clicking the NPC, for an entry of a binding at that place. */
    public static String clickCounterFor(Entity npc, ServerPlayer player, @Nullable ResourceLocation binding, int entry) {
        return clickCounter(npc, player, binding, entry);
    }

    /** For tests: the counter of a chatter variant (-1: the chatter's own phrases). */
    public static String chatterCounterFor(LivingEntity npc, int variant) {
        return chatterCounter(npc, variant);
    }

    static void prune(long now) {
        USED.entrySet().removeIf(entry -> {
            boolean old = now - entry.getValue() > FORGET_TICKS;
            if (old) {
                POSITION.remove(entry.getKey());
            }
            return old;
        });
        LAST_SPOKE.values().removeIf(tick -> now - tick > FORGET_TICKS);
        SPEAKS_UNTIL.values().removeIf(tick -> tick < now);
    }

    static void reset() {
        POSITION.clear();
        USED.clear();
        LAST_SPOKE.clear();
        SPEAKS_UNTIL.clear();
    }

    /** For tests: the counter of a player's hits on an NPC. */
    public static String hitCounterFor(LivingEntity npc, ServerPlayer player) {
        return hitCounter(npc, player);
    }

    /** For tests: the counter of an NPC's chatter. */
    public static String chatterCounterFor(LivingEntity npc) {
        return chatterCounter(npc);
    }
}
