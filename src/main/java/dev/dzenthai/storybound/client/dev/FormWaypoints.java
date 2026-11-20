package dev.dzenthai.storybound.client.dev;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.dzenthai.storybound.client.dev.Schemas.Node;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * The waypoint's form ({@link FormView}): which fields show, where it points (a point or something found), and
 * the {@code add_waypoint} action's target.
 * <p>
 * One layer of the form, split by topic, each seeing the ones below it; the rest of the editor uses FormView:
 * FormCore &larr; FormPickers &larr; FormInputs &larr; FormEntities &larr; FormNpc &larr; FormWaypoints &larr; FormDialogues &larr; FormStory &larr; FormView.
 */
abstract class FormWaypoints extends FormNpc {
    FormWaypoints(Host host) {
        super(host);
    }

    /**
     * A waypoint's form in the order its author asks (October 29, 2026; before, the file's order: the colour before when
     * it shows, point or area at the very bottom, the stage even without a quest): its name, where it points and whether
     * a point or an area; how it looks; when it shows.
     */
    static final Map<String, String> WAYPOINT_GROUPS = Map.of(
            "name", "waypoint.group.where",
            "color", "waypoint.group.look",
            "quest", "waypoint.group.when");

    /** The stage only with a quest (or when the file still names one). */
    static boolean waypointFieldShown(String name, @Nullable JsonObject waypoint) {
        return !name.equals("stage") || waypoint != null && (waypoint.get("quest") instanceof JsonPrimitive quest && !quest.getAsString().isBlank()
                || waypoint.has("stage"));
    }

    /**
     * A waypoint is a target (a point, the diamond) or a search area (the ring icon, a radius, its radius written by
     * the name and its edge drawn on the ground). Switching to a target takes the area's fields away; to an area gives
     * it a radius of {@link #NEW_AREA_RADIUS}.
     */
    void waypointKindField(List<Object> path, boolean area) {
        if (!row(LABEL_H + CONTROL_H)) {
            return;
        }
        labelRow(DevUi.text("form.waypoint_kind"));
        Component shown = DevUi.text(area ? "form.waypoint_area" : "form.waypoint_target");
        Button button = Button.builder(shown, b -> {
            JsonObject owner = ensureObject(path);
            if (area) {
                owner.remove("radius");
                owner.remove("show_radius");
                owner.remove("show_ring");
            } else {
                owner.addProperty("radius", NEW_AREA_RADIUS);
            }
            host.changed();
        }).bounds(x, rowY(LABEL_H + CONTROL_H) + LABEL_H, w, CONTROL_H).build();
        button.setTooltip(Tooltip.create(DevUi.text("form.waypoint_kind.tooltip")));
        screen.add(button);
    }

    /** A waypoint's targets known in advance: a point, one entity followed. */
    private static final List<String> FIXED_KINDS = List.of("pos", "entity");
    /** And those found as the game goes: the nearest structure, the nearest mob, an NPC by its node. */
    private static final List<String> DYNAMIC_KINDS = List.of("structure", "nearest", "npc");

    /**
     * Where a waypoint points (November 9, 2026, as an NPC's "Who it is"): "Rule: Known place / Dynamic", then "What"
     * among that rule's two or three (before, one button went round all five, and the author did not find the structure
     * or the mob behind it); then that kind's fields, with their hints. What a switch took
     * away comes back on switching to it again, while the node is open.
     */
    void waypointTargetPage(List<Object> path, List<Node> variants, int current) {
        String kind = Schemas.required(variants.get(current)).iterator().next();
        boolean dynamic = DYNAMIC_KINDS.contains(kind);
        String rule = dynamic ? "dynamic" : "fixed";
        // As every switch of the forms (an NPC's "Rule", "How many"): its label, one button "Value ▸" going round, a
        // tooltip saying what it is (November 9, 2026: a row of radio buttons first, which no other form had).
        switchRow(DevUi.text("waypoint.rule"), DevUi.text("waypoint.rule." + rule), DevUi.text("waypoint.rule.tooltip"), () -> {
            String other = dynamic ? "fixed" : "dynamic";
            List<String> kinds = other.equals("dynamic") ? DYNAMIC_KINDS : FIXED_KINDS;
            switchTargetKind(path, variants, kind, entityModes.getOrDefault(path + "#rule:" + other, kinds.getFirst()));
        });
        List<String> kinds = dynamic ? DYNAMIC_KINDS : FIXED_KINDS;
        switchRow(DevUi.text("waypoint.what"), DevUi.text("waypoint.what." + kind),
                Component.empty().append(DevUi.text("waypoint.what." + kind + ".tooltip")).append(" ").append(DevUi.text("form.click_to_switch")),
                () -> switchTargetKind(path, variants, kind, kinds.get((kinds.indexOf(kind) + 1) % kinds.size())));
        if (kind.equals("nearest")) {
            // It finds only what is loaded near the player (November 9, 2026: a quest pointing at "the nearest alchemist"
            // from its start showed nothing in a new world).
            text(DevUi.text("waypoint.kind.nearest.note"), DevUi.MUTED);
        } else if (kind.equals("npc")) {
            text(DevUi.text("waypoint.kind.npc.note"), DevUi.MUTED);
        }
        objectPage(path, variants.get(current), Set.of(), null);
    }

