package dev.dzenthai.storybound.client.quest;

import dev.dzenthai.storybound.client.ClientQuests;
import dev.dzenthai.storybound.client.SeenMarks;
import dev.dzenthai.storybound.client.menu.ListDetailView;
import dev.dzenthai.storybound.client.menu.MenuTheme;
import dev.dzenthai.storybound.core.state.QuestProgress;
import dev.dzenthai.storybound.core.state.QuestStatus;
import dev.dzenthai.storybound.module.quest.DifficultyScale;
import dev.dzenthai.storybound.module.quest.QuestDefinition;
import dev.dzenthai.storybound.module.quest.QuestRegistries;
import dev.dzenthai.storybound.module.quest.QuestRewardData;
import dev.dzenthai.storybound.module.quest.QuestRewards;
import dev.dzenthai.storybound.network.ClaimRewardPayload;
import dev.dzenthai.storybound.network.TrackQuestPayload;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/**
 * The Quests tab: active quests by category, then completed and failed ones. The detail shows the
 * quest's description, the log of passed stages and the current objectives.
 */
public class QuestsView extends ListDetailView<QuestsView.Entry> {
    private static final int ITEM_ROW = 18;

    private Button trackButton;
    private Button claimButton;
    /** The reward chosen for {@link #chosenFor}; -1 while none is. */
    private int chosen = -1;
    private @Nullable ResourceLocation chosenFor;

    /** True while some quest changed since the player last looked at it. */
    public static boolean hasUnseen() {
        if (entries().stream().anyMatch(entry -> ClientQuests.waitingReward(entry.id()).isPresent())) {
            return true;
        }
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
        claimButton = addWidget(Button.builder(Component.translatable("storybound.quests.rewards.claim"), button -> claim())
                .bounds(left + width - 184, buttonY(), 90, 18)
                .build());
        super.init();
    }

    @Override
    protected int dataVersion() {
        // A running clock (a time limit, a cooldown) shows a new second every second.
        Entry shown = selected();
        boolean ticking = shown != null && (secondsLeft(shown.definition(), shown.progress()).isPresent()
                || secondsUntilRepeat(shown.definition(), shown.progress()).isPresent());
        return ClientQuests.version() * 31 + (ticking ? (int) (gameTime() / 20) : 0);
    }

