package dev.dzenthai.storybound.module.dialogue;

import dev.dzenthai.storybound.compat.cutscene.Cutscenes;
import dev.dzenthai.storybound.core.logic.StoryContext;
import dev.dzenthai.storybound.module.scene.Scenes;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.animal.FlyingAnimal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;

/**
 * NPC bindings' {@code movement} and conditional {@code follow}:
 * <ul>
 *   <li>{@code stay} keeps the NPC where it stands, its head turning to the nearest player near;</li>
 *   <li>{@code home} lets it walk within {@code home_radius} blocks of its place; pushed or led away, it walks back;</li>
 *   <li>{@code follow} overrides {@code movement} while its {@code conditions} hold for an eligible player in the same
 *       dimension: walks toward them past {@code distance}, stands and looks at them once near, and teleports safely
 *       behind/beside them past {@code teleport_distance} (0 disables teleporting). When {@code follow} stops (the
 *       condition turns false, or the player dies/leaves), a {@code stay}/{@code home} NPC settles where it arrived
 *       instead of walking all the way back to where it started.</li>
 * </ul>
 * <p>
 * Its place is where it stood when the binding first held it (a spawned NPC: where it was spawned; one living in the
 * world: where it was then), kept on the entity; moved farther in one tick than it can walk (a story's teleport) or to
 * another dimension, its place moves with it. A binding that no longer holds it (free, or none, and no follow) lets it
 * go and forgets its place.
 * <p>
 * It does not hold an NPC that has more to do: in a dialogue (which holds it itself), sleeping, ridden or on a lead,
 * fighting or running (a binding's reaction or its kind's own: a villager's panic, hiding from a raid), or with
 * {@code NoAI}. When that ends it resumes. Mobs are asked whether a binding holds them once a second (and active
 * followers re-check their conditions twice a second).
 */
public final class NpcMovement {
    private static final int CHECK_TICKS = 20;
    /** Active followers re-check follow conditions twice a second so stopping feels immediate. */
    private static final int FOLLOW_CHECK_TICKS = 10;
    /** Stays: walks back once pushed farther than this from its place, in blocks. */
    private static final double STAY_SLACK = 1.5;
    /** Follow hysteresis: stops at {@code distance}, resumes walking once player moves this much farther. */
    private static final double FOLLOW_RESUME_SLACK = 1.0;
    /** How far an idle follower looks for a matching player to start escorting (matches RunScript.RANGE). */
    private static final double PICKUP_RANGE = 64.0;
    /** Minimum ticks between navigation repath requests while following. */
    private static final int FOLLOW_REPATH_TICKS = 10;
    /** Minimum ticks between failed catch-up teleport searches. */
    private static final int TELEPORT_RETRY_TICKS = 10;
    /** Ticks without making progress before a stuck follower teleports to catch up (only when teleporting is enabled). */
    private static final int STUCK_TICKS = 100;
    /** Moved farther than this in one tick, it was put there: its place moves with it. */
    private static final double JUMP = 8;
    /** Stays: looks at the nearest player this close. */
    private static final double LOOK = 8;
    /** Speed walking back (and base speed for follow): a brain's walk (a villager's 0.5 strolls), a goal mob's own walk. */
    private static final float BRAIN_SPEED = 0.6f;
    private static final double GOAL_SPEED = 1.0;
    /** How long after being hurt an NPC is let run as its kind does (a goal mob's panic). */
    private static final int HURT_TICKS = 100;
    /** How often the server-tick watchdog checks active followers that stopped ticking (border chunks / unloaded / throttled). */
    private static final int WATCHDOG_TICKS = 20;
    /** Brief chunk ticket to wake an unloaded follower so it can verify conditions and teleport to the player. */
    private static final TicketType<UUID> CATCHUP_TICKET = TicketType.create("storybound_follow", Comparator.comparing(UUID::toString), 40);
    private static final int WAKE_COOLDOWN_TICKS = 40;
    private static final int MAX_WAKE_ATTEMPTS = 3;

    /** Entity → what holds it, for the mobs some binding holds now. */
    private static final Map<UUID, Held> HELD = new HashMap<>();
    /** Active followers with teleporting enabled, tracked server-side so sprinting out of simulation distance never strands them. */
    private static final Map<UUID, TrackedFollow> TRACKED = new HashMap<>();

