package dev.dzenthai.storybound.client.quest;

import dev.dzenthai.storybound.client.ClientQuests;
import dev.dzenthai.storybound.client.SeenMarks;
import dev.dzenthai.storybound.client.menu.ListDetailView;
import dev.dzenthai.storybound.client.menu.MenuTheme;
import dev.dzenthai.storybound.core.state.QuestProgress;
import dev.dzenthai.storybound.core.state.QuestStatus;
import dev.dzenthai.storybound.module.quest.QuestDefinition;
import dev.dzenthai.storybound.module.quest.QuestRegistries;
import dev.dzenthai.storybound.network.TrackQuestPayload;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/**
 * The Quests tab: active quests by category, then completed and failed ones. The detail shows the
 * quest's description, the log of passed stages and the current objectives.
 */
public class QuestsView extends ListDetailView<QuestsView.Entry> {
    private Button trackButton;

    /** True while some quest changed since the player last looked at it. */
    public static boolean hasUnseen() {
        for (Entry entry : entries()) {
            if (!SeenMarks.isSeen(seenKey(entry))) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void init() {
        trackButton = addWidget(Button.builder(Component.empty(), button -> toggleTracking())
                .bounds(left + width - 90, buttonY(), 90, 18)
                .build());
        super.init();
    }

    @Override
    protected int dataVersion() {
        return ClientQuests.version();
    }

    @Override
    protected List<Row<Entry>> buildRows() {
        List<Entry> entries = entries();
        entries.sort(Comparator.comparing(entry -> entry.title().getString()));
        Map<ResourceLocation, List<Entry>> active = new LinkedHashMap<>();
        List<Entry> completed = new ArrayList<>();
        List<Entry> failed = new ArrayList<>();
        for (Entry entry : entries) {
            switch (entry.progress().status()) {
                case ACTIVE -> active.computeIfAbsent(entry.definition().category(), key -> new ArrayList<>()).add(entry);
                case COMPLETED -> completed.add(entry);
                case FAILED -> failed.add(entry);
            }
        }
        List<Row<Entry>> rows = new ArrayList<>();
        Optional<ResourceLocation> tracked = ClientQuests.tracked();
        active.entrySet().stream()
                .sorted(Comparator.comparing(category -> categoryName(category.getKey()).getString()))
                .forEach(category -> {
                    rows.add(Row.header(categoryName(category.getKey())));
                    for (Entry entry : category.getValue()) {
                        Component text = tracked.equals(Optional.of(entry.id())) ? Component.literal("» ").append(entry.title()) : entry.title();
                        rows.add(Row.entry(text, entry));
                    }
                });
        addSection(rows, "storybound.quests.section.completed", completed, theme().completed());
        addSection(rows, "storybound.quests.section.failed", failed, theme().failed());
        return rows;
    }

    private static void addSection(List<Row<Entry>> rows, String title, List<Entry> entries, int color) {
        if (!entries.isEmpty()) {
            rows.add(new Row<>(Component.translatable(title), null, 0));
            entries.forEach(entry -> rows.add(new Row<>(entry.title(), entry, color)));
        }
    }

    private static List<Entry> entries() {
        List<Entry> entries = new ArrayList<>();
        ClientQuests.all().forEach((id, progress) -> {
            QuestDefinition definition = QuestRegistries.QUESTS.clientStore().getOrNull(id);
            if (definition != null) {
                entries.add(new Entry(id, definition, progress));
            }
        });
        return entries;
    }

    private static Component categoryName(ResourceLocation category) {
        return Component.translatableWithFallback(category.getNamespace() + ".quest.category." + category.getPath(), category.getPath());
    }

    @Override
    protected @Nullable Entry preferredSelection(List<Entry> entries) {
        Optional<ResourceLocation> tracked = ClientQuests.tracked();
        return entries.stream().filter(entry -> tracked.equals(Optional.of(entry.id()))).findFirst()
                .orElseGet(() -> super.preferredSelection(entries));
    }

    @Override
    protected String key(Entry entry) {
        return entry.id().toString();
    }

    /** Changes with the state and stage, so an advanced quest shows as new again. */
    private static String seenKey(Entry entry) {
        return "quest:" + entry.id() + ":" + entry.progress().status().name().toLowerCase(Locale.ROOT) + ":" + entry.progress().stage().orElse("");
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
        QuestDefinition definition = entry.definition();
        QuestProgress progress = entry.progress();
        detail.text(entry.title().copy().withStyle(ChatFormatting.BOLD), theme.title());
        detail.text(Component.translatable("storybound.quests.status." + progress.status().name().toLowerCase(Locale.ROOT))
                .append(" · ").append(categoryName(definition.category())), statusColor(theme, progress.status()));
        detail.gap(6);

        definition.text().forStatus(progress.status()).or(definition::description)
                .ifPresent(text -> detail.text(text.asComponent(), theme.ink()).gap(6));

        // Passed stages, then the current one: an entry shows up as soon as its stage is reached.
        List<String> reached = new ArrayList<>(progress.log());
        progress.stage().filter(stage -> !reached.contains(stage)).ifPresent(reached::add);
        for (String stageId : reached) {
            definition.stage(stageId).flatMap(QuestDefinition.Stage::log)
                    .ifPresent(log -> detail.text(log.asComponent(), theme.ink()).gap(4));
        }

        if (progress.status() == QuestStatus.ACTIVE) {
            progress.stage().flatMap(definition::stage).ifPresent(stage -> addObjectives(detail, theme, stage, progress));
        }
    }

    private static void addObjectives(Detail detail, MenuTheme theme, QuestDefinition.Stage stage, QuestProgress progress) {
        boolean header = false;
        for (int i = 0; i < stage.objectives().size(); i++) {
            QuestDefinition.Objective objective = stage.objectives().get(i);
            if (objective.hidden() || objective.text().isEmpty()) {
                continue;
            }
            if (!header) {
                detail.gap(2).text(Component.translatable("storybound.quests.objectives").withStyle(ChatFormatting.BOLD), theme.accent());
                header = true;
            }
            detail.item(objectiveMark(objective, progress.count(stage.objectiveId(i))), objectiveText(objective, progress.count(stage.objectiveId(i))),
                    progress.count(stage.objectiveId(i)) >= objective.count() ? theme.muted() : theme.ink());
        }
    }

    static String objectiveMark(QuestDefinition.Objective objective, int count) {
        return count >= objective.count() ? "■" : "□";
    }

    /** The objective's text, with {@code (n/count)} for counted objectives and a note for optional ones. */
    static Component objectiveText(QuestDefinition.Objective objective, int count) {
        Component text = objective.text().map(value -> value.asComponent().copy()).orElseGet(Component::empty);
        if (objective.count() > 1) {
            text = text.copy().append(" (" + Math.min(count, objective.count()) + "/" + objective.count() + ")");
        }
        if (objective.optional()) {
            text = text.copy().append(" ").append(Component.translatable("storybound.quests.optional"));
        }
        return text;
    }

    private static int statusColor(MenuTheme theme, QuestStatus status) {
        return switch (status) {
            case ACTIVE -> theme.accent();
            case COMPLETED -> theme.completed();
            case FAILED -> theme.failed();
        };
    }

    @Override
    protected Component emptyText() {
        return Component.translatable("storybound.quests.empty");
    }

    @Override
    protected void selectionChanged() {
        Entry entry = selected();
        trackButton.visible = entry != null && entry.progress().status() == QuestStatus.ACTIVE;
        boolean tracked = entry != null && ClientQuests.tracked().equals(Optional.of(entry.id()));
        trackButton.setMessage(Component.translatable(tracked ? "storybound.quests.untrack" : "storybound.quests.track"));
    }

    private void toggleTracking() {
        Entry entry = selected();
        if (entry != null) {
            boolean tracked = ClientQuests.tracked().equals(Optional.of(entry.id()));
            PacketDistributor.sendToServer(new TrackQuestPayload(tracked ? Optional.empty() : Optional.of(entry.id())));
        }
    }

    public record Entry(ResourceLocation id, QuestDefinition definition, QuestProgress progress) {
        Component title() {
            return definition.title().asComponent();
        }
    }
}
