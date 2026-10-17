package dev.dzenthai.storybound.client.text;

import dev.dzenthai.storybound.core.text.ConfigTexts;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;

/**
 * The story folder's texts as the server sent them. Translations are laid over the game's language: every
 * {@code Component.translatable} sees them, in the player's language first, then {@code en_us}, then whatever the
 * resource packs say. Long texts are read by {@code DocumentTexts} before the resource packs'.
 */
public final class ClientConfigTexts {
    private static final String FALLBACK_LANGUAGE = "en_us";
    private static ConfigTexts.Texts texts = ConfigTexts.Texts.EMPTY;

    private ClientConfigTexts() {}

    public static void set(ConfigTexts.Texts value) {
        texts = value;
        install();
    }

    /** On logout: the next server brings its own. */
    public static void clear() {
        texts = ConfigTexts.Texts.EMPTY;
    }

    /** The {@code .sbt} source for this id in this language, if the story folder has one. */
    public static Optional<String> document(ResourceLocation id, String language) {
        return Optional.ofNullable(texts.documents().getOrDefault(language, Map.of()).get(id));
    }

    static @Nullable String translation(String key) {
        if (texts.translations().isEmpty()) {
            return null;
        }
        String language = Minecraft.getInstance().getLanguageManager().getSelected();
        String text = texts.translations().getOrDefault(language, Map.of()).get(key);
        return text != null ? text : texts.translations().getOrDefault(FALLBACK_LANGUAGE, Map.of()).get(key);
    }

    /**
     * Lays the story folder's translations over the current language, unless already done. The game replaces the
     * language on every resource reload, so this runs again every client tick (a cheap instance check).
     */
    public static void install() {
        Language current = Language.getInstance();
        if (!(current instanceof Overlay)) {
            Language.inject(new Overlay(current));
        }
    }

    /** The game's language with the story folder's translations in front. */
    private static final class Overlay extends Language {
        private final Language base;

        Overlay(Language base) {
            this.base = base;
        }

        @Override
        public String getOrDefault(String key, String defaultValue) {
            String text = translation(key);
            return text != null ? text : base.getOrDefault(key, defaultValue);
        }

        @Override
        public boolean has(String key) {
            return translation(key) != null || base.has(key);
        }

        @Override
        public boolean isDefaultRightToLeft() {
            return base.isDefaultRightToLeft();
        }

        @Override
        public FormattedCharSequence getVisualOrder(FormattedText text) {
            return base.getVisualOrder(text);
        }

        @Override
        public List<FormattedCharSequence> getVisualOrder(List<FormattedText> text) {
            return base.getVisualOrder(text);
        }

        @Override
        public Map<String, String> getLanguageData() {
            return base.getLanguageData();
        }

        @Override
        public @Nullable Component getComponent(String key) {
            return translation(key) != null ? null : base.getComponent(key);
        }
    }
}