    private record TrackedFollow(UUID player, ResourceKey<Level> dimension, BlockPos lastPos, float teleportDistance,
                                 float stopDistance, int lastWakeTick, int wakeAttempts) {
        TrackedFollow at(BlockPos pos, float tp, float stop) {
            return new TrackedFollow(player, dimension, pos, tp, stop, lastWakeTick, 0);
        }

        TrackedFollow woke(int tick) {
            return new TrackedFollow(player, dimension, lastPos, teleportDistance, stopDistance, tick, wakeAttempts + 1);
        }
    }

    private static final class Held {
        @Nullable NpcBinding.Movement movement;
        @Nullable NpcBinding.Follow follow;
        @Nullable UUID following;
        @Nullable Vec3 last;
        boolean pausedGoals;
        boolean standingByTarget;
        boolean wasInDialogue;
        int lastPathTick;
        @Nullable Vec3 lastGoal;
        int lastTeleportTryTick = -TELEPORT_RETRY_TICKS;
        @Nullable Vec3 progressPos;
        int progressTick;

        Held(@Nullable NpcBinding.Movement movement, @Nullable NpcBinding.Follow follow) {
            this.movement = movement;
            this.follow = follow;
        }
    }

    private NpcMovement() {}

    static void register(IEventBus gameBus) {
        gameBus.addListener(NpcMovement::onTick);
        gameBus.addListener(NpcMovement::onServerTick);
        gameBus.addListener(NpcMovement::onLeave);
        gameBus.addListener((ServerStoppedEvent event) -> {
            HELD.clear();
            TRACKED.clear();
        });
    }

