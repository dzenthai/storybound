package dev.dzenthai.storybound.core.story;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.dzenthai.storybound.Storybound;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Renaming a story node in the dev mode (October 29, 2026; before, an id was fixed once the node was made): what may be
 * renamed, what a new id may be, and the rewriting of what names the old one: references in other definitions,
 * {@code {var:}} and {@code {flag:}} in texts, and the migrations that move players' saved progress. Pure, so the
 * editor checks a name as it is typed and the server does the rename with the same rules.
 */
public final class StoryRenames {
    public static final ResourceLocation FLAG = Storybound.id("flag");
    public static final ResourceLocation CHAPTER = Storybound.id("chapter");
    /** The story's nodes and flags; not chapters (their title is their name) or the editor's own files. */
    public static final Set<ResourceLocation> RENAMEABLE = Set.of(StoryLinks.QUEST, StoryLinks.DIALOGUE, StoryLinks.NPC, StoryLinks.DOCUMENT,
            StoryLinks.WAYPOINT, StoryLinks.BOSS, StoryLinks.CODEX, StoryLinks.EVENT, StoryLinks.SCRIPT, FLAG);
    /** The definitions that may name a renamed id. */
    public static final Set<ResourceLocation> REFERRING = Set.of(StoryLinks.QUEST, StoryLinks.DIALOGUE, StoryLinks.NPC, StoryLinks.DOCUMENT,
            StoryLinks.WAYPOINT, StoryLinks.BOSS, StoryLinks.CODEX, StoryLinks.EVENT, StoryLinks.SCRIPT, FLAG, CHAPTER);
    /** An NPC's relationship scale is the flag {@code <ns>:rep/<npc path>}. */
    public static final String RELATIONSHIP_PREFIX = "rep/";