    /** Puts the target of this kind in place of the one written, keeping that one to give back. */
    private void switchTargetKind(List<Object> path, List<Node> variants, String from, String to) {
        JsonElement now = get(path);
        if (now != null) {
            switchedAway.put(path + "#kind:" + from, now.deepCopy());
        }
        entityModes.put(path + "#rule:" + (DYNAMIC_KINDS.contains(to) ? "dynamic" : "fixed"), to);
        JsonElement back = switchedAway.remove(path + "#kind:" + to);
        Node variant = variants.stream().filter(candidate -> Schemas.required(candidate).contains(to)).findFirst().orElse(null);
        if (back == null && variant == null) {
            return;
        }
        JsonElement next = back;
        if (next == null) {
            // Its own field written empty, so the kind is known (the skeleton leaves out a field with no default: the
            // target was {}, read as a point again, and the button seemed to do nothing; November 9, 2026). A point
            // starts without a position rather than at 0 64 0; the status line says what to fill in.
            JsonObject skeleton = Schemas.skeleton(variant);
            skeleton.add(to, to.equals("pos") ? new JsonArray() : to.equals("nearest") ? new JsonObject() : new JsonPrimitive(""));
            next = skeleton;
        }
        put(ensureParent(path), path, path.size() - 1, next);
        host.changed();
    }

    /**
     * A switch as every form has it (an NPC's "Rule", "How many"): its label above, one button "Value ▸" across the row
     * that goes to the next value on a click, and a tooltip saying what the value is.
     */
    private void switchRow(Component label, Component value, Component tooltip, Runnable next) {
        if (!row(LABEL_H + CONTROL_H)) {
            return;
        }
        labelRow(label);
        Button button = Button.builder(Component.empty().append(value).append(" ▸"), b -> next.run())
                .bounds(x, rowY(LABEL_H + CONTROL_H) + LABEL_H, w, CONTROL_H).build();
        button.setTooltip(Tooltip.create(tooltip));
        screen.add(button);
    }

    /** add_waypoint's fields saying where it points, drawn together by {@link #addWaypointTarget}. */
    static final Set<String> ADD_WAYPOINT_TARGET = Set.of("pos", "dimension", "follow", "structure", "search_radius", "npc", "nearest", "range");
    /** add_waypoint's places known in advance: a point, the one talked to (the context entity), where it stands now. */
    private static final List<String> ADD_FIXED_KINDS = List.of("pos", "follow", "here");
    /** Each kind's fields, in the order asked: the dimension before the structure it lists. */
    private static final Map<String, List<String>> ADD_KIND_FIELDS = Map.of(
            "pos", List.of("pos", "dimension"),
            "follow", List.of(),
            "here", List.of(),
            "structure", List.of("dimension", "structure", "search_radius"),
            "nearest", List.of("nearest", "range"),
            "npc", List.of("npc", "range"));

    /** Which kind an add_waypoint is, as its run reads it: a point, a structure, an NPC, the nearest mob, the one talked to, else here. */
    private static String addWaypointKind(@Nullable JsonObject action) {
        if (action == null) {
            return "here";
        }
        for (String kind : List.of("pos", "structure", "npc", "nearest")) {
            if (action.has(kind)) {
                return kind;
            }
        }
        return flag(action, "follow", false) ? "follow" : "here";
    }

