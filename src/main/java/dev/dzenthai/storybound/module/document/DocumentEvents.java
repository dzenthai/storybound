package dev.dzenthai.storybound.module.document;

import dev.dzenthai.storybound.core.data.DefinitionType;
import dev.dzenthai.storybound.core.data.ValidationIssue;
import dev.dzenthai.storybound.core.data.ValidationService;
import dev.dzenthai.storybound.core.logic.LogicOwners;
import dev.dzenthai.storybound.core.logic.References;
import dev.dzenthai.storybound.module.journal.Journal;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.items.ItemHandlerHelper;

public final class DocumentEvents {
    private DocumentEvents() {}

    public static void register(IEventBus gameBus) {
        // LOW: the client needs the document definitions before the Journal that lists them.
        gameBus.addListener(EventPriority.LOW, DocumentEvents::onDatapackSync);
        gameBus.addListener(DocumentEvents::onRightClickBlock);
        LogicOwners.register(DocumentRegistries.DOCUMENTS, (document, references) -> {
            document.onRead().forEach(action -> action.collectReferences(references));
            document.contract().flatMap(DocumentDefinition.Contract::quest).ifPresent(quest -> references.quest(quest, null));
        });
        ValidationService.registerValidator(DocumentEvents::validate);
    }

    private static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        BlockState state = level.getBlockState(pos);
        if (!state.is(Blocks.LECTERN) || !state.getValue(LecternBlock.HAS_BOOK)) {
            return;
        }
        if (!(level.getBlockEntity(pos) instanceof LecternBlockEntity lectern)) {
            return;
        }
        ItemStack book = lectern.getBook();
        if (!book.is(DocumentRegistries.DOCUMENT_ITEM.get())) {
            return;
        }
        Player player = event.getEntity();
        DocumentRef ref = book.get(DocumentRegistries.DOCUMENT_COMPONENT.get());
        // Sneaking takes it, as "Take Book" under it does; one who may not build reads it instead, as vanilla's lectern.
        if (player.isSecondaryUseActive() && player.mayBuild()) {
            if (player instanceof ServerPlayer serverPlayer && ref != null) {
                takeFromLectern(serverPlayer, pos, ref.id());
            }
        } else if (player instanceof ServerPlayer serverPlayer && ref != null) {
            Documents.read(serverPlayer, ref.id(), true, pos);
            LecternBlock.signalPageChange(level, pos, state);
        }
        event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide()));
        event.setCanceled(true);
    }

    /**
     * Takes the book off the lectern at {@code pos} into the player's inventory (on the ground by them when it is full):
     * only when that lectern still holds this document, the player is within vanilla's lectern reach and may build
     * (not in adventure mode). False, with nothing done, otherwise: another player took it, the lectern is gone, the
     * book was swapped.
     */
    public static boolean takeFromLectern(ServerPlayer player, BlockPos pos, ResourceLocation document) {
        Level level = player.level();
        if (!player.isAlive() || player.isSpectator() || !player.mayBuild() || !level.isLoaded(pos)
                || !player.canInteractWithBlock(pos, 4.0)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        if (!state.is(Blocks.LECTERN) || !state.getValue(LecternBlock.HAS_BOOK)
                || !(level.getBlockEntity(pos) instanceof LecternBlockEntity lectern)) {
            return false;
        }
        ItemStack book = lectern.getBook();
        DocumentRef ref = book.get(DocumentRegistries.DOCUMENT_COMPONENT.get());
        if (!book.is(DocumentRegistries.DOCUMENT_ITEM.get()) || ref == null || !ref.id().equals(document)) {
            return false;
        }
        ItemStack taken = book.copy();
        lectern.clearContent();
        LecternBlock.resetBookState(player, level, pos, state, false);
        level.playSound(null, pos, SoundEvents.BOOK_PUT, SoundSource.BLOCKS, 1.0F, 1.0F);
        ItemHandlerHelper.giveItemToPlayer(player, taken);
        return true;
    }

    private static void onDatapackSync(OnDatapackSyncEvent event) {
        event.getRelevantPlayers().forEach(player -> {
            Journal.archiveMissing(player, document -> {
                DocumentDefinition definition = DocumentRegistries.DOCUMENTS.serverStore().getOrNull(document);
                return definition != null && definition.journal().addOnRead();
            });
            Journal.sync(player);
        });
    }

    /** Every definition with logic must refer to documents that exist. */
    private static void validate(Consumer<ValidationIssue> sink) {
        LogicOwners.walkAll((type, id) -> unknownDocuments(type, id, sink));
    }

    private static References unknownDocuments(DefinitionType<?> owner, ResourceLocation ownerId, Consumer<ValidationIssue> sink) {
        return new References() {
            @Override
            public void document(ResourceLocation document) {
                if (!DocumentRegistries.DOCUMENTS.serverStore().contains(document)) {
                    sink.accept(ValidationIssue.error(owner.id(), ownerId, "Unknown document " + document));
                }
            }
        };
    }
}
