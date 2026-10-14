package dev.dzenthai.storybound.module.document;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.data.DefinitionType;
import dev.dzenthai.storybound.core.data.DefinitionTypes;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class DocumentRegistries {
    /** Clients get titles, kinds and styles for the item name and the screens, but never {@code on_read}. */
    public static final DefinitionType<DocumentDefinition> DOCUMENTS = DefinitionTypes.register(
            DefinitionType.syncedView(Storybound.id("document"), "storybound/documents", DocumentDefinition.CODEC, DocumentDefinition::clientView));

    private static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, Storybound.MOD_ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Storybound.MOD_ID);
    private static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Storybound.MOD_ID);

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<DocumentRef>> DOCUMENT_COMPONENT = COMPONENTS.registerComponentType(
            "document", builder -> builder.persistent(DocumentRef.CODEC).networkSynchronized(DocumentRef.STREAM_CODEC));

    public static final DeferredItem<DocumentItem> DOCUMENT_ITEM = ITEMS.register("document", () -> new DocumentItem(new Item.Properties().stacksTo(16)));

    /** Every loaded document, built on the client from the synced definitions. */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register("storybound", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.storybound"))
            .icon(() -> new ItemStack(DOCUMENT_ITEM.get()))
            .displayItems((parameters, output) -> DOCUMENTS.clientStore().all()
                    .forEach((id, definition) -> output.accept(Documents.createStack(id, definition))))
            .build());

    private DocumentRegistries() {}

    public static void register(IEventBus modBus) {
        COMPONENTS.register(modBus);
        ITEMS.register(modBus);
        TABS.register(modBus);
        DocumentLogic.REGISTER_CONDITIONS.register(modBus);
        DocumentLogic.REGISTER_ACTIONS.register(modBus);
        DocumentLogic.REGISTER_TRIGGERS.register(modBus);
    }
}