    /**
     * Where add_waypoint points (November 9, 2026): "Rule" and "What" as a waypoint's target page has them, over the
     * action's own fields (it can also follow the one talked to, or mark where it stands now), then that kind's fields
     * and hints. Before, every field at once, and no NPC nor mob: a story could not point at Miron or "the nearest
     * alchemist" from an event or a dialogue.
     */
    void addWaypointTarget(List<Object> path, Node schema, @Nullable JsonObject action) {
        String kind = addWaypointKind(action);
        boolean dynamic = DYNAMIC_KINDS.contains(kind);
        switchRow(DevUi.text("waypoint.rule"), DevUi.text("waypoint.rule." + (dynamic ? "dynamic" : "fixed")), DevUi.text("waypoint.rule.tooltip"), () -> {
            String other = dynamic ? "fixed" : "dynamic";
            List<String> kinds = other.equals("dynamic") ? DYNAMIC_KINDS : ADD_FIXED_KINDS;
            switchAddWaypointKind(path, kind, entityModes.getOrDefault(path + "#rule:" + other, kinds.getFirst()));
        });
        List<String> kinds = dynamic ? DYNAMIC_KINDS : ADD_FIXED_KINDS;
        switchRow(DevUi.text("waypoint.what"), DevUi.text("waypoint.what." + kind),
                Component.empty().append(DevUi.text("waypoint.what." + kind + ".tooltip")).append(" ").append(DevUi.text("form.click_to_switch")),
                () -> switchAddWaypointKind(path, kind, kinds.get((kinds.indexOf(kind) + 1) % kinds.size())));
        if (kind.equals("nearest")) {
            text(DevUi.text("waypoint.kind.nearest.note"), DevUi.MUTED);
        } else if (kind.equals("npc")) {
            text(DevUi.text("waypoint.kind.npc.note"), DevUi.MUTED);
        } else if (kind.equals("follow") || kind.equals("here")) {
            text(DevUi.text("waypoint.what." + kind + ".note"), DevUi.MUTED);
        }
        Map<String, Node> fields = Schemas.properties(schema);
        for (String name : ADD_KIND_FIELDS.get(kind)) {
            if (fields.containsKey(name)) {
                field(path, name, fields.get(name), false, schema);
            }
        }
    }

    /** Puts away the target fields written and writes this kind's, given back when switched to again. */
    private void switchAddWaypointKind(List<Object> path, String from, String to) {
        JsonObject action = ensureObject(path);
        JsonObject away = new JsonObject();
        for (String name : ADD_WAYPOINT_TARGET) {
            JsonElement value = action.remove(name);
            if (value != null) {
                away.add(name, value);
            }
        }
        switchedAway.put(path + "#akind:" + from, away);
        entityModes.put(path + "#rule:" + (DYNAMIC_KINDS.contains(to) ? "dynamic" : "fixed"), to);
        JsonElement back = switchedAway.remove(path + "#akind:" + to);
        if (back instanceof JsonObject restored && !restored.isEmpty()) {
            restored.entrySet().forEach(entry -> action.add(entry.getKey(), entry.getValue()));
        } else {
            // Its own field written empty, so the kind is known; the one talked to is a switch on, here is nothing.
            switch (to) {
                case "pos" -> action.add("pos", new JsonArray());
                case "follow" -> action.addProperty("follow", true);
                case "nearest" -> action.add("nearest", new JsonObject());
                case "structure", "npc" -> action.addProperty(to, "");
                default -> { }
            }
        }
        host.changed();
    }

    /**
     * A waypoint's target in words: "point 120, 64, -30", "· the Nether" when not the overworld; "nearest village";
     * "nearest Wolf · within 64"; "entity …a1b2". Before, only the kind ("Position").
     */
    static String targetSummary(@Nullable JsonElement value) {
        if (!(value instanceof JsonObject target) || target.isEmpty()) {
            return "—";
        }
        String dimension = target.get("dimension") instanceof JsonPrimitive named && !named.getAsString().equals("minecraft:overworld")
                ? " · " + dimensionName(named.getAsString()) : "";
        if (target.get("pos") instanceof JsonArray pos && pos.size() == 3) {
            List<String> parts = new ArrayList<>();
            pos.forEach(part -> parts.add(format(part.getAsDouble())));
            return DevUi.str("waypoint.target.pos", String.join(", ", parts)) + dimension;
        }
        if (target.get("entity") instanceof JsonPrimitive uuid) {
            String id = uuid.getAsString();
            return DevUi.str("waypoint.target.entity", id.length() > 4 ? id.substring(id.length() - 4) : id);
        }
        if (target.get("structure") instanceof JsonPrimitive structure) {
            String id = structure.getAsString();
            return DevUi.str("waypoint.target.structure", id.substring(id.indexOf(':') + 1).replace('_', ' ')) + dimension;
        }
        if (target.get("npc") instanceof JsonPrimitive npc) {
            String id = npc.getAsString();
            return DevUi.str("waypoint.target.npc", id.substring(id.indexOf(':') + 1));
        }
        if (target.has("nearest")) {
            JsonElement nearest = target.get("nearest");
            // A type, a #tag or a list reads as a selector of only a type.
            JsonObject selector = nearest instanceof JsonObject written ? written : new JsonObject();
            if (!(nearest instanceof JsonObject)) {
                selector.add("type", nearest);
            }
            String what = entitySummary(selector);
            int range = target.get("range") instanceof JsonPrimitive number && number.isNumber() ? number.getAsInt() : 64;
            return DevUi.str("waypoint.target.nearest", what, range);
        }
        return "—";
    }
}
