package dev.dzenthai.storybound.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.CoreDefinitions;
import dev.dzenthai.storybound.core.data.DefinitionStore;
import dev.dzenthai.storybound.core.data.ValidationService;
import dev.dzenthai.storybound.core.logic.StoryEvent;
import dev.dzenthai.storybound.core.logic.StoryEvents;
import dev.dzenthai.storybound.core.logic.trigger.ProgressTriggers;
import dev.dzenthai.storybound.core.logic.trigger.SeenTracker;
import dev.dzenthai.storybound.core.logic.trigger.TriggerDispatcher;
import dev.dzenthai.storybound.module.dialogue.DialogueRegistries;
import dev.dzenthai.storybound.module.dialogue.Dialogues;
import dev.dzenthai.storybound.module.dialogue.NpcBinding;
import dev.dzenthai.storybound.module.dialogue.NpcBindings;
import dev.dzenthai.storybound.module.quest.ChapterMap;
import dev.dzenthai.storybound.module.quest.QuestDefinition;
import dev.dzenthai.storybound.module.quest.QuestEvents;
import dev.dzenthai.storybound.module.quest.QuestHints;
import dev.dzenthai.storybound.module.quest.QuestRegistries;
import dev.dzenthai.storybound.module.quest.Quests;
import dev.dzenthai.storybound.module.waypoint.WaypointDefinition;
import dev.dzenthai.storybound.module.waypoint.WaypointRegistries;
import dev.dzenthai.storybound.module.waypoint.Waypoints;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * A busy server, measured: {@value #PLAYERS} players with {@value #ACTIVE} active quests each (of {@value #QUESTS},
 * with hints, item objectives, {@code fail_when} and time limits), {@value #EVENTS} story events on {@code entity_seen} and {@value #AREA_EVENTS} on biomes, villages, levels and statistics,
 * {@value #WAYPOINTS} waypoints at the nearest pig, {@value #BINDINGS} NPC bindings and {@value #PIGS} pigs around. Each
 * of the mod's server tick handlers runs many times and its cost per tick (a handler that runs every n ticks counts
 * 1/n) goes to the log and {@code logs/storybound-load.txt}. Not part of the usual run: {@code -PloadTest=true}.
 */
@SuppressWarnings("removal")
@GameTestHolder(Storybound.MOD_ID + "_load")
@PrefixGameTestTemplate(false)
public final class LoadGameTests {
    private static final int PLAYERS = 40;
    private static final int QUESTS = 200;
    private static final int ACTIVE = 20;
    private static final int EVENTS = 50;
    private static final int AREA_EVENTS = 40;
    private static final int WAYPOINTS = 20;
    private static final int BINDINGS = 30;
    private static final int PIGS = 60;
    private static final int RUNS = 100;
    /** What the mod may take of the 50 ms a tick has, at most. */
    private static final double BUDGET_MS = 2.5;

    private LoadGameTests() {}

    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void storyTicksStayCheap(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
        Map<ResourceLocation, String> quests = new LinkedHashMap<>();
        for (int i = 0; i < QUESTS; i++) {
            quests.put(key("load_q" + i), """
                    { "title": { "literal": "Load %1$d" }, "time_limit": 100000,
                      "fail_when": { "type": "flag", "key": "gametest:load_fail_%1$d" },
                      "start": { "trigger": { "type": "custom", "id": "gametest:load_start_%1$d" } },
                      "stages": [ { "id": "a", "objectives": [
                        { "id": "kill", "trigger": { "type": "entity_killed", "entity": "minecraft:zombie" }, "count": 10,
                          "hints": [ { "text": { "literal": "h" }, "conditions": { "type": "flag", "key": "gametest:load_hint" } },
                                     { "text": { "literal": "w" }, "after": 30 } ] },
                        { "id": "iron", "items": { "item": "minecraft:iron_ingot" }, "count": 5 } ] },
                        { "id": "b" } ] }
                    """.formatted(i));
        }
        Map<ResourceLocation, String> events = new LinkedHashMap<>();
        for (int i = 0; i < EVENTS; i++) {
            events.put(key("load_e" + i), """
                    { "trigger": { "type": "entity_seen", "entity": "minecraft:pig" },
                      "conditions": { "type": "flag", "key": "gametest:load_seen_%d" },
                      "actions": { "type": "set_flag", "key": "gametest:load_seen_done", "value": true } }
                    """.formatted(i));
        }
        // Areas and values: entering a biome or a village, reaching a level or a statistic.
        for (int i = 0; i < AREA_EVENTS; i++) {
            String trigger = switch (i % 4) {
                case 0 -> "{ \"type\": \"biome_entered\", \"biome\": \"minecraft:plains\" }";
                case 1 -> "{ \"type\": \"structure_entered\", \"structure\": \"#minecraft:village\" }";
                case 2 -> "{ \"type\": \"level_reached\", \"level\": " + (5 + i) + " }";
                default -> "{ \"type\": \"stat_reached\", \"stat\": \"minecraft:jump\", \"value\": " + (100 + i) + " }";
            };
            events.put(key("load_a" + i), "{ \"trigger\": " + trigger + ", \"actions\": { \"type\": \"set_flag\", \"key\": \"gametest:load_area_" + i + "\" } }");
        }
        Map<ResourceLocation, String> waypoints = new LinkedHashMap<>();
        for (int i = 0; i < WAYPOINTS; i++) {
            waypoints.put(key("load_w" + i), "{ \"name\": { \"literal\": \"Pig %d\" }, \"target\": { \"nearest\": \"minecraft:pig\", \"range\": 48 }, \"quest\": \"gametest:load_q%d\" }"
                    .formatted(i, i));
        }
        Map<ResourceLocation, String> bindings = new LinkedHashMap<>();
        for (int i = 0; i < BINDINGS; i++) {
            bindings.put(key("load_b" + i), "{ \"entity\": { \"tag\": \"load_npc_%d\" }, \"dialogues\": [ { \"dialogue\": \"gametest:none\" } ] }".formatted(i));
        }
        add(ops, QuestRegistries.QUESTS.serverStore(), QuestDefinition.CODEC, quests);
        add(ops, CoreDefinitions.EVENTS.serverStore(), StoryEvent.CODEC, events);
        add(ops, WaypointRegistries.WAYPOINTS.serverStore(), WaypointDefinition.CODEC, waypoints);
        add(ops, DialogueRegistries.NPC_BINDINGS.serverStore(), NpcBinding.CODEC, bindings);
        QuestEvents.rebuild();
        StoryEvents.rebuild();
        NpcBindings.rebuild();
        Waypoints.rebuild();
        for (int i = 0; i < PIGS; i++) {
            helper.spawn(EntityType.PIG, 1 + i % 8 * 2, 2, 1 + i / 8 * 2);
        }
        List<ServerPlayer> players = new ArrayList<>();
        for (int p = 0; p < PLAYERS; p++) {
            ServerPlayer player = helper.makeMockServerPlayerInLevel();
            player.getInventory().add(new ItemStack(Items.IRON_INGOT, 3));
            for (int q = 0; q < ACTIVE; q++) {
                Quests.start(player, key("load_q" + ((p * 7 + q) % QUESTS)));
            }
            players.add(player);
        }
        List<String> report = new ArrayList<>();
        double total = 0;
        try {
            total += measure(report, "quest checks (fail_when, time, items, hints), every 20 ticks", 20, () -> players.forEach(player -> {
                Quests.checkFailures(player);
                Quests.checkItems(player);
                QuestHints.check(player);
            }));
            total += measure(report, "entity_seen, every " + SeenTracker.INTERVAL + " ticks", SeenTracker.INTERVAL, () -> players.forEach(SeenTracker::check));
            total += measure(report, "areas (location, structures, biomes), every 10 ticks", 10, () -> TriggerDispatcher.tickAreas(server));
            total += measure(report, "values (levels, statistics), every 20 ticks", 20, () -> ProgressTriggers.checkValues(server));
            total += measure(report, "NPC proximity, every 10 ticks", 10, () -> players.forEach(player -> NpcBindings.checkProximity(player, server.getTickCount())));
            total += measure(report, "waypoints (nearest pig), every 20 ticks", 20, () -> players.forEach(Waypoints::refresh));
            total += measure(report, "dialogue sessions, every tick", 1, () -> Dialogues.tick(server));
            report.add(String.format(Locale.ROOT, "total per tick: %.3f ms of the %.1f ms allowed", total, BUDGET_MS));
            long start = System.nanoTime();
            ChapterMap.assemble(server);
            report.add(String.format(Locale.ROOT, "chapter map built once: %.1f ms", (System.nanoTime() - start) / 1e6));
            start = System.nanoTime();
            ValidationService.validate();
            report.add(String.format(Locale.ROOT, "validation once: %.1f ms", (System.nanoTime() - start) / 1e6));
            report.add(0, "Storybound load test: " + PLAYERS + " players, " + ACTIVE + " active quests each of " + QUESTS + ", " + EVENTS
                    + " entity_seen events, " + AREA_EVENTS + " area and value events, " + WAYPOINTS + " nearest-entity waypoints, " + BINDINGS + " NPC bindings, " + PIGS + " pigs");
            report.forEach(line -> Storybound.LOGGER.info("{}", line));
            try {
                Path file = FMLPaths.GAMEDIR.get().resolve("logs").resolve("storybound-load.txt");
                Files.createDirectories(file.getParent());
                Files.write(file, report, StandardCharsets.UTF_8);
            } catch (IOException e) {
                Storybound.LOGGER.warn("Storybound: could not write the load report: {}", e.getMessage());
            }
        } finally {
            players.forEach(player -> server.getPlayerList().remove(player));
            remove(QuestRegistries.QUESTS.serverStore(), quests.keySet());
            remove(CoreDefinitions.EVENTS.serverStore(), events.keySet());
            remove(WaypointRegistries.WAYPOINTS.serverStore(), waypoints.keySet());
            remove(DialogueRegistries.NPC_BINDINGS.serverStore(), bindings.keySet());
            QuestEvents.rebuild();
            StoryEvents.rebuild();
            NpcBindings.rebuild();
            Waypoints.rebuild();
        }
        helper.assertTrue(total <= BUDGET_MS, String.join("\n", report));
        helper.succeed();
    }

    /** Runs the work, after warming up, and returns its cost per tick in ms (once every {@code every} ticks). */
    private static double measure(List<String> report, String what, int every, Runnable work) {
        for (int i = 0; i < 10; i++) {
            work.run();
        }
        long start = System.nanoTime();
        for (int i = 0; i < RUNS; i++) {
            work.run();
        }
        double perRun = (System.nanoTime() - start) / 1e6 / RUNS;
        double perTick = perRun / every;
        report.add(String.format(Locale.ROOT, "%s: %.3f ms a run, %.3f ms per tick", what, perRun, perTick));
        return perTick;
    }

    private static <T> void add(RegistryOps<JsonElement> ops, DefinitionStore<T> store, Codec<T> codec, Map<ResourceLocation, String> definitions) {
        Map<ResourceLocation, T> next = new LinkedHashMap<>(store.all());
        definitions.forEach((id, json) -> next.put(id, codec.parse(ops, JsonParser.parseString(json)).getOrThrow(GameTestAssertException::new)));
        store.replace(next);
    }

    private static <T> void remove(DefinitionStore<T> store, Collection<ResourceLocation> ids) {
        Map<ResourceLocation, T> next = new LinkedHashMap<>(store.all());
        ids.forEach(next::remove);
        store.replace(next);
    }

    private static ResourceLocation key(String path) {
        return ResourceLocation.fromNamespaceAndPath("gametest", path);
    }
}
