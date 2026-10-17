package dev.dzenthai.storybound.module.boss;

import dev.dzenthai.storybound.config.StoryboundConfig;
import dev.dzenthai.storybound.core.event.BossDefeatedEvent;
import dev.dzenthai.storybound.core.logic.Action;
import dev.dzenthai.storybound.core.logic.Actions;
import dev.dzenthai.storybound.core.logic.KillCredit;
import dev.dzenthai.storybound.core.logic.OnFail;
import dev.dzenthai.storybound.core.logic.StoryContext;
import dev.dzenthai.storybound.core.logic.trigger.TriggerDispatcher;
import dev.dzenthai.storybound.core.notify.Notifications;
import dev.dzenthai.storybound.core.notify.NotifyKind;
import dev.dzenthai.storybound.module.codex.Unlock;
import dev.dzenthai.storybound.network.BossSyncPayload;
import dev.dzenthai.storybound.network.StoryboundNetwork;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;

/**
 * The Bosses rules. While a boss fights, the server keeps "player → damage dealt"; when it dies, the victory
 * goes to every player who dealt at least {@code participation} of its maximum health, to the killer, and to
 * their party members near the boss.
 */
public final class Bosses {
    private static List<Map.Entry<ResourceLocation, BossDefinition>> bosses = List.of();
    /** Boss entity → its fight. */
    private static final Map<UUID, Fight> FIGHTS = new HashMap<>();

    private Bosses() {}

    /** {@code modules.bosses}; while disabled, fights are not tracked and nothing is recorded. */
    public static boolean isEnabled() {
        return StoryboundConfig.COMMON.bosses.get();
    }

    public static @Nullable BossDefinition definition(ResourceLocation boss) {
        return BossRegistries.BOSSES.serverStore().getOrNull(boss);
    }

    public static BossData data(ServerPlayer player) {
        return player.getData(BossRegistries.DATA);
    }

    /** Caches the loaded bosses in id order; the first whose selector matches an entity is its boss. */
    static void rebuild() {
        List<Map.Entry<ResourceLocation, BossDefinition>> sorted = new ArrayList<>(BossRegistries.BOSSES.serverStore().all().entrySet());
        sorted.sort(Map.Entry.comparingByKey(Comparator.comparing(ResourceLocation::toString)));
        bosses = List.copyOf(sorted);
    }

    public static Optional<ResourceLocation> bossOf(Entity entity) {
        for (Map.Entry<ResourceLocation, BossDefinition> boss : bosses) {
            if (boss.getValue().entity().matches(entity)) {
                return Optional.of(boss.getKey());
            }
        }
        return Optional.empty();
    }

    /** A player hit a boss: the fight starts or goes on, and the player has met it. */
    static void damaged(LivingEntity victim, ServerPlayer attacker, float amount) {
        if (!isEnabled()) {
            return;
        }
        ResourceLocation boss = bossOf(victim).orElse(null);
        if (boss == null) {
            return;
        }
        Fight fight = FIGHTS.computeIfAbsent(victim.getUUID(), id -> new Fight(boss, attacker.server.getTickCount(), new HashMap<>()));
        fight.damage().merge(attacker.getUUID(), amount, Float::sum);
        encounter(attacker, boss);
    }

    /** A boss died: every credited player gets the victory. */
    static void died(LivingEntity victim, @Nullable ServerPlayer killer) {
        Fight fight = FIGHTS.remove(victim.getUUID());
        if (!isEnabled()) {
            return;
        }
        ResourceLocation boss = fight != null ? fight.boss() : bossOf(victim).orElse(null);
        BossDefinition definition = boss != null ? definition(boss) : null;
        MinecraftServer server = victim.getServer();
        if (definition == null || server == null) {
            return;
        }
        long ticks = fight != null ? server.getTickCount() - fight.startTick() : 0;
        for (ServerPlayer player : credited(victim, killer, fight, definition)) {
            defeated(player, boss, definition, victim, ticks);
        }
    }

    /** Players who dealt enough damage, the killer, and the party members of all of them near the boss. Public for tests. */
    public static List<ServerPlayer> credited(LivingEntity victim, @Nullable ServerPlayer killer, @Nullable Fight fight, BossDefinition definition) {
        Set<ServerPlayer> credited = new LinkedHashSet<>();
        MinecraftServer server = victim.getServer();
        if (fight != null && server != null) {
            float needed = definition.participation() * victim.getMaxHealth();
            fight.damage().forEach((id, damage) -> {
                ServerPlayer player = server.getPlayerList().getPlayer(id);
                if (player != null && damage >= needed) {
                    credited.add(player);
                }
            });
        }
        if (killer != null) {
            credited.add(killer);
        }
        for (ServerPlayer player : List.copyOf(credited)) {
            credited.addAll(KillCredit.partyNear(player, victim));
        }
        return List.copyOf(credited);
    }

    /** The current fight with a boss entity, if any. Public for tests. */
    public static @Nullable Fight fight(Entity boss) {
        return FIGHTS.get(boss.getUUID());
    }

