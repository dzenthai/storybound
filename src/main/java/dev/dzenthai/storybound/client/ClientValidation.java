package dev.dzenthai.storybound.client;

import dev.dzenthai.storybound.client.document.DocumentStyles;
import dev.dzenthai.storybound.client.document.DocumentTexts;
import dev.dzenthai.storybound.core.text.StoryText;
import dev.dzenthai.storybound.module.boss.BossDefinition;
import dev.dzenthai.storybound.module.boss.BossRegistries;
import dev.dzenthai.storybound.module.codex.CodexDefinition;
import dev.dzenthai.storybound.module.codex.CodexRegistries;
import dev.dzenthai.storybound.module.document.DocumentDefinition;
import dev.dzenthai.storybound.module.document.DocumentRegistries;
import dev.dzenthai.storybound.module.quest.QuestDefinition;
import dev.dzenthai.storybound.module.quest.QuestRegistries;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.locale.Language;
import net.minecraft.resources.ResourceLocation;

/**
 * {@code /storybound_client validate}: what only a client can check, in the game's language — every translation key
 * the received definitions name (quests, documents, Codex entries, bosses), their long texts and the documents'
 * styles. Dialogues and other server-only data never reach clients, so they are not checked here.
 */
public final class ClientValidation {
    /** A problem: where it is ({@code quest mypack:hunt stage track}) and what is wrong. */
    public record Issue(String where, String what) {
        public String describe() {
            return where + ": " + what;
        }
    }

    private ClientValidation() {}

    /** Where the long texts and the styles come from: the game's resource packs and story folder, or a test's. */
    public interface Sources {
        /** A long text in exactly that language. */
        Optional<String> text(ResourceLocation textId, String language);

        boolean style(ResourceLocation style);
    }

    /**
     * @param translated whether a key has a translation (the game's {@link Language} in game)
     * @param language   the language long texts are looked up in
     */
    public static List<Issue> check(Predicate<String> translated, Sources sources, String language) {
        List<Issue> issues = new ArrayList<>();
        QuestRegistries.QUESTS.clientStore().all().forEach((id, quest) -> quest(issues, translated, "quest " + id, quest));
        DocumentRegistries.DOCUMENTS.clientStore().all().forEach((id, document) -> document(issues, translated, sources, language, id, document));
        CodexRegistries.CODEX.clientStore().all().forEach((id, entry) -> {
            String where = "codex " + id;
            text(issues, translated, where + " title", entry.title());
            for (CodexDefinition.Section section : entry.sections()) {
                section.title().ifPresent(title -> text(issues, translated, where + " section " + section.id(), title));
                longText(issues, sources, language, where + " section " + section.id(), section.textId(id));
            }
        });
        BossRegistries.BOSSES.clientStore().all().forEach((id, boss) -> {
            String where = "boss " + id;
            text(issues, translated, where + " title", boss.title());
            boss.lore().ifPresent(part -> longText(issues, sources, language, where + " lore", part.text().orElse(BossDefinition.defaultText(id, "lore"))));
            boss.tactics().ifPresent(part -> longText(issues, sources, language, where + " tactics", part.text().orElse(BossDefinition.defaultText(id, "tactics"))));
        });
        return issues;
    }

    /** In game: the game's language and its translations. */
    public static List<Issue> check(String language) {
        Language game = Language.getInstance();
        return check(game::has, new Sources() {
            @Override
            public Optional<String> text(ResourceLocation textId, String in) {
                return DocumentTexts.load(textId, in);
            }

            @Override
            public boolean style(ResourceLocation style) {
                return DocumentStyles.INSTANCE.has(style);
            }
        }, language);
    }

    private static void quest(List<Issue> issues, Predicate<String> translated, String where, QuestDefinition quest) {
        text(issues, translated, where + " title", quest.title());
        quest.description().ifPresent(text -> text(issues, translated, where + " description", text));
        quest.text().active().ifPresent(text -> text(issues, translated, where + " text.active", text));
        quest.text().completed().ifPresent(text -> text(issues, translated, where + " text.completed", text));
        quest.text().failed().ifPresent(text -> text(issues, translated, where + " text.failed", text));
        for (QuestDefinition.Stage stage : quest.stages()) {
            String at = where + " stage " + stage.id();
            stage.log().ifPresent(text -> text(issues, translated, at + " log", text));
            for (int i = 0; i < stage.objectives().size(); i++) {
                QuestDefinition.Objective objective = stage.objectives().get(i);
                String of = at + " objective " + stage.objectiveId(i);
                objective.text().ifPresent(text -> text(issues, translated, of, text));
                objective.hint().ifPresent(text -> text(issues, translated, of + " hint", text));
                for (QuestDefinition.Hint hint : objective.hints()) {
                    text(issues, translated, of + " hints", hint.text());
                }
            }
        }
    }

    private static void document(List<Issue> issues, Predicate<String> translated, Sources sources, String language, ResourceLocation id,
                                 DocumentDefinition document) {
        String where = "document " + id;
        text(issues, translated, where + " title", document.title());
        longText(issues, sources, language, where, document.contentId(id));
        if (!sources.style(document.styleId())) {
            issues.add(new Issue(where, "no style " + document.styleId() + " (a resource pack's storybound/styles/)"));
        }
        document.contract().ifPresent(contract -> {
            contract.conditions().ifPresent(text -> text(issues, translated, where + " contract.conditions", text));
            contract.reward().ifPresent(text -> text(issues, translated, where + " contract.reward", text));
            contract.hint().ifPresent(text -> text(issues, translated, where + " contract.hint", text));
        });
    }

    /** A key without a translation (and without a fallback), or a text written into the JSON. */
    private static void text(List<Issue> issues, Predicate<String> translated, String where, StoryText text) {
        if (text instanceof StoryText.Key key && key.fallback().isEmpty() && !translated.test(key.key())) {
            issues.add(new Issue(where, "no translation for " + key.key()));
        } else if (text instanceof StoryText.Literal) {
            issues.add(new Issue(where, "written into the JSON (literal): not translated; /storybound lang extract makes it a key"));
        }
    }

    /** A long text missing in the language: shown in English when there is one, else as "missing text". */
    private static void longText(List<Issue> issues, Sources sources, String language, String where, ResourceLocation textId) {
        if (sources.text(textId, language).isPresent()) {
            return;
        }
        Optional<String> english = sources.text(textId, "en_us");
        issues.add(new Issue(where, english.isPresent() ? "no " + language + " text " + textId + " (en_us shows)" : "no text " + textId + " in any language"));
    }
}
