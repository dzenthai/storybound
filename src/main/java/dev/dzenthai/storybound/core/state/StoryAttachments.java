package dev.dzenthai.storybound.core.state;

import dev.dzenthai.storybound.Storybound;
import java.util.function.Supplier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

public final class StoryAttachments {
    private static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, Storybound.MOD_ID);

    /** The {@code player} scope; survives death and relogging. */
    public static final Supplier<AttachmentType<StoryContainer>> PLAYER_STATE = ATTACHMENT_TYPES.register("player_state",
            () -> AttachmentType.builder(() -> new StoryContainer()).serialize(StoryContainer.CODEC).copyOnDeath().build());

    private StoryAttachments() {}

    public static void register(IEventBus modBus) {
        ATTACHMENT_TYPES.register(modBus);
    }
}