    /** The player saw the boss (or hit it): it appears in their Bosses tab; sections opening on {@code seen} count it. */
    static void sighted(StoryContext context, ResourceLocation boss) {
        BossDefinition definition = definition(boss);
        if (definition == null || !isEnabled()) {
            return;
        }
        BossData data = data(context.player());
        BossData.Record before = data.get(boss);
        BossData.Record record = before.sighted();
        data.put(record);
        openSections(context, boss, definition, Unlock.Source.SEEN, record.seen());
        announceEncounter(context.player(), definition, before);
        sync(context.player());
    }

    static void read(StoryContext context, ResourceLocation boss, Unlock.Source source) {
        BossDefinition definition = definition(boss);
        // Read before meeting the boss: the section waits, the entry shows up once the boss is encountered.
        if (definition == null || !isEnabled()) {
            return;
        }
        if (openSections(context, boss, definition, source, Integer.MAX_VALUE)) {
            sync(context.player());
        }
    }

    private static void encounter(ServerPlayer player, ResourceLocation boss) {
        BossDefinition definition = definition(boss);
        BossData data = data(player);
        BossData.Record before = data.get(boss);
        if (definition == null || before.encountered()) {
            return;
        }
        data.put(before.encounter());
        announceEncounter(player, definition, before);
        sync(player);
    }

    private static void defeated(ServerPlayer player, ResourceLocation boss, BossDefinition definition, LivingEntity entity, long ticks) {
        BossData data = data(player);
        BossData.Record before = data.get(boss);
        BossData.Record after = before.victory(System.currentTimeMillis(), ticks);
        data.put(after);
        StoryContext context = new StoryContext(player, entity);
        openSections(context, boss, definition, Unlock.Source.KILLED, after.victories());
        sync(player);
        if (definition.announce()) {
            Notifications.send(player, NotifyKind.BOSS_DEFEATED, definition.title().resolve(player), Optional.empty());
        }
        boolean first = before.victories() == 0;
        if (first) {
            run(definition.onFirstKill(), context, "boss " + boss + " on_first_kill");
        }
        run(definition.onKill(), context, "boss " + boss + " on_kill");
        NeoForge.EVENT_BUS.post(new BossDefeatedEvent(player, boss, entity, first, ticks));
        TriggerDispatcher.fire(BossLogic.BOSS_KILLED_TRIGGER.get(), context, new BossLogic.Defeated(boss, first));
    }

    /** @return true if a section opened */
    private static boolean openSections(StoryContext context, ResourceLocation boss, BossDefinition definition, Unlock.Source source, int counter) {
        BossData data = data(context.player());
        boolean opened = false;
        for (String section : BossDefinition.SECTIONS) {
            Unlock unlock = definition.unlock(section).orElse(null);
            BossData.Record record = data.get(boss);
            if (unlock != null && !record.isUnlocked(section) && unlock.on().contains(source) && counter >= unlock.count()
                    && unlock.conditions().test(context)) {
                data.put(record.open(section));
                opened = true;
            }
        }
        return opened;
    }

    /** Opens a section (it also marks the boss as met). @return false for an unknown boss or section, or a disabled module */
    public static boolean unlock(ServerPlayer player, ResourceLocation boss, String section) {
        BossDefinition definition = definition(boss);
        if (definition == null || !isEnabled() || definition.unlock(section).isEmpty()) {
            return false;
        }
        BossData data = data(player);
        BossData.Record before = data.get(boss);
        data.put(before.encounter().open(section));
        announceEncounter(player, definition, before);
        sync(player);
        return true;
    }

    /** Forgets everything the player knows about the boss, statistics included. */
    public static boolean reset(ServerPlayer player, ResourceLocation boss) {
        boolean removed = data(player).remove(boss);
        if (removed) {
            sync(player);
        }
        return removed;
    }

    public static void sync(ServerPlayer player) {
        StoryboundNetwork.sendToPlayer(player, new BossSyncPayload(data(player).known()));
    }

    /** A fight with a boss that left the world without dying (despawned, unloaded) is forgotten. */
    static void left(Entity entity) {
        FIGHTS.remove(entity.getUUID());
    }

    static void reset() {
        FIGHTS.clear();
    }

    private static void announceEncounter(ServerPlayer player, BossDefinition definition, BossData.Record before) {
        if (!before.encountered() && definition.announce()) {
            Notifications.send(player, NotifyKind.BOSS_ENCOUNTERED, definition.title().resolve(player), Optional.empty());
        }
    }

    private static void run(List<Action> actions, StoryContext context, String origin) {
        if (!actions.isEmpty()) {
            Actions.runAll(actions, context, origin, OnFail.ABORT);
        }
    }

    /**
     * @param startTick the server tick of the first player hit
     * @param damage    player → damage dealt to the boss in this fight
     */
    public record Fight(ResourceLocation boss, long startTick, Map<UUID, Float> damage) {}
}
