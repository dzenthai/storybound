package dev.dzenthai.storybound.client.journal;

import dev.dzenthai.storybound.client.ClientJournal;
import dev.dzenthai.storybound.client.SeenMarks;
import dev.dzenthai.storybound.client.document.DocumentScreen;
import dev.dzenthai.storybound.client.menu.BookButton;
import dev.dzenthai.storybound.client.menu.JournalSkin;
import dev.dzenthai.storybound.client.menu.ListDetailView;
import dev.dzenthai.storybound.module.document.DocumentDefinition;
import dev.dzenthai.storybound.module.document.DocumentRegistries;
import dev.dzenthai.storybound.module.journal.JournalBook;
import dev.dzenthai.storybound.module.journal.JournalNote;
import dev.dzenthai.storybound.network.JournalBookActionPayload;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/** The Journal tab: read documents, notes and the signed books kept (November 20, 2026), grouped by category. */
public class JournalView extends ListDetailView<JournalView.Entry> {
    private static final ResourceLocation VIGNETTE = ResourceLocation.fromNamespaceAndPath(
            "storybound", "textures/gui/journal/traveler_corner.png");
    private Button readButton;
    /** A kept signed book out of the Journal. */
    private Button removeButton;
    /** The category of the signed books kept: "Your books". */
    private static final ResourceLocation PLAYER_BOOKS = ResourceLocation.fromNamespaceAndPath("storybound", "player_books");

    /** The corner drawing's size in the book's pixels, and where its bottom-right corner sits on the right page. */
    private static final int VIGNETTE_SIZE = 128;
    private static final int VIGNETTE_RIGHT = 340;
    private static final int VIGNETTE_BOTTOM = 200;

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // The author's drawing (November 19, 2026; before, AI art of 1254 x 1254 squeezed to 170 GUI pixels, its fake
        // pixels 1.5 of the book's each, with three stars drawn by the code), on the book's pixels: one pixel of it is
        // one of the book's, at the page's bottom right.
        var layout = screen.bookLayout();
        double px = layout.bookPixel();
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        graphics.pose().pushPose();
        graphics.pose().translate(layout.x(), layout.y(), 0);
        graphics.pose().scale((float) px, (float) px, 1);
        graphics.blit(VIGNETTE, VIGNETTE_RIGHT - VIGNETTE_SIZE, VIGNETTE_BOTTOM - VIGNETTE_SIZE, 0, 0,
                VIGNETTE_SIZE, VIGNETTE_SIZE, VIGNETTE_SIZE, VIGNETTE_SIZE);
        graphics.pose().popPose();
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    protected int detailWidth() {
        return super.detailWidth() - 44;
    }

    @Override
    protected int detailBottom() {
        return super.detailBottom() - (readButton.visible ? 55 : 33);
    }