    /** A node's name: one word, no folder ({@code /}) and no namespace, which is the story's (see {@link #isStoryNamespace}). */
    private static final Pattern NAME = Pattern.compile("^[a-z0-9_.-]+$");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(var|flag):([a-z0-9_.-]+:[a-z0-9_./-]+)}");

    private StoryRenames() {}

    public record Rename(ResourceLocation type, ResourceLocation from, ResourceLocation to) {}

    /** Why a typed name cannot be the node's new id; the editor words it ({@code rename.problem.<name>}). */
    public enum Problem { SAME, FORMAT, TAKEN, SCALE_TAKEN, NAMESPACE }

    /**
     * The id the author typed: a name ({@code chelovek}), which stays in the node's namespace; the namespace written
     * before it is taken only when it is that one (October 29, 2026; before, any namespace and folders could be typed,
     * but the namespace is the story's, set once for the project). Empty when it is not a name.
     */
    public static Optional<ResourceLocation> parse(String typed, ResourceLocation current) {
        String text = typed.trim();
        int colon = text.indexOf(':');
        if (colon >= 0) {
            if (!text.substring(0, colon).equals(current.getNamespace())) {
                return Optional.empty();
            }
            text = text.substring(colon + 1);
        }
        return NAME.matcher(text).matches() && !text.equals(".") && !text.equals("..")
                ? Optional.of(ResourceLocation.fromNamespaceAndPath(current.getNamespace(), text)) : Optional.empty();
    }

    /**
     * A namespace a story may be written in, as set in the project: lowercase letters, digits, {@code _ - .}; not
     * {@code minecraft}, and not {@code demo}, which reinstalling the demo replaces.
     */
    public static boolean isStoryNamespace(String namespace) {
        return NAME.matcher(namespace).matches() && !namespace.equals(".") && !namespace.equals("..")
                && !namespace.equals("minecraft") && !namespace.equals("demo");
    }

    /**
     * What keeps the typed name from being the node's new id; empty when it can be. Ids are unique within a type, so a
     * quest and an NPC may both be {@code demo:elder}.
     *
     * @param exists whether a definition of the type has the id
     */
    public static Optional<Problem> problem(ResourceLocation type, ResourceLocation current, String typed, BiPredicate<ResourceLocation, ResourceLocation> exists) {
        Optional<ResourceLocation> to = parse(typed, current);
        if (to.isEmpty()) {
            int colon = typed.trim().indexOf(':');
            return Optional.of(colon >= 0 && !typed.trim().substring(0, colon).equals(current.getNamespace()) ? Problem.NAMESPACE : Problem.FORMAT);
        }
        if (to.get().equals(current)) {
            return Optional.of(Problem.SAME);
        }
        if (exists.test(type, to.get())) {
            return Optional.of(Problem.TAKEN);
        }
        if (type.equals(StoryLinks.NPC) && exists.test(FLAG, relationshipScale(current)) && exists.test(FLAG, relationshipScale(to.get()))) {
            return Optional.of(Problem.SCALE_TAKEN);
        }
        return Optional.empty();
    }

    public static ResourceLocation relationshipScale(ResourceLocation npc) {
        return ResourceLocation.fromNamespaceAndPath(npc.getNamespace(), RELATIONSHIP_PREFIX + npc.getPath());
    }

    /** The renames one rename brings: an NPC's relationship scale goes with it. */
    public static List<Rename> cascade(Rename rename, BiPredicate<ResourceLocation, ResourceLocation> exists) {
        List<Rename> renames = new ArrayList<>(List.of(rename));
        if (rename.type().equals(StoryLinks.NPC) && exists.test(FLAG, relationshipScale(rename.from()))) {
            renames.add(new Rename(FLAG, relationshipScale(rename.from()), relationshipScale(rename.to())));
        }
        return renames;
    }

    /** The field of a migrations file that moves saved progress of the type; null for what players keep nothing of. */
    public static @Nullable String migrationField(ResourceLocation type) {
        return Map.of(StoryLinks.QUEST, "quests", StoryLinks.EVENT, "events", StoryLinks.DIALOGUE, "dialogues", StoryLinks.NPC, "npc_bindings",
                StoryLinks.DOCUMENT, "documents", StoryLinks.CODEX, "codex", StoryLinks.BOSS, "bosses", StoryLinks.WAYPOINT, "waypoints",
                FLAG, "flags").get(type);
    }

    /**
     * Adds a rename to a migrations file's JSON: what was renamed to the old id now goes to the new one (A→B, then B→C
     * gives A→C and B→C), and a rename back drops the way out (A→B, then B→A leaves B→A), so the renames never loop.
     */
    public static void addMigration(JsonObject migrations, String field, ResourceLocation from, ResourceLocation to) {
        JsonObject renames = migrations.get(field) instanceof JsonObject existing ? existing : new JsonObject();
        for (String key : List.copyOf(renames.keySet())) {
            if (renames.get(key) instanceof JsonPrimitive value && from.equals(ResourceLocation.tryParse(value.getAsString()))) {
                renames.addProperty(key, to.toString());
            }
        }
        renames.remove(to.toString());
        for (String key : List.copyOf(renames.keySet())) {
            if (renames.get(key) instanceof JsonPrimitive value && value.getAsString().equals(key)) {
                renames.remove(key);
            }
        }
        renames.addProperty(from.toString(), to.toString());
        migrations.add(field, renames);
    }

    /**
     * Rewrites what names a renamed id in a definition of {@code ownerType}, in place: fields that name a quest
     * ({@code quest}), a dialogue ({@code dialogue}, {@code busy_dialogue}), a document, a boss, a Codex entry
     * ({@code entry}) or a flag ({@code key} of a logic element), a quest stage's {@code waypoints}, a chapter's nodes,
     * and {@code {var:}} / {@code {flag:}} in texts. Returns how many values changed.
     */
    public static int rewrite(ResourceLocation ownerType, JsonElement json, List<Rename> renames) {
        return walk(ownerType, json, renames);
    }

    /** {@code {var:<flag>}} and {@code {flag:<flag>}} of a renamed flag, in a text. */
    public static String rewriteText(String text, List<Rename> renames) {
        if (!text.contains("{var:") && !text.contains("{flag:")) {
            return text;
        }
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            ResourceLocation flag = ResourceLocation.tryParse(matcher.group(2));
            ResourceLocation renamed = flag == null ? null : renamed(FLAG, flag, renames);
            String replacement = renamed == null ? matcher.group() : "{" + matcher.group(1) + ":" + renamed + "}";
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    // --- inside a quest: its stages and objectives (October 29, 2026; before, their id was a plain text field, and
    // changing it broke every "next", condition and waypoint naming the stage, and players' place in the quest)

    /** A stage's or an objective's name: lowercase letters, digits, {@code _ - .}; {@code @} is for {@code @complete}. */
    private static final Pattern INSIDE_NAME = Pattern.compile("^[a-z0-9_.-]+$");

    /** The quest's stage of that id, or null. */
    public static @Nullable JsonObject stage(JsonObject quest, String id) {
        if (quest.get("stages") instanceof JsonArray stages) {
            for (JsonElement element : stages) {
                if (element instanceof JsonObject stage && stage.get("id") instanceof JsonPrimitive own && own.getAsString().equals(id)) {
                    return stage;
                }
            }
        }
        return null;
    }

    /** The stage's objectives' ids: their own, or their position when they have none (as the game counts them). */
    public static List<String> objectiveIds(JsonObject stage) {
        List<String> ids = new ArrayList<>();
        if (stage.get("objectives") instanceof JsonArray objectives) {
            for (int i = 0; i < objectives.size(); i++) {
                ids.add(objectives.get(i) instanceof JsonObject objective && objective.get("id") instanceof JsonPrimitive own ? own.getAsString() : String.valueOf(i));
            }
        }
        return ids;
    }

    public static Optional<Problem> stageProblem(JsonObject quest, String current, String typed) {
        String to = typed.trim();
        if (to.equals(current)) {
            return Optional.of(Problem.SAME);
        }
        if (!INSIDE_NAME.matcher(to).matches()) {
            return Optional.of(Problem.FORMAT);
        }
        return stage(quest, to) != null ? Optional.of(Problem.TAKEN) : Optional.empty();
    }

    /** Empty typed text means no change: an objective without an id of its own is shown so. */
    public static Optional<Problem> objectiveProblem(JsonObject quest, String stage, String current, String typed) {
        String to = typed.trim();
        if (to.isEmpty() || to.equals(current)) {
            return Optional.of(Problem.SAME);
        }
        if (!INSIDE_NAME.matcher(to).matches()) {
            return Optional.of(Problem.FORMAT);
        }
        JsonObject found = stage(quest, stage);
        return found == null || objectiveIds(found).contains(to) ? Optional.of(Problem.TAKEN) : Optional.empty();
    }

    /** Renames the stage and the other stages' {@code next} that lead to it; returns how many values changed. */
    public static int renameStage(JsonObject quest, String from, String to) {
        JsonObject found = stage(quest, from);
        if (found == null) {
            return 0;
        }
        found.addProperty("id", to);
        int changed = 1;
        for (JsonElement element : quest.getAsJsonArray("stages")) {
            if (element instanceof JsonObject other && other.get("next") instanceof JsonPrimitive next && next.getAsString().equals(from)) {
                other.addProperty("next", to);
                changed++;
            }
        }
        return changed;
    }

    /** Gives the stage's objective of that id (its own or its position) the new id; returns whether it found it. */
    public static boolean renameObjective(JsonObject quest, String stage, String from, String to) {
        JsonObject found = stage(quest, stage);
        int index = found == null ? -1 : objectiveIds(found).indexOf(from);
        if (index < 0) {
            return false;
        }
        found.getAsJsonArray("objectives").get(index).getAsJsonObject().addProperty("id", to);
        return true;
    }

    /**
     * Rewrites what names the quest's stage elsewhere, in place: an object with that {@code quest} and {@code stage}
     * (the condition "quest at stage", set_quest_stage, the trigger "stage changed", a waypoint shown at a stage).
     */
    public static int rewriteStage(JsonElement element, ResourceLocation quest, String from, String to) {
        int changed = 0;
        if (element instanceof JsonArray array) {
            for (JsonElement child : array) {
                changed += rewriteStage(child, quest, from, to);
            }
        } else if (element instanceof JsonObject object) {
            if (object.get("stage") instanceof JsonPrimitive stage && stage.getAsString().equals(from)
                    && object.get("quest") instanceof JsonPrimitive named && quest.equals(ResourceLocation.tryParse(named.getAsString()))) {
                object.addProperty("stage", to);
                changed++;
            }
            for (JsonElement child : object.asMap().values()) {
                changed += rewriteStage(child, quest, from, to);
            }
        }
        return changed;
    }

    // --- inside a dialogue: its nodes (October 29, 2026; before, a node kept the id it was made with, "line_3")

    public static Optional<Problem> nodeProblem(JsonObject dialogue, String current, String typed) {
        String to = typed.trim();
        if (to.equals(current)) {
            return Optional.of(Problem.SAME);
        }
        if (!INSIDE_NAME.matcher(to).matches()) {
            return Optional.of(Problem.FORMAT);
        }
        return dialogue.get("nodes") instanceof JsonObject nodes && nodes.has(to) ? Optional.of(Problem.TAKEN) : Optional.empty();
    }

    /**
     * Renames the dialogue's node, keeping the nodes' order, and every arrow of the dialogue leading to it: {@code start},
     * {@code next}, {@code then}, {@code else} (of nodes, options, a timeout, random branches) and a jump's or call's
     * {@code node} into this dialogue. Returns how many values changed.
     */
    public static int renameNode(JsonObject dialogue, ResourceLocation id, String from, String to) {
        if (!(dialogue.get("nodes") instanceof JsonObject nodes) || !nodes.has(from)) {
            return 0;
        }
        JsonObject renamed = new JsonObject();
        nodes.entrySet().forEach(entry -> renamed.add(entry.getKey().equals(from) ? to : entry.getKey(), entry.getValue()));
        dialogue.add("nodes", renamed);
        int changed = 1;
        if (dialogue.get("start") instanceof JsonPrimitive start && start.getAsString().equals(from)) {
            dialogue.addProperty("start", to);
            changed++;
        }
        return changed + retarget(renamed, id, from, to);
    }

    private static int retarget(JsonElement element, ResourceLocation dialogue, String from, String to) {
        int changed = 0;
        if (element instanceof JsonArray array) {
            for (JsonElement child : array) {
                changed += retarget(child, dialogue, from, to);
            }
        } else if (element instanceof JsonObject object) {
            for (String field : List.of("next", "then", "else")) {
                if (object.get(field) instanceof JsonPrimitive target && target.isString() && target.getAsString().equals(from)) {
                    object.addProperty(field, to);
                    changed++;
                }
            }
            boolean here = !(object.get("dialogue") instanceof JsonPrimitive other) || dialogue.equals(ResourceLocation.tryParse(other.getAsString()));
            if (here && object.get("node") instanceof JsonPrimitive node && node.getAsString().equals(from)) {
                object.addProperty("node", to);
                changed++;
            }
            for (JsonElement child : object.asMap().values()) {
                changed += retarget(child, dialogue, from, to);
            }
        }
        return changed;
    }

    /**
     * Rewrites what names the dialogue's node elsewhere, in place: an object with that {@code dialogue} and {@code node}
     * (another dialogue's jump or call into it, the trigger "dialogue ended at a node").
     */
    public static int rewriteNode(JsonElement element, ResourceLocation dialogue, String from, String to) {
        int changed = 0;
        if (element instanceof JsonArray array) {
            for (JsonElement child : array) {
                changed += rewriteNode(child, dialogue, from, to);
            }
        } else if (element instanceof JsonObject object) {
            if (object.get("node") instanceof JsonPrimitive node && node.getAsString().equals(from)
                    && object.get("dialogue") instanceof JsonPrimitive named && dialogue.equals(ResourceLocation.tryParse(named.getAsString()))) {
                object.addProperty("node", to);
                changed++;
            }
            for (JsonElement child : object.asMap().values()) {
                changed += rewriteNode(child, dialogue, from, to);
            }
        }
        return changed;
    }

    /** The node's place in a dialogue's layout goes with its new name. */
    public static boolean renameLayoutNode(JsonObject layout, String from, String to) {
        if (!(layout.get("nodes") instanceof JsonObject nodes) || !nodes.has(from)) {
            return false;
        }
        JsonObject renamed = new JsonObject();
        nodes.entrySet().forEach(entry -> renamed.add(entry.getKey().equals(from) ? to : entry.getKey(), entry.getValue()));
        layout.add("nodes", renamed);
        return true;
    }

    /** Adds a stage's or objective's rename to a migrations file ({@code stages} or {@code objectives}), as {@link #addMigration}. */
    public static void addInsideMigration(JsonObject migrations, String field, ResourceLocation quest, String from, String to) {
        JsonObject quests = migrations.get(field) instanceof JsonObject existing ? existing : new JsonObject();
        JsonObject renames = quests.get(quest.toString()) instanceof JsonObject existing ? existing : new JsonObject();
        for (String key : List.copyOf(renames.keySet())) {
            if (renames.get(key) instanceof JsonPrimitive value && value.getAsString().equals(from)) {
                renames.addProperty(key, to);
            }
        }
        renames.remove(to);
        for (String key : List.copyOf(renames.keySet())) {
            if (renames.get(key) instanceof JsonPrimitive value && value.getAsString().equals(key)) {
                renames.remove(key);
            }
        }
        renames.addProperty(from, to);
        quests.add(quest.toString(), renames);
        migrations.add(field, quests);
    }

    /** A renamed quest: its stages' and objectives' renames are named by its new id, as the game looks them up. */
    public static void moveInsideMigrations(JsonObject migrations, ResourceLocation from, ResourceLocation to) {
        for (String field : List.of("stages", "objectives")) {
            if (migrations.get(field) instanceof JsonObject quests && quests.remove(from.toString()) instanceof JsonObject moved) {
                JsonObject target = quests.get(to.toString()) instanceof JsonObject existing ? existing : new JsonObject();
                moved.entrySet().forEach(entry -> {
                    if (!target.has(entry.getKey())) {
                        target.add(entry.getKey(), entry.getValue());
                    }
                });
                quests.add(to.toString(), target);
            }
        }
    }

    private static int walk(ResourceLocation ownerType, JsonElement element, List<Rename> renames) {
        int changed = 0;
        if (element instanceof JsonArray array) {
            for (int i = 0; i < array.size(); i++) {
                if (array.get(i) instanceof JsonPrimitive primitive && primitive.isString()) {
                    String text = rewriteText(primitive.getAsString(), renames);
                    if (!text.equals(primitive.getAsString())) {
                        array.set(i, new JsonPrimitive(text));
                        changed++;
                    }
                } else {
                    changed += walk(ownerType, array.get(i), renames);
                }
            }
            return changed;
        }
        if (!(element instanceof JsonObject object)) {
            return 0;
        }
        if (ownerType.equals(CHAPTER) && object.has("x") && object.get("type") instanceof JsonPrimitive type && object.get("id") instanceof JsonPrimitive id) {
            // A node's place on a chapter's canvas: the type may leave out the namespace.
            String typeName = type.getAsString();
            ResourceLocation nodeType = typeName.contains(":") ? ResourceLocation.tryParse(typeName) : Storybound.id(typeName);
            return nodeType != null && rename(object, "id", nodeType, id, renames) ? 1 : 0;
        }
        boolean logic = object.has("type");
        for (String key : List.copyOf(object.keySet())) {
            JsonElement value = object.get(key);
            ResourceLocation type = referenceType(key, logic);
            if (type != null && value instanceof JsonPrimitive primitive && primitive.isString() && rename(object, key, type, primitive, renames)) {
                changed++;
            } else if (key.equals("waypoints") && ownerType.equals(StoryLinks.QUEST) && value instanceof JsonArray waypoints) {
                for (int i = 0; i < waypoints.size(); i++) {
                    ResourceLocation waypoint = waypoints.get(i) instanceof JsonPrimitive primitive && primitive.isString() ? ResourceLocation.tryParse(primitive.getAsString()) : null;
                    ResourceLocation renamed = waypoint == null ? null : renamed(StoryLinks.WAYPOINT, waypoint, renames);
                    if (renamed != null) {
                        waypoints.set(i, new JsonPrimitive(renamed.toString()));
                        changed++;
                    }
                }
            } else if (value instanceof JsonPrimitive primitive && primitive.isString()) {
                String text = rewriteText(primitive.getAsString(), renames);
                if (!text.equals(primitive.getAsString())) {
                    object.addProperty(key, text);
                    changed++;
                }
            } else {
                changed += walk(ownerType, value, renames);
            }
        }
        return changed;
    }

    /** The type a field names; {@code entry} and {@code key} only in a logic element (a story text has a key too). */
    private static @Nullable ResourceLocation referenceType(String field, boolean logic) {
        return switch (field) {
            case "quest" -> StoryLinks.QUEST;
            case "dialogue", "busy_dialogue" -> StoryLinks.DIALOGUE;
            case "document" -> StoryLinks.DOCUMENT;
            case "boss" -> StoryLinks.BOSS;
            case "entry" -> logic ? StoryLinks.CODEX : null;
            case "key" -> logic ? FLAG : null;
            default -> null;
        };
    }

    private static boolean rename(JsonObject object, String field, ResourceLocation type, JsonPrimitive value, List<Rename> renames) {
        ResourceLocation id = ResourceLocation.tryParse(value.getAsString());
        ResourceLocation renamed = id == null ? null : renamed(type, id, renames);
        if (renamed == null) {
            return false;
        }
        object.addProperty(field, renamed.toString());
        return true;
    }

    private static @Nullable ResourceLocation renamed(ResourceLocation type, ResourceLocation id, List<Rename> renames) {
        for (Rename rename : renames) {
            if (rename.type().equals(type) && rename.from().equals(id)) {
                return rename.to();
            }
        }
        return null;
    }
}
