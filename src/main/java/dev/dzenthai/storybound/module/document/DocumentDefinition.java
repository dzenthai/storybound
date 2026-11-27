package dev.dzenthai.storybound.module.document;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.dzenthai.storybound.core.data.StoryIds;
import dev.dzenthai.storybound.core.logic.Action;
import dev.dzenthai.storybound.core.logic.Condition;
import dev.dzenthai.storybound.core.logic.LogicCodecs;
import dev.dzenthai.storybound.core.logic.StructureRef;
import dev.dzenthai.storybound.core.logic.WorldPlaces;
import dev.dzenthai.storybound.core.text.StoryText;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryCodecs;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

/**
 * {@code data/<ns>/storybound/documents/<path>.json}. The text itself lives in the resource pack
 * ({@code assets/<ns>/storybound/texts/<lang>/<content path>.sbt}), so it is translated and restyled
 * without touching the data or re-issuing items.
 */
public record DocumentDefinition(DocumentKind kind, StoryText title, Optional<ResourceLocation> content, Optional<ResourceLocation> style,
                                 JournalSettings journal, List<Action> onRead, Optional<Integer> customModelData,
                                 Optional<Contract> contract, List<LootRule> loot) {
    public static final Codec<DocumentDefinition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            DocumentKind.CODEC.fieldOf("kind").forGetter(DocumentDefinition::kind),
            StoryText.CODEC.fieldOf("title").forGetter(DocumentDefinition::title),
            ResourceLocation.CODEC.optionalFieldOf("content").forGetter(DocumentDefinition::content),
            StoryIds.CODEC.optionalFieldOf("style").forGetter(DocumentDefinition::style),
            JournalSettings.CODEC.optionalFieldOf("journal", JournalSettings.DEFAULT).forGetter(DocumentDefinition::journal),
            LogicCodecs.ACTIONS.optionalFieldOf("on_read", List.of()).forGetter(DocumentDefinition::onRead),
            Codec.INT.optionalFieldOf("custom_model_data").forGetter(DocumentDefinition::customModelData),
            Contract.CODEC.optionalFieldOf("contract").forGetter(DocumentDefinition::contract),
            LootRule.CODEC.listOf().optionalFieldOf("loot", List.of()).forGetter(DocumentDefinition::loot)
    ).apply(instance, DocumentDefinition::new));

    public DocumentDefinition(DocumentKind kind, StoryText title, Optional<ResourceLocation> content, Optional<ResourceLocation> style,
                              JournalSettings journal, List<Action> onRead, Optional<Integer> customModelData) {
        this(kind, title, content, style, journal, onRead, customModelData, Optional.empty(), List.of());
    }

    public DocumentDefinition(DocumentKind kind, StoryText title, Optional<ResourceLocation> content, Optional<ResourceLocation> style,
                              JournalSettings journal, List<Action> onRead, Optional<Integer> customModelData,
                              Optional<Contract> contract) {
        this(kind, title, content, style, journal, onRead, customModelData, contract, List.of());
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

    /** What clients receive: everything except server-only {@code on_read} actions and {@code loot} spawn rules. */
    DocumentDefinition clientView() {
        return new DocumentDefinition(kind, title, content, style, journal, List.of(), customModelData, contract, List.of());
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

    /**
     * One place where this document can appear as world loot (evaluated by {@link DocumentLootModifier}).
     * When {@code tables} is empty, matches any chest loot table; when non-empty, matches only listed tables.
     */
    public record LootRule(List<ResourceLocation> tables, Optional<ResourceKey<Level>> dimension,
                           Optional<HolderSet<Biome>> biome, Optional<List<StructureRef>> structure,
                           float chance, Once once, Optional<Condition> conditions) {
        private static final Codec<List<ResourceLocation>> TABLES = Codec.either(ResourceLocation.CODEC, ResourceLocation.CODEC.listOf())
                .xmap(either -> either.map(List::of, Function.identity()),
                        list -> list.size() == 1 ? Either.left(list.getFirst()) : Either.right(list));
        private static final Codec<List<StructureRef>> STRUCTURES = Codec.either(StructureRef.CODEC, StructureRef.CODEC.listOf())
                .xmap(either -> either.map(List::of, Function.identity()),
                        list -> list.size() == 1 ? Either.left(list.getFirst()) : Either.right(list));

        public static final Codec<LootRule> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                TABLES.optionalFieldOf("tables", List.of()).forGetter(LootRule::tables),
                Level.RESOURCE_KEY_CODEC.optionalFieldOf("dimension").forGetter(LootRule::dimension),
                RegistryCodecs.homogeneousList(Registries.BIOME).optionalFieldOf("biome").forGetter(LootRule::biome),
                STRUCTURES.optionalFieldOf("structure").forGetter(LootRule::structure),
                Codec.floatRange(0.0f, 1.0f).optionalFieldOf("chance", 1.0f).forGetter(LootRule::chance),
                Once.CODEC.optionalFieldOf("once", Once.NONE).forGetter(LootRule::once),
                LogicCodecs.CONDITION.optionalFieldOf("conditions").forGetter(LootRule::conditions)
        ).apply(instance, LootRule::new));

        public WorldPlaces.Place place() {
            return new WorldPlaces.Place(dimension, biome, structure);
        }

        public boolean matchesTable(ResourceLocation tableId) {
            return tables.isEmpty() ? isChestTable(tableId) : tables.contains(tableId);
        }

        /** Whether a loot table id looks like a chest/container table (vanilla or modded). */
        public static boolean isChestTable(ResourceLocation tableId) {
            String path = tableId.getPath().toLowerCase(Locale.ROOT);
            return path.startsWith("chests/") || path.startsWith("chest/")
                    || path.contains("/chests/") || path.contains("/chest/");
        }

        public enum Once implements StringRepresentable {
            NONE("none"),
            PLAYER("player"),
            WORLD("world");

            public static final Codec<Once> CODEC = StringRepresentable.fromEnum(Once::values);

            private final String name;

            Once(String name) {
                this.name = name;
            }

            @Override
            public String getSerializedName() {
                return name;
            }
        }
    }
}
