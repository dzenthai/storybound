package dev.dzenthai.storybound.client;

import dev.dzenthai.storybound.Storybound;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;

/**
 * Which Adventure Menu entries this player has already looked at, for the "new" markers. Purely
 * cosmetic, so it stays on the client: {@code storybound/seen/<world or server>.txt} in the game
 * directory. Keys are chosen by the tabs, e.g. {@code document:<id>} or {@code quest:<id>:<state>:<stage>}.
 */
public final class SeenMarks {
    private static final Set<String> SEEN = new HashSet<>();
    private static @Nullable Path file;

    private SeenMarks() {}

    public static boolean isSeen(String key) {
        ensureLoaded();
        return SEEN.contains(key);
    }

    public static void markSeen(String key) {
        ensureLoaded();
        if (SEEN.add(key) && file != null) {
            try {
                Files.createDirectories(file.getParent());
                Files.write(file, SEEN);
            } catch (IOException e) {
                Storybound.LOGGER.warn("Storybound: could not save {}", file, e);
            }
        }
    }

    /** Called on logout; the next world loads its own file. */
    public static void reset() {
        SEEN.clear();
        file = null;
    }

    private static void ensureLoaded() {
        if (file != null) {
            return;
        }
        Path root = FMLPaths.GAMEDIR.get().resolve("storybound");
        String name = worldKey() + ".txt";
        file = root.resolve("seen").resolve(name);
        // Before stage 4 only the Journal had markers, stored under journal_seen/.
        Path source = Files.exists(file) ? file : root.resolve("journal_seen").resolve(name);
        if (Files.exists(source)) {
            try {
                SEEN.addAll(Files.readAllLines(source));
            } catch (IOException e) {
                Storybound.LOGGER.warn("Storybound: could not read {}", source, e);
            }
        }
    }

    private static String worldKey() {
        Minecraft minecraft = Minecraft.getInstance();
        MinecraftServer server = minecraft.getSingleplayerServer();
        String key;
        if (server != null) {
            key = "world-" + server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName();
        } else {
            ServerData serverData = minecraft.getCurrentServer();
            key = "server-" + (serverData != null ? serverData.ip : "unknown");
        }
        return key.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
