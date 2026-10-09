package dev.dzenthai.storybound.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.dzenthai.storybound.core.state.FlagDeclaration;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class DefinitionLoaderTest {
    private static final DefinitionType<FlagDeclaration> FLAGS = DefinitionType.synced(
            ResourceLocation.fromNamespaceAndPath("test", "flag"), "storybound/flags", FlagDeclaration.CODEC, FlagDeclaration::sync);

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("example", path);
    }

    @Test
    void brokenFileDoesNotDropOthers() {
        Map<ResourceLocation, JsonElement> files = Map.of(
                id("met_ciri"), JsonParser.parseString("{ \"type\": \"bool\" }"),
                id("gold"), JsonParser.parseString("{ \"type\": \"int\", \"default\": \"lots\" }"));

        DefinitionLoader.ParseResult<FlagDeclaration> result = DefinitionLoader.parse(FLAGS, files, JsonOps.INSTANCE);

        assertEquals(1, result.definitions().size());
        assertTrue(result.definitions().containsKey(id("met_ciri")));
        assertEquals(1, result.issues().size());
        ValidationIssue issue = result.issues().getFirst();
        assertTrue(issue.isError());
        assertEquals(id("gold"), issue.entry());
        assertEquals(FLAGS.id(), issue.type());
    }

    @Test
    void syncFilterKeepsOnlyPublicFlags() {
        Map<ResourceLocation, JsonElement> files = Map.of(
                id("secret"), JsonParser.parseString("{ \"type\": \"bool\" }"),
                id("gold"), JsonParser.parseString("{ \"type\": \"int\", \"sync\": true }"));
        FLAGS.serverStore().replace(DefinitionLoader.parse(FLAGS, files, JsonOps.INSTANCE).definitions());

        Map<ResourceLocation, FlagDeclaration> synced = FLAGS.valuesToSync();

        assertEquals(1, synced.size());
        assertEquals(Optional.of(true), Optional.ofNullable(synced.get(id("gold"))).map(FlagDeclaration::sync));
    }
}
