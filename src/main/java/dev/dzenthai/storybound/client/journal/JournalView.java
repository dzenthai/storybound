package dev.dzenthai.storybound.client.journal;

import dev.dzenthai.storybound.client.ClientJournal;
import dev.dzenthai.storybound.client.SeenMarks;
import dev.dzenthai.storybound.client.document.DocumentScreen;
import dev.dzenthai.storybound.client.menu.ListDetailView;
import dev.dzenthai.storybound.module.document.DocumentDefinition;
import dev.dzenthai.storybound.module.document.DocumentRegistries;
import dev.dzenthai.storybound.module.journal.JournalNote;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/** The Journal tab: read documents and notes grouped by category. */
public class JournalView extends ListDetailView<JournalView.Entry> {
    private Button readButton;

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
        readButton = addWidget(Button.builder(Component.translatable("storybound.journal.read"), button -> openSelected())
                .bounds(left + width - 80, buttonY(), 80, 18)
                .build());
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
                entries.add(new Entry("document:" + id, definition.journalCategory(), definition.title().asComponent(), id, null));
            }
        }
        for (JournalNote note : ClientJournal.notes()) {
            entries.add(new Entry("note:" + note.id(), note.category(), note.title().toComponent(), null, note));
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
        detail.text(entry.title().copy().withStyle(ChatFormatting.BOLD), theme().title()).gap(4);
        if (entry.note() != null) {
            detail.text(entry.note().text().toComponent(), theme().ink());
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
        readButton.visible = entry != null && entry.document() != null;
    }

    private void openSelected() {
        Entry entry = selected();
        if (entry != null && entry.document() != null) {
            minecraft.setScreen(new DocumentScreen(entry.document(), screen));
        }
    }

    /** @param key stable id for the "seen" marker: {@code document:<id>} or {@code note:<id>} */
    public record Entry(String key, ResourceLocation category, Component title, @Nullable ResourceLocation document, @Nullable JournalNote note) {}
}
