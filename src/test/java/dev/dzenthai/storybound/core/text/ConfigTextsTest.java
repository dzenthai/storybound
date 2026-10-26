package dev.dzenthai.storybound.core.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.dzenthai.storybound.core.data.ValidationIssue;
import dev.dzenthai.storybound.TestTranslations;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigTextsTest {
    @TempDir
    Path root;

    @Test
    void readsTranslationsAndLongTextsPerLanguage() throws IOException {
        write("lang/en_us.json", "{ \"mypack.title\": \"The Letter\", \"mypack.nested\": { \"a\": 1 } }");
        String translatedTitle = TestTranslations.russian("fixture.letter.title");
        String translatedBody = TestTranslations.russian("fixture.letter.body");
        var translated = new com.google.gson.JsonObject();
        translated.addProperty("mypack.title", translatedTitle);
        write("lang/ru_ru.json", translated.toString());
        write("lang/broken.json", "{ nope");
        write("texts/en_us/mypack/letter.sbt", "**Hello**");
        write("texts/en_us/note.sbt", "plain");
        write("texts/ru_ru/mypack/letter.sbt", translatedBody);
        write("texts/en_us/Bad Name.sbt", "x");
        List<ValidationIssue> issues = new ArrayList<>();

        ConfigTexts.Texts texts = ConfigTexts.load(root, issues);

        assertEquals("en_us", texts.sourceLanguage());
        assertEquals("The Letter", texts.translations().get("en_us").get("mypack.title"));
        assertEquals(translatedTitle, texts.translations().get("ru_ru").get("mypack.title"));
        assertEquals("**Hello**", texts.documents().get("en_us").get(ResourceLocation.fromNamespaceAndPath("mypack", "letter")));
        assertEquals("plain", texts.documents().get("en_us").get(ResourceLocation.fromNamespaceAndPath("storybound", "note")), "a file right in the language folder");
        assertEquals(translatedBody, texts.documents().get("ru_ru").get(ResourceLocation.fromNamespaceAndPath("mypack", "letter")));
        assertEquals(3, issues.size(), "a value that is not text, a broken lang file, a bad file name: " + issues);
    }

    @Test
    void travelsToClientsUnchanged() throws IOException {
        write("lang/en_us.json", "{ \"k\": \"v\" }");
        write("texts/en_us/mypack/long.sbt", "x".repeat(100_000));
        ConfigTexts.Texts texts = ConfigTexts.load(root, new ArrayList<>());
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());

        ConfigTexts.Texts.STREAM_CODEC.encode(buf, texts);
        ConfigTexts.Texts decoded = ConfigTexts.Texts.STREAM_CODEC.decode(buf);

        assertEquals(texts, decoded);
        assertTrue(decoded.documents().get("en_us").get(ResourceLocation.fromNamespaceAndPath("mypack", "long")).length() == 100_000,
                "long texts are not cut at the usual string limit");
    }

    private void write(String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
