package dev.dzenthai.storybound.module.quest;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.data.ProjectSettings;
import io.netty.buffer.ByteBuf;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.Mth;

/**
 * How quests show their difficulty, picked by the modpack's author for every quest in {@code project.json}:
 * <pre>
 * "difficulty": { "style": "stars" }
 * "difficulty": { "style": "levels", "levels": [ { "id": "easy", "color": "#5DBB63", "name": "mypack.difficulty.easy" }, ... ] }
 * </pre>
 * Stars go from 1 to {@link #MAX_STARS}, as in Stardew Valley; a scale has up to as many levels. Without it: levels
 * {@code easy} (green), {@code normal} (yellow), {@code hard} (red), {@code deadly} (dark red). A quest may name a
 * level or a number of stars either way: the n-th level is n stars, so the style can change without editing the quests.
 */
public record DifficultyScale(Style style, List<Level> levels) {
    public enum Style { STARS, LEVELS }

    /** The most stars, and the most levels a scale may have. */
    public static final int MAX_STARS = 5;

    /** @param name a translation key or the text itself */
    public record Level(String id, int color, String name) {}

    public static final DifficultyScale DEFAULT = new DifficultyScale(Style.LEVELS, List.of(
            new Level("easy", 0x5DBB63, "storybound.difficulty.easy"),
            new Level("normal", 0xE8C547, "storybound.difficulty.normal"),
            new Level("hard", 0xD9534F, "storybound.difficulty.hard"),
            new Level("deadly", 0x8B1A1A, "storybound.difficulty.deadly")));

    public static final StreamCodec<ByteBuf, DifficultyScale> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.idMapper(index -> Style.values()[index], Style::ordinal), DifficultyScale::style,
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, Level::id,
                    ByteBufCodecs.INT, Level::color,
                    ByteBufCodecs.STRING_UTF8, Level::name,
                    Level::new).apply(ByteBufCodecs.list()), DifficultyScale::levels,
            DifficultyScale::new);

    /** The scale in use: the server's from {@code project.json}, a client's from the last quest sync. */
    private static volatile DifficultyScale current = DEFAULT;

    public static DifficultyScale current() {
        return current;
    }

    public static void set(DifficultyScale scale) {
        current = scale;
    }

    /** Reads {@code difficulty} from {@code project.json}; anything wrong in it is logged and the default used. */
    public static DifficultyScale load(Path root) {
        Path file = root.resolve(ProjectSettings.FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return DEFAULT;
        }
        try {
            JsonElement json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!json.isJsonObject() || !json.getAsJsonObject().has("difficulty")) {
                return DEFAULT;
            }
            return parse(json.getAsJsonObject().getAsJsonObject("difficulty"));
        } catch (IOException | JsonParseException | IllegalStateException | ClassCastException | NumberFormatException e) {
            Storybound.LOGGER.warn("Storybound: the difficulty in config/storybound/{} does not load ({}); the default scale is used",
                    ProjectSettings.FILE_NAME, e.getMessage());
            return DEFAULT;
        }
    }

    /** Public for tests. */
    public static DifficultyScale parse(JsonObject json) {
        Style style = json.has("style") && json.get("style").getAsString().equalsIgnoreCase("stars") ? Style.STARS : Style.LEVELS;
        List<Level> levels = new ArrayList<>();
        if (json.has("levels")) {
            for (JsonElement element : json.getAsJsonArray("levels")) {
                JsonObject level = element.getAsJsonObject();
                String id = level.get("id").getAsString();
                int color = level.has("color") ? color(level.get("color")) : 0xFFFFFF;
                levels.add(new Level(id, color, level.has("name") ? level.get("name").getAsString() : "storybound.difficulty." + id));
            }
        }
        if (levels.size() > MAX_STARS) {
            Storybound.LOGGER.warn("Storybound: the difficulty in config/storybound/{} has {} levels; only the first {} are used",
                    ProjectSettings.FILE_NAME, levels.size(), MAX_STARS);
        }
        return new DifficultyScale(style, levels.isEmpty() ? DEFAULT.levels() : List.copyOf(levels.subList(0, Math.min(levels.size(), MAX_STARS))));
    }

    /** {@code "#8B1A1A"}, {@code "8B1A1A"} or a number. */
    private static int color(JsonElement value) {
        if (value.getAsJsonPrimitive().isNumber()) {
            return value.getAsInt() & 0xFFFFFF;
        }
        String text = value.getAsString().trim();
        return Integer.parseInt(text.startsWith("#") ? text.substring(1) : text, 16) & 0xFFFFFF;
    }

    /** The level a quest's difficulty stands for: named, or the n-th for n stars (the last one beyond). */
    public Optional<Level> level(QuestDefinition.Difficulty difficulty) {
        if (difficulty.level().isPresent()) {
            String id = difficulty.level().get().toLowerCase(Locale.ROOT);
            return levels.stream().filter(level -> level.id().equals(id)).findFirst();
        }
        int stars = difficulty.stars().orElse(1);
        return Optional.of(levels.get(Mth.clamp(stars - 1, 0, levels.size() - 1)));
    }

    /** The stars a quest's difficulty stands for: given, or the level's place (n-th level, n stars); 0 for a level not in the scale. */
    public int stars(QuestDefinition.Difficulty difficulty) {
        if (difficulty.stars().isPresent()) {
            return Mth.clamp(difficulty.stars().get(), 1, MAX_STARS);
        }
        String id = difficulty.level().orElse("").toLowerCase(Locale.ROOT);
        for (int i = 0; i < levels.size(); i++) {
            if (levels.get(i).id().equals(id)) {
                return i + 1;
            }
        }
        return 0;
    }

    /** What a quest's difficulty can be on this scale, easiest first: 1 to 5 stars, or the levels by id. */
    public List<QuestDefinition.Difficulty> choices() {
        List<QuestDefinition.Difficulty> choices = new ArrayList<>();
        if (style == Style.STARS) {
            for (int stars = 1; stars <= MAX_STARS; stars++) {
                choices.add(new QuestDefinition.Difficulty(Optional.of(stars), Optional.empty()));
            }
        } else {
            levels.forEach(level -> choices.add(new QuestDefinition.Difficulty(Optional.empty(), Optional.of(level.id()))));
        }
        return choices;
    }

    /** As the Journal shows it: {@code ★★★☆☆} in gold, or the level's name in its colour. */
    public Component render(QuestDefinition.Difficulty difficulty) {
        if (style == Style.STARS) {
            int stars = stars(difficulty);
            MutableComponent line = Component.literal("★".repeat(stars)).withStyle(ChatFormatting.GOLD);
            return line.append(Component.literal("☆".repeat(Math.max(0, MAX_STARS - stars))).withStyle(ChatFormatting.DARK_GRAY));
        }
        return level(difficulty)
                .<Component>map(level -> Component.translatableWithFallback(level.name(), level.name().startsWith("storybound.difficulty.")
                        ? level.id() : level.name()).withColor(level.color()))
                .orElseGet(() -> Component.literal(difficulty.level().orElse("?")).withStyle(ChatFormatting.GRAY));
    }
}
