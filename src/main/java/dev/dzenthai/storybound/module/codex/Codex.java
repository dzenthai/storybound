package dev.dzenthai.storybound.module.codex;

import dev.dzenthai.storybound.config.StoryboundConfig;
import dev.dzenthai.storybound.core.event.CodexUnlockedEvent;
import dev.dzenthai.storybound.core.logic.Conditions;
import dev.dzenthai.storybound.core.logic.StoryContext;
import dev.dzenthai.storybound.core.notify.Notifications;
import dev.dzenthai.storybound.core.notify.NotifyKind;
import dev.dzenthai.storybound.module.journal.Journal;
import dev.dzenthai.storybound.network.CodexKillsPayload;
import dev.dzenthai.storybound.network.CodexSyncPayload;
import dev.dzenthai.storybound.network.StoryboundNetwork;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;

/** The Codex rules: counting sightings, kills and reads, opening sections, syncing and announcing them. */
public final class Codex {
    private Codex() {}

    /** {@code modules.codex}; while disabled, nothing is counted or opened (closing still works). */
    public static boolean isEnabled() {
        return StoryboundConfig.COMMON.codex.get();
    }

    public static @Nullable CodexDefinition definition(ResourceLocation entry) {
        return CodexRegistries.CODEX.serverStore().getOrNull(entry);
    }

    public static CodexData data(ServerPlayer player) {
        return player.getData(CodexRegistries.DATA);
    }

    /**
     * A source of the entry happened for the player (they saw or killed its entity, read a document):
     * counts it and opens every section it now satisfies.
     */
    /** Whether the source would open a section of the entry now: one waiting for it, its conditions holding (quietly). */
    static boolean wouldOpen(StoryContext context, ResourceLocation entry, Unlock.Source source) {
        CodexDefinition definition = definition(entry);
        if (definition == null || !isEnabled()) {
            return false;
        }
        CodexData data = data(context.player());
        return definition.sections().stream().anyMatch(section -> !data.isUnlocked(entry, section.id()) && section.unlock().on().contains(source)
                && section.unlock().conditions().test(context));
    }

    static void observed(StoryContext context, ResourceLocation entry, Unlock.Source source) {
        CodexDefinition definition = definition(entry);
        if (definition == null || !isEnabled()) {
            return;
        }
        CodexData data = data(context.player());
        int counter = switch (source) {
            case Unlock.Source.Seen seen -> data.countSeen(entry);
            case Unlock.Source.Killed killed -> data.countKill(entry);
            case Unlock.Source.Read read -> Integer.MAX_VALUE;
            case Unlock.Source.Observed observed -> Integer.MAX_VALUE;
            case Unlock.Source.Manual manual -> 0;
        };
        boolean wasNew = data.sections(entry).isEmpty();
        List<String> opened = new ArrayList<>();
        for (CodexDefinition.Section section : definition.sections()) {
            Unlock unlock = section.unlock();
            if (!data.isUnlocked(entry, section.id()) && unlock.on().contains(source) && counter >= unlock.count()
                    && Conditions.check(unlock.conditions(), context, "codex " + entry + " section " + section.id()) && data.open(entry, section.id())) {
                opened.add(section.id());
            }
        }
        // Counters changed even when nothing opened; the attachment is saved with the player either way.
        if (!opened.isEmpty()) {
            changed(context.player(), entry, definition, opened, wasNew);
        } else if (source instanceof Unlock.Source.Killed && definition.showKills()) {
            // One number, not the whole Codex: kills come often (a mob farm).
            StoryboundNetwork.sendToPlayer(context.player(), new CodexKillsPayload(Map.of(entry, counter), false));
        }
    }

    /**
     * Sections that open on reading a document the player has read already (before the entry or the section existed,
     * or while its conditions did not hold) open now: a read is a state that holds, as quests and story events count
     * it (October 29, 2026). On joining and after a reload.
     */
    public static void catchUpReads(ServerPlayer player) {
        if (!isEnabled()) {
            return;
        }
        StoryContext context = StoryContext.of(player);
        CodexRegistries.CODEX.serverStore().all().forEach((entry, definition) -> definition.sections().stream()
                .flatMap(section -> section.unlock().on().stream())
                .filter(source -> source instanceof Unlock.Source.Read read && Journal.of(player).hasRead(read.document()))
                .distinct()
                .forEach(source -> observed(context, entry, source)));
    }

    /** Opens one section, or every section when {@code section} is null. @return false for an unknown entry or section, or a disabled module */
    public static boolean unlock(ServerPlayer player, ResourceLocation entry, @Nullable String section) {
        CodexDefinition definition = definition(entry);
        if (definition == null || !isEnabled() || (section != null && definition.section(section).isEmpty())) {
            return false;
        }
        CodexData data = data(player);
        boolean wasNew = data.sections(entry).isEmpty();
        List<String> opened = new ArrayList<>();
        for (CodexDefinition.Section candidate : definition.sections()) {
            if ((section == null || candidate.id().equals(section)) && data.open(entry, candidate.id())) {
                opened.add(candidate.id());
            }
        }
        if (!opened.isEmpty()) {
            changed(player, entry, definition, opened, wasNew);
        }
        return true;
    }

    /** Closes one section, or forgets the whole entry when {@code section} is null. */
    public static boolean lock(ServerPlayer player, ResourceLocation entry, @Nullable String section) {
        boolean changed = data(player).close(entry, section);
        if (changed) {
            sync(player);
        }
        return changed;
    }

    public static void sync(ServerPlayer player) {
        StoryboundNetwork.sendToPlayer(player, new CodexSyncPayload(data(player).unlocked()));
        StoryboundNetwork.sendToPlayer(player, new CodexKillsPayload(kills(player), true));
    }

    /** The kills counted for entries that show them. */
    private static Map<ResourceLocation, Integer> kills(ServerPlayer player) {
        CodexData data = data(player);
        Map<ResourceLocation, Integer> kills = new HashMap<>();
        CodexRegistries.CODEX.serverStore().all().forEach((entry, definition) -> {
            if (definition.showKills() && data.kills(entry) > 0) {
                kills.put(entry, data.kills(entry));
            }
        });
        return kills;
    }

    private static void changed(ServerPlayer player, ResourceLocation entry, CodexDefinition definition, List<String> opened, boolean wasNew) {
        sync(player);
        if (definition.announce()) {
            Notifications.send(player, wasNew ? NotifyKind.CODEX_NEW : NotifyKind.CODEX_UPDATED, definition.title().resolve(player), Optional.empty());
        }
        for (int i = 0; i < opened.size(); i++) {
            NeoForge.EVENT_BUS.post(new CodexUnlockedEvent(player, entry, opened.get(i), wasNew && i == 0));
        }
    }
}
