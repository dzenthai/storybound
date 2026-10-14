package dev.dzenthai.storybound.module.quest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.data.DefinitionType;
import dev.dzenthai.storybound.core.data.DefinitionTypes;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

public final class QuestRegistries {
    /** Clients get titles, texts, stages and objective counts; triggers, conditions and actions stay on the server. */
    public static final DefinitionType<QuestDefinition> QUESTS = DefinitionTypes.register(
            DefinitionType.syncedView(Storybound.id("quest"), "storybound/quests", QuestDefinition.CODEC, QuestDefinition::clientView));

    private static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES = DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, Storybound.MOD_ID);

    /** The quest a player tracks on the HUD; personal even for party quests. */
    public static final Supplier<AttachmentType<TrackedQuest>> TRACKED = ATTACHMENT_TYPES.register("tracked_quest",
            () -> AttachmentType.builder(() -> TrackedQuest.NONE).serialize(TrackedQuest.CODEC).copyOnDeath().build());

    private QuestRegistries() {}

    public static void register(IEventBus modBus) {
        ATTACHMENT_TYPES.register(modBus);
        QuestLogic.REGISTER_CONDITIONS.register(modBus);
        QuestLogic.REGISTER_ACTIONS.register(modBus);
        QuestLogic.REGISTER_TRIGGERS.register(modBus);
    }

    public record TrackedQuest(Optional<ResourceLocation> quest) {
        public static final TrackedQuest NONE = new TrackedQuest(Optional.empty());
        public static final Codec<TrackedQuest> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ResourceLocation.CODEC.optionalFieldOf("quest").forGetter(TrackedQuest::quest)
        ).apply(instance, TrackedQuest::new));
    }
}