    /** True while the Journal has an entry the player has not opened yet. */
    public static boolean hasUnseen() {
        for (Entry entry : entries()) {
            if (!SeenMarks.isSeen(entry.key())) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void init() {
        readButton = addWidget(new BookButton(left + width - 80, buttonY(), 80, 18,
                JournalSkin.text(Component.translatable("storybound.journal.read")), button -> openSelected()).onBook(screen::bookLayout));
        removeButton = addWidget(new BookButton(left + width - 80, buttonY(), 80, 18,
                JournalSkin.text(Component.translatable("storybound.journal.remove")), button -> removeSelected()).onBook(screen::bookLayout));
        super.init();
    }

    @Override
    protected int dataVersion() {
        return ClientJournal.version();
    }

    @Override
    protected List<Row<Entry>> buildRows() {
        Map<ResourceLocation, List<Entry>> byCategory = new LinkedHashMap<>();
        for (Entry entry : entries()) {
            byCategory.computeIfAbsent(entry.category(), key -> new ArrayList<>()).add(entry);
        }
        List<Row<Entry>> rows = new ArrayList<>();
        byCategory.entrySet().stream()
                .sorted(Comparator.comparing(category -> categoryName(category.getKey()).getString()))
                .forEach(category -> {
                    rows.add(Row.header(categoryName(category.getKey())));
                    category.getValue().forEach(entry -> rows.add(Row.entry(entry.title(), entry)));
                });
        return rows;
    }

    private static List<Entry> entries() {
        List<Entry> entries = new ArrayList<>();
        for (ResourceLocation id : ClientJournal.documents()) {
            DocumentDefinition definition = DocumentRegistries.DOCUMENTS.clientStore().getOrNull(id);
            if (definition != null) {
                entries.add(new Entry("document:" + id, definition.journalCategory(), definition.title().asComponent(), id, null, null));
            }
        }
        for (JournalNote note : ClientJournal.notes()) {
            entries.add(new Entry("note:" + note.id(), note.category(), note.title().toComponent(), null, note, null));
        }
        for (JournalBook.Info book : ClientJournal.books()) {
            entries.add(new Entry("book:" + book.key(), PLAYER_BOOKS, Component.literal(book.title()), null, null, book));
        }
        return entries;
    }

    private static Component categoryName(ResourceLocation category) {
        return Component.translatableWithFallback(category.getNamespace() + ".journal.category." + category.getPath(), category.getPath());
    }

    @Override
    protected String key(Entry entry) {
        return entry.key();
    }

    @Override
    protected boolean isUnseen(Entry entry) {
        return !SeenMarks.isSeen(entry.key());
    }

    @Override
    protected void markSeen(Entry entry) {
        SeenMarks.markSeen(entry.key());
    }

    @Override
    protected void buildDetail(Entry entry, Detail detail) {
        detail.heading(entry.title().copy().withStyle(ChatFormatting.BOLD), theme().title()).gap(4);
        detail.block(14, (graphics, x, y, width, mouseX, mouseY, partialTick) ->
                JournalSkin.bookRule(graphics, screen.bookLayout(), x, y + 3, Math.min(width, 130), theme().divider()));
        if (entry.note() != null) {
            detail.text(entry.note().text().toComponent(), theme().ink());
        } else if (entry.book() != null) {
            detail.text(Component.translatable("storybound.journal.book_by", entry.book().author()), theme().ink()).gap(4);
            detail.text(Component.translatable("storybound.journal.book_hint"), theme().muted());
        } else {
            detail.text(Component.translatable("storybound.journal.document_hint"), theme().muted());
        }
    }

    @Override
    protected Component emptyText() {
        return Component.translatable("storybound.journal.empty");
    }

    @Override
    protected void selectionChanged() {
        Entry entry = selected();
        readButton.visible = entry != null && (entry.document() != null || entry.book() != null);
        removeButton.visible = entry != null && entry.book() != null;
        if (readButton.visible) {
            int headingLines = font.split(JournalSkin.heading(entry.title()), Math.max(1, detailWidth() / 2)).size();
            String hint = entry.book() != null ? "storybound.journal.book_hint" : "storybound.journal.document_hint";
            int hintLines = font.split(JournalSkin.text(Component.translatable(hint)), detailWidth()).size() + (entry.book() != null ? 1 : 0);
            int afterText = detailTop() + headingLines * 22 + hintLines * 13 + 33;
            // Both within the text's width, clear of the corner drawing (November 20, 2026: two of 80 ran into it).
            int buttonWidth = entry.book() != null ? Math.min(80, (detailWidth() - 4) / 2) : 80;
            readButton.setWidth(buttonWidth);
            removeButton.setWidth(buttonWidth);
            readButton.setPosition(detailX(), Math.min(detailBottom() + 8, afterText));
            removeButton.setPosition(detailX() + buttonWidth + 4, readButton.getY());
        }
    }

    private void openSelected() {
        Entry entry = selected();
        if (entry != null && entry.document() != null) {
            minecraft.setScreen(new DocumentScreen(entry.document(), screen));
        } else if (entry != null && entry.book() != null) {
            // Its pages are asked for; the book opens when they come (ClientJournal.openBook).
            PacketDistributor.sendToServer(new JournalBookActionPayload(entry.book().key(), false));
        }
    }

    private void removeSelected() {
        Entry entry = selected();
        if (entry != null && entry.book() != null) {
            PacketDistributor.sendToServer(new JournalBookActionPayload(entry.book().key(), true));
        }
    }

    /** @param key stable id for the "seen" marker: {@code document:<id>}, {@code note:<id>} or {@code book:<author, title>} */
    public record Entry(String key, ResourceLocation category, Component title, @Nullable ResourceLocation document, @Nullable JournalNote note,
                        @Nullable JournalBook.Info book) {}
}
