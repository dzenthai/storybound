package dev.dzenthai.storybound.client.codex;

import dev.dzenthai.storybound.client.ClientBosses;
import dev.dzenthai.storybound.client.SeenMarks;
import dev.dzenthai.storybound.client.menu.ListDetailView;
import dev.dzenthai.storybound.client.menu.MenuTheme;
import dev.dzenthai.storybound.module.boss.BossData;
import dev.dzenthai.storybound.module.boss.BossDefinition;
import dev.dzenthai.storybound.module.boss.BossRegistries;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * The Bosses tab: defeated bosses, then the ones only met. The detail shows the boss, its status, the lore,
 * tactics and rewards the player has uncovered, and their statistics after the first victory.
 */
public class BossesView extends ListDetailView<BossesView.Entry> {
    private static final int ITEM_ROW = 20;

    public static boolean hasUnseen() {
        return entries().stream().anyMatch(entry -> !SeenMarks.isSeen(seenKey(entry)));
    }

    @Override
    protected int dataVersion() {
        return ClientBosses.version();
    }

    @Override
    protected List<Row<Entry>> buildRows() {
        List<Entry> entries = entries();
        entries.sort(Comparator.comparing(entry -> entry.title().getString()));
        List<Row<Entry>> rows = new ArrayList<>();
        addGroup(rows, "storybound.bosses.section.defeated", entries.stream().filter(entry -> entry.record().victories() > 0).toList(), theme().completed());
        addGroup(rows, "storybound.bosses.section.encountered", entries.stream().filter(entry -> entry.record().victories() == 0).toList(), 0);
        return rows;
    }

    private static void addGroup(List<Row<Entry>> rows, String title, List<Entry> entries, int color) {
        if (!entries.isEmpty()) {
            rows.add(Row.header(Component.translatable(title)));
            entries.forEach(entry -> rows.add(new Row<>(entry.title(), entry, color)));
        }
    }

    private static List<Entry> entries() {
        List<Entry> entries = new ArrayList<>();
        ClientBosses.all().forEach((id, record) -> {
            BossDefinition definition = BossRegistries.BOSSES.clientStore().getOrNull(id);
            if (definition != null && record.encountered()) {
                entries.add(new Entry(id, definition, record));
            }
        });
        return entries;
    }

    @Override
    protected String key(Entry entry) {
        return entry.id().toString();
    }

    private static String seenKey(Entry entry) {
        return "boss:" + entry.id() + ":" + entry.record().sections().size() + ":" + entry.record().victories();
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
        BossDefinition definition = entry.definition();
        BossData.Record record = entry.record();
        definition.entity().singleType().ifPresent(type -> detail.block(CodexView.PORTRAIT_HEIGHT, (graphics, x, y, width, mouseX, mouseY, partialTick) -> {
            int boxWidth = Math.min(width, CodexView.PORTRAIT_HEIGHT);
            graphics.fill(x, y, x + boxWidth, y + CodexView.PORTRAIT_HEIGHT - 4, theme.selection());
            EntityPortraits.render(graphics, type, x, y, boxWidth, CodexView.PORTRAIT_HEIGHT - 4, mouseX, mouseY);
        }));
        detail.text(entry.title().copy().withStyle(ChatFormatting.BOLD), theme.title());
        detail.text(record.victories() > 0
                ? Component.translatable("storybound.bosses.status.defeated", record.victories())
                : Component.translatable("storybound.bosses.status.encountered"), record.victories() > 0 ? theme.completed() : theme.failed());
        detail.gap(6);

        definition.lore().ifPresent(part -> section(detail, theme, entry, BossDefinition.LORE, part.text()));
        definition.tactics().ifPresent(part -> section(detail, theme, entry, BossDefinition.TACTICS, part.text()));
        definition.rewards().ifPresent(rewards -> {
            heading(detail, theme, BossDefinition.REWARDS);
            if (!record.isUnlocked(BossDefinition.REWARDS)) {
                detail.text(Component.translatable("storybound.bosses.locked"), theme.muted()).gap(6);
                return;
            }
            List<ItemStack> items = rewards.items();
            if (!items.isEmpty()) {
                detail.block(ITEM_ROW, (graphics, x, y, width, mouseX, mouseY, partialTick) -> {
                    for (int i = 0; i < items.size() && (i + 1) * 18 <= width; i++) {
                        graphics.renderItem(items.get(i), x + i * 18, y);
                        graphics.renderItemDecorations(detail.font(), items.get(i), x + i * 18, y);
                    }
                });
                for (ItemStack item : items) {
                    detail.item("•", Component.literal(item.getCount() + " × ").append(item.getHoverName()), theme.ink());
                }
            }
            rewards.text().ifPresent(text -> SbtDetail.append(detail, text, theme.ink()));
            detail.gap(6);
        });

        if (record.victories() > 0) {
            heading(detail, theme, "statistics");
            detail.item("•", Component.translatable("storybound.bosses.victories", record.victories()), theme.ink());
            if (record.firstVictory() > 0) {
                String date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(Instant.ofEpochMilli(record.firstVictory()).atZone(ZoneId.systemDefault()));
                detail.item("•", Component.translatable("storybound.bosses.first_victory", date), theme.ink());
            }
            if (record.bestTicks() >= 0) {
                detail.item("•", Component.translatable("storybound.bosses.best_time", formatTime(record.bestTicks())), theme.ink());
            }
        }
    }

    private static void section(Detail detail, MenuTheme theme, Entry entry, String section, Optional<ResourceLocation> text) {
        heading(detail, theme, section);
        if (entry.record().isUnlocked(section)) {
            SbtDetail.append(detail, text.orElseGet(() -> BossDefinition.defaultText(entry.id(), section)), theme.ink());
        } else {
            detail.text(Component.translatable("storybound.bosses.locked"), theme.muted());
        }
        detail.gap(6);
    }

    private static void heading(Detail detail, MenuTheme theme, String section) {
        detail.text(Component.translatable("storybound.bosses." + section).withStyle(ChatFormatting.BOLD), theme.accent());
    }

    /** {@code m:ss} or {@code h:mm:ss}. */
    static String formatTime(long ticks) {
        long seconds = ticks / 20;
        long hours = seconds / 3600;
        return hours > 0
                ? String.format("%d:%02d:%02d", hours, seconds / 60 % 60, seconds % 60)
                : String.format("%d:%02d", seconds / 60, seconds % 60);
    }

    @Override
    protected Component emptyText() {
        return Component.translatable("storybound.bosses.empty");
    }

    public record Entry(ResourceLocation id, BossDefinition definition, BossData.Record record) {
        Component title() {
            return definition.title().asComponent();
        }
    }
}
