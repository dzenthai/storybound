package dev.dzenthai.storybound.module.document;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.StoryException;
import dev.dzenthai.storybound.core.data.StoryIds;
import dev.dzenthai.storybound.core.logic.Action;
import dev.dzenthai.storybound.core.logic.ActionType;
import dev.dzenthai.storybound.core.logic.Condition;
import dev.dzenthai.storybound.core.logic.ConditionType;
import dev.dzenthai.storybound.core.logic.LogicRegistries;
import dev.dzenthai.storybound.core.logic.References;
import dev.dzenthai.storybound.core.logic.StoryContext;
import dev.dzenthai.storybound.core.logic.Trigger;
import dev.dzenthai.storybound.core.logic.TriggerType;
import dev.dzenthai.storybound.core.text.StoryText;
import dev.dzenthai.storybound.module.journal.Journal;
import dev.dzenthai.storybound.module.journal.JournalNote;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** The document module's additions to the logic language. */
public final class DocumentLogic {
    static final DeferredRegister<ConditionType<?>> REGISTER_CONDITIONS = DeferredRegister.create(LogicRegistries.CONDITION_TYPE_KEY, Storybound.MOD_ID);
    static final DeferredRegister<ActionType<?>> REGISTER_ACTIONS = DeferredRegister.create(LogicRegistries.ACTION_TYPE_KEY, Storybound.MOD_ID);
    static final DeferredRegister<TriggerType<?, ?>> REGISTER_TRIGGERS = DeferredRegister.create(LogicRegistries.TRIGGER_TYPE_KEY, Storybound.MOD_ID);

    public static final DeferredHolder<ConditionType<?>, ConditionType<HasRead>> DOCUMENT_READ_CONDITION =
            REGISTER_CONDITIONS.register("document_read", () -> new ConditionType<>(HasRead.CODEC));
    public static final DeferredHolder<ActionType<?>, ActionType<GiveDocument>> GIVE_DOCUMENT =
            REGISTER_ACTIONS.register("give_document", () -> new ActionType<>(GiveDocument.CODEC));
    public static final DeferredHolder<ActionType<?>, ActionType<JournalEntry>> JOURNAL_ENTRY =
            REGISTER_ACTIONS.register("journal_entry", () -> new ActionType<>(JournalEntry.CODEC));
    public static final DeferredHolder<TriggerType<?, ?>, TriggerType<Read, DocumentRead>> DOCUMENT_READ =
            REGISTER_TRIGGERS.register("document_read", () -> new TriggerType<>(Read.CODEC));

    private DocumentLogic() {}

    /** The payload of the {@code document_read} trigger. */
    public record DocumentRead(ResourceLocation document, boolean firstTime) {}

    /** {@code { "type": "document_read", "document": "mypack:letters/ciri" }}: the player has read it at least once. */
    public record HasRead(ResourceLocation document) implements Condition {
        public static final MapCodec<HasRead> CODEC = ResourceLocation.CODEC.fieldOf("document").xmap(HasRead::new, HasRead::document);

        @Override
        public ConditionType<?> type() {
            return DOCUMENT_READ_CONDITION.get();
        }

        @Override
        public boolean test(StoryContext context) {
            return Journal.of(context.player()).hasRead(document);
        }

        @Override
        public void collectReferences(References references) {
            references.document(document);
        }
    }

    /** {@code { "type": "give_document", "document": "mypack:letters/ciri", "count": 1 }}. */
    public record GiveDocument(ResourceLocation document, int count) implements Action {
        public static final MapCodec<GiveDocument> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                ResourceLocation.CODEC.fieldOf("document").forGetter(GiveDocument::document),
                Codec.intRange(1, 64).optionalFieldOf("count", 1).forGetter(GiveDocument::count)
        ).apply(instance, GiveDocument::new));

        @Override
        public ActionType<?> type() {
            return GIVE_DOCUMENT.get();
        }

        @Override
        public void run(StoryContext context) throws StoryException {
            if (!Documents.give(context.player(), document, count)) {
                throw new StoryException(Component.translatableWithFallback("storybound.document.unknown", "Unknown document %s", document.toString()));
            }
        }

        @Override
        public void collectReferences(References references) {
            references.document(document);
        }
    }

    /**
     * A character's note in the Journal: {@code { "type": "journal_entry", "id": "mypack:wolves", "title": "...", "text": "..." }}.
     * Writing the same id again replaces the note; arguments are resolved at the moment of writing.
     */
    public record JournalEntry(ResourceLocation id, ResourceLocation category, StoryText title, StoryText text) implements Action {
        public static final MapCodec<JournalEntry> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                ResourceLocation.CODEC.fieldOf("id").forGetter(JournalEntry::id),
                StoryIds.CODEC.optionalFieldOf("category", Storybound.id("notes")).forGetter(JournalEntry::category),
                StoryText.CODEC.fieldOf("title").forGetter(JournalEntry::title),
                StoryText.CODEC.fieldOf("text").forGetter(JournalEntry::text)
        ).apply(instance, JournalEntry::new));

        @Override
        public ActionType<?> type() {
            return JOURNAL_ENTRY.get();
        }

        @Override
        public void run(StoryContext context) {
            Journal.putNote(context.player(), new JournalNote(id, category, title.resolve(context.player()), text.resolve(context.player())));
        }
    }

    /** Fires on every read; {@code "first_time": true} limits it to the first one. {@code document} omitted means any. */
    public record Read(Optional<ResourceLocation> document, boolean firstTime) implements Trigger<DocumentRead> {
        public static final MapCodec<Read> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                ResourceLocation.CODEC.optionalFieldOf("document").forGetter(Read::document),
                Codec.BOOL.optionalFieldOf("first_time", false).forGetter(Read::firstTime)
        ).apply(instance, Read::new));

        @Override
        public TriggerType<?, DocumentRead> type() {
            return DOCUMENT_READ.get();
        }

        @Override
        public boolean matches(StoryContext context, DocumentRead event) {
            return document.map(event.document()::equals).orElse(true) && (!firstTime || event.firstTime());
        }

        /** A named document, once read; "any document" has no state. */
        @Override
        public StateKind stateKind() {
            return document.isPresent() ? StateKind.HISTORY : StateKind.NONE;
        }

        @Override
        public boolean holdsNow(StoryContext context) {
            return Journal.of(context.player()).hasRead(document.orElseThrow());
        }

        @Override
        public void collectReferences(References references) {
            document.ifPresent(references::document);
        }
    }
}
