package dev.dzenthai.storybound.core.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.dzenthai.storybound.core.text.StoryText;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

/**
 * A script block of the dev mode's story canvas, in {@code config/storybound/scripts/}: a KubeJS handler the story
 * calls (the action or condition "Script" with {@code hook}), the file it is written in, and what it leads to. The
 * code stays in KubeJS; this only shows where it enters the story. The game never reads script blocks.
 *
 * @param hook    the handler's id; the block's own id when absent
 * @param file    under {@code kubejs/server_scripts/}
 * @param leadsTo what the script goes on to (starts, gives), for what reading its file does not show
 */
public record ScriptDefinition(Optional<StoryText> title, Optional<String> description, Optional<String> file, Optional<ResourceLocation> hook,
                               List<Target> leadsTo) {
    public static final Codec<ScriptDefinition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            StoryText.CODEC.optionalFieldOf("title").forGetter(ScriptDefinition::title),
            Codec.STRING.optionalFieldOf("description").forGetter(ScriptDefinition::description),
            Codec.STRING.optionalFieldOf("file").forGetter(ScriptDefinition::file),
            ResourceLocation.CODEC.optionalFieldOf("hook").forGetter(ScriptDefinition::hook),
            Target.CODEC.listOf().optionalFieldOf("leads_to", List.of()).forGetter(ScriptDefinition::leadsTo)
    ).apply(instance, ScriptDefinition::new));

    /** A definition by its type ({@code quest}, {@code dialogue}...; the namespace defaults to storybound) and id. */
    public record Target(ResourceLocation type, ResourceLocation id) {
        public static final Codec<Target> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                StoryIds.CODEC.fieldOf("type").forGetter(Target::type),
                ResourceLocation.CODEC.fieldOf("id").forGetter(Target::id)
        ).apply(instance, Target::new));
    }
}
