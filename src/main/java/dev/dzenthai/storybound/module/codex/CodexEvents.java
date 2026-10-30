package dev.dzenthai.storybound.module.codex;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.data.DefinitionType;
import dev.dzenthai.storybound.core.data.ValidationIssue;
import dev.dzenthai.storybound.core.data.ValidationService;
import dev.dzenthai.storybound.core.logic.LogicOwners;
import dev.dzenthai.storybound.core.logic.References;
import dev.dzenthai.storybound.core.logic.Trigger;
import dev.dzenthai.storybound.core.logic.EntitySelector;
import dev.dzenthai.storybound.core.logic.trigger.CoreTriggers;
import dev.dzenthai.storybound.core.logic.trigger.Subscription;
import dev.dzenthai.storybound.core.logic.trigger.TriggerDispatcher;
import dev.dzenthai.storybound.module.document.DocumentLogic;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;

public final class CodexEvents {
    private static final String SOURCE = Storybound.id("codex").toString();

    private CodexEvents() {}

    public static void register(IEventBus gameBus) {
        gameBus.addListener(EventPriority.HIGH, CodexEvents::onTagsUpdated);
        // LOW: the client needs the entries before the progress that refers to them.
        gameBus.addListener(EventPriority.LOW, CodexEvents::onDatapackSync);
        LogicOwners.register(CodexRegistries.CODEX, CodexEvents::collectReferences);
        ValidationService.registerValidator(CodexEvents::validate);
    }

    /**
     * The unlock sources are ordinary triggers: {@code seen} is {@code entity_seen}, {@code killed} is a shared
     * {@code entity_killed}, {@code read:<doc>} is {@code document_read}. One subscription per entry and source,
     * so a sighting is counted once however many sections wait for it.
     */
    public static void rebuild() {
        List<Subscription<?>> subscriptions = new ArrayList<>();
        if (Codex.isEnabled()) {
            CodexRegistries.CODEX.serverStore().all().forEach((entry, definition) -> {
                Set<Unlock.Source> sources = new LinkedHashSet<>();
                definition.sections().forEach(section -> sources.addAll(section.unlock().on()));
                if (definition.showKills() && definition.entity().isPresent()) {
                    // Kills are counted for "Killed: N" even when no section opens on them.
                    sources.add(Unlock.Source.KILLED);
                }
                for (Unlock.Source source : sources) {
                    Trigger<?> trigger = switch (source) {
                        case Unlock.Source.Seen seen -> definition.entity().<Trigger<?>>map(set -> new CoreTriggers.EntitySeen(Optional.of(set))).orElse(null);
                        case Unlock.Source.Killed killed -> definition.entity().<Trigger<?>>map(set -> new CoreTriggers.EntityKilled(Optional.of(set), true)).orElse(null);
                        case Unlock.Source.Read read -> new DocumentLogic.Read(Optional.of(read.document()), false);
                        case Unlock.Source.Observed observed -> definition.entity().<Trigger<?>>map(set -> CoreTriggers.Observed.of(
                                new EntitySelector(Optional.of(set), Optional.empty(), Optional.empty(), Optional.empty()), observed.seconds())).orElse(null);
                        case Unlock.Source.Manual manual -> null;
                    };
                    if (trigger != null) {
                        subscriptions.add(subscribe("codex " + entry + " " + source, trigger, entry, source));
                    }
                }
            });
        }
        TriggerDispatcher.setSubscriptions(SOURCE, subscriptions);
    }

    private static <E> Subscription<E> subscribe(String name, Trigger<E> trigger, ResourceLocation entry, Unlock.Source source) {
        return new Subscription<>(name, trigger, context -> Codex.observed(context, entry, source), context -> Codex.wouldOpen(context, entry, source));
    }

    private static void onTagsUpdated(TagsUpdatedEvent event) {
        if (event.getUpdateCause() == TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) {
            rebuild();
        }
    }

    private static void onDatapackSync(OnDatapackSyncEvent event) {
        event.getRelevantPlayers().forEach(player -> {
            Codex.catchUpReads(player);
            Codex.sync(player);
        });
    }

    /** The conditions and the documents of every section's unlock. */
    public static void collectReferences(CodexDefinition definition, References references) {
        for (CodexDefinition.Section section : definition.sections()) {
            collectReferences(section.unlock(), references);
        }
    }

    public static void collectReferences(Unlock unlock, References references) {
        unlock.conditions().collectReferences(references);
        for (Unlock.Source source : unlock.on()) {
            if (source instanceof Unlock.Source.Read read) {
                references.document(read.document());
            }
        }
    }

    /** Each entry's sections (ids, sources that need an entity) and references to entries everywhere. */
    private static void validate(Consumer<ValidationIssue> sink) {
        DefinitionType<CodexDefinition> type = CodexRegistries.CODEX;
        type.serverStore().all().forEach((id, entry) -> {
            if (entry.sections().isEmpty()) {
                sink.accept(ValidationIssue.error(type.id(), id, "Has no sections"));
            }
            Set<String> ids = new HashSet<>();
            for (CodexDefinition.Section section : entry.sections()) {
                if (!ids.add(section.id())) {
                    sink.accept(ValidationIssue.error(type.id(), id, "Duplicate section id '" + section.id() + "'"));
                }
                if (section.unlock().on().isEmpty()) {
                    sink.accept(ValidationIssue.error(type.id(), id, "Section '" + section.id() + "' has no unlock source; use \"manual\" for actions and commands"));
                }
                boolean needsEntity = section.unlock().on().stream().anyMatch(source -> source instanceof Unlock.Source.Seen || source instanceof Unlock.Source.Killed);
                if (needsEntity && entry.entity().isEmpty()) {
                    sink.accept(ValidationIssue.error(type.id(), id, "Section '" + section.id() + "' opens on seen or killed, but the entry has no 'entity'"));
                }
            }
        });
        LogicOwners.walkAll((owner, ownerId) -> new References() {
            @Override
            public void codex(ResourceLocation entry, String section) {
                CodexDefinition definition = type.serverStore().getOrNull(entry);
                if (definition == null) {
                    sink.accept(ValidationIssue.error(owner.id(), ownerId, "Unknown Codex entry " + entry));
                } else if (section != null && definition.section(section).isEmpty()) {
                    sink.accept(ValidationIssue.error(owner.id(), ownerId, "Codex entry " + entry + " has no section '" + section + "'"));
                }
            }
        });
    }
}
