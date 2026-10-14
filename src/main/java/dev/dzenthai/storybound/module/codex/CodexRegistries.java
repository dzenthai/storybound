package dev.dzenthai.storybound.module.codex;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.data.DefinitionType;
import dev.dzenthai.storybound.core.data.DefinitionTypes;
import java.util.function.Supplier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

public final class CodexRegistries {
    /** Clients get titles, categories, entities and section texts; how sections open stays on the server. */
    public static final DefinitionType<CodexDefinition> CODEX = DefinitionTypes.register(
            DefinitionType.syncedView(Storybound.id("codex"), "storybound/codex", CodexDefinition.CODEC, CodexDefinition::clientView));

    private static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES = DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, Storybound.MOD_ID);

    public static final Supplier<AttachmentType<CodexData>> DATA = ATTACHMENT_TYPES.register("codex",
            () -> AttachmentType.builder(CodexData::new).serialize(CodexData.CODEC).copyOnDeath().build());

    private CodexRegistries() {}

    public static void register(IEventBus modBus) {
        ATTACHMENT_TYPES.register(modBus);
        CodexLogic.REGISTER_CONDITIONS.register(modBus);
        CodexLogic.REGISTER_ACTIONS.register(modBus);
    }
}
