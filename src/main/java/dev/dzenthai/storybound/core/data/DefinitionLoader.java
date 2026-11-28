package dev.dzenthai.storybound.core.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

/**
 * Loads one definition type from datapacks and then from the story folder ({@link ConfigStorySource}), whose files
 * replace datapack entries with the same id. A broken file does not abort loading: the file is skipped and the
 * problem goes to the validation report with its path and cause.
 */
public final class DefinitionLoader<T> extends SimplePreparableReloadListener<DefinitionLoader.Scan> {
    private final DefinitionType<T> type;
    private final HolderLookup.Provider registries;

    public DefinitionLoader(DefinitionType<T> type, HolderLookup.Provider registries) {
        this.type = type;
        this.registries = registries;
    }

    @Override
    protected Scan prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        FileToIdConverter converter = FileToIdConverter.json(type.directory());
        Map<ResourceLocation, JsonElement> files = new HashMap<>();
        List<ValidationIssue> issues = new ArrayList<>();
        for (Map.Entry<ResourceLocation, Resource> entry : converter.listMatchingResources(resourceManager).entrySet()) {
            ResourceLocation id = converter.fileToId(entry.getKey());
            try (Reader reader = entry.getValue().openAsReader()) {
                files.put(id, JsonParser.parseReader(reader));
            } catch (IOException | JsonParseException e) {
                issues.add(ValidationIssue.error(type.id(), id, "Malformed JSON: " + e.getMessage()));
            }
        }
        // The story folder last: its files replace datapack entries with the same id.
        StoryProjects.scan(ConfigStorySource.root(), type, files, issues);
        return new Scan(files, issues);
    }

    @Override
    protected void apply(Scan scan, ResourceManager resourceManager, ProfilerFiller profiler) {
        // Deferred until reparse() on TagsUpdatedEvent(SERVER_DATA_LOAD) when tags are bound, avoiding double parsing.
        LAST.put(type, scan);
    }

    /**
     * The files of each type's last load, parsed by {@link #reparse} once Minecraft has bound its tags: during a
     * load they are not bound yet (on the first load of a world, none are), so a selector naming a tag
     * ({@code "#minecraft:zombies"}) cannot be read until then.
     */
    private static final Map<DefinitionType<?>, Scan> LAST = new java.util.concurrent.ConcurrentHashMap<>();

    /** Every type's last files parsed once registries have tags bound, then cleared so raw JSON trees are not retained. */
    public static void reparse(HolderLookup.Provider registries) {
        if (LAST.isEmpty()) {
            return;
        }
        LAST.forEach((type, scan) -> store(type, scan, registries));
        LAST.clear();
    }

    /** Drops any buffered raw scan trees when a server stops. */
    public static void clearPending() {
        LAST.clear();
    }

    private static <T> void store(DefinitionType<T> type, Scan scan, HolderLookup.Provider registries) {
        ParseResult<T> result = parse(type, scan.files(), RegistryOps.create(JsonOps.INSTANCE, registries));
        List<ValidationIssue> issues = new ArrayList<>(scan.issues());
        issues.addAll(result.issues());
        type.serverStore().replace(result.definitions());
        ValidationService.recordLoad(type, issues);
    }

    public static <T> ParseResult<T> parse(DefinitionType<T> type, Map<ResourceLocation, JsonElement> files, DynamicOps<JsonElement> ops) {
        Map<ResourceLocation, T> definitions = new LinkedHashMap<>();
        List<ValidationIssue> issues = new ArrayList<>();
        files.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(file -> type.codec().parse(ops, file.getValue())
                        .ifSuccess(value -> definitions.put(file.getKey(), value))
                        .ifError(error -> issues.add(ValidationIssue.error(type.id(), file.getKey(), error.message()))));
        return new ParseResult<>(definitions, issues);
    }

    public record Scan(Map<ResourceLocation, JsonElement> files, List<ValidationIssue> issues) {}

    public record ParseResult<T>(Map<ResourceLocation, T> definitions, List<ValidationIssue> issues) {}
}
