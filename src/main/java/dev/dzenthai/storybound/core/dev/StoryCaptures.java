package dev.dzenthai.storybound.core.dev;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.dzenthai.storybound.core.data.ConfigStorySource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * {@code /storybound capture pos|entity <name>}: places and entities shown in the world, written under a name to
 * {@code config/storybound/captures.json} for an author who reads files (the world pickers of a text editor). The
 * file is editor-only: the game never reads it, and capturing the same name again replaces the entry.
 */
public final class StoryCaptures {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    /** How far the looked-at block is searched. */
    private static final double REACH = 64;

    private StoryCaptures() {}

    public static Path file() {
        return ConfigStorySource.root().resolve("captures.json");
    }

    /** Where the player stands, with the block they look at. */
    public static JsonObject position(ServerPlayer player) {
        JsonObject capture = base(player, "pos");
        capture.add("pos", blockPos(player.blockPosition()));
        capture.add("exact", exact(player.position()));
        capture.addProperty("yaw", Math.round(player.getYRot() * 10) / 10.0);
        capture.addProperty("pitch", Math.round(player.getXRot() * 10) / 10.0);
        ServerLevel level = player.serverLevel();
        level.getBiome(player.blockPosition()).unwrapKey().ifPresent(biome -> capture.addProperty("biome", biome.location().toString()));
        BlockHitResult hit = level.clip(new ClipContext(player.getEyePosition(), player.getEyePosition().add(player.getViewVector(1).scale(REACH)),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (hit.getType() == HitResult.Type.BLOCK) {
            JsonObject looking = new JsonObject();
            looking.addProperty("block", BuiltInRegistries.BLOCK.getKey(level.getBlockState(hit.getBlockPos()).getBlock()).toString());
            looking.add("pos", blockPos(hit.getBlockPos()));
            capture.add("looking_at", looking);
        }
        return capture;
    }

    /** An entity: its uuid, type, tags and name, for an NPC binding's selector or a waypoint that follows it. */
    public static JsonObject entity(ServerPlayer player, Entity entity) {
        JsonObject capture = base(player, "entity");
        capture.addProperty("uuid", entity.getUUID().toString());
        capture.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
        if (entity.getCustomName() != null) {
            capture.addProperty("name", entity.getCustomName().getString());
        }
        JsonArray tags = new JsonArray();
        entity.getTags().stream().sorted().forEach(tags::add);
        capture.add("tags", tags);
        capture.addProperty("dimension", entity.level().dimension().location().toString());
        capture.add("pos", blockPos(entity.blockPosition()));
        capture.add("exact", exact(entity.position()));
        return capture;
    }

    /** Writes the capture under the name, keeping the others. */
    public static Path save(String name, JsonObject capture) throws IOException {
        Path file = file();
        JsonObject all = new JsonObject();
        if (Files.isRegularFile(file)) {
            JsonElement read = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!read.isJsonObject()) {
                // Never overwrite what an author wrote by hand into something else.
                throw new IOException(file + " is not a JSON object");
            }
            all = read.getAsJsonObject();
        }
        all.remove(name);
        all.add(name, capture);
        Files.createDirectories(file.getParent());
        Files.writeString(file, GSON.toJson(all) + "\n", StandardCharsets.UTF_8);
        return file;
    }

    /** The entity the player looks at within reach, if any. */
    public static @Nullable Entity lookedAt(ServerPlayer player) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1).scale(REACH));
        Entity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity candidate : player.serverLevel().getEntities(player, player.getBoundingBox().expandTowards(end.subtract(eye)).inflate(1),
                entity -> !entity.isSpectator() && entity.isPickable())) {
            var hit = candidate.getBoundingBox().inflate(candidate.getPickRadius() + 0.3).clip(eye, end);
            if (hit.isPresent() && eye.distanceToSqr(hit.get()) < bestDistance) {
                best = candidate;
                bestDistance = eye.distanceToSqr(hit.get());
            }
        }
        return best;
    }

    private static JsonObject base(ServerPlayer player, String kind) {
        JsonObject capture = new JsonObject();
        capture.addProperty("kind", kind);
        capture.addProperty("dimension", player.level().dimension().location().toString());
        capture.addProperty("by", player.getGameProfile().getName());
        capture.addProperty("written", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return capture;
    }

    private static JsonArray blockPos(BlockPos pos) {
        JsonArray array = new JsonArray();
        array.add(pos.getX());
        array.add(pos.getY());
        array.add(pos.getZ());
        return array;
    }

    private static JsonArray exact(Vec3 pos) {
        JsonArray array = new JsonArray();
        array.add(Math.round(pos.x * 100) / 100.0);
        array.add(Math.round(pos.y * 100) / 100.0);
        array.add(Math.round(pos.z * 100) / 100.0);
        return array;
    }
}
