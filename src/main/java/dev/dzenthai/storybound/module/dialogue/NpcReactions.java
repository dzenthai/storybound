package dev.dzenthai.storybound.module.dialogue;

import dev.dzenthai.storybound.core.logic.StoryTrace;
import dev.dzenthai.storybound.module.dialogue.NpcBinding.WhenAttacked;
import dev.dzenthai.storybound.module.dialogue.NpcBinding.WhenAttacked.Reaction;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.PathfinderMob;
import dev.dzenthai.storybound.mixin.LivingEntityAccessor;
import java.util.List;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import org.jetbrains.annotations.Nullable;

/**
 * An NPC binding's {@code invulnerable} and {@code when_attacked} (November 5, 2026). The reaction: {@code ignore} (no
 * revenge), {@code flee} (runs from the player for a few seconds after each hit) or {@code fight}, which forgives its
 * {@code patience} hits, each with the next of its {@code warning} phrases ("Careful!", "Once more and..."), and goes
 * for the player on the next. Every hit it reacts to says the next of its {@code phrases}. {@code calm_after} seconds without a hit forgive it all: the
 * reaction ends, the count and the phrases start over. Revenge is kept off by refusing the NPC the player as a target
 * ({@link LivingChangeTargetEvent}): a golem's anger never gets as far as a swing. Mobs' hits leave it to its kind.
 */
public final class NpcReactions {
    /** How long a fleeing NPC keeps running from the player after a hit. */
    static final int FLEE_TICKS = 20 * 5;
    /** Hits closer than this count once (the attack cooldown can let two through). */
    private static final int HIT_GAP_TICKS = 10;

    /** What one NPC holds against one player. */
    private static final class Grudge {
        int hits;
        long lastHit;
        long calmTicks;
        boolean fighting;
        /** Fighting or running from the player: then it does not talk with them. */
        boolean angry;
        long fleeUntil;
        /** Who, for giving up the fight once it calms down. */
        LivingEntity npc;
        ServerPlayer player;

        boolean active(long now) {
            return now - lastHit <= calmTicks;
        }
    }

    private static final Map<UUID, Map<UUID, Grudge>> GRUDGES = new HashMap<>();
    /** Fleeing NPCs and whom they run from, looked at every few ticks. */
    private static final Map<Mob, ServerPlayer> FLEEING = new HashMap<>();
    /**
     * How long after a hit it does not react to its kind's panic is held off: a villager's (its brain's "hurt by"
     * memory) and an animal's (its panic goal, started by the last mob that hurt it) last about that long.
     */
    static final int CALM_TICKS = 50;
    /** NPCs kept from panicking, until the tick. */
    private static final Map<Mob, Long> CALMING = new HashMap<>();

    private NpcReactions() {}

    /** Before the damage: a player's hit is counted and reacted to; an invulnerable NPC then takes none. */
    static void onIncomingDamage(LivingIncomingDamageEvent event) {
        LivingEntity npc = event.getEntity();
        if (!(npc.level() instanceof ServerLevel level) || !Dialogues.isEnabled()) {
            return;
        }
        NpcBinding binding = NpcBindings.find(npc).map(NpcBindings.Found::binding).orElse(null);
        if (binding == null) {
            return;
        }
        if (event.getSource().getEntity() instanceof ServerPlayer player && player != npc && binding.whenAttacked().isPresent()) {
            hit(level.getServer().getTickCount(), npc, player, binding.whenAttacked().get());
        }
        if (binding.invulnerable() && !event.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            event.setCanceled(true);
        }
    }

    /** An NPC that holds a grudge it does not fight over is refused the player as a target (its kind's anger, a golem's). */
    static void onChangeTarget(LivingChangeTargetEvent event) {
        if (event.getNewAboutToBeSetTarget() instanceof ServerPlayer player && event.getEntity().level() instanceof ServerLevel level) {
            Grudge grudge = grudge(event.getEntity(), player);
            if (grudge != null && grudge.active(level.getServer().getTickCount()) && !grudge.fighting) {
                event.setCanceled(true);
            }
        }
    }

