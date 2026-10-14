package dev.dzenthai.storybound.module.document;

import com.mojang.serialization.Codec;
import dev.dzenthai.storybound.Storybound;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.StringRepresentable;

/** How the reading screen behaves. Appearance comes from the style, not the kind. */
public enum DocumentKind implements StringRepresentable {
    /** Two-page spread with page turning. */
    BOOK("book", "book_leather", "books"),
    /** One long column with scrolling. */
    SCROLL("scroll", "scroll", "scrolls"),
    /** A single sheet; scrolls if the text does not fit. */
    NOTE("note", "note_torn", "notes"),
    LETTER("letter", "parchment", "letters");

    public static final Codec<DocumentKind> CODEC = StringRepresentable.fromEnum(DocumentKind::values);

    private final String name;
    private final ResourceLocation defaultStyle;
    private final ResourceLocation defaultCategory;

    DocumentKind(String name, String defaultStyle, String defaultCategory) {
        this.name = name;
        this.defaultStyle = Storybound.id(defaultStyle);
        this.defaultCategory = Storybound.id(defaultCategory);
    }

    public ResourceLocation defaultStyle() {
        return defaultStyle;
    }

    public ResourceLocation defaultCategory() {
        return defaultCategory;
    }

    /** Value of the {@code storybound:kind} item property; the item model picks the icon by it. */
    public float modelPredicate() {
        return (ordinal() + 1) / 10f;
    }

    @Override
    public String getSerializedName() {
        return name;
    }
}
