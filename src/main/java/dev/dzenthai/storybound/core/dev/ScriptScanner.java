package dev.dzenthai.storybound.core.dev;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a KubeJS script's text for what concerns the story, without running it: the handlers it registers with
 * {@code StoryboundEvents.registerActions} / {@code registerConditions} ({@code e.add('id', ...)}), the story events it
 * listens to ({@code StoryboundEvents.questStateChanged('mypack:hunt', ...)}), and every namespaced id it mentions
 * ({@code Storybound.quests(player).start('mypack:hunt')}). A reading, not a parse: enough to draw the arrows. A comment
 * {@code // @storybound leads_to quest:mypack:chapter_2} names what the file leads to that its code does not show.
 */
public final class ScriptScanner {
    private static final Pattern EVENT = Pattern.compile("StoryboundEvents\\s*\\.\\s*(\\w+)\\s*\\(");
    private static final Pattern FIRST_ARGUMENT = Pattern.compile("\\G\\s*['\"`]([^'\"`]+)['\"`]");
    private static final Pattern ADD = Pattern.compile("\\.\\s*add\\s*\\(\\s*['\"`]([^'\"`]+)['\"`]");
    private static final Pattern ID = Pattern.compile("['\"`]([a-z0-9_.-]+:[a-z0-9_./-]+)['\"`]");
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n]*");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LEADS_TO = Pattern.compile("@storybound\\s+leads_to\\s+([a-z_]+):([a-z0-9_.-]+:[a-z0-9_./-]+)");

    /** A story event the script listens to, with its first argument (the quest, dialogue, trigger id...). */
    public record Listener(String event, String id) {}

    /** What a {@code @storybound leads_to <type>:<id>} comment names: {@code type} as in a script block's {@code leads_to}. */
    public record Lead(String type, String id) {}

    /**
     * @param actions    handlers registered as actions
     * @param conditions handlers registered as conditions
     * @param ids        every namespaced id the script mentions, handlers and listened ids included
     * @param leadsTo    what its {@code @storybound leads_to} comments name
     */
    public record Result(List<String> actions, List<String> conditions, List<Listener> listeners, List<String> ids, List<Lead> leadsTo) {}

    private ScriptScanner() {}

    public static Result scan(String source) {
        String text = LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(source).replaceAll(" ")).replaceAll("");
        Set<String> actions = new LinkedHashSet<>();
        Set<String> conditions = new LinkedHashSet<>();
        List<Listener> listeners = new ArrayList<>();
        Matcher event = EVENT.matcher(text);
        List<Integer> starts = new ArrayList<>();
        List<String> names = new ArrayList<>();
        while (event.find()) {
            starts.add(event.end());
            names.add(event.group(1));
        }
        for (int i = 0; i < starts.size(); i++) {
            int start = starts.get(i);
            // A registration's handlers are the add(...) calls before the next StoryboundEvents call.
            int end = i + 1 < starts.size() ? starts.get(i + 1) : text.length();
            String name = names.get(i);
            if (name.equals("registerActions") || name.equals("registerConditions")) {
                Matcher add = ADD.matcher(text.substring(start, end));
                while (add.find()) {
                    (name.equals("registerActions") ? actions : conditions).add(add.group(1));
                }
            } else {
                Matcher argument = FIRST_ARGUMENT.matcher(text);
                argument.region(start, text.length());
                if (argument.find()) {
                    listeners.add(new Listener(name, argument.group(1)));
                }
            }
        }
        Set<String> ids = new LinkedHashSet<>();
        Matcher id = ID.matcher(text);
        while (id.find()) {
            ids.add(id.group(1));
        }
        Set<Lead> leadsTo = new LinkedHashSet<>();
        Matcher lead = LEADS_TO.matcher(source);
        while (lead.find()) {
            leadsTo.add(new Lead(lead.group(1), lead.group(2)));
        }
        return new Result(List.copyOf(actions), List.copyOf(conditions), List.copyOf(listeners), List.copyOf(ids), List.copyOf(leadsTo));
    }
}
