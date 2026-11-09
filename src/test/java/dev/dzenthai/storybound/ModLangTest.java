package dev.dzenthai.storybound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.stream.JsonReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * The mod's own lang files (November 9, 2026: a second "storybound.dev.field.nbt" quietly replaced the first, and the
 * form said "NBT" where it meant "Mob's data"): no key twice in a file, the same keys in English and Russian.
 */
final class ModLangTest {
    @Test
    void noKeyIsWrittenTwice() throws IOException {
        for (String language : List.of("en_us", "ru_ru")) {
            List<String> twice = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (String key : keys(language)) {
                if (!seen.add(key)) {
                    twice.add(key);
                }
            }
            assertTrue(twice.isEmpty(), language + " has keys twice (the later one wins unseen): " + twice);
        }
    }

    @Test
    void englishAndRussianHaveTheSameKeys() throws IOException {
        Set<String> english = new TreeSet<>(keys("en_us"));
        Set<String> russian = new TreeSet<>(keys("ru_ru"));
        Set<String> onlyEnglish = new TreeSet<>(english);
        onlyEnglish.removeAll(russian);
        Set<String> onlyRussian = new TreeSet<>(russian);
        onlyRussian.removeAll(english);
        assertEquals(Set.of(), onlyEnglish, "in English only");
        assertEquals(Set.of(), onlyRussian, "in Russian only");
    }

    /** The file's keys in order, each as often as it is written (a parser into an object would keep the last only). */
    private static List<String> keys(String language) throws IOException {
        List<String> keys = new ArrayList<>();
        try (JsonReader reader = new JsonReader(new InputStreamReader(Objects.requireNonNull(
                ModLangTest.class.getResourceAsStream("/assets/storybound/lang/" + language + ".json"), language), StandardCharsets.UTF_8))) {
            reader.beginObject();
            while (reader.hasNext()) {
                keys.add(reader.nextName());
                reader.skipValue();
            }
            reader.endObject();
        }
        return keys;
    }
}