    /** Fleeing NPCs keep running; forgiven grudges go. Every tick. */
    static void tick(MinecraftServer server) {
        long now = server.getTickCount();
        if (now % 5 == 0 && !FLEEING.isEmpty()) {
            FLEEING.entrySet().removeIf(entry -> !flee(entry.getKey(), entry.getValue(), now));
        }
        if (!CALMING.isEmpty()) {
            CALMING.entrySet().removeIf(entry -> !keepCalm(entry.getKey(), entry.getValue(), now));
        }
        if (now % 20 == 0) {
            // Calmed down: a fight is given up (November 6, 2026; before, the grudge went and the mob kept chasing).
            GRUDGES.values().forEach(byPlayer -> byPlayer.values().removeIf(grudge -> {
                if (grudge.active(now)) {
                    return false;
                }
                if (grudge.fighting && grudge.npc != null && grudge.npc.isAlive()) {
                    calm(grudge.npc, grudge.player);
                }
                return true;
            }));
            GRUDGES.values().removeIf(Map::isEmpty);
            NpcPhrases.prune(now);
        }
    }

    static void reset() {
        GRUDGES.clear();
        FLEEING.clear();
        CALMING.clear();
    }

    /**
     * Whether the NPC is angry at anyone now, as {@link #angryAt} for one player: then it does not chatter (everyone near
     * hears chatter). A hit it ignores or forgives does not keep it quiet (November 6, 2026; before, any hit did, and a
     * golem chasing a player by its own anger chattered on).
     */
    static boolean angryAtAnyone(Entity npc, long now) {
        Map<UUID, Grudge> byPlayer = GRUDGES.get(npc.getUUID());
        if (byPlayer != null && byPlayer.values().stream().anyMatch(grudge -> grudge.angry && grudge.active(now))) {
            return true;
        }
        // A hostile kind's own target is its nature, as for talking (see angryAt): it chatters through its fights.
        if (npc instanceof Mob mob && !(npc instanceof Enemy)) {
            return mob.getTarget() instanceof ServerPlayer || mob.getBrain().checkMemory(MemoryModuleType.ATTACK_TARGET, MemoryStatus.REGISTERED)
                    && mob.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null) instanceof ServerPlayer;
        }
        return false;
    }

    /**
     * Whether the NPC is angry at the player (November 6, 2026): fighting them or running from them since a hit, until
     * it calms down, or going for them by its kind's own anger (a golem without a binding's reaction; not a hostile kind,
     * which always does). It does not talk with them meanwhile; other players it talks with as ever.
     */
    public static boolean angryAt(Entity npc, ServerPlayer player) {
        if (npc.level() instanceof ServerLevel level) {
            Grudge grudge = grudge(npc, player);
            if (grudge != null && grudge.angry && grudge.active(level.getServer().getTickCount())) {
                return true;
            }
        }
        // A hostile kind (a vindicator, a zombie) goes for the player always: that is its nature, not anger at them, and
        // an author who gives it dialogues wants it to talk (an enemy's taunts in a duel; November 6, 2026, before, it
        // never did). Anger from a hit (above) still counts, and so does a friendly kind's own (a golem's).
        if (npc instanceof Mob mob && !(npc instanceof Enemy)) {
            return mob.getTarget() == player || mob.getBrain().checkMemory(MemoryModuleType.ATTACK_TARGET, MemoryStatus.REGISTERED)
                    && mob.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null) == player;
        }
        return false;
    }

    /**
     * An angry NPC asked to talk: it says the next of its reaction's phrases instead, if it has them (a text; a line that
     * is a dialogue is skipped, as it would start a talk), else nothing. Returns whether it refused.
     */
    static boolean refuse(Entity npc, ServerPlayer player, @Nullable NpcBinding binding) {
        if (!angryAt(npc, player)) {
            return false;
        }
        StoryTrace.note(player, "npc " + npc.getName().getString(), "talk: refused, it is angry at the player");
        if (npc instanceof LivingEntity living && binding != null) {
            binding.whenAttacked().flatMap(WhenAttacked::phrases).ifPresent(phrases ->
                    NpcPhrases.say(living, phrases, NpcPhrases.hitCounter(living, player), player, player.server.getTickCount(), true));
        }
        return true;
    }

    /** How many hits of the player the NPC holds now; for tests. */
    public static int hits(Entity npc, ServerPlayer player) {
        Grudge grudge = grudge(npc, player);
        return grudge == null ? 0 : grudge.hits;
    }

    private static void hit(long now, LivingEntity npc, ServerPlayer player, WhenAttacked rules) {
        Map<UUID, Grudge> byPlayer = GRUDGES.computeIfAbsent(npc.getUUID(), uuid -> new HashMap<>());
        Grudge grudge = byPlayer.get(player.getUUID());
        if (grudge != null && now - grudge.lastHit < HIT_GAP_TICKS) {
            return;
        }
        if (grudge == null || !grudge.active(now)) {
            // Forgiven since: the count and the phrases start over.
            grudge = new Grudge();
            grudge.npc = npc;
            grudge.player = player;
            byPlayer.put(player.getUUID(), grudge);
            NpcPhrases.forget(NpcPhrases.hitCounter(npc, player));
            NpcPhrases.forget(NpcPhrases.warningCounter(npc, player));
        }
        grudge.hits++;
        grudge.lastHit = now;
        grudge.calmTicks = Math.round(rules.calmAfter() * 20);
        boolean forgiven = rules.reaction() == Reaction.FIGHT && grudge.hits <= rules.forgiven();
        if (!forgiven && rules.reaction() != Reaction.IGNORE && !grudge.angry) {
            // Angry now: a conversation with this player ends first, so the reaction's own dialogue line can start.
            grudge.angry = true;
            Dialogues.attackedBy(player, npc, now);
        }
        if (forgiven) {
            rules.warning().ifPresent(warning -> NpcPhrases.say(npc, warning, NpcPhrases.warningCounter(npc, player), player, now, true));
        } else {
            rules.phrases().ifPresent(phrases -> NpcPhrases.say(npc, phrases, NpcPhrases.hitCounter(npc, player), player, now, true));
            rules.dialogue().ifPresent(dialogue -> startScene(npc, player, dialogue));
        }
        if (rules.reaction() == Reaction.FIGHT && !forgiven) {
            if (!grudge.fighting) {
                StoryTrace.note(player, "npc " + npc.getName().getString(), "hit " + grudge.hits + ": fights back");
            }
            grudge.fighting = true;
            if (npc instanceof Mob mob) {
                mob.setTarget(player);
                if (mob.getBrain().checkMemory(MemoryModuleType.ATTACK_TARGET, MemoryStatus.REGISTERED)) {
                    mob.getBrain().setMemory(MemoryModuleType.ATTACK_TARGET, player);
                }
            }
            return;
        }
        StoryTrace.note(player, "npc " + npc.getName().getString(), "hit " + grudge.hits + ": " + (rules.reaction() == Reaction.FIGHT
                ? "forgiven (" + rules.forgiven() + ")" : rules.reaction().getSerializedName()));
        calm(npc, player);
        if (rules.reaction() != Reaction.FLEE && npc instanceof Mob mob) {
            // Not reacting is not panicking either: its kind's own flight is held off.
            CALMING.put(mob, now + CALM_TICKS);
        }
        if (rules.reaction() == Reaction.FLEE && npc instanceof Mob mob) {
            grudge.fleeUntil = now + FLEE_TICKS;
            FLEEING.put(mob, player);
            flee(mob, player, now);
        }
    }

    /**
     * A hit's dialogue (November 6, 2026): started with the player after the one they are in; not again while it runs
     * or waits, so every hit of a fight does not queue it once more.
     */
    private static void startScene(LivingEntity npc, ServerPlayer player, ResourceLocation dialogue) {
        boolean running = Dialogues.state(player).map(state -> state.dialogue().equals(dialogue)).orElse(false);
        if (!running && !Dialogues.queued(player).contains(dialogue)) {
            Dialogues.startOrQueue(player, dialogue, npc);
        }
    }

    /** Takes the player away as the NPC's target, if they were it (revenge chosen before this hit was counted). */
    private static void calm(LivingEntity npc, ServerPlayer player) {
        if (npc instanceof Mob mob) {
            if (mob.getTarget() == player) {
                mob.setTarget(null);
            }
            if (mob.getBrain().checkMemory(MemoryModuleType.ATTACK_TARGET, MemoryStatus.REGISTERED)
                    && mob.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null) == player) {
                mob.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
            }
        }
        if (npc instanceof NeutralMob neutral && player.getUUID().equals(neutral.getPersistentAngerTarget())) {
            neutral.stopBeingAngry();
        }
    }

    /**
     * Before the entities tick: the NPCs kept calm forget they were hurt, before a villager's brain reads it. The game
     * writes the last damage after every damage event, and the brain reads it for two seconds and panics; so it is wiped
     * here, ahead of the brain, as well as at the end of the tick (November 6, 2026; before, only at the end, and a
     * villager panicked for a moment now and then).
     */
    static void beforeTick() {
        CALMING.keySet().forEach(NpcReactions::forgetHurt);
    }

    private static void forgetHurt(Mob mob) {
        ((LivingEntityAccessor) mob).storybound$setLastDamageSource(null);
        mob.setLastHurtByMob(null);
        Brain<?> brain = mob.getBrain();
        for (MemoryModuleType<?> memory : List.of(MemoryModuleType.HURT_BY, MemoryModuleType.HURT_BY_ENTITY)) {
            if (brain.checkMemory(memory, MemoryStatus.REGISTERED)) {
                brain.eraseMemory(memory);
            }
        }
    }

    /**
     * Holds off the NPC's panic for this tick: no damage remembered (a villager's panic reads it), no mob counted as
     * having hurt it (an animal's panic goal starts from that), and a villager already panicking goes back to its day;
     * its panic run is stopped at once. False once the time is up or it is gone.
     */
    private static boolean keepCalm(Mob mob, long until, long now) {
        if (now > until || !mob.isAlive()) {
            return false;
        }
        boolean panicking = mob.getLastHurtByMob() != null || mob.getBrain().isActive(Activity.PANIC);
        forgetHurt(mob);
        Brain<?> brain = mob.getBrain();
        if (brain.isActive(Activity.PANIC)) {
            brain.updateActivityFromSchedule(mob.level().getDayTime(), mob.level().getGameTime());
        }
        if (panicking) {
            mob.getNavigation().stop();
        }
        return true;
    }

    /** One step away from the player; false once it is done (time up, either gone, or a kind that cannot walk away). */
    private static boolean flee(Mob mob, ServerPlayer player, long now) {
        Grudge grudge = grudge(mob, player);
        if (grudge == null || now > grudge.fleeUntil || !mob.isAlive() || !player.isAlive() || mob.level() != player.level()
                || !(mob instanceof PathfinderMob walker)) {
            return false;
        }
        Vec3 away = DefaultRandomPos.getPosAway(walker, 16, 7, player.position());
        if (away != null) {
            walker.getNavigation().moveTo(away.x, away.y, away.z, 1.4);
        }
        return true;
    }

    private static @Nullable Grudge grudge(Entity npc, ServerPlayer player) {
        Map<UUID, Grudge> byPlayer = GRUDGES.get(npc.getUUID());
        return byPlayer == null ? null : byPlayer.get(player.getUUID());
    }
}
