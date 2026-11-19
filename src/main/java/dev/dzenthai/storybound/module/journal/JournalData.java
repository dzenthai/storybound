package dev.dzenthai.storybound.module.journal;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.dzenthai.storybound.core.state.StoryMigrations;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

/**
 * A player's personal archive, stored on the player: which documents they have read, which of them
 * are in the Journal, and notes. Always the {@code player} scope, even in a party.
 */
public final class JournalData {
    public static final Codec<JournalData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ResourceLocation.CODEC.listOf().optionalFieldOf("read", List.of()).forGetter(data -> List.copyOf(data.read)),
            ResourceLocation.CODEC.listOf().optionalFieldOf("documents", List.of()).forGetter(data -> List.copyOf(data.documents)),
            JournalNote.CODEC.listOf().optionalFieldOf("notes", List.of()).forGetter(data -> List.copyOf(data.notes.values())),
            JournalBook.CODEC.listOf().optionalFieldOf("books", List.of()).forGetter(data -> List.copyOf(data.books.values()))
    ).apply(instance, JournalData::new));

    /** How many signed books the Journal keeps; reading another drops the one read longest ago. */
    public static final int MAX_BOOKS = 64;

    private final Set<ResourceLocation> read;
    private final Set<ResourceLocation> documents;
    private final Map<ResourceLocation, JournalNote> notes = new LinkedHashMap<>();
    /** Signed books read, by author and title, the one read last at the end. */
    private final Map<String, JournalBook> books = new LinkedHashMap<>();

    public JournalData() {
        this(List.of(), List.of(), List.of(), List.of());
    }

    private JournalData(List<ResourceLocation> read, List<ResourceLocation> documents, List<JournalNote> notes, List<JournalBook> books) {
        this.read = new LinkedHashSet<>(read);
        this.documents = new LinkedHashSet<>(documents);
        notes.forEach(note -> this.notes.put(note.id(), note));
        books.forEach(book -> this.books.put(book.key(), book));
    }

    public boolean hasRead(ResourceLocation document) {
        return read.contains(document);
    }

    /** @return true on the first read */
    boolean markRead(ResourceLocation document) {
        return read.add(document);
    }

    /** @return true if the document was not in the Journal yet */
    boolean addDocument(ResourceLocation document) {
        return documents.add(document);
    }

    /** A note with the same id replaces the old one, so a story can update an entry. */
    void putNote(JournalNote note) {
        notes.put(note.id(), note);
    }

    /**
     * Keeps a signed book read: another one of the same author and title replaces it (a new edition, or a copy) and
     * moves to the end, as the one read last; past {@link #MAX_BOOKS} the one read longest ago goes.
     *
     * @return true if the Journal had no book of this author and title
     */
    boolean putBook(JournalBook book) {
        boolean fresh = books.remove(book.key()) == null;
        books.put(book.key(), book);
        while (books.size() > MAX_BOOKS) {
            books.remove(books.keySet().iterator().next());
        }
        return fresh;
    }

    /** @return whether there was such a book */
    boolean removeBook(String key) {
        return books.remove(key) != null;
    }

    public java.util.Optional<JournalBook> book(String key) {
        return java.util.Optional.ofNullable(books.get(key));
    }

    public Collection<JournalBook> books() {
        return Collections.unmodifiableCollection(books.values());
    }

    /** Renamed documents and notes (see {@link StoryMigrations}); returns how many moved. */
    int migrate(StoryMigrations.Renames renames) {
        int moved = renameAll(read, renames) + renameAll(documents, renames);
        return moved + StoryMigrations.renameKeys(notes, id -> renames.id(StoryMigrations.Kind.NOTES, id),
                (id, note) -> note.id().equals(id) ? note : new JournalNote(id, note.category(), note.title(), note.text()));
    }

    private static int renameAll(Set<ResourceLocation> documents, StoryMigrations.Renames renames) {
        List<ResourceLocation> before = List.copyOf(documents);
        List<ResourceLocation> after = StoryMigrations.rename(before, StoryMigrations.Kind.DOCUMENTS, renames);
        if (after.equals(before)) {
            return 0;
        }
        documents.clear();
        documents.addAll(after);
        return 1;
    }

    /** In the order they were added. */
    /** Every document the player has read, archived or not. */
    public Collection<ResourceLocation> read() {
        return Collections.unmodifiableSet(read);
    }

    public Collection<ResourceLocation> documents() {
        return Collections.unmodifiableSet(documents);
    }

    public Collection<JournalNote> notes() {
        return Collections.unmodifiableCollection(notes.values());
    }
}
