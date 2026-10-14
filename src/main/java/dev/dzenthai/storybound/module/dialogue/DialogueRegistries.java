package dev.dzenthai.storybound.module.dialogue;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.data.DefinitionType;
import dev.dzenthai.storybound.core.data.DefinitionTypes;
import java.util.function.Supplier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

public final class DialogueRegistries {
    /** Server only: the client gets one line or choice at a time, so the graph cannot be read ahead. */
    public static final DefinitionType<DialogueDefinition> DIALOGUES = DefinitionTypes.register(
            DefinitionType.serverOnly(Storybound.id("dialogue"), "storybound/dialogues", DialogueDefinition.CODEC));

    public static final DefinitionType<NpcBinding> NPC_BINDINGS = DefinitionTypes.register(
            DefinitionType.serverOnly(Storybound.id("npc_binding"), "storybound/npc_bindings", NpcBinding.CODEC));

    private static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES = DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, Storybound.MOD_ID);

    /** A binding stored on one entity by {@code /storybound npc bind}; check {@code hasData}, the default is a placeholder. */
    public static final Supplier<AttachmentType<NpcBinding.Attached>> NPC_BINDING = ATTACHMENT_TYPES.register("npc_binding",
            () -> AttachmentType.builder(() -> new NpcBinding.Attached(Storybound.id("none"), false, false)).serialize(NpcBinding.Attached.CODEC).build());

    private DialogueRegistries() {}

    public static void register(IEventBus modBus) {
        ATTACHMENT_TYPES.register(modBus);
        DialogueLogic.REGISTER_ACTIONS.register(modBus);
        DialogueLogic.REGISTER_TRIGGERS.register(modBus);
    }
}
