package dev.dzenthai.storybound.module.document;

import dev.dzenthai.storybound.core.state.StoryMigrations;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.fml.LogicalSide;
import net.neoforged.fml.util.thread.EffectiveSide;

/** The single item behind every book, scroll, note and letter; the {@code storybound:document} component says which one. */
public class DocumentItem extends Item {
    public DocumentItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        DocumentRef ref = stack.get(DocumentRegistries.DOCUMENT_COMPONENT.get());
        if (ref == null) {
            return InteractionResultHolder.pass(stack);
        }
        if (player instanceof ServerPlayer serverPlayer) {
            Documents.read(serverPlayer, ref.id(), true);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    // A renamed document (migrations) is carried over in the inventory, so the item keeps working.
    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        StoryMigrations.Renames renames = StoryMigrations.current();
        DocumentRef ref = stack.get(DocumentRegistries.DOCUMENT_COMPONENT.get());
        if (!level.isClientSide() && !renames.isEmpty() && ref != null) {
            ResourceLocation id = renames.id(StoryMigrations.Kind.DOCUMENTS, ref.id());
            if (!id.equals(ref.id())) {
                stack.set(DocumentRegistries.DOCUMENT_COMPONENT.get(), new DocumentRef(id));
            }
        }
    }

    @Override
    public Component getName(ItemStack stack) {
        DocumentRef ref = stack.get(DocumentRegistries.DOCUMENT_COMPONENT.get());
        if (ref == null) {
            return super.getName(stack);
        }
        DocumentDefinition definition = (EffectiveSide.get() == LogicalSide.CLIENT
                ? DocumentRegistries.DOCUMENTS.clientStore()
                : DocumentRegistries.DOCUMENTS.serverStore()).getOrNull(ref.id());
        return definition != null
                ? definition.title().asComponent()
                : Component.translatable("item.storybound.document.unknown", ref.id().toString());
    }
}
