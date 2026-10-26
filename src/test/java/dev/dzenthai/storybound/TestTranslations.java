package dev.dzenthai.storybound;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Localized fixture values remain in language resources, including Unicode round-trip samples. */
public final class TestTranslations {
    private static final JsonObject VALUES = load();

    private TestTranslations() {}

    public static String russian(String key) {
        return VALUES.get(key).getAsString();
    }

    private static JsonObject load() {
        try (var reader = new InputStreamReader(Objects.requireNonNull(
                TestTranslations.class.getResourceAsStream("/lang/ru_ru.json")), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (IOException error) {
            throw new ExceptionInInitializerError(error);
        }
    }
}
