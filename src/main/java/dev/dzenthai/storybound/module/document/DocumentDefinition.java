package dev.dzenthai.storybound.module.document;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.dzenthai.storybound.core.data.StoryIds;
import dev.dzenthai.storybound.core.logic.Action;
import dev.dzenthai.storybound.core.logic.LogicCodecs;
import dev.dzenthai.storybound.core.text.StoryText;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

/**
 * {@code data/<ns>/storybound/documents/<path>.json}. The text itself lives in the resource pack
 * ({@code assets/<ns>/storybound/texts/<lang>/<content path>.sbt}), so it is translated and restyled
 * without touching the data or re-issuing items.
 */
public record DocumentDefinition(DocumentKind kind, StoryText title, Optional<ResourceLocation> content, Optional<ResourceLocation> style,
                                 JournalSettings journal, List<Action> onRead, Optional<Integer> customModelData,
                                 Optional<Contract> contract) {
    public static final Codec<DocumentDefinition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            DocumentKind.CODEC.fieldOf("kind").forGetter(DocumentDefinition::kind),
            StoryText.CODEC.fieldOf("title").forGetter(DocumentDefinition::title),
            ResourceLocation.CODEC.optionalFieldOf("content").forGetter(DocumentDefinition::content),
            StoryIds.CODEC.optionalFieldOf("style").forGetter(DocumentDefinition::style),
            JournalSettings.CODEC.optionalFieldOf("journal", JournalSettings.DEFAULT).forGetter(DocumentDefinition::journal),
            LogicCodecs.ACTIONS.optionalFieldOf("on_read", List.of()).forGetter(DocumentDefinition::onRead),
            Codec.INT.optionalFieldOf("custom_model_data").forGetter(DocumentDefinition::customModelData),
            Contract.CODEC.optionalFieldOf("contract").forGetter(DocumentDefinition::contract)
    ).apply(instance, DocumentDefinition::new));

    public DocumentDefinition(DocumentKind kind, StoryText title, Optional<ResourceLocation> content, Optional<ResourceLocation> style,
                              JournalSettings journal, List<Action> onRead, Optional<Integer> customModelData) {
        this(kind, title, content, style, journal, onRead, customModelData, Optional.empty());
    }

    /** The text file id; defaults to the document id. */
    public ResourceLocation contentId(ResourceLocation documentId) {
        return content.orElse(documentId);
    }

    public ResourceLocation styleId() {
        return style.orElse(kind.defaultStyle());
    }

    public ResourceLocation journalCategory() {
        return journal.category().orElse(kind.defaultCategory());
    }

    /** What clients receive: everything except the server-side {@code on_read} actions. */
    DocumentDefinition clientView() {
        return new DocumentDefinition(kind, title, content, style, journal, List.of(), customModelData, contract);
    }

    /**
     * @param category Journal section; defaults by kind ({@code letters}, {@code books}, {@code notes}, {@code scrolls})
     * @param addOnRead put the document into the Journal when it is read for the first time
     */
    public record JournalSettings(Optional<ResourceLocation> category, boolean addOnRead) {
        public static final JournalSettings DEFAULT = new JournalSettings(Optional.empty(), true);
        public static final Codec<JournalSettings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                StoryIds.CODEC.optionalFieldOf("category").forGetter(JournalSettings::category),
                Codec.BOOL.optionalFieldOf("add_on_read", true).forGetter(JournalSettings::addOnRead)
        ).apply(instance, JournalSettings::new));
    }

    public record Contract(Optional<ResourceLocation> quest, Optional<StoryText> conditions, Optional<StoryText> reward, Optional<StoryText> hint) {
        public static final Codec<Contract> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ResourceLocation.CODEC.optionalFieldOf("quest").forGetter(Contract::quest),
                StoryText.CODEC.optionalFieldOf("conditions").forGetter(Contract::conditions),
                StoryText.CODEC.optionalFieldOf("reward").forGetter(Contract::reward),
                StoryText.CODEC.optionalFieldOf("hint").forGetter(Contract::hint)
        ).apply(instance, Contract::new));
    }
}
