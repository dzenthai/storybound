package dev.dzenthai.storybound.module.journal;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.config.StoryboundConfig;
import dev.dzenthai.storybound.core.state.StoryMigrations;
import dev.dzenthai.storybound.network.JournalSyncPayload;
import dev.dzenthai.storybound.network.StoryboundNetwork;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/** Server side of the Journal: reading marks, archive, notes and their sync to the owner. */
public final class Journal {
    private static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, Storybound.MOD_ID);

    public static final Supplier<AttachmentType<JournalData>> DATA = ATTACHMENT_TYPES.register("journal",
            () -> AttachmentType.builder(() -> new JournalData()).serialize(JournalData.CODEC).copyOnDeath().build());

    private Journal() {}

    public static void register(IEventBus modBus) {
        ATTACHMENT_TYPES.register(modBus);
        StoryMigrations.registerPlayer((player, renames) -> player.getData(DATA).migrate(renames));
    }

    /** {@code modules.journal}; a disabled Journal still tracks reading, it just keeps no archive. */
    public static boolean isEnabled() {
        return StoryboundConfig.COMMON.journal.get();
    }

    public static JournalData of(ServerPlayer player) {
        return player.getData(DATA);
    }

    /**
     * Marks a document as read and, if {@code addToJournal}, archives it.
     *
     * @return true if this is the first time the player reads it
     */
    public static boolean markRead(ServerPlayer player, ResourceLocation document, boolean addToJournal) {
        JournalData data = of(player);
        boolean first = data.markRead(document);
        boolean archived = addToJournal && isEnabled() && data.addDocument(document);
        if (archived) {
            sync(player);
        }
        return first;
    }

    /**
     * Archives what the player has read that belongs in the Journal ({@code belongs}) but is not there: read while the
     * Journal was disabled, or before the document's {@code add_on_read} was turned on (October 29, 2026). On joining
     * and after a reload; the caller syncs.
     */
    public static void archiveMissing(ServerPlayer player, Predicate<ResourceLocation> belongs) {
        if (!isEnabled()) {
            return;
        }
        JournalData data = of(player);
        for (ResourceLocation document : List.copyOf(data.read())) {
            if (belongs.test(document)) {
                data.addDocument(document);
            }
        }
    }

    public static void putNote(ServerPlayer player, JournalNote note) {
        if (!isEnabled()) {
            Storybound.LOGGER.debug("Storybound: Journal is disabled, note {} dropped", note.id());
            return;
        }
        of(player).putNote(note);
        sync(player);
    }

    public static void sync(ServerPlayer player) {
        JournalData data = of(player);
        StoryboundNetwork.sendToPlayer(player, new JournalSyncPayload(isEnabled(), List.copyOf(data.documents()), List.copyOf(data.notes())));
    }
}
