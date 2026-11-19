package dev.dzenthai.storybound.module.journal;

import dev.dzenthai.storybound.network.JournalBookPayload;
import dev.dzenthai.storybound.network.StoryboundNetwork;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Signed books into the Journal (November 20, 2026, the author): a player who opens a signed book, theirs or another's,
 * from the hand or from a lectern, keeps a copy under "Your books", to read again from the Journal even once the book
 * is gone. Book and quill (not signed) are not kept: they are still being written. Nothing when the Journal is off.
 */
public final class JournalBooks {
    private JournalBooks() {}

    public static void register(IEventBus gameBus) {
        gameBus.addListener(JournalBooks::onRightClickItem);
        gameBus.addListener(JournalBooks::onRightClickBlock);
    }

    private static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            keep(player, event.getItemStack());
        }
    }

    /** Reading a signed book on a lectern keeps it too (the vanilla lectern; the mod's own documents are documents). */
    private static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        BlockState state = event.getLevel().getBlockState(event.getPos());
        if (state.is(Blocks.LECTERN) && state.getValue(LecternBlock.HAS_BOOK)
                && event.getLevel().getBlockEntity(event.getPos()) instanceof LecternBlockEntity lectern) {
            keep(player, lectern.getBook());
        }
    }

    /**
     * Keeps the book if it is a signed one: in the Journal, the last read of its author and title; the first time, a
     * word above the hotbar says so. Returns whether it was kept.
     */
    public static boolean keep(ServerPlayer player, ItemStack stack) {
        WrittenBookContent content = stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
        if (content == null || !Journal.isEnabled()) {
            return false;
        }
        JournalBook book = new JournalBook(content.title().raw(), content.author(), content.getPages(false));
        boolean fresh = Journal.of(player).putBook(book);
        Journal.sync(player);
        if (fresh) {
            player.displayClientMessage(Component.translatable("storybound.journal.book_kept", book.title()), true);
        }
        return true;
    }

    /** The client asks for a kept book's pages to read it, or to take it out of the Journal. */
    public static void act(ServerPlayer player, String key, boolean remove) {
        JournalData data = Journal.of(player);
        if (remove) {
            if (data.removeBook(key)) {
                Journal.sync(player);
            }
            return;
        }
        data.book(key).ifPresent(book -> StoryboundNetwork.sendToPlayer(player, new JournalBookPayload(book.title(), book.author(), book.pages())));
    }
}
