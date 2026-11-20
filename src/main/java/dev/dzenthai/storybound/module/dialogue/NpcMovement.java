package dev.dzenthai.storybound.module.dialogue;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * NPC bindings' {@code movement}: {@code stay} keeps the NPC where it stands, its head turning to the nearest player
 * near; {@code home} lets it walk within {@code home_radius} blocks of its place; pushed or led away, it walks back.
 * Its place is where it stood when the binding first held it (a spawned NPC: where it was spawned; one living in the
 * world: where it was then), kept on the entity; moved farther in one tick than it can walk (a story's teleport) or to
 * another dimension, its place moves with it. A binding that no longer holds it (free, or none) lets it go and forgets
 * its place.
 * <p>
 * It does not hold an NPC that has more to do: in a dialogue (which holds it itself), sleeping, ridden or on a lead,
 * fighting or running (a binding's reaction or its kind's own: a villager's panic, hiding from a raid), or with
 * {@code NoAI}. When that ends it walks back. Mobs are asked whether a binding holds them once a second.
 */
public final class NpcMovement {
    private static final int CHECK_TICKS = 20;
    /** Stays: walks back once pushed farther than this from its place, in blocks. */
    private static final double STAY_SLACK = 1.5;
    /** Moved farther than this in one tick, it was put there: its place moves with it. */
    private static final double JUMP = 8;
    /** Stays: looks at the nearest player this close. */
    private static final double LOOK = 8;
    /** Speed walking back: a brain's walk (a villager's 0.5 strolls), a goal mob's own walk. */
    private static final float BRAIN_SPEED = 0.6f;
    private static final double GOAL_SPEED = 1.0;

    /** Entity → what holds it, for the mobs some binding holds now. */
    private static final Map<UUID, Held> HELD = new HashMap<>();

    private static final class Held {
        NpcBinding.Movement movement;
        Vec3 last;

        Held(NpcBinding.Movement movement) {
            this.movement = movement;
        }
    }

    private NpcMovement() {}

    static void register(IEventBus gameBus) {
        gameBus.addListener(NpcMovement::onTick);
        gameBus.addListener(NpcMovement::onLeave);
        gameBus.addListener((ServerStoppedEvent event) -> HELD.clear());
    }

    private static void onTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Mob mob) || !(mob.level() instanceof ServerLevel level)) {
            return;
        }
        Held held = HELD.get(mob.getUUID());
        if (held == null && !NpcBindings.moves()) {
            return;
        }
        if ((mob.tickCount + mob.getId()) % CHECK_TICKS == 0) {
            held = refresh(mob, level, held);
        }
        if (held != null && mob.isAlive()) {
            hold(mob, level, held);
        }
    }

    /** Unloaded, gone or off to another dimension: no longer looked at here; its place stays on it. */
    private static void onLeave(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide()) {
            HELD.remove(event.getEntity().getUUID());
        }
    }

    /** What holds the mob now: its binding's movement, unless free; its place set when it is first held. */
    private static Held refresh(Mob mob, ServerLevel level, Held held) {
        Optional<NpcBinding.Movement> movement = Dialogues.isEnabled()
                ? NpcBindings.find(mob).flatMap(found -> found.binding().movement()).filter(written -> written.mode() != NpcBinding.Movement.Mode.FREE)
                : Optional.empty();
        if (movement.isEmpty()) {
            if (held != null) {
                HELD.remove(mob.getUUID());
                mob.removeData(DialogueRegistries.NPC_HOME);
            }
            return null;
        }
        if (held == null) {
            held = new Held(movement.get());
            HELD.put(mob.getUUID(), held);
        }
        held.movement = movement.get();
        if (!mob.hasData(DialogueRegistries.NPC_HOME) || !mob.getData(DialogueRegistries.NPC_HOME).dimension().equals(level.dimension())) {
            settle(mob, level);
        }
        return held;
    }

    /** Its place is where it stands now. */
    private static void settle(Mob mob, ServerLevel level) {
        mob.setData(DialogueRegistries.NPC_HOME, GlobalPos.of(level.dimension(), mob.blockPosition()));
    }

    private static void hold(Mob mob, ServerLevel level, Held held) {
        Vec3 now = mob.position();
        if (held.last != null && held.last.distanceToSqr(now) > JUMP * JUMP) {
            settle(mob, level);
        }
        held.last = now;
        if (busy(mob, level)) {
            return;
        }
        BlockPos home = mob.getData(DialogueRegistries.NPC_HOME).pos();
        double away = flat(now, Vec3.atBottomCenterOf(home));
        if (held.movement.mode() == NpcBinding.Movement.Mode.STAY) {
            if (away > STAY_SLACK) {
                walkTo(mob, home);
            } else {
                standStill(mob);
                lookAtNearest(mob, level);
            }
        } else if (away > held.movement.homeRadius()) {
            walkTo(mob, home);
        } else {
            keepWithin(mob, home, held.movement.homeRadius());
        }
    }

    /** It has more to do than keep its place now. */
    private static boolean busy(Mob mob, ServerLevel level) {
        if (mob.isNoAi() || mob.isSleeping() || mob.isPassenger() || mob.isLeashed() || mob.getTarget() != null) {
            return true;
        }
        Brain<?> brain = mob.getBrain();
        if (brain.isActive(Activity.PANIC) || brain.isActive(Activity.HIDE) || brain.isActive(Activity.FIGHT)
                || brain.checkMemory(MemoryModuleType.ATTACK_TARGET, MemoryStatus.VALUE_PRESENT)) {
            return true;
        }
        return Dialogues.inDialogue(mob) || NpcReactions.angryAtAnyone(mob, level.getServer().getTickCount());
    }

    private static double flat(Vec3 from, Vec3 to) {
        double dx = from.x - to.x;
        double dz = from.z - to.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Back to its place: a brain mob by its walk target, a goal mob by its navigation (a path asked only once). */
    private static void walkTo(Mob mob, BlockPos home) {
        Brain<?> brain = mob.getBrain();
        if (brain.checkMemory(MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED)) {
            boolean going = brain.getMemory(MemoryModuleType.WALK_TARGET).map(target -> target.getTarget().currentBlockPosition().equals(home)).orElse(false);
            if (!going) {
                brain.setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(home, BRAIN_SPEED, 0));
            }
            return;
        }
        BlockPos target = mob.getNavigation().getTargetPos();
        if (mob.getNavigation().isDone() || target == null || !target.equals(home)) {
            mob.getNavigation().moveTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, GOAL_SPEED);
        }
    }

    /** As a dialogue holds it: no path, no walk target. */
    private static void standStill(Mob mob) {
        mob.getNavigation().stop();
        Brain<?> brain = mob.getBrain();
        if (brain.checkMemory(MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED)) {
            brain.eraseMemory(MemoryModuleType.WALK_TARGET);
        }
    }

    /** Walks on, but not to a place beyond the radius: such a walk target or path is dropped. */
    private static void keepWithin(Mob mob, BlockPos home, int radius) {
        Vec3 center = Vec3.atBottomCenterOf(home);
        Brain<?> brain = mob.getBrain();
        if (brain.checkMemory(MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED)) {
            brain.getMemory(MemoryModuleType.WALK_TARGET)
                    .filter(target -> flat(Vec3.atBottomCenterOf(target.getTarget().currentBlockPosition()), center) > radius)
                    .ifPresent(target -> {
                        brain.eraseMemory(MemoryModuleType.WALK_TARGET);
                        mob.getNavigation().stop();
                    });
        }
        BlockPos target = mob.getNavigation().getTargetPos();
        if (!mob.getNavigation().isDone() && target != null && flat(Vec3.atBottomCenterOf(target), center) > radius) {
            mob.getNavigation().stop();
        }
    }

    /** Its head to the nearest player near, as a dialogue turns it (a brain mob also by its look target). */
    private static void lookAtNearest(Mob mob, ServerLevel level) {
        Player player = level.getNearestPlayer(mob, LOOK);
        if (player == null || player.isSpectator()) {
            return;
        }
        mob.getLookControl().setLookAt(player);
        Brain<?> brain = mob.getBrain();
        if (brain.checkMemory(MemoryModuleType.LOOK_TARGET, MemoryStatus.REGISTERED)) {
            brain.setMemory(MemoryModuleType.LOOK_TARGET, new EntityTracker(player, true));
        }
    }

    /** Whether a binding holds the mob now (for tests and the trace). */
    public static boolean held(Mob mob) {
        return HELD.containsKey(mob.getUUID());
    }
}