    private static long gameTime() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level != null ? minecraft.level.getGameTime() : 0;
    }

    /** Seconds before the time limit of an active quest; empty without one. */
    public static OptionalLong secondsLeft(QuestDefinition definition, QuestProgress progress) {
        if (definition.timeLimit().isEmpty() || progress.status() != QuestStatus.ACTIVE || progress.startedAt() == 0) {
            return OptionalLong.empty();
        }
        long end = progress.startedAt() + Math.round(definition.timeLimit().get() * 20);
        return OptionalLong.of(Math.max(0, (end - gameTime() + 19) / 20));
    }

    /** Seconds before a repeatable quest that ended can start again; empty when it cannot (or can already). */
    static OptionalLong secondsUntilRepeat(QuestDefinition definition, QuestProgress progress) {
        if (definition.repeatable().isEmpty() || progress.status() == QuestStatus.ACTIVE) {
            return OptionalLong.empty();
        }
        long left = progress.finishedAt() + definition.repeatable().get().cooldownTicks() - gameTime();
        return left > 0 ? OptionalLong.of((left + 19) / 20) : OptionalLong.empty();
    }

    /** {@code 4:05}, {@code 1:02:03}. */
    public static String clock(long seconds) {
        long hours = seconds / 3600;
        long minutes = seconds / 60 % 60;
        long rest = seconds % 60;
        return hours > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, rest) : String.format(Locale.ROOT, "%d:%02d", minutes, rest);
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
        active.entrySet().stream()
                .sorted(Comparator.comparing(category -> categoryName(category.getKey()).getString()))
                .forEach(category -> {
                    rows.add(Row.header(categoryName(category.getKey())));
                    for (Entry entry : category.getValue()) {
                        Component text = ClientQuests.isPinned(entry.id()) ? Component.literal("» ").append(entry.title()) : entry.title();
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
            // Rewards waiting to be claimed are marked, so the player comes back for them.
            entries.forEach(entry -> rows.add(new Row<>(ClientQuests.waitingReward(entry.id()).isPresent()
                    ? Component.literal("✦ ").append(entry.title()) : entry.title(), entry, color)));
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
        return entries.stream().filter(entry -> ClientQuests.waitingReward(entry.id()).isPresent()).findFirst()
                .or(() -> entries.stream().filter(entry -> tracked.equals(Optional.of(entry.id()))).findFirst())
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
        definition.difficulty().ifPresent(difficulty ->
                detail.text(Component.translatable("storybound.quests.difficulty", DifficultyScale.current().render(difficulty)), theme.ink()));
        secondsLeft(definition, progress).ifPresent(left -> detail.text(Component.translatable("storybound.quests.time_left", clock(left)),
                left < 60 ? 0xFFD9534F : theme.ink()));
        if (definition.repeatable().isPresent()) {
            OptionalLong again = secondsUntilRepeat(definition, progress);
            Component repeat = progress.status() == QuestStatus.ACTIVE ? Component.translatable("storybound.quests.repeatable")
                    : again.isPresent() ? Component.translatable("storybound.quests.repeat_in", clock(again.getAsLong()))
                    : Component.translatable("storybound.quests.repeat_ready");
            detail.text(repeat, theme.ink());
        }
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
            progress.stage().flatMap(definition::stage).ifPresent(stage -> addObjectives(detail, theme, entry.id(), stage, progress));
        }
        addRewards(detail, theme, entry);
    }

    private static void addObjectives(Detail detail, MenuTheme theme, ResourceLocation quest, QuestDefinition.Stage stage, QuestProgress progress) {
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
            int count = progress.count(stage.objectiveId(i));
            String mark = objectiveMark(objective, count);
            boolean done = count >= objective.count();
            detail.item(mark, objectiveText(objective, count), done ? theme.muted() : theme.ink());
            // A hint helps with what is left to do; a finished objective needs none.
            if (!done) {
                objective.hint().ifPresent(hint -> detail.note(mark, hint.asComponent().copy().withStyle(ChatFormatting.ITALIC), theme.muted()));
                for (Component hint : shownHints(quest, stage, i)) {
                    detail.note(mark, Component.literal("» ").append(hint).withStyle(ChatFormatting.ITALIC), theme.accent());
                }
            }
        }
    }

    /** The objective's hints the server says show now (conditions, waiting). */
    static List<Component> shownHints(ResourceLocation quest, QuestDefinition.Stage stage, int objective) {
        List<Component> shown = new ArrayList<>();
        List<QuestDefinition.Hint> hints = stage.objectives().get(objective).hints();
        for (int h = 0; h < hints.size(); h++) {
            if (ClientQuests.hintShown(quest, stage.id(), stage.objectiveId(objective), h)) {
                shown.add(hints.get(h).text().asComponent());
            }
        }
        return shown;
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
        if (entry == null || !entry.id().equals(chosenFor)) {
            chosen = -1;
            chosenFor = entry != null ? entry.id() : null;
        }
        trackButton.visible = entry != null && entry.progress().status() == QuestStatus.ACTIVE;
        boolean pinned = entry != null && ClientQuests.isPinned(entry.id());
        trackButton.setMessage(Component.translatable(pinned ? "storybound.quests.untrack" : "storybound.quests.track"));
        boolean waiting = entry != null && ClientQuests.waitingReward(entry.id()).isPresent();
        claimButton.visible = waiting;
        claimButton.active = waiting && (entry.definition().rewards().choice().isEmpty() || chosen >= 0);
        claimButton.setTooltip(claimButton.active ? null : Tooltip.create(Component.translatable("storybound.quests.rewards.choose")));
    }

    private void toggleTracking() {
        Entry entry = selected();
        if (entry != null) {
            PacketDistributor.sendToServer(new TrackQuestPayload(entry.id(), !ClientQuests.isPinned(entry.id())));
        }
    }

    private void claim() {
        Entry entry = selected();
        if (entry != null) {
            PacketDistributor.sendToServer(new ClaimRewardPayload(entry.id(), entry.definition().rewards().choice().isEmpty() ? -1 : chosen));
        }
    }

    /**
     * What the quest gives: shown beforehand on every quest that has rewards; the rolled loot and the choice while
     * they wait to be claimed.
     */
    private void addRewards(Detail detail, MenuTheme theme, Entry entry) {
        QuestRewards.Rewards rewards = entry.definition().rewards();
        if (!rewards.hasThings()) {
            return;
        }
        Optional<QuestRewardData.Pending> waiting = ClientQuests.waitingReward(entry.id());
        detail.gap(4).text(Component.translatable(waiting.isPresent() ? "storybound.quests.rewards.waiting" : "storybound.quests.rewards")
                .withStyle(ChatFormatting.BOLD), theme.accent());
        List<ItemStack> shown = new ArrayList<>();
        rewards.items().forEach(item -> shown.add(item.stack()));
        waiting.ifPresent(pending -> shown.addAll(pending.loot()));
        if (!shown.isEmpty()) {
            detail.block(ITEM_ROW, (graphics, x, y, width, mouseX, mouseY, partialTick) -> {
                for (int i = 0; i < shown.size() && (i + 1) * 18 <= width; i++) {
                    graphics.renderItem(shown.get(i), x + i * 18, y);
                    graphics.renderItemDecorations(detail.font(), shown.get(i), x + i * 18, y);
                }
            });
            for (ItemStack stack : shown) {
                detail.item("•", Component.literal(stack.getCount() + " × ").append(stack.getHoverName()), theme.ink());
            }
        }
        if (rewards.loot().isPresent() && waiting.isEmpty()) {
            detail.item("•", Component.translatable("storybound.quests.rewards.loot"), theme.ink());
        }
        if (rewards.xp() > 0) {
            detail.item("•", Component.translatable("storybound.quests.rewards.xp", rewards.xp()), theme.ink());
        }
        if (!rewards.choice().isEmpty()) {
            detail.gap(2).text(Component.translatable("storybound.quests.rewards.choice"), theme.ink());
            for (int i = 0; i < rewards.choice().size(); i++) {
                ItemStack stack = rewards.choice().get(i).stack();
                Component text = Component.literal(stack.getCount() + " × ").append(stack.getHoverName());
                if (waiting.isPresent()) {
                    int index = i;
                    detail.clickable(index == chosen ? "◉" : "○", text, index == chosen ? theme.accent() : theme.ink(), () -> {
                        chosen = index;
                        refreshDetail();
                    });
                } else {
                    detail.item("○", text, theme.ink());
                }
            }
        }
        long times = ClientQuests.waitingRewards(entry.id());
        if (times > 1) {
            detail.text(Component.translatable("storybound.quests.rewards.times", times), theme.muted());
        }
    }

    public record Entry(ResourceLocation id, QuestDefinition definition, QuestProgress progress) {
        Component title() {
            return definition.title().asComponent();
        }
    }
}
