package dev.dzenthai.storybound.module.dialogue;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Whether a player in a dialogue is in a fight, for the dialogues a fight pauses (November 5, 2026). A fight is a hit
 * between the player (or the NPC they talk to) and a living thing within the last {@code quiet} seconds, or a mob near
 * the player that targets either of them. Vanilla's combat tracker counts only hits taken and lets go after 15 seconds,
 * so it is not used.
 */
final class DialogueCombat {
    /** How far a mob targeting the player or the NPC may be to count as a fight. */
    static final double TARGET_RANGE = 16;
    /** Ticks between two looks for mobs targeting them; hits count at once. */
    static final int SCAN_INTERVAL = 10;
    /** Hits older than this are forgotten. */
    private static final long FORGET_TICKS = 20 * 60 * 2;

    /** The last tick each entity hit or was hit by a living thing, or was targeted. */
    private static final Map<UUID, Long> LAST_FIGHT = new HashMap<>();

    private DialogueCombat() {}

    /** A hit between two living things: both are in a fight from now on. */
    static void hit(LivingEntity victim, @Nullable Entity attacker, long now) {
        if (!(attacker instanceof LivingEntity) || attacker == victim) {
            return;
        }
        LAST_FIGHT.put(victim.getUUID(), now);
        LAST_FIGHT.put(attacker.getUUID(), now);
    }

    /**
     * Whether the player, or the NPC they talk to, has fought within the last {@code quietTicks}. Every
     * {@link #SCAN_INTERVAL} ticks it also looks for a mob near the player targeting either of them (the NPC itself
     * aside: a boss taunting the player is not a fight with itself).
     */
    static boolean inFight(ServerPlayer player, @Nullable Entity partner, long quietTicks, long now) {
        if (Math.floorMod(now + player.getId(), SCAN_INTERVAL) == 0 && targeted(player, partner)) {
            LAST_FIGHT.put(player.getUUID(), now);
        }
        return recent(player, quietTicks, now) || partner != null && recent(partner, quietTicks, now);
    }

    /** Forgets old hits; every few seconds. */
    static void prune(long now) {
        LAST_FIGHT.values().removeIf(tick -> now - tick > FORGET_TICKS);
    }

    static void reset() {
        LAST_FIGHT.clear();
    }

    private static boolean recent(Entity entity, long quietTicks, long now) {
        Long last = LAST_FIGHT.get(entity.getUUID());
        return last != null && now - last < quietTicks;
    }

    private static boolean targeted(ServerPlayer player, @Nullable Entity partner) {
        return !player.level().getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(TARGET_RANGE),
                mob -> mob != partner && mob.isAlive() && targets(mob, player, partner)).isEmpty();
    }

    /** A mob's target, or, for brain-driven ones (piglins, wardens), its attack target memory. */
    private static boolean targets(Mob mob, ServerPlayer player, @Nullable Entity partner) {
        LivingEntity target = mob.getTarget();
        if (target == null && mob.getBrain().checkMemory(MemoryModuleType.ATTACK_TARGET, MemoryStatus.REGISTERED)) {
            target = mob.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null);
        }
        return target != null && (target == player || target == partner);
    }
}
