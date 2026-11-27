package dev.dzenthai.storybound.client.codex;

import dev.dzenthai.storybound.client.ClientCodex;
import dev.dzenthai.storybound.client.SeenMarks;
import dev.dzenthai.storybound.client.dev.DevFrameCapture;
import dev.dzenthai.storybound.client.menu.AdventureMenu;
import dev.dzenthai.storybound.client.menu.BookButton;
import dev.dzenthai.storybound.client.menu.ListDetailView;
import dev.dzenthai.storybound.client.menu.MenuTheme;
import dev.dzenthai.storybound.module.codex.CodexDefinition;
import dev.dzenthai.storybound.module.codex.CodexRegistries;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * The Codex tab: entries the player has uncovered, by category. The detail shows the player's captured photo (or
 * author illustration, or creature portrait) and every section; sections still closed show only that something is left
 * to learn.
 */
public class CodexView extends ListDetailView<CodexView.Entry> {
    static final int PORTRAIT_HEIGHT = 72;
    private static @Nullable ResourceLocation focusOnOpen;
    private @Nullable BookButton photoButton;

    @Override
    protected void init() {
        int buttonWidth = Math.min(118, Math.max(70, detailWidth()));
        photoButton = addWidget(new BookButton(left + width - buttonWidth, buttonY(), buttonWidth, 18,
                Component.empty(), button -> capturePhoto()).onBook(screen::bookLayout));
        photoButton.visible = false;
        super.init();
    }

    @Override
    protected @Nullable Entry preferredSelection(List<Entry> entries) {
        ResourceLocation target = focusOnOpen;
        focusOnOpen = null;
        if (target != null) {
            for (Entry entry : entries) {
                if (entry.id().equals(target)) {
                    return entry;
                }
            }
        }
        return super.preferredSelection(entries);
    }

    @Override
    protected void selectionChanged() {
        if (photoButton == null) {
            return;
        }
        Entry entry = selected();
        boolean canPhoto = entry != null && entry.definition().canPhotograph();
        photoButton.visible = canPhoto;
        photoButton.active = canPhoto;
        if (canPhoto) {
            boolean has = PlayerCodexPhotos.hasPhoto(entry.id());
            photoButton.setMessage(Component.translatable(has ? "storybound.codex.photo.retake" : "storybound.codex.photo.take"));
        }
    }

    private void capturePhoto() {
        Entry entry = selected();
        if (entry == null || !entry.definition().canPhotograph()) {
            return;
        }
        focusOnOpen = entry.id();
        DevFrameCapture.captureForPlayer(entry.title(), () -> AdventureMenu.open(AdventureMenu.CODEX),
                image -> PlayerCodexPhotos.save(entry.id(), image));
    }

    /** True while some entry gained a section since the player last looked at it. */
    public static boolean hasUnseen() {
        return entries().stream().anyMatch(entry -> !SeenMarks.isSeen(seenKey(entry)));
    }

    @Override
    protected int dataVersion() {
        return ClientCodex.version();
    }

    @Override
    protected List<Row<Entry>> buildRows() {
        Map<ResourceLocation, List<Entry>> byCategory = new LinkedHashMap<>();
        for (Entry entry : entries()) {
            byCategory.computeIfAbsent(entry.definition().category(), key -> new ArrayList<>()).add(entry);
        }
        List<Row<Entry>> rows = new ArrayList<>();
        byCategory.entrySet().stream()
                .sorted(Comparator.comparing(category -> categoryName(category.getKey()).getString()))
                .forEach(category -> {
                    rows.add(Row.header(categoryName(category.getKey())));
                    category.getValue().stream()
                            .sorted(Comparator.comparing(entry -> entry.title().getString()))
                            .forEach(entry -> rows.add(Row.entry(entry.title(), entry)));
                });
        return rows;
    }

    private static List<Entry> entries() {
        List<Entry> entries = new ArrayList<>();
        ClientCodex.all().forEach((id, sections) -> {
            CodexDefinition definition = CodexRegistries.CODEX.clientStore().getOrNull(id);
            if (definition != null && !sections.isEmpty()) {
                entries.add(new Entry(id, definition, Set.copyOf(sections)));
            }
        });
        return entries;
    }

    static Component categoryName(ResourceLocation category) {
        return Component.translatableWithFallback(category.getNamespace() + ".codex.category." + category.getPath(), category.getPath());
    }

    @Override
    protected String key(Entry entry) {
        return entry.id().toString();
    }

    /** Changes with every opened section, so an entry with new knowledge shows as new again. */
    private static String seenKey(Entry entry) {
        return "codex:" + entry.id() + ":" + entry.sections().size();
    }

    @Override
    protected boolean isUnseen(Entry entry) {
        return !SeenMarks.isSeen(seenKey(entry));
    }

    @Override
    protected void markSeen(Entry entry) {
        SeenMarks.markSeen(seenKey(entry));
    }

    @Override
    protected void buildDetail(Entry entry, Detail detail) {
        MenuTheme theme = theme();
        CodexImages.Loaded illustration = PlayerCodexPhotos.resolve(entry.id());
        if (illustration == null) {
            illustration = entry.definition().image().map(CodexImages::resolve).orElse(null);
        }
        if (illustration != null) {
            CodexImages.Loaded plate = illustration;
            var bookLayout = screen.bookLayout();
            int frameHeight = CodexFrame.heightForWidth(bookLayout, detail.width());
            detail.block(frameHeight + 6, (graphics, x, y, width, mouseX, mouseY, partialTick) ->
                    CodexFrame.draw(graphics, screen.bookLayout(), plate, x, y, width, frameHeight));
        } else {
            entry.definition().singleType().ifPresent(type -> detail.block(PORTRAIT_HEIGHT, (graphics, x, y, width, mouseX, mouseY, partialTick) -> {
                int boxWidth = Math.min(width, PORTRAIT_HEIGHT);
                graphics.fill(x, y, x + boxWidth, y + PORTRAIT_HEIGHT - 4, theme.selection());
                EntityPortraits.render(graphics, type, x, y, boxWidth, PORTRAIT_HEIGHT - 4, mouseX, mouseY);
            }));
        }
        detail.heading(entry.title().copy().withStyle(ChatFormatting.BOLD), theme.title());
        detail.text(categoryName(entry.definition().category()), theme.muted());
        int kills = ClientCodex.kills(entry.id());
        if (entry.definition().showKills() && entry.definition().entity().isPresent() && kills > 0) {
            detail.text(Component.translatable("storybound.codex.kills", kills), theme.muted());
        }
        detail.gap(6);
        for (CodexDefinition.Section section : entry.definition().sections()) {
            if (!entry.sections().contains(section.id())) {
                detail.text(Component.translatable("storybound.codex.locked"), theme.muted()).gap(6);
                continue;
            }
            section.title().ifPresent(title -> detail.text(title.asComponent().copy().withStyle(ChatFormatting.BOLD), theme.accent()));
            SbtDetail.append(detail, section.textId(entry.id()), theme.ink());
            detail.gap(6);
        }
    }

    @Override
    protected Component emptyText() {
        return Component.translatable("storybound.codex.empty");
    }

    public record Entry(ResourceLocation id, CodexDefinition definition, Set<String> sections) {
        Component title() {
            return definition.title().asComponent();
        }
    }
}
