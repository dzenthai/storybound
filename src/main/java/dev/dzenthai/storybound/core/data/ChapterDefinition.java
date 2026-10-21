package dev.dzenthai.storybound.core.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.dzenthai.storybound.core.text.StoryText;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

/**
 * A dev mode chapter, in {@code config/storybound/chapters/}: a page of the story canvas with its title, its place
 * among the chapters and where each definition sits on it. Server only; the game itself never reads chapters.
 */
public record ChapterDefinition(StoryText title, int order, Optional<ResourceLocation> icon, List<PlacedNode> nodes) {
    public static final Codec<ChapterDefinition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            StoryText.CODEC.fieldOf("title").forGetter(ChapterDefinition::title),
            Codec.INT.optionalFieldOf("order", 0).forGetter(ChapterDefinition::order),
            ResourceLocation.CODEC.optionalFieldOf("icon").forGetter(ChapterDefinition::icon),
            PlacedNode.CODEC.listOf().optionalFieldOf("nodes", List.of()).forGetter(ChapterDefinition::nodes)
    ).apply(instance, ChapterDefinition::new));

    /** A definition on the canvas: its definition type ({@code quest}, {@code dialogue}...; the namespace defaults to {@code storybound}), its id and position. */
    public record PlacedNode(ResourceLocation type, ResourceLocation id, int x, int y) {
        public static final Codec<PlacedNode> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                StoryIds.CODEC.fieldOf("type").forGetter(PlacedNode::type),
                ResourceLocation.CODEC.fieldOf("id").forGetter(PlacedNode::id),
                Codec.INT.fieldOf("x").forGetter(PlacedNode::x),
                Codec.INT.fieldOf("y").forGetter(PlacedNode::y)
        ).apply(instance, PlacedNode::new));
    }
}

