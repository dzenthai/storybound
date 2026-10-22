package dev.dzenthai.storybound.module.quest;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.CoreDefinitions;
import dev.dzenthai.storybound.core.data.ChapterDefinition;
import dev.dzenthai.storybound.core.data.ConfigStorySource;
import dev.dzenthai.storybound.core.data.DefinitionType;
import dev.dzenthai.storybound.core.data.DefinitionTypes;
import dev.dzenthai.storybound.core.data.ProjectSettings;
import dev.dzenthai.storybound.core.dev.DevScripts;
import dev.dzenthai.storybound.core.story.StoryFlow;
import dev.dzenthai.storybound.core.story.StoryLinks;
import dev.dzenthai.storybound.core.text.StoryText;
import dev.dzenthai.storybound.network.ChapterMapPayload;
import dev.dzenthai.storybound.network.StoryboundNetwork;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;

/**
 * The players' chapter map, in the manner of an FTB Quests book: the chapters of the dev mode with their quests where
 * the author placed them, and arrows from a quest to the quests that follow it (the story map's {@link StoryFlow}, the
 * dialogues and scripts between them folded in). Off unless {@code "chapter_map": true} in {@code project.json}. The
 * same for every player, built on every data load and sent with the quests; each client colours it with its own
 * progress and hides what {@link QuestDefinition.Visibility} says. Quests placed in no chapter go to a last chapter,
 * laid out in the story map's columns.
 */
public record ChapterMap(List<Chapter> chapters, List<Link> links) {
    public static final ChapterMap EMPTY = new ChapterMap(List.of(), List.of());
    /** The chapter of quests placed in no chapter. */
    public static final ResourceLocation OTHER = Storybound.id("other");
    /** The distance between columns and rows of quests laid out automatically, in the editor's units. */
    private static final int SPACING = 160;

