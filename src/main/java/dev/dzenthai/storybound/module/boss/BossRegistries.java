package dev.dzenthai.storybound.module.boss;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.data.DefinitionType;
import dev.dzenthai.storybound.core.data.DefinitionTypes;
import java.util.function.Supplier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

public final class BossRegistries {
    /** Clients get titles, selectors, texts and reward items; actions and unlock rules stay on the server. */
    public static final DefinitionType<BossDefinition> BOSSES = DefinitionTypes.register(
            DefinitionType.syncedView(Storybound.id("boss"), "storybound/bosses", BossDefinition.CODEC, BossDefinition::clientView));

    private static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES = DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, Storybound.MOD_ID);

    public static final Supplier<AttachmentType<BossData>> DATA = ATTACHMENT_TYPES.register("bosses",
            () -> AttachmentType.builder(BossData::new).serialize(BossData.CODEC).copyOnDeath().build());

    private BossRegistries() {}

    public static void register(IEventBus modBus) {
        ATTACHMENT_TYPES.register(modBus);
        BossLogic.REGISTER_CONDITIONS.register(modBus);
        BossLogic.REGISTER_ACTIONS.register(modBus);
    }
}
