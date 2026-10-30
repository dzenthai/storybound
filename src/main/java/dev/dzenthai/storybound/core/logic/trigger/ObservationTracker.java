package dev.dzenthai.storybound.core.logic.trigger;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.logic.StoryContext;
import dev.dzenthai.storybound.core.text.ResolvedText;
import dev.dzenthai.storybound.network.ObservationPayload;
import dev.dzenthai.storybound.network.StoryboundNetwork;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Fires {@code observed} when a player has looked straight at its target long enough (October 30, 2026). Every
 * {@link #INTERVAL} ticks, for each player, while something subscribes to {@code observed}: what is under the crosshair
 * (an entity, else the block; a wall in between hides what is behind it), and for each subscription that the target
 * matches, within its distance, and that would do something for the player now ({@link Subscription#active}: an
 * objective of their stage not done yet, a section not open yet), its time adds up. A bar under the crosshair shows the
 * one nearest to done. Looking away pauses it; {@link #GRACE} ticks later it is lost. Each subscription counts on its
 * own and fires alone ({@link TriggerDispatcher#fireOne}); a target it counted does not count again for it until the
 * player has gone out of its distance (studying three bookshelves needs three of them).
 */
public final class ObservationTracker {
    public static final int INTERVAL = 4;
    /** How long looking away keeps the progress. */
    public static final int GRACE = 40;
    /** A counted target counts again once the player is this much farther than the subscription's distance. */
    private static final double FORGET_MARGIN = 8;

    /** How far one subscription got for one player. */
    private static final class Progress {
        int ticks;
        int lastSeen;
        ResolvedText label;

        Progress(ResolvedText label) {
            this.label = label;
        }
    }

    /** A target a subscription counted, by where it was. */
    private record Counted(Vec3 at, ResourceLocation dimension) {}

    private static final Map<UUID, Map<Subscription<?>, Progress>> PROGRESS = new HashMap<>();
    private static final Map<UUID, Map<Subscription<?>, Map<Object, Counted>>> COUNTED = new HashMap<>();
    /** The players whose bar is shown, so it is hidden once. */
    private static final Map<UUID, Boolean> SHOWN = new HashMap<>();

    private ObservationTracker() {}

    static void tick(MinecraftServer server) {
        if (!TriggerDispatcher.hasSubscribers(BuiltinTriggers.OBSERVED.get())) {
            if (!PROGRESS.isEmpty() || !SHOWN.isEmpty()) {
                server.getPlayerList().getPlayers().forEach(player -> hide(player));
                reset();
            }
            return;
        }
        int tick = server.getTickCount();
        for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
            // Each player on a tick of their own, so a full server has no spike.
            if (Math.floorMod(tick + player.getId(), INTERVAL) == 0) {
                check(player, tick);
            }
        }
    }

    /** One check for one player at server tick {@code now}; public for tests. */
    @SuppressWarnings("unchecked")
    public static void check(ServerPlayer player, int now) {
        UUID id = player.getUUID();
        if (player.isSpectator() || !player.isAlive()) {
            forget(id);
            hide(player);
            return;
        }
        List<Subscription<?>> subscriptions = TriggerDispatcher.subscriptions(BuiltinTriggers.OBSERVED.get());
        Map<Subscription<?>, Progress> progress = PROGRESS.computeIfAbsent(id, uuid -> new IdentityHashMap<>());
        Map<Subscription<?>, Map<Object, Counted>> counted = COUNTED.computeIfAbsent(id, uuid -> new IdentityHashMap<>());
        // Subscriptions of a reload before are gone.
        progress.keySet().retainAll(identities(subscriptions));
        counted.keySet().retainAll(identities(subscriptions));
        double reach = 0;
        for (Subscription<?> subscription : subscriptions) {
            reach = Math.max(reach, ((CoreTriggers.Observed) subscription.trigger()).distance());
        }
        ResourceLocation dimension = player.level().dimension().location();
        Vec3 eye = player.getEyePosition();
        Looked looked = look(player, reach);
        Progress shown = null;
        boolean shownActive = false;
        for (Subscription<?> raw : subscriptions) {
            Subscription<CoreTriggers.Observation> subscription = (Subscription<CoreTriggers.Observation>) raw;
            CoreTriggers.Observed trigger = (CoreTriggers.Observed) subscription.trigger();
            Map<Object, Counted> done = counted.computeIfAbsent(raw, s -> new HashMap<>());
            // Gone out of reach (or to another dimension): it may count again.
            done.values().removeIf(at -> !at.dimension().equals(dimension) || at.at().distanceTo(eye) > trigger.distance() + FORGET_MARGIN);
            StoryContext context = looked == null ? null : new StoryContext(player, looked.target().entity());
            boolean observing = looked != null && looked.distance() <= trigger.distance() && !done.containsKey(looked.key())
                    && matches(subscription, context, looked.target()) && active(subscription, context);
            Progress current = progress.get(raw);
            if (observing) {
                if (current == null) {
                    current = new Progress(label(player, trigger, looked.target()));
                    progress.put(raw, current);
                }
                current.ticks += INTERVAL;
                current.lastSeen = now;
                if (current.ticks >= Math.round(trigger.time() * 20)) {
                    progress.remove(raw);
                    done.put(looked.key(), new Counted(looked.at(), dimension));
                    TriggerDispatcher.fireOne(subscription, context, looked.target());
                    continue;
                }
            } else if (current != null && now - current.lastSeen > GRACE) {
                progress.remove(raw);
                continue;
            }
            if (current != null && (shown == null || fraction(current, raw) > fraction(shown, shownOf(progress, shown)))) {
                shown = current;
                shownActive = observing;
            }
        }
        if (shown == null) {
            hide(player);
        } else {
            Subscription<?> of = shownOf(progress, shown);
            StoryboundNetwork.sendToPlayer(player, new ObservationPayload(Optional.of(shown.label), fraction(shown, of), shownActive));
            SHOWN.put(id, true);
        }
    }

    /** What the player looks straight at, and from how far. */
    private record Looked(CoreTriggers.Observation target, Object key, Vec3 at, double distance) {}

    private static @Nullable Looked look(ServerPlayer player, double reach) {
        if (reach <= 0) {
            return null;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 view = player.getViewVector(1f);
        Vec3 end = eye.add(view.scale(reach));
        ResourceLocation dimension = player.level().dimension().location();
        // The outline, so a flower or tall grass can be looked at; a wall, glass too, stops the look.
        BlockHitResult block = player.level().clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        double blockDistance = block.getType() == HitResult.Type.MISS ? reach : block.getLocation().distanceTo(eye);
        Vec3 blockEnd = eye.add(view.scale(blockDistance));
        AABB box = player.getBoundingBox().expandTowards(view.scale(blockDistance)).inflate(1);
        EntityHitResult entity = ProjectileUtil.getEntityHitResult(player, eye, blockEnd, box,
                candidate -> !candidate.isSpectator() && candidate.isPickable() && candidate.isAlive(), blockDistance * blockDistance);
        if (entity != null) {
            Entity target = entity.getEntity();
            return new Looked(new CoreTriggers.Observation(target, null, null, dimension), target.getUUID(), target.position(),
                    entity.getLocation().distanceTo(eye));
        }
        if (block.getType() == HitResult.Type.MISS) {
            return null;
        }
        BlockPos pos = block.getBlockPos();
        BlockState state = player.level().getBlockState(pos);
        return new Looked(new CoreTriggers.Observation(null, pos.immutable(), state, dimension), pos.immutable(), Vec3.atCenterOf(pos), blockDistance);
    }

    private static boolean matches(Subscription<CoreTriggers.Observation> subscription, @Nullable StoryContext context, CoreTriggers.Observation target) {
        try {
            return context != null && subscription.trigger().matches(context, target);
        } catch (RuntimeException e) {
            Storybound.LOGGER.error("Storybound: {} failed", subscription.name(), e);
            return false;
        }
    }

    private static boolean active(Subscription<?> subscription, @Nullable StoryContext context) {
        try {
            return context != null && subscription.active().test(context);
        } catch (RuntimeException e) {
            Storybound.LOGGER.error("Storybound: {} failed", subscription.name(), e);
            return false;
        }
    }

    /** The trigger's own text, else "Observing: <the target>". */
    private static ResolvedText label(ServerPlayer player, CoreTriggers.Observed trigger, CoreTriggers.Observation target) {
        if (trigger.text().isPresent()) {
            return trigger.text().get().resolve(player);
        }
        Component name = target.entity() != null ? target.entity().getDisplayName()
                : target.state() != null ? target.state().getBlock().getName() : Component.empty();
        return new ResolvedText("storybound.observation.default", false, List.of(name), Optional.empty(), Optional.empty());
    }

    private static float fraction(Progress progress, @Nullable Subscription<?> of) {
        float needed = of == null ? 1 : Math.max(1, Math.round(((CoreTriggers.Observed) of.trigger()).time() * 20));
        return Math.min(1f, progress.ticks / needed);
    }

    private static @Nullable Subscription<?> shownOf(Map<Subscription<?>, Progress> progress, Progress shown) {
        for (Map.Entry<Subscription<?>, Progress> entry : progress.entrySet()) {
            if (entry.getValue() == shown) {
                return entry.getKey();
            }
        }
        return null;
    }

    private static List<Subscription<?>> identities(List<Subscription<?>> subscriptions) {
        // retainAll on an identity map's key set compares by identity, as the map does.
        return new ArrayList<>(subscriptions);
    }

    private static void hide(ServerPlayer player) {
        if (SHOWN.remove(player.getUUID()) != null) {
            StoryboundNetwork.sendToPlayer(player, new ObservationPayload(Optional.empty(), 0, false));
        }
    }

    /** How far the player got with the subscription, 0 to 1; for tests. */
    public static float progress(ServerPlayer player, Subscription<?> subscription) {
        Progress progress = PROGRESS.getOrDefault(player.getUUID(), Map.of()).get(subscription);
        return progress == null ? 0 : fraction(progress, subscription);
    }

    static void forget(UUID player) {
        PROGRESS.remove(player);
        COUNTED.remove(player);
    }

    static void reset() {
        PROGRESS.clear();
        COUNTED.clear();
        SHOWN.clear();
    }
}
