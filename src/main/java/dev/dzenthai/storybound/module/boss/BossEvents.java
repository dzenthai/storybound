package dev.dzenthai.storybound.module.boss;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.data.ValidationIssue;
import dev.dzenthai.storybound.core.data.ValidationService;
import dev.dzenthai.storybound.core.logic.EntitySelector;
import dev.dzenthai.storybound.core.logic.LogicOwners;
import dev.dzenthai.storybound.core.logic.References;
import dev.dzenthai.storybound.core.logic.StoryContext;
import dev.dzenthai.storybound.core.logic.Trigger;
import dev.dzenthai.storybound.core.logic.TriggerType;
import dev.dzenthai.storybound.core.logic.trigger.BuiltinTriggers;
import dev.dzenthai.storybound.core.logic.trigger.Subscription;
import dev.dzenthai.storybound.core.logic.trigger.TriggerDispatcher;
import dev.dzenthai.storybound.module.codex.CodexEvents;
import dev.dzenthai.storybound.module.codex.Unlock;
import dev.dzenthai.storybound.module.document.DocumentLogic;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

public final class BossEvents {
    private static final String SOURCE = Storybound.id("bosses").toString();

    private BossEvents() {}

    public static void register(IEventBus gameBus) {
        gameBus.addListener(EventPriority.HIGH, BossEvents::onTagsUpdated);
        // LOW: the client needs the boss definitions before the records that refer to them.
        gameBus.addListener(EventPriority.LOW, BossEvents::onDatapackSync);
        gameBus.addListener(BossEvents::onDamage);
        gameBus.addListener(BossEvents::onDeath);
        gameBus.addListener(BossEvents::onLeave);
        gameBus.addListener(BossEvents::onServerStopped);
        LogicOwners.register(BossRegistries.BOSSES, BossEvents::collectReferences);
        ValidationService.registerValidator(BossEvents::validate);
    }

    /** Sightings go through {@code entity_seen} with the boss's selector; reads through {@code document_read}. Kills are handled here directly. */
    public static void rebuild() {
        Bosses.rebuild();
        List<Subscription<?>> subscriptions = new ArrayList<>();
        if (Bosses.isEnabled()) {
            BossRegistries.BOSSES.serverStore().all().forEach((boss, definition) -> {
                subscriptions.add(new Subscription<>("boss " + boss + " seen", new SeenBoss(definition.entity()), context -> Bosses.sighted(context, boss)));
                Set<ResourceLocation> documents = new LinkedHashSet<>();
                for (String section : BossDefinition.SECTIONS) {
                    definition.unlock(section).ifPresent(unlock -> unlock.on().forEach(source -> {
                        if (source instanceof Unlock.Source.Read read) {
                            documents.add(read.document());
                        }
                    }));
                }
                for (ResourceLocation document : documents) {
                    subscriptions.add(new Subscription<>("boss " + boss + " read " + document, new DocumentLogic.Read(Optional.of(document), false),
                            context -> Bosses.read(context, boss, new Unlock.Source.Read(document))));
                }
            });
        }
        TriggerDispatcher.setSubscriptions(SOURCE, subscriptions);
    }

    private static void onTagsUpdated(TagsUpdatedEvent event) {
        if (event.getUpdateCause() == TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) {
            rebuild();
        }
    }

    private static void onDatapackSync(OnDatapackSyncEvent event) {
        event.getRelevantPlayers().forEach(Bosses::sync);
    }

    private static void onDamage(LivingDamageEvent.Post event) {
        if (event.getSource().getEntity() instanceof ServerPlayer attacker && event.getNewDamage() > 0 && !event.getEntity().level().isClientSide()) {
            Bosses.damaged(event.getEntity(), attacker, event.getNewDamage());
        }
    }

    private static void onDeath(LivingDeathEvent event) {
        if (!event.getEntity().level().isClientSide()) {
            Bosses.died(event.getEntity(), event.getSource().getEntity() instanceof ServerPlayer killer ? killer : null);
        }
    }

    private static void onLeave(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide()) {
            Bosses.left(event.getEntity());
        }
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        Bosses.reset();
    }

    public static void collectReferences(BossDefinition definition, References references) {
        for (String section : BossDefinition.SECTIONS) {
            definition.unlock(section).ifPresent(unlock -> CodexEvents.collectReferences(unlock, references));
        }
        definition.onFirstKill().forEach(action -> action.collectReferences(references));
        definition.onKill().forEach(action -> action.collectReferences(references));
    }

    private static void validate(Consumer<ValidationIssue> sink) {
        BossRegistries.BOSSES.serverStore().all().forEach((id, boss) -> {
            if (boss.lore().isEmpty() && boss.tactics().isEmpty() && boss.rewards().isEmpty()) {
                sink.accept(ValidationIssue.warning(BossRegistries.BOSSES.id(), id, "Has no lore, tactics or rewards; its entry shows only status and statistics"));
            }
        });
        LogicOwners.walkAll((owner, ownerId) -> new References() {
            @Override
            public void boss(ResourceLocation boss) {
                if (!BossRegistries.BOSSES.serverStore().contains(boss)) {
                    sink.accept(ValidationIssue.error(owner.id(), ownerId, "Unknown boss " + boss));
                }
            }
        });
    }

    /** {@code entity_seen} for the entities a boss selector picks; only for subscriptions, never written in JSON. */
    private record SeenBoss(EntitySelector selector) implements Trigger<Entity> {
        @Override
        public TriggerType<?, Entity> type() {
            return BuiltinTriggers.ENTITY_SEEN.get();
        }

        @Override
        public boolean matches(StoryContext context, Entity entity) {
            return selector.matches(entity);
        }
    }
}
