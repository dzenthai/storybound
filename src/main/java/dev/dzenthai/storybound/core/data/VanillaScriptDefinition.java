package dev.dzenthai.storybound.core.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.dzenthai.storybound.core.text.StoryText;
import java.util.List;
import java.util.Optional;
import org.jetbrains.annotations.Nullable;

/**
 * A script of vanilla commands, in {@code config/storybound/scripts/}: its {@code lines} run in order by the action
 * {@code run_script}, as a {@code .mcfunction} would, at the player or at an entity. A line {@code wait <seconds>}
 * pauses, a line starting with {@code #} is a note, an empty one is skipped. The validator parses every command when
 * the story loads, so a typo is reported with its line rather than doing nothing in the game.
 */
public record VanillaScriptDefinition(Optional<StoryText> title, Optional<String> description, List<String> lines) {
    public static final Codec<VanillaScriptDefinition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            StoryText.CODEC.optionalFieldOf("title").forGetter(VanillaScriptDefinition::title),
            Codec.STRING.optionalFieldOf("description").forGetter(VanillaScriptDefinition::description),
            Codec.STRING.listOf().optionalFieldOf("lines", List.of()).forGetter(VanillaScriptDefinition::lines)
    ).apply(instance, VanillaScriptDefinition::new));

    /** The longest pause a {@code wait} line may ask for, as the {@code wait} action. */
    public static final double MAX_WAIT = 86_400;

    /** Whether the line is a note or empty: nothing to run. */
    public static boolean skipped(String line) {
        String text = line.strip();
        return text.isEmpty() || text.startsWith("#");
    }

    /** The seconds of a {@code wait <seconds>} line; null for a command. Not a number: {@code NaN}. */
    public static @Nullable Double waitSeconds(String line) {
        String text = line.strip();
        if (!text.equals("wait") && !text.startsWith("wait ")) {
            return null;
        }
        try {
            return Double.parseDouble(text.substring(4).strip());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** A command as the game takes it: without a leading slash. */
    public static String command(String line) {
        String text = line.strip();
        return text.startsWith("/") ? text.substring(1) : text;
    }
}
