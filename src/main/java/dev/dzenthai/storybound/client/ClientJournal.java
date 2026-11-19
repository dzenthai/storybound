package dev.dzenthai.storybound.client;

import dev.dzenthai.storybound.module.journal.JournalBook;
import dev.dzenthai.storybound.module.journal.JournalNote;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** The local player's Journal as last sent by the server. */
public final class ClientJournal {
    private static volatile boolean enabled = true;
    private static volatile List<ResourceLocation> documents = List.of();
    private static volatile List<JournalNote> notes = List.of();
    private static volatile List<JournalBook.Info> books = List.of();

    /** Incremented on every change (on the main thread); open views compare it to know when to refresh. */
    private static volatile int version;

    private ClientJournal() {}

    public static int version() {
        return version;
    }

    public static void apply(boolean enabled, List<ResourceLocation> documents, List<JournalNote> notes, List<JournalBook.Info> books) {
        ClientJournal.enabled = enabled;
        ClientJournal.documents = List.copyOf(documents);
        ClientJournal.notes = List.copyOf(notes);
        ClientJournal.books = List.copyOf(books);
        version++;
    }

    /** The signed books kept, the one read last at the end. */
    public static List<JournalBook.Info> books() {
        return books;
    }

    /**
     * A kept signed book's pages came: it opens as the game's own book, and closing it goes back to where it was
     * opened from (the Journal), not out of the menu.
     */
    public static void openBook(List<Component> pages) {
        Minecraft minecraft = Minecraft.getInstance();
        Screen back = minecraft.screen;
        minecraft.setScreen(new BookViewScreen(new BookViewScreen.BookAccess(pages)) {
            @Override
            public void onClose() {
                minecraft.setScreen(back);
            }
        });
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
        books = List.of();
        version++;
    }
}
