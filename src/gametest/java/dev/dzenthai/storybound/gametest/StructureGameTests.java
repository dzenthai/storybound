package dev.dzenthai.storybound.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.CoreDefinitions;
import dev.dzenthai.storybound.core.logic.Condition;
import dev.dzenthai.storybound.core.logic.LogicCodecs;
import dev.dzenthai.storybound.core.logic.StoryContext;
import dev.dzenthai.storybound.core.logic.StoryEvent;
import dev.dzenthai.storybound.core.logic.StoryEvents;
import dev.dzenthai.storybound.core.logic.trigger.TriggerDispatcher;
import dev.dzenthai.storybound.core.state.StoryScope;
import dev.dzenthai.storybound.core.state.StoryState;
import dev.dzenthai.storybound.core.state.StoryValue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Being within a structure: the {@code location} condition's {@code structure} and the {@code structure_entered} trigger.
 * The GameTest world generates no structures, so each test plants a plains village start in its own chunk for the
 * duration of the test, the way world generation records one: a start in the chunk and a reference to it.
 */
@SuppressWarnings("removal")
@GameTestHolder(Storybound.MOD_ID)
@PrefixGameTestTemplate(false)
public final class StructureGameTests {
    /** The planted village: a piece this many blocks wide around the given position. */
    private static final int VILLAGE_SIZE = 5;
    /**
     * How far "outside" is, in x and z. A village blends into the terrain, and the game keeps its box 12 blocks wider
     * than its pieces on every side (as for every structure with terrain adaptation): so 2 + 12 is still inside.
     */
    private static final int OUTSIDE = VILLAGE_SIZE / 2 + 12 + 4;

    private StructureGameTests() {}

    @GameTest(template = "open")
    public static void locationChecksTheStructureAroundThePlayer(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        Vec3 inside = helper.absoluteVec(new Vec3(2.5, 1, 2.5));
        player.setPos(inside);
        Vec3 outside = inside.add(OUTSIDE, 0, OUTSIDE);
        withVillage(helper, BlockPos.containing(inside), BlockPos.containing(outside), () -> {
            StoryContext context = StoryContext.of(player);
            expect(helper, condition(helper, "{ \"type\": \"location\", \"structure\": \"minecraft:village_plains\" }").test(context), true, "inside the village");
            expect(helper, condition(helper, "{ \"type\": \"location\", \"structure\": \"#minecraft:village\" }").test(context), true, "by its tag");
            expect(helper, condition(helper, "{ \"type\": \"location\", \"structure\": \"minecraft:stronghold\" }").test(context), false, "another structure");
            expect(helper, condition(helper, "{ \"type\": \"location\", \"structure\": \"gametest:no_such_structure\" }").test(context), false,
                    "an unknown structure is never matched");
            expect(helper, condition(helper, "{ \"type\": \"location\", \"structure\": \"#minecraft:village\", \"dimension\": \"minecraft:the_nether\" }")
                    .test(context), false, "every part must match");
            player.setPos(outside);
            expect(helper, condition(helper, "{ \"type\": \"location\", \"structure\": \"#minecraft:village\" }").test(StoryContext.of(player)), false,
                    "outside its bounds " + structuresAt(helper, player.blockPosition()) + ", planted at " + structuresAt(helper, BlockPos.containing(inside)));
        });
        helper.getLevel().getServer().getPlayerList().remove(player);
        helper.succeed();
    }

    @GameTest(template = "open")
    public static void structureEnteredFiresOnWalkingIn(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        Vec3 inside = helper.absoluteVec(new Vec3(2.5, 1, 2.5));
        Vec3 outside = inside.add(OUTSIDE, 0, OUTSIDE);
        player.setPos(inside);
        withVillage(helper, BlockPos.containing(inside), BlockPos.containing(outside), () -> withEvents(helper, Map.of(
                key("village_login"), "{ \"trigger\": { \"type\": \"structure_entered\", \"structure\": \"#minecraft:village\", \"fire_on_login\": true },"
                        + " \"actions\": { \"type\": \"add_var\", \"key\": \"gametest:village_login_count\", \"amount\": 1 } }",
                key("village_walk"), "{ \"trigger\": { \"type\": \"structure_entered\", \"structure\": \"minecraft:village_plains\" },"
                        + " \"actions\": { \"type\": \"add_var\", \"key\": \"gametest:village_walk_count\", \"amount\": 1 } }",
                key("stronghold_walk"), "{ \"trigger\": { \"type\": \"structure_entered\", \"structure\": \"minecraft:stronghold\" },"
                        + " \"actions\": { \"type\": \"add_var\", \"key\": \"gametest:stronghold_count\", \"amount\": 1 } }"
        ), () -> {
            TriggerDispatcher.tickAreas(helper.getLevel().getServer());
            expect(helper, stored(player, "village_login_count"), Optional.of(new StoryValue.Int(1)), "fire_on_login fires right after login");
            expect(helper, stored(player, "village_walk_count"), Optional.empty(), "a plain trigger does not fire on login");

            TriggerDispatcher.tickAreas(helper.getLevel().getServer());
            expect(helper, stored(player, "village_walk_count"), Optional.empty(), "standing inside fires nothing");
            player.setPos(outside);
            String atOutside = structuresAt(helper, player.blockPosition());
            TriggerDispatcher.tickAreas(helper.getLevel().getServer());
            player.setPos(inside);
            TriggerDispatcher.tickAreas(helper.getLevel().getServer());
            expect(helper, stored(player, "village_walk_count"), Optional.of(new StoryValue.Int(1)),
                    "walking in fires (outside " + atOutside + ", inside " + structuresAt(helper, player.blockPosition()) + ")");
            expect(helper, stored(player, "village_login_count"), Optional.of(new StoryValue.Int(2)), "and so does the login one");
            expect(helper, stored(player, "stronghold_count"), Optional.empty(), "another structure never fires");
        }));
        helper.getLevel().getServer().getPlayerList().remove(player);
        helper.succeed();
    }

