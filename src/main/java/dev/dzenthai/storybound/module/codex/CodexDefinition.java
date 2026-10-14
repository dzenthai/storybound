package dev.dzenthai.storybound.module.codex;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.data.StoryIds;
import dev.dzenthai.storybound.core.text.StoryText;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryCodecs;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;

/**
 * {@code data/<ns>/storybound/codex/<path>.json}: an entry made of sections the player uncovers one by one,
 * each by its own {@link Unlock}. Listed in the Codex once any section is open.
 *
 * @param entity   what {@code seen} and {@code killed} refer to: an id, a #tag or a list; also the portrait
 * @param announce JSON {@code notify}: toasts when the entry appears or a section opens
 */
public record CodexDefinition(ResourceLocation category, Optional<HolderSet<EntityType<?>>> entity, StoryText title,
                              List<Section> sections, boolean announce) {
    public static final Codec<CodexDefinition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            StoryIds.CODEC.optionalFieldOf("category", Storybound.id("monsters")).forGetter(CodexDefinition::category),
            RegistryCodecs.homogeneousList(Registries.ENTITY_TYPE).optionalFieldOf("entity").forGetter(CodexDefinition::entity),
            StoryText.CODEC.fieldOf("title").forGetter(CodexDefinition::title),
            Section.CODEC.listOf().fieldOf("sections").forGetter(CodexDefinition::sections),
            Codec.BOOL.optionalFieldOf("notify", true).forGetter(CodexDefinition::announce)
    ).apply(instance, CodexDefinition::new));

    public Optional<Section> section(String id) {
        return sections.stream().filter(section -> section.id().equals(id)).findFirst();
    }

    /** The entity type when {@code entity} names exactly one, for the portrait. */
    public Optional<EntityType<?>> singleType() {
        return entity.filter(set -> set.size() == 1 && set.unwrapKey().isEmpty()).map(set -> set.get(0).value());
    }

    /** What clients receive: everything except how sections open. */
    CodexDefinition clientView() {
        return new CodexDefinition(category, entity, title, sections.stream().map(Section::clientView).toList(), announce);
    }

    /**
     * @param title shown above the section's text
     * @param text  a long text, {@code assets/<ns>/storybound/texts/<lang>/<path>.sbt}; by default
     *              {@code <ns>:codex/<entry path>/<section id>}
     */
    public record Section(String id, Optional<StoryText> title, Optional<ResourceLocation> text, Unlock unlock) {
        public static final Codec<Section> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("id").forGetter(Section::id),
                StoryText.CODEC.optionalFieldOf("title").forGetter(Section::title),
                ResourceLocation.CODEC.optionalFieldOf("text").forGetter(Section::text),
                Unlock.CODEC.fieldOf("unlock").forGetter(Section::unlock)
        ).apply(instance, Section::new));

        public ResourceLocation textId(ResourceLocation entry) {
            return text.orElseGet(() -> ResourceLocation.fromNamespaceAndPath(entry.getNamespace(), "codex/" + entry.getPath() + "/" + id));
        }

        Section clientView() {
            return new Section(id, title, text, Unlock.HIDDEN);
        }
    }
}
