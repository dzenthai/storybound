package dev.dzenthai.storybound.module.document;

import dev.dzenthai.storybound.core.logic.Actions;
import dev.dzenthai.storybound.core.logic.OnFail;
import dev.dzenthai.storybound.core.logic.StoryContext;
import dev.dzenthai.storybound.core.logic.trigger.TriggerDispatcher;
import dev.dzenthai.storybound.module.journal.Journal;
import dev.dzenthai.storybound.network.OpenDocumentPayload;
import dev.dzenthai.storybound.network.StoryboundNetwork;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import org.jetbrains.annotations.Nullable;

/** Reading and issuing documents. The server decides what was read; the client only shows it. */
public final class Documents {
    private Documents() {}

    /**
     * The player reads a document: optionally opens the screen, records the read, archives it in the
     * Journal and, on the first read only, runs {@code on_read}. Fires {@code document_read} every time.
     *
     * @return false if no such document is loaded
     */
    public static boolean read(ServerPlayer player, ResourceLocation id, boolean openScreen) {
        DocumentDefinition definition = DocumentRegistries.DOCUMENTS.serverStore().getOrNull(id);
        if (definition == null) {
            player.sendSystemMessage(Component.translatable("storybound.document.unknown", id.toString()).withStyle(ChatFormatting.RED));
            return false;
        }
        if (openScreen) {
            StoryboundNetwork.sendToPlayer(player, new OpenDocumentPayload(id));
        }
        boolean first = Journal.markRead(player, id, definition.journal().addOnRead());
        StoryContext context = StoryContext.of(player);
        if (first && !definition.onRead().isEmpty()) {
            Actions.runAll(definition.onRead(), context, "document " + id + " on_read", OnFail.ABORT);
        }
        TriggerDispatcher.fire(DocumentLogic.DOCUMENT_READ.get(), context, new DocumentLogic.DocumentRead(id, first));
        return true;
    }

    /** A document item; also applies the definition's {@code custom_model_data}. */
    public static ItemStack createStack(ResourceLocation id, @Nullable DocumentDefinition definition) {
        ItemStack stack = new ItemStack(DocumentRegistries.DOCUMENT_ITEM.get());
        stack.set(DocumentRegistries.DOCUMENT_COMPONENT.get(), new DocumentRef(id));
        if (definition != null) {
            definition.customModelData().ifPresent(value -> stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(value)));
        }
        return stack;
    }

    /** @return false if no such document is loaded */
    public static boolean give(ServerPlayer player, ResourceLocation id, int count) {
        DocumentDefinition definition = DocumentRegistries.DOCUMENTS.serverStore().getOrNull(id);
        if (definition == null) {
            return false;
        }
        ItemStack stack = createStack(id, definition);
        int remaining = count;
        while (remaining > 0) {
            int size = Math.min(remaining, stack.getMaxStackSize());
            ItemHandlerHelper.giveItemToPlayer(player, stack.copyWithCount(size));
            remaining -= size;
        }
        return true;
    }
}