    private static void onTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Mob mob) || !(mob.level() instanceof ServerLevel level)) {
            return;
        }
        if (HELD.isEmpty() && !NpcBindings.moves()) {
            return;
        }
        Held held = HELD.isEmpty() ? null : HELD.get(mob.getUUID());
        if (held == null && !NpcBindings.mayMove(mob)) {
            return;
        }
        int interval = held != null && held.following != null ? FOLLOW_CHECK_TICKS : CHECK_TICKS;
        boolean inDialogue = held != null && Dialogues.holdsStill(mob);
        boolean dialogueJustEnded = held != null && held.wasInDialogue && !inDialogue;
        if (held != null) {
            held.wasInDialogue = inDialogue;
        }
        if (dialogueJustEnded || (mob.tickCount + mob.getId()) % interval == 0) {
            held = refresh(mob, level, held);
        }
        if (held != null && mob.isAlive()) {
            hold(mob, level, held);
        }
    }

    /**
     * Player-side watchdog for active followers with teleporting enabled: catches followers stuck in non-entity-ticking
     * border chunks, throttled by entity optimization mods, or unloaded after the player sprinted/rode ahead.
     */
    private static void onServerTick(ServerTickEvent.Post event) {
        if (TRACKED.isEmpty()) {
            return;
        }
        MinecraftServer server = event.getServer();
        int now = server.getTickCount();
        if (now % WATCHDOG_TICKS != 0) {
            return;
        }
        for (Map.Entry<UUID, TrackedFollow> entry : List.copyOf(TRACKED.entrySet())) {
            UUID mobId = entry.getKey();
            TrackedFollow tracked = entry.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(tracked.player());
            if (player == null || !player.isAlive() || player.isSpectator() || player.hasDisconnected()
                    || !player.level().dimension().equals(tracked.dimension())) {
                TRACKED.remove(mobId);
                continue;
            }
            if (Scenes.watching(player) != null || Cutscenes.watching(player) != null) {
                continue;
            }
            ServerLevel level = player.serverLevel();
            double tpDist = Math.max(tracked.teleportDistance(), tracked.stopDistance() + 2.0);
            Entity entity = level.getEntity(mobId);
            if (entity instanceof Mob mob) {
                if (!mob.isAlive()) {
                    TRACKED.remove(mobId);
                    continue;
                }
                double dist = Math.sqrt(mob.distanceToSqr(player));
                if (dist <= tpDist) {
                    TRACKED.put(mobId, tracked.at(mob.blockPosition(), tracked.teleportDistance(), tracked.stopDistance()));
                    continue;
                }
                // Loaded (possibly in a non-ticking border chunk or throttled): re-verify binding & conditions and pull in.
                Held held = refresh(mob, level, HELD.get(mobId));
                if (held == null || held.follow == null || !tracked.player().equals(held.following) || held.follow.teleportDistance() <= 0f) {
                    TRACKED.remove(mobId);
                    continue;
                }
                if (hardBusy(mob, level)) {
                    continue;
                }
                if (combatBusy(mob)) {
                    if (dist <= Math.max(tpDist + 4.0, 28.0)) {
                        continue;
                    }
                    disengageCombat(mob);
                }
                if (tryTeleportNear(mob, level, held, player)) {
                    TRACKED.put(mobId, tracked.at(mob.blockPosition(), held.follow.teleportDistance(), held.follow.distance()));
                }
                continue;
            }
            // Unloaded from chunk while player moved far ahead: briefly ticket its last chunk so it loads and catches up.
            double flatDist = flat(player.position(), Vec3.atBottomCenterOf(tracked.lastPos()));
            if (flatDist <= tpDist || now - tracked.lastWakeTick() < WAKE_COOLDOWN_TICKS) {
                continue;
            }
            if (tracked.wakeAttempts() >= MAX_WAKE_ATTEMPTS) {
                TRACKED.remove(mobId);
                continue;
            }
            TRACKED.put(mobId, tracked.woke(now));
            level.getChunkSource().addRegionTicket(CATCHUP_TICKET, new ChunkPos(tracked.lastPos()), 2, mobId);
        }
    }

    /** Unloaded, gone or off to another dimension: no longer looked at here; its place stays on it. */
    private static void onLeave(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide()) {
            Entity entity = event.getEntity();
            Held held = HELD.remove(entity.getUUID());
            Entity.RemovalReason reason = entity.getRemovalReason();
            if (reason == Entity.RemovalReason.UNLOADED_TO_CHUNK && held != null && held.following != null
                    && held.follow != null && held.follow.teleportDistance() > 0f && entity.level() instanceof ServerLevel level) {
                TRACKED.put(entity.getUUID(), new TrackedFollow(held.following, level.dimension(), entity.blockPosition(),
                        held.follow.teleportDistance(), held.follow.distance(), -WAKE_COOLDOWN_TICKS, 0));
            } else if (reason != Entity.RemovalReason.UNLOADED_TO_CHUNK) {
                TRACKED.remove(entity.getUUID());
            }
        }
    }

    /** What holds the mob now: its binding's movement (unless free) and/or follow; its place set when first held. */
    private static @Nullable Held refresh(Mob mob, ServerLevel level, @Nullable Held held) {
        Optional<NpcBindings.Found> found = Dialogues.isEnabled() ? NpcBindings.find(mob) : Optional.empty();
        NpcBinding.Movement movement = found.flatMap(f -> f.binding().movement())
                .filter(written -> written.mode() != NpcBinding.Movement.Mode.FREE).orElse(null);
        NpcBinding.Follow follow = found.flatMap(f -> f.binding().follow()).orElse(null);
        if (movement == null && follow == null) {
            if (held != null) {
                releaseGoals(mob, held);
                HELD.remove(mob.getUUID());
                mob.removeData(DialogueRegistries.NPC_HOME);
            }
            TRACKED.remove(mob.getUUID());
            return null;
        }
        if (held == null) {
            held = new Held(movement, follow);
            HELD.put(mob.getUUID(), held);
        }
        held.movement = movement;
        held.follow = follow;
        if (movement == null) {
            // Movement is free/absent: no home post to keep, only conditional follow.
            if (mob.hasData(DialogueRegistries.NPC_HOME)) {
                mob.removeData(DialogueRegistries.NPC_HOME);
            }
        } else if (!mob.hasData(DialogueRegistries.NPC_HOME) || !mob.getData(DialogueRegistries.NPC_HOME).dimension().equals(level.dimension())) {
            settle(mob, level);
        }
        ServerPlayer next = follow != null ? resolveFollowTarget(mob, level, held, follow) : null;
        UUID nextId = next != null ? next.getUUID() : null;
        if (held.following != null && !held.following.equals(nextId)) {
            stopFollow(mob, level, held);
        }
        if (nextId != null && !nextId.equals(held.following)) {
            startFollow(mob, held, nextId);
        }
        return held;
    }

    private static void startFollow(Mob mob, Held held, UUID targetId) {
        releaseGoals(mob, held);
        held.following = targetId;
        held.standingByTarget = false;
        held.lastPathTick = mob.tickCount - FOLLOW_REPATH_TICKS;
        held.lastTeleportTryTick = (mob.level() instanceof ServerLevel lvl ? lvl.getServer().getTickCount() : 0) - TELEPORT_RETRY_TICKS;
        held.lastGoal = null;
        held.progressPos = mob.position();
        held.progressTick = mob.tickCount;
        if (held.follow != null && held.follow.teleportDistance() > 0f && mob.level() instanceof ServerLevel level) {
            TRACKED.put(mob.getUUID(), new TrackedFollow(targetId, level.dimension(), mob.blockPosition(),
                    held.follow.teleportDistance(), held.follow.distance(), -WAKE_COOLDOWN_TICKS, 0));
        } else {
            TRACKED.remove(mob.getUUID());
        }
    }

    private static void stopFollow(Mob mob, ServerLevel level, Held held) {
        held.following = null;
        held.standingByTarget = false;
        held.lastGoal = null;
        held.progressPos = null;
        TRACKED.remove(mob.getUUID());
        mob.getNavigation().stop();
        Brain<?> brain = mob.getBrain();
        if (brain.checkMemory(MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED)) {
            brain.eraseMemory(MemoryModuleType.WALK_TARGET);
        }
        releaseGoals(mob, held);
        if (held.movement != null) {
            // Settle where the companion ended up so stay/home holds them there instead of marching back to start.
            settle(mob, level);
        }
    }

    /** Picks who to follow: sticks to the current player while eligible, else the nearest matching player within pickup range. */
    private static @Nullable ServerPlayer resolveFollowTarget(Mob mob, ServerLevel level, Held held, NpcBinding.Follow follow) {
        if (held.following != null) {
            ServerPlayer current = playerInLevel(level, held.following);
            if (eligible(current, level, mob, follow)) {
                return current;
            }
        }
        double reach = Math.max(PICKUP_RANGE, follow.teleportDistance());
        double bestDistSqr = reach * reach;
        ServerPlayer best = null;
        for (ServerPlayer candidate : level.players()) {
            double distSqr = candidate.distanceToSqr(mob);
            if (distSqr <= bestDistSqr && eligible(candidate, level, mob, follow)) {
                best = candidate;
                bestDistSqr = distSqr;
            }
        }
        return best;
    }

    private static @Nullable ServerPlayer playerInLevel(ServerLevel level, UUID uuid) {
        return level.getPlayerByUUID(uuid) instanceof ServerPlayer player ? player : null;
    }

    private static boolean eligible(@Nullable ServerPlayer player, ServerLevel level, Mob mob, NpcBinding.Follow follow) {
        return player != null && player.level() == level && player.isAlive() && !player.isSpectator()
                && !player.hasDisconnected() && follow.conditions().test(new StoryContext(player, mob));
    }

    /** Its place is where it stands now. */
    private static void settle(Mob mob, ServerLevel level) {
        mob.setData(DialogueRegistries.NPC_HOME, GlobalPos.of(level.dimension(), mob.blockPosition()));
    }

    private static void hold(Mob mob, ServerLevel level, Held held) {
        Vec3 now = mob.position();
        if (held.movement != null && held.last != null && held.last.distanceToSqr(now) > JUMP * JUMP) {
            settle(mob, level);
        }
        held.last = now;
        if (held.follow != null && held.following != null) {
            ServerPlayer target = playerInLevel(level, held.following);
            if (target == null || target.level() != level || !target.isAlive() || target.isSpectator() || target.hasDisconnected()) {
                stopFollow(mob, level, held);
            } else {
                followTick(mob, level, held, target);
                return;
            }
        }
        if (held.movement == null) {
            releaseGoals(mob, held);
            return;
        }
        if (busy(mob, level)) {
            releaseGoals(mob, held);
            return;
        }
        BlockPos home = mob.getData(DialogueRegistries.NPC_HOME).pos();
        double away = flat(now, Vec3.atBottomCenterOf(home));
        if (held.movement.mode() == NpcBinding.Movement.Mode.STAY) {
            if (away > STAY_SLACK) {
                releaseGoals(mob, held);
                walkTo(mob, home);
            } else {
                standStill(mob, held);
                lookAtNearest(mob, level, held);
            }
        } else if (away > held.movement.homeRadius()) {
            releaseGoals(mob, held);
            walkTo(mob, home);
        } else {
            releaseGoals(mob, held);
            keepWithin(mob, home, held.movement.homeRadius());
        }
    }

    private static void followTick(Mob mob, ServerLevel level, Held held, ServerPlayer target) {
        NpcBinding.Follow follow = held.follow;
        if (follow == null) {
            return;
        }
        if (hardBusy(mob, level)) {
            releaseGoals(mob, held);
            held.progressPos = null;
            return;
        }
        if (Scenes.watching(target) != null || Cutscenes.watching(target) != null) {
            // Do not walk into a cutscene frame or teleport onto the player while a scene is playing.
            standStill(mob, held);
            held.progressPos = null;
            return;
        }
        if (follow.teleportDistance() > 0f) {
            TrackedFollow prev = TRACKED.get(mob.getUUID());
            if (prev == null || !prev.player().equals(target.getUUID()) || !prev.dimension().equals(level.dimension())
                    || prev.lastPos().distManhattan(mob.blockPosition()) >= 4) {
                TRACKED.put(mob.getUUID(), new TrackedFollow(target.getUUID(), level.dimension(), mob.blockPosition(),
                        follow.teleportDistance(), follow.distance(), prev != null ? prev.lastWakeTick() : -WAKE_COOLDOWN_TICKS, 0));
            }
        } else {
            TRACKED.remove(mob.getUUID());
        }
        // Keep saved home synced on solid ground so chunk unload / restart mid-escort never walks back to start.
        if (held.movement != null && mob.onGround()) {
            if (!mob.hasData(DialogueRegistries.NPC_HOME)
                    || flat(mob.position(), Vec3.atBottomCenterOf(mob.getData(DialogueRegistries.NPC_HOME).pos())) > 2.0) {
                settle(mob, level);
            }
        }
        double dist = Math.sqrt(mob.distanceToSqr(target));
        double stopDist = follow.distance();
        double resumeDist = stopDist + FOLLOW_RESUME_SLACK;
        double tpDist = follow.teleportDistance() > 0f ? Math.max(follow.teleportDistance(), stopDist + 2.0) : 0.0;

        if (combatBusy(mob)) {
            // Let the companion fight alongside the player, unless the player ran ahead and would leave them behind.
            if (tpDist > 0.0 && dist > Math.max(tpDist + 4.0, 28.0)) {
                disengageCombat(mob);
                if (tryTeleportNear(mob, level, held, target)) {
                    return;
                }
            }
            releaseGoals(mob, held);
            held.progressPos = null;
            return;
        }

        if (tpDist > 0.0 && dist > tpDist) {
            if (tryTeleportNear(mob, level, held, target)) {
                return;
            }
        }

        if (dist <= stopDist || (held.standingByTarget && dist <= resumeDist)) {
            held.standingByTarget = true;
            held.progressPos = null;
            standStill(mob, held);
            lookAt(mob, target, held);
            return;
        }

        held.standingByTarget = false;
        if (tpDist > 0.0) {
            if (held.progressPos == null || mob.position().distanceToSqr(held.progressPos) >= 1.5 * 1.5) {
                held.progressPos = mob.position();
                held.progressTick = mob.tickCount;
            } else if (mob.tickCount - held.progressTick >= STUCK_TICKS && dist > stopDist + 3.0) {
                held.progressTick = mob.tickCount;
                if (tryTeleportNear(mob, level, held, target)) {
                    return;
                }
            }
        }
        walkToPlayer(mob, target, follow.speed(), Math.max(1, (int) Math.floor(stopDist)), held);
    }

    /** Hard interruptions where neither walking nor catch-up teleporting may touch the mob. */
    private static boolean hardBusy(Mob mob, ServerLevel level) {
        return mob.isNoAi() || mob.isSleeping() || mob.isPassenger() || mob.isVehicle() || mob.isLeashed()
                || Dialogues.holdsStill(mob) || NpcReactions.angryAtAnyone(mob, level.getServer().getTickCount());
    }

    /** Combat or hurt-panic state where a companion temporarily handles threats. */
    private static boolean combatBusy(Mob mob) {
        if (mob.getTarget() != null) {
            return true;
        }
        if (mob.getLastHurtByMob() != null && mob.tickCount - mob.getLastHurtByMobTimestamp() < HURT_TICKS) {
            return true;
        }
        Brain<?> brain = mob.getBrain();
        return brain.isActive(Activity.PANIC) || brain.isActive(Activity.FIGHT)
                || brain.checkMemory(MemoryModuleType.ATTACK_TARGET, MemoryStatus.VALUE_PRESENT);
    }

    private static void disengageCombat(Mob mob) {
        mob.setTarget(null);
        mob.setLastHurtByMob(null);
        if (mob instanceof NeutralMob neutral) {
            neutral.stopBeingAngry();
        }
        Brain<?> brain = mob.getBrain();
        if (brain.checkMemory(MemoryModuleType.ATTACK_TARGET, MemoryStatus.REGISTERED)) {
            brain.eraseMemory(MemoryModuleType.ATTACK_TARGET);
        }
        if (brain.checkMemory(MemoryModuleType.ANGRY_AT, MemoryStatus.REGISTERED)) {
            brain.eraseMemory(MemoryModuleType.ANGRY_AT);
        }
    }

    /** It has more to do than keep its place now. */
    private static boolean busy(Mob mob, ServerLevel level) {
        if (hardBusy(mob, level) || combatBusy(mob)) {
            return true;
        }
        return mob.getBrain().isActive(Activity.HIDE);
    }

    private static double flat(Vec3 from, Vec3 to) {
        double dx = from.x - to.x;
        double dz = from.z - to.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Walks toward the followed player: Brain mobs via WALK_TARGET, Goal mobs via navigation (stroll goals paused). */
    private static void walkToPlayer(Mob mob, ServerPlayer target, float speedMultiplier, int closeEnough, Held held) {
        Brain<?> brain = mob.getBrain();
        if (brain.checkMemory(MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED)) {
            releaseGoals(mob, held);
            boolean repath = mob.tickCount - held.lastPathTick >= FOLLOW_REPATH_TICKS
                    || brain.getMemory(MemoryModuleType.WALK_TARGET).isEmpty()
                    || held.lastGoal == null || target.position().distanceToSqr(held.lastGoal) > 4.0;
            if (repath) {
                brain.setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(new EntityTracker(target, false), BRAIN_SPEED * speedMultiplier, closeEnough));
                held.lastPathTick = mob.tickCount;
                held.lastGoal = target.position();
            }
            if (brain.checkMemory(MemoryModuleType.LOOK_TARGET, MemoryStatus.REGISTERED) && mob.distanceToSqr(target) <= 144.0) {
                brain.setMemory(MemoryModuleType.LOOK_TARGET, new EntityTracker(target, true));
            }
            return;
        }
        // Keep random stroll / village goals from hijacking the path; navigation and FloatGoal (Flag.JUMP) still run.
        mob.goalSelector.disableControlFlag(Goal.Flag.MOVE);
        held.pausedGoals = true;
        boolean repath = mob.tickCount - held.lastPathTick >= FOLLOW_REPATH_TICKS
                || mob.getNavigation().isDone()
                || held.lastGoal == null || target.position().distanceToSqr(held.lastGoal) > 4.0;
        if (repath) {
            mob.getNavigation().moveTo(target, GOAL_SPEED * speedMultiplier);
            held.lastPathTick = mob.tickCount;
            held.lastGoal = target.position();
        }
        if (mob.distanceToSqr(target) <= 144.0) {
            mob.getLookControl().setLookAt(target);
        }
    }

    /** Safely teleports the companion behind or beside the player on solid walkable ground (or air for flying mobs). */
    private static boolean tryTeleportNear(Mob mob, ServerLevel level, Held held, ServerPlayer target) {
        int serverTick = level.getServer().getTickCount();
        if (serverTick - held.lastTeleportTryTick < TELEPORT_RETRY_TICKS) {
            return false;
        }
        held.lastTeleportTryTick = serverTick;
        Vec3 dest = findSafeSpotNear(mob, level, target);
        if (dest == null) {
            return false;
        }
        mob.getNavigation().stop();
        Brain<?> brain = mob.getBrain();
        if (brain.checkMemory(MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED)) {
            brain.eraseMemory(MemoryModuleType.WALK_TARGET);
        }
        mob.teleportTo(dest.x, dest.y, dest.z);
        mob.setDeltaMovement(Vec3.ZERO);
        mob.resetFallDistance();
        held.last = mob.position();
        held.progressPos = mob.position();
        held.progressTick = mob.tickCount;
        held.lastGoal = null;
        if (held.movement != null) {
            settle(mob, level);
        }
        mob.getLookControl().setLookAt(target);
        return true;
    }

    /**
     * Searches around the player (preferring behind and to the flanks so the NPC does not pop into the player's face)
     * for a collision-free, liquid-free spot that fits the mob's full bounding box (important for tall/wide mobs like
     * Iron Golems). Supports full blocks, village paths ({@code dirt_path}), farmland, snow, slabs, and hills via a
     * strict-then-relaxed pass, falling back to right beside/at the player's grounded feet in tight spaces. Returns
     * null when no safe spot exists (e.g. player flying high over void or swimming in deep ocean).
     */
    private static @Nullable Vec3 findSafeSpotNear(Mob mob, ServerLevel level, ServerPlayer target) {
        BlockPos center = target.blockPosition();
        Vec3 look = target.getLookAngle();
        double backX = -look.x;
        double backZ = -look.z;
        double backLen = Math.hypot(backX, backZ);
        if (backLen < 1.0e-3) {
            backX = 0;
            backZ = 1;
        } else {
            backX /= backLen;
            backZ /= backLen;
        }
        boolean flying = isFlying(mob);
        Vec3 strict = searchRing(mob, level, target, center, backX, backZ, flying, 2, 6, true);
        if (strict != null) {
            return strict;
        }
        Vec3 relaxed = searchRing(mob, level, target, center, backX, backZ, flying, 2, 6, false);
        if (relaxed != null) {
            return relaxed;
        }
        Vec3 close = searchRing(mob, level, target, center, backX, backZ, flying, 1, 1, false);
        if (close != null) {
            return close;
        }
        if (flying || target.onGround()) {
            for (int dy : new int[] {0, 1, -1}) {
                Vec3 atPlayer = landingSpot(mob, level, center.offset(0, dy, 0), flying, false);
                if (atPlayer != null) {
                    return atPlayer;
                }
            }
        }
        return null;
    }

    private static @Nullable Vec3 searchRing(Mob mob, ServerLevel level, ServerPlayer target, BlockPos center,
                                             double backX, double backZ, boolean flying, int minRadius, int maxRadius, boolean strict) {
        Vec3 best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        double minHoriz = minRadius <= 1 ? 0.5 : 1.8;
        for (int radius = minRadius; radius <= maxRadius; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    double horiz = Math.hypot(dx, dz);
                    if (horiz < minHoriz) {
                        continue;
                    }
                    for (int dy = 0; dy <= 8; dy++) {
                        int offsetY = dy == 0 ? 0 : (dy % 2 == 1 ? (dy + 1) / 2 : -(dy / 2));
                        BlockPos candidate = center.offset(dx, offsetY, dz);
                        Vec3 spot = landingSpot(mob, level, candidate, flying, strict);
                        if (spot == null) {
                            continue;
                        }
                        double distToPlayer = Math.hypot(spot.x - target.getX(), spot.z - target.getZ());
                        if (distToPlayer < minHoriz) {
                            continue;
                        }
                        // Prefer spots behind/beside player, close to target Y, and at radius 2–4.
                        double dot = (dx * backX + dz * backZ) / horiz;
                        double score = dot * 2.0 - Math.abs(offsetY) * 0.5 - radius * 0.35;
                        if (score > bestScore) {
                            bestScore = score;
                            best = spot;
                        }
                        break;
                    }
                }
            }
            if (best != null && radius <= 4 && bestScore > 0.0) {
                return best;
            }
        }
        return best;
    }

    private static @Nullable Vec3 landingSpot(Mob mob, ServerLevel level, BlockPos pos, boolean flying, boolean strict) {
        if (!level.isPositionEntityTicking(pos) || pos.getY() < level.getMinBuildHeight() + 1 || pos.getY() > level.getMaxBuildHeight() - 3) {
            return null;
        }
        double destX = pos.getX() + 0.5;
        double destY = pos.getY();
        double destZ = pos.getZ() + 0.5;
        if (!flying) {
            BlockState atPos = level.getBlockState(pos);
            if (!atPos.getFluidState().isEmpty() || isHazardBlock(atPos)) {
                return null;
            }
            BlockPos below = pos.below();
            BlockState floor = level.getBlockState(below);
            if (!floor.getFluidState().isEmpty() || isHazardBlock(floor)
                    || floor.is(BlockTags.FENCES) || floor.is(BlockTags.WALLS) || floor.is(BlockTags.LEAVES)) {
                return null;
            }
            VoxelShape shape = floor.getCollisionShape(level, below);
            if (shape.isEmpty()) {
                return null;
            }
            double topY = shape.max(Direction.Axis.Y);
            if (topY < 0.5) {
                return null;
            }
            destY = below.getY() + topY;
            if (strict) {
                PathType pathType = WalkNodeEvaluator.getPathTypeStatic(mob, pos);
                if (!isSafePathType(pathType)) {
                    return null;
                }
            }
        }
        AABB shifted = mob.getBoundingBox().move(destX - mob.getX(), destY - mob.getY(), destZ - mob.getZ());
        if (!level.noCollision(mob, shifted) || level.containsAnyLiquid(shifted)) {
            return null;
        }
        return new Vec3(destX, destY, destZ);
    }

    private static boolean isSafePathType(PathType type) {
        return switch (type) {
            case WALKABLE, OPEN, WALKABLE_DOOR, WATER_BORDER, RAIL -> true;
            default -> false;
        };
    }

    private static boolean isHazardBlock(BlockState state) {
        return state.is(BlockTags.FIRE)
                || state.is(Blocks.MAGMA_BLOCK)
                || state.is(Blocks.CAMPFIRE)
                || state.is(Blocks.SOUL_CAMPFIRE)
                || state.is(Blocks.CACTUS)
                || state.is(Blocks.SWEET_BERRY_BUSH)
                || state.is(Blocks.POWDER_SNOW)
                || state.is(Blocks.WITHER_ROSE);
    }

    private static boolean isFlying(Mob mob) {
        return mob instanceof FlyingAnimal || mob.getNavigation() instanceof FlyingPathNavigation;
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
    private static void standStill(Mob mob, Held held) {
        mob.getNavigation().stop();
        // A goal mob (a wandering trader) would start a stroll and take a step before the next stop: its moving goals
        // are kept from starting while it stays (vanilla gives them back within five ticks once it is let go).
        mob.goalSelector.disableControlFlag(Goal.Flag.MOVE);
        held.pausedGoals = true;
        Brain<?> brain = mob.getBrain();
        if (brain.checkMemory(MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED)) {
            brain.eraseMemory(MemoryModuleType.WALK_TARGET);
        }
    }

    /** Re-enables goal control flags we disabled, unless another system (dialogue, bed, mount) is holding the mob. */
    private static void releaseGoals(Mob mob, Held held) {
        if (!held.pausedGoals) {
            return;
        }
        held.pausedGoals = false;
        if (!Dialogues.holdsStill(mob) && !mob.isSleeping() && !mob.isPassenger()) {
            mob.goalSelector.enableControlFlag(Goal.Flag.MOVE);
            mob.goalSelector.enableControlFlag(Goal.Flag.LOOK);
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
    private static void lookAtNearest(Mob mob, ServerLevel level, Held held) {
        Player player = level.getNearestPlayer(mob, LOOK);
        if (player == null || player.isSpectator()) {
            return;
        }
        lookAt(mob, player, held);
    }

    private static void lookAt(Mob mob, Player player, Held held) {
        mob.getLookControl().setLookAt(player);
        // Its own looking goals (at any mob near: a trader's llama) would turn its head away.
        mob.goalSelector.disableControlFlag(Goal.Flag.LOOK);
        held.pausedGoals = true;
        Brain<?> brain = mob.getBrain();
        if (brain.checkMemory(MemoryModuleType.LOOK_TARGET, MemoryStatus.REGISTERED)) {
            brain.setMemory(MemoryModuleType.LOOK_TARGET, new EntityTracker(player, true));
        }
    }

    /** Whether a binding holds the mob now (for tests and the trace). */
    public static boolean held(Mob mob) {
        return HELD.containsKey(mob.getUUID());
    }

    /** Whether the entity is actively following a player right now (keeps it awake at night, and for tests). */
    public static boolean following(Entity entity) {
        Held held = HELD.get(entity.getUUID());
        return held != null && held.following != null;
    }

    /** The player the mob is currently following, or null (for tests and debug). */
    public static @Nullable UUID followingPlayer(Mob mob) {
        Held held = HELD.get(mob.getUUID());
        return held != null ? held.following : null;
    }
}
