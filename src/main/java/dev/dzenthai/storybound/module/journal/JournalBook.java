package dev.dzenthai.storybound.module.journal;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * A signed book a player read, kept in their Journal under "Your books" (November 20, 2026, the author): its title,
 * author and pages as they were then, to read again even when the book itself is gone. One per author and title, the
 * last read; see {@link JournalData#putBook}.
 */
public record JournalBook(String title, String author, List<Component> pages) {
    public static final Codec<JournalBook> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("title").forGetter(JournalBook::title),
            Codec.STRING.fieldOf("author").forGetter(JournalBook::author),
            ComponentSerialization.CODEC.listOf().optionalFieldOf("pages", List.of()).forGetter(JournalBook::pages)
    ).apply(instance, JournalBook::new));

    /**
     * The same book: one entry per author and title, apart by the unit separator, which a book's title or a name cannot
     * hold (not a line break: the client keeps its "seen" marks a line each).
     */
    public String key() {
        return author + "\u001F" + title;
    }

    /** What the client is told of a kept book, its pages asked for when it is opened (a Journal may hold many). */
    public record Info(String key, String title, String author) {
        public static final StreamCodec<ByteBuf, Info> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Info::key,
                ByteBufCodecs.STRING_UTF8, Info::title,
                ByteBufCodecs.STRING_UTF8, Info::author,
                Info::new);
    }

    public Info info() {
        return new Info(key(), title, author);
    }
}