    public record Node(ResourceLocation quest, int x, int y) {
        static final Codec<Node> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ResourceLocation.CODEC.fieldOf("quest").forGetter(Node::quest),
                Codec.INT.fieldOf("x").forGetter(Node::x),
                Codec.INT.fieldOf("y").forGetter(Node::y)
        ).apply(instance, Node::new));
    }

    public record Chapter(ResourceLocation id, StoryText title, Optional<ResourceLocation> icon, List<Node> nodes) {
        static final Codec<Chapter> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ResourceLocation.CODEC.fieldOf("id").forGetter(Chapter::id),
                StoryText.CODEC.fieldOf("title").forGetter(Chapter::title),
                ResourceLocation.CODEC.optionalFieldOf("icon").forGetter(Chapter::icon),
                Node.CODEC.listOf().fieldOf("nodes").forGetter(Chapter::nodes)
        ).apply(instance, Chapter::new));
    }

    /** One quest leads to another. */
    public record Link(ResourceLocation from, ResourceLocation to) {
        static final Codec<Link> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ResourceLocation.CODEC.fieldOf("from").forGetter(Link::from),
                ResourceLocation.CODEC.fieldOf("to").forGetter(Link::to)
        ).apply(instance, Link::new));
    }

    public static final Codec<ChapterMap> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Chapter.CODEC.listOf().fieldOf("chapters").forGetter(ChapterMap::chapters),
            Link.CODEC.listOf().fieldOf("links").forGetter(ChapterMap::links)
    ).apply(instance, ChapterMap::new));

    /** Built lazily after a data load: the scripts folder and registries are only at hand once the server runs. */
    private static volatile ChapterMap current = EMPTY;
    private static volatile boolean stale = true;

    public boolean isEmpty() {
        return chapters.isEmpty();
    }

    /** The quests that come right before {@code quest}. */
    public List<ResourceLocation> before(ResourceLocation quest) {
        return links.stream().filter(link -> link.to().equals(quest)).map(Link::from).toList();
    }

    public static void register(IEventBus gameBus) {
        gameBus.addListener((TagsUpdatedEvent event) -> {
            if (event.getUpdateCause() == TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) {
                stale = true;
            }
        });
        // LOWEST: after the quest definitions, which the map refers to.
        gameBus.addListener(EventPriority.LOWEST, ChapterMap::onDatapackSync);
    }

    public static ChapterMap current(MinecraftServer server) {
        if (stale) {
            current = build(server);
            stale = false;
        }
        return current;
    }

    /** Builds the map again and sends it to everyone, e.g. after the dev mode saved a chapter or a quest. */
    public static void refresh(MinecraftServer server) {
        stale = true;
        ChapterMap map = current(server);
        server.getPlayerList().getPlayers().forEach(player -> StoryboundNetwork.sendToPlayer(player, new ChapterMapPayload(map)));
    }

    private static void onDatapackSync(OnDatapackSyncEvent event) {
        ChapterMap map = current(event.getPlayerList().getServer());
        event.getRelevantPlayers().forEach((ServerPlayer player) -> StoryboundNetwork.sendToPlayer(player, new ChapterMapPayload(map)));
    }

    /** Whether {@code project.json} turns the map on ({@code "chapter_map": true}). */
    public static boolean enabled(Path root) {
        Path file = root.resolve(ProjectSettings.FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try {
            JsonElement json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            return json.isJsonObject() && json.getAsJsonObject().has("chapter_map") && json.getAsJsonObject().get("chapter_map").getAsBoolean();
        } catch (IOException | JsonParseException | IllegalStateException | ClassCastException | UnsupportedOperationException e) {
            return false;
        }
    }

    private static ChapterMap build(MinecraftServer server) {
        return Quests.isEnabled() && enabled(ConfigStorySource.root()) ? assemble(server) : EMPTY;
    }

    /** The map of the loaded story, whether the pack turned it on or not. Public for tests. */
    public static ChapterMap assemble(MinecraftServer server) {
        Map<ResourceLocation, QuestDefinition> quests = QuestRegistries.QUESTS.serverStore().all();
        if (quests.isEmpty()) {
            return EMPTY;
        }
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
        Map<ResourceLocation, Map<ResourceLocation, String>> encoded = new HashMap<>();
        List<StoryLinks.Edge> edges = StoryLinks.collect(new StoryLinks.Source() {
            @Override
            public Map<ResourceLocation, String> byType(ResourceLocation type) {
                return encoded.computeIfAbsent(type, id -> {
                    DefinitionType<?> definitions = DefinitionTypes.get(id);
                    return definitions != null ? encode(definitions, ops) : Map.of();
                });
            }

            @Override
            public StoryLinks.ScriptFile scriptFile(String path) {
                return DevScripts.read(server, path);
            }
        });
        Set<Link> links = new LinkedHashSet<>();
        List<StoryFlow.Flow> flows = StoryFlow.flows(edges);
        for (StoryFlow.Flow flow : flows) {
            if (quests.containsKey(flow.from().id()) && quests.containsKey(flow.to().id()) && !flow.from().equals(flow.to())) {
                links.add(new Link(flow.from().id(), flow.to().id()));
            }
        }
        List<Chapter> chapters = new ArrayList<>();
        Set<ResourceLocation> placed = new LinkedHashSet<>();
        CoreDefinitions.CHAPTERS.serverStore().all().entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<ResourceLocation, ChapterDefinition> entry) -> entry.getValue().order())
                        .thenComparing(entry -> entry.getKey().toString()))
                .forEach(entry -> {
                    List<Node> nodes = new ArrayList<>();
                    for (ChapterDefinition.PlacedNode node : entry.getValue().nodes()) {
                        if (node.type().equals(StoryLinks.QUEST) && quests.containsKey(node.id()) && placed.add(node.id())) {
                            nodes.add(new Node(node.id(), node.x(), node.y()));
                        }
                    }
                    if (!nodes.isEmpty()) {
                        chapters.add(new Chapter(entry.getKey(), entry.getValue().title(), entry.getValue().icon(), List.copyOf(nodes)));
                    }
                });
        List<StoryLinks.NodeKey> rest = quests.keySet().stream().filter(quest -> !placed.contains(quest)).sorted(Comparator.comparing(ResourceLocation::toString))
                .map(quest -> new StoryLinks.NodeKey(StoryLinks.QUEST, quest)).toList();
        if (!rest.isEmpty()) {
            Map<StoryLinks.NodeKey, Integer> columns = StoryFlow.columns(rest, flows);
            Map<Integer, Integer> rows = new LinkedHashMap<>();
            List<Node> nodes = new ArrayList<>();
            for (StoryLinks.NodeKey quest : rest) {
                int column = columns.getOrDefault(quest, 0);
                int row = rows.merge(column, 1, Integer::sum) - 1;
                nodes.add(new Node(quest.id(), column * SPACING, row * SPACING / 2));
            }
            chapters.add(new Chapter(OTHER, StoryText.key("storybound.map.other"), Optional.empty(), List.copyOf(nodes)));
        }
        return new ChapterMap(List.copyOf(chapters), List.copyOf(links));
    }

    private static <T> Map<ResourceLocation, String> encode(DefinitionType<T> type, DynamicOps<JsonElement> ops) {
        Map<ResourceLocation, String> out = new LinkedHashMap<>();
        type.serverStore().all().forEach((id, value) -> type.codec().encodeStart(ops, value).result().ifPresent(json -> out.put(id, json.toString())));
        return out;
    }
}
