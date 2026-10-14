package dev.dzenthai.storybound.module.journal;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
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
            JournalNote.CODEC.listOf().optionalFieldOf("notes", List.of()).forGetter(data -> List.copyOf(data.notes.values()))
    ).apply(instance, JournalData::new));

    private final Set<ResourceLocation> read;
    private final Set<ResourceLocation> documents;
    private final Map<ResourceLocation, JournalNote> notes = new LinkedHashMap<>();

    public JournalData() {
        this(List.of(), List.of(), List.of());
    }

    private JournalData(List<ResourceLocation> read, List<ResourceLocation> documents, List<JournalNote> notes) {
        this.read = new LinkedHashSet<>(read);
        this.documents = new LinkedHashSet<>(documents);
        notes.forEach(note -> this.notes.put(note.id(), note));
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

    /** In the order they were added. */
    public Collection<ResourceLocation> documents() {
        return Collections.unmodifiableSet(documents);
    }

    public Collection<JournalNote> notes() {
        return Collections.unmodifiableCollection(notes.values());
    }
}
