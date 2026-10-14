package dev.dzenthai.storybound.client;

import dev.dzenthai.storybound.module.journal.JournalNote;
import java.util.List;
import net.minecraft.resources.ResourceLocation;

/** The local player's Journal as last sent by the server. */
public final class ClientJournal {
    private static volatile boolean enabled = true;
    private static volatile List<ResourceLocation> documents = List.of();
    private static volatile List<JournalNote> notes = List.of();

    /** Incremented on every change (on the main thread); open views compare it to know when to refresh. */
    private static volatile int version;

    private ClientJournal() {}

    public static int version() {
        return version;
    }

    public static void apply(boolean enabled, List<ResourceLocation> documents, List<JournalNote> notes) {
        ClientJournal.enabled = enabled;
        ClientJournal.documents = List.copyOf(documents);
        ClientJournal.notes = List.copyOf(notes);
        version++;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static List<ResourceLocation> documents() {
        return documents;
    }

    public static List<JournalNote> notes() {
        return notes;
    }

    public static void clear() {
        enabled = true;
        documents = List.of();
        notes = List.of();
        version++;
    }
}
