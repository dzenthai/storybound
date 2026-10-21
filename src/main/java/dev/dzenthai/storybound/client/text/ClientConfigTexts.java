package dev.dzenthai.storybound.client.text;

import dev.dzenthai.storybound.core.text.ConfigTexts;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    private static @Nullable String previewLanguage = null;

    private ClientConfigTexts() {}

    public static void set(ConfigTexts.Texts value) {
        texts = value;
        install();
    }

    public static void clear() {
        texts = ConfigTexts.Texts.EMPTY;
        previewLanguage = null;
    }

    public static void setPreviewLanguage(@Nullable String language) {
        previewLanguage = language;
    }

    public static @Nullable String previewLanguage() {
        return previewLanguage;
    }

    public static ConfigTexts.Texts texts() {
        return texts;
    }

    public static String sourceLanguage() {
        return texts.sourceLanguage();
    }

    public static @Nullable String rawTranslation(String language, String key) {
        return texts.translations().getOrDefault(language, Map.of()).get(key);
    }

    public static Set<String> availableLanguages() {
        Set<String> set = new LinkedHashSet<>();
        set.addAll(texts.translations().keySet());
        set.addAll(texts.documents().keySet());
        return set;
    }

    public static Optional<String> document(ResourceLocation id, String language) {
        return Optional.ofNullable(texts.documents().getOrDefault(language, Map.of()).get(id));
    }

    public static @Nullable String translation(String key) {
        if (texts.translations().isEmpty()) {
            return null;
        }
        String language = previewLanguage != null ? previewLanguage : Minecraft.getInstance().getLanguageManager().getSelected();
        return translation(key, language);
    }

    public static @Nullable String translation(String key, String selectedLanguage) {
        if (texts.translations().isEmpty()) {
            return null;
        }
        Map<String, String> langMap = texts.translations().get(selectedLanguage);
        if (langMap != null && langMap.containsKey(key)) {
            return langMap.get(key);
        }
        if (selectedLanguage.contains("_")) {
            String prefix = selectedLanguage.substring(0, selectedLanguage.indexOf('_')) + "_";
            for (Map.Entry<String, Map<String, String>> entry : texts.translations().entrySet()) {
                if (!entry.getKey().equalsIgnoreCase(selectedLanguage) && entry.getKey().startsWith(prefix)) {
                    String sibling = entry.getValue().get(key);
                    if (sibling != null) {
                        return sibling;
                    }
                }
            }
        }
        String source = texts.sourceLanguage();
        if (source != null && !source.equalsIgnoreCase(selectedLanguage)) {
            Map<String, String> srcMap = texts.translations().get(source);
            if (srcMap != null && srcMap.containsKey(key)) {
                return srcMap.get(key);
            }
        }
        if (!FALLBACK_LANGUAGE.equalsIgnoreCase(selectedLanguage)) {
            Map<String, String> fallbackMap = texts.translations().get(FALLBACK_LANGUAGE);
            if (fallbackMap != null && fallbackMap.containsKey(key)) {
                return fallbackMap.get(key);
            }
        }
        return null;
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
