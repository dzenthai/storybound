package dev.dzenthai.storybound.module.dialogue;

import dev.dzenthai.storybound.core.logic.StoryContext;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

/**
 * NPC bindings' {@code chatter} (November 5, 2026): an NPC with players within its radius says one of its phrases now
 * and then, a random wait between the intervals apart. It keeps quiet while it is in a dialogue (with anyone, also
 * paused by a fight), while it sleeps (November 7, 2026), while it is angry at anyone (fighting, running, its kind's own anger), and right after it said something else; with nobody near it
 * keeps quiet too, and its phrases do not move on. Two NPCs near each other each wait their own random time, so they do
 * not speak in chorus. Looked at once a second.
 */
public final class NpcChatter {
    private static final int CHECK_TICKS = 20;
    /** Gone this long without a player near, the NPC waits afresh when one comes back (it does not speak at once). */
    private static final int AWAY_TICKS = 40;
    /** A busy NPC tries again this much later. */
    private static final int RETRY_TICKS = 100;

    private static final Map<UUID, Long> NEXT_AT = new HashMap<>();
    private static final Map<UUID, Long> LAST_NEAR = new HashMap<>();

    private NpcChatter() {}

    static void tick(MinecraftServer server) {
        long now = server.getTickCount();
        if (now % CHECK_TICKS != 0 || !Dialogues.isEnabled()) {
            return;
        }
        double reach = NpcBindings.chatterReach();
        if (reach <= 0) {
            return;
        }
        Set<Entity> seen = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.isSpectator()) {
                continue;
            }
            for (Entity entity : player.level().getEntities(player, player.getBoundingBox().inflate(reach),
                    candidate -> candidate instanceof LivingEntity && !(candidate instanceof Player) && candidate.isAlive())) {
                if (seen.add(entity)) {
                    NpcBindings.find(entity).flatMap(found -> found.binding().chatter())
                            .ifPresent(chatter -> consider((LivingEntity) entity, chatter, now));
                }
            }
        }
        if (now % 1200 == 0) {
            LAST_NEAR.entrySet().removeIf(entry -> {
                boolean gone = now - entry.getValue() > 1200;
                if (gone) {
                    NEXT_AT.remove(entry.getKey());
                }
                return gone;
            });
        }
    }

    private static void consider(LivingEntity npc, NpcBinding.Chatter chatter, long now) {
        ServerPlayer nearest = nearest(npc, chatter.radius());
        if (nearest == null) {
            return;
        }
        UUID id = npc.getUUID();
        Long lastNear = LAST_NEAR.put(id, now);
        Long next = NEXT_AT.get(id);
        if (next == null || lastNear == null || now - lastNear > AWAY_TICKS) {
            // Just came within reach: the first phrase after a random wait, not at once.
            NEXT_AT.put(id, now + random(npc, 0, chatter.minInterval()));
            return;
        }
        if (now < next) {
            return;
        }
        // Asleep in its bed, it says nothing (November 7, 2026; before, villagers chattered in their sleep); awake, it goes on.
        if (npc.isSleeping() || Dialogues.inDialogue(npc) || NpcReactions.angryAtAnyone(npc, now) || now - NpcPhrases.lastSpoke(npc) < NpcPhrases.LINE_GAP_TICKS) {
            NEXT_AT.put(id, now + RETRY_TICKS);
            return;
        }
        StoryContext context = new StoryContext(nearest, npc);
        if (chatter.conditions().test(context)) {
            // A variant for this part of the story, else its own phrases; each goes through its lines on its own.
            chatter.pick(context).ifPresent(said -> NpcPhrases.say(npc, said.phrases(), NpcPhrases.chatterCounter(npc, said.variant()), nearest, now, false));
        }
        NEXT_AT.put(id, now + random(npc, chatter.minInterval(), Math.max(chatter.minInterval(), chatter.maxInterval())));
    }

    /** The nearest player within the radius who is not a spectator, or null. */
    private static @Nullable ServerPlayer nearest(LivingEntity npc, float radius) {
        ServerPlayer nearest = null;
        double best = radius * radius;
        for (Player candidate : npc.level().players()) {
            double distance = candidate.distanceToSqr(npc);
            if (candidate instanceof ServerPlayer player && !player.isSpectator() && player.isAlive() && distance <= best) {
                nearest = player;
                best = distance;
            }
        }
        return nearest;
    }

    private static long random(LivingEntity npc, float fromSeconds, float toSeconds) {
        int from = Math.round(fromSeconds * 20);
        int to = Math.round(toSeconds * 20);
        return from + (to > from ? npc.getRandom().nextInt(to - from + 1) : 0);
    }

    /** When the NPC speaks next, or -1; for tests. */
    public static long nextAt(Entity npc) {
        return NEXT_AT.getOrDefault(npc.getUUID(), -1L);
    }

    static void reset() {
        NEXT_AT.clear();
        LAST_NEAR.clear();
    }
}
