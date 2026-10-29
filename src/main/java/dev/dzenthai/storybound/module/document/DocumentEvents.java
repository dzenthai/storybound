package dev.dzenthai.storybound.module.document;

import dev.dzenthai.storybound.core.data.DefinitionType;
import dev.dzenthai.storybound.core.data.ValidationIssue;
import dev.dzenthai.storybound.core.data.ValidationService;
import dev.dzenthai.storybound.core.logic.LogicOwners;
import dev.dzenthai.storybound.core.logic.References;
import dev.dzenthai.storybound.module.journal.Journal;
import java.util.function.Consumer;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;

public final class DocumentEvents {
    private DocumentEvents() {}

    public static void register(IEventBus gameBus) {
        // LOW: the client needs the document definitions before the Journal that lists them.
        gameBus.addListener(EventPriority.LOW, DocumentEvents::onDatapackSync);
        LogicOwners.register(DocumentRegistries.DOCUMENTS, (document, references) -> {
            document.onRead().forEach(action -> action.collectReferences(references));
            document.contract().flatMap(DocumentDefinition.Contract::quest).ifPresent(quest -> references.quest(quest, null));
        });
        ValidationService.registerValidator(DocumentEvents::validate);
    }

    private static void onDatapackSync(OnDatapackSyncEvent event) {
        event.getRelevantPlayers().forEach(player -> {
            Journal.archiveMissing(player, document -> {
                DocumentDefinition definition = DocumentRegistries.DOCUMENTS.serverStore().getOrNull(document);
                return definition != null && definition.journal().addOnRead();
            });
            Journal.sync(player);
        });
    }

    /** Every definition with logic must refer to documents that exist. */
    private static void validate(Consumer<ValidationIssue> sink) {
        LogicOwners.walkAll((type, id) -> unknownDocuments(type, id, sink));
    }

    private static References unknownDocuments(DefinitionType<?> owner, ResourceLocation ownerId, Consumer<ValidationIssue> sink) {
        return new References() {
            @Override
            public void document(ResourceLocation document) {
                if (!DocumentRegistries.DOCUMENTS.serverStore().contains(document)) {
                    sink.accept(ValidationIssue.error(owner.id(), ownerId, "Unknown document " + document));
                }
            }
        };
    }
}
