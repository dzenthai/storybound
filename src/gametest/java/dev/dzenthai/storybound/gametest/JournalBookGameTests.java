package dev.dzenthai.storybound.gametest;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.module.journal.Journal;
import dev.dzenthai.storybound.module.journal.JournalBook;
import dev.dzenthai.storybound.module.journal.JournalBooks;
import dev.dzenthai.storybound.module.journal.JournalData;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Signed books into the Journal: read from the hand, a signed book is kept with its pages; another of the same author
 * and title replaces it (a new edition), a book and quill is not kept, the Journal keeps at most
 * {@link JournalData#MAX_BOOKS}, and a kept book can be taken out.
 */
@SuppressWarnings("removal")
@GameTestHolder(Storybound.MOD_ID)
@PrefixGameTestTemplate(false)
public final class JournalBookGameTests {
    private JournalBookGameTests() {}

    @GameTest(template = "empty")
    public static void aSignedBookReadIsKeptInTheJournal(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.SURVIVAL);
        ItemStack book = signed("Notes", "Dev", "first edition");
        player.setItemInHand(InteractionHand.MAIN_HAND, book);
        player.gameMode.useItem(player, helper.getLevel(), book, InteractionHand.MAIN_HAND);
        JournalData data = Journal.of(player);
        String key = new JournalBook("Notes", "Dev", List.of()).key();
        helper.assertTrue(data.book(key).isPresent(), "reading it from the hand keeps it");
        helper.assertTrue(data.book(key).get().pages().getFirst().getString().equals("first edition"), "with its pages");

        JournalBooks.keep(player, signed("Notes", "Dev", "second edition"));
        helper.assertTrue(data.books().stream().filter(kept -> kept.key().equals(key)).count() == 1, "the same author and title once");
        helper.assertTrue(data.book(key).get().pages().getFirst().getString().equals("second edition"), "the last read replaces it");

        helper.assertTrue(!JournalBooks.keep(player, new ItemStack(Items.WRITABLE_BOOK)), "a book and quill is not kept");

        for (int i = 0; i < JournalData.MAX_BOOKS + 3; i++) {
            JournalBooks.keep(player, signed("Book " + i, "Dev", "page"));
        }
        helper.assertTrue(data.books().size() == JournalData.MAX_BOOKS, "at most " + JournalData.MAX_BOOKS + ", got " + data.books().size());
        helper.assertTrue(data.book(key).isEmpty(), "the one read longest ago went first");

        String last = new JournalBook("Book " + (JournalData.MAX_BOOKS + 2), "Dev", List.of()).key();
        JournalBooks.act(player, last, true);
        helper.assertTrue(data.book(last).isEmpty(), "taken out of the Journal");
        helper.getLevel().getServer().getPlayerList().remove(player);
        helper.succeed();
    }

    private static ItemStack signed(String title, String author, String page) {
        ItemStack stack = new ItemStack(Items.WRITTEN_BOOK);
        stack.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(Filterable.passThrough(title), author, 0,
                List.of(Filterable.passThrough(Component.literal(page))), true));
        return stack;
    }
}