    /** For failure messages: the position, its chunk and every structure referenced there, with its bounds. */
    private static String structuresAt(GameTestHelper helper, BlockPos pos) {
        StructureManager manager = helper.getLevel().structureManager();
        StringBuilder text = new StringBuilder(pos.toShortString() + " in chunk " + new ChunkPos(pos) + " loaded=" + helper.getLevel().isLoaded(pos) + ":");
        manager.getAllStructuresAt(pos).forEach((structure, references) -> {
            text.append(' ').append(helper.getLevel().registryAccess().registryOrThrow(Registries.STRUCTURE).getKey(structure)).append(" refs ").append(references);
            manager.startsForStructure(SectionPos.of(pos), structure).forEach(start -> text.append(" box ").append(start.getBoundingBox()));
        });
        return text.toString();
    }

    /**
     * Plants a plains village start around {@code center} in its chunk for the duration of {@code body}, and a
     * reference to it in {@code alsoReferencedAt}'s chunk too, as world generation does for every chunk a structure
     * reaches: so a check there really looks at the village's box instead of finding no village in the chunk.
     */
    private static void withVillage(GameTestHelper helper, BlockPos center, BlockPos alsoReferencedAt, Runnable body) {
        Structure village = helper.getLevel().registryAccess().registryOrThrow(Registries.STRUCTURE).getOrThrow(BuiltinStructures.VILLAGE_PLAINS);
        ChunkAccess chunk = helper.getLevel().getChunk(center);
        ChunkAccess other = helper.getLevel().getChunk(alsoReferencedAt);
        Map<Structure, StructureStart> starts = new HashMap<>(chunk.getAllStarts());
        Map<Structure, LongSet> references = copy(chunk.getAllReferences());
        Map<Structure, LongSet> otherReferences = copy(other.getAllReferences());
        int half = VILLAGE_SIZE / 2;
        BoundingBox box = new BoundingBox(center.getX() - half, center.getY() - 1, center.getZ() - half,
                center.getX() + half, center.getY() + 3, center.getZ() + half);
        StructurePiece piece = new StructurePiece(StructurePieceType.JIGSAW, 0, box) {
            @Override
            protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {}

            @Override
            public void postProcess(WorldGenLevel level, StructureManager manager, ChunkGenerator generator, RandomSource random, BoundingBox area,
                                    ChunkPos chunkPos, BlockPos pivot) {}
        };
        chunk.setStartForStructure(village, new StructureStart(village, chunk.getPos(), 0, new PiecesContainer(List.of(piece))));
        chunk.addReferenceForStructure(village, chunk.getPos().toLong());
        other.addReferenceForStructure(village, chunk.getPos().toLong());
        try {
            body.run();
        } finally {
            chunk.setAllStarts(starts);
            // The other chunk first: it may be the same chunk, and then the planted chunk's copy must win.
            other.setAllReferences(otherReferences);
            chunk.setAllReferences(references);
        }
    }

    private static Map<Structure, LongSet> copy(Map<Structure, LongSet> references) {
        Map<Structure, LongSet> copy = new HashMap<>();
        references.forEach((structure, set) -> copy.put(structure, new LongOpenHashSet(set)));
        return copy;
    }

    private static void withEvents(GameTestHelper helper, Map<ResourceLocation, String> events, Runnable body) {
        Map<ResourceLocation, StoryEvent> previous = CoreDefinitions.EVENTS.serverStore().all();
        Map<ResourceLocation, StoryEvent> installed = new LinkedHashMap<>(previous);
        events.forEach((id, json) -> installed.put(id, StoryEvent.CODEC.parse(ops(helper), JsonParser.parseString(json))
                .getOrThrow(message -> new GameTestAssertException("Parse failed: " + message))));
        CoreDefinitions.EVENTS.serverStore().replace(installed);
        StoryEvents.rebuild();
        try {
            body.run();
        } finally {
            CoreDefinitions.EVENTS.serverStore().replace(previous);
            StoryEvents.rebuild();
        }
    }

    private static Condition condition(GameTestHelper helper, String json) {
        return LogicCodecs.CONDITION.parse(ops(helper), JsonParser.parseString(json))
                .getOrThrow(message -> new GameTestAssertException("Condition parse failed: " + message));
    }

    private static RegistryOps<JsonElement> ops(GameTestHelper helper) {
        return RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().registryAccess());
    }

    private static ResourceLocation key(String path) {
        return ResourceLocation.fromNamespaceAndPath("gametest", path);
    }

    private static Optional<StoryValue> stored(ServerPlayer player, String path) {
        return StoryState.getStored(player, StoryScope.PLAYER, key(path));
    }

    private static void expect(GameTestHelper helper, Object actual, Object expected, String what) {
        helper.assertTrue(Objects.equals(actual, expected), what + ": expected " + expected + ", got " + actual);
    }
}
