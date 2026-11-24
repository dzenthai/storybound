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
import net.minecraft.world.phys.Vec3;
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
     * A waypoint's form in the order its author asks: its name, where it points and whether a point or an area; how it
     * looks; when it shows.
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
     * A waypoint is a target (a point, the diamond), a search area (the magnifier, a radius, its radius written by the
     * name and its edge drawn on the ground) or, in a waypoint's own file, a trail (dashes on the ground, no marker).
     * One button goes round them. Leaving a kind takes its fields away; an area starts with a radius of
     * {@link #NEW_AREA_RADIUS}, a trail empty, to be recorded ({@link #traceField}), with no marker.
     */
    void waypointKindField(List<Object> path, String kind, boolean traceOffered) {
        if (!row(LABEL_H + CONTROL_H)) {
            return;
        }
        labelRow(DevUi.text("form.waypoint_kind"));
        Button button = Button.builder(DevUi.text("form.waypoint_" + kind), b -> {
            JsonObject owner = ensureObject(path);
            String next = switch (kind) {
                case "target" -> "area";
                case "area" -> traceOffered ? "trace" : "target";
                default -> "target";
            };
            owner.remove("radius");
            owner.remove("show_radius");
            owner.remove("show_ring");
            owner.remove("trace");
            if (next.equals("area")) {
                owner.addProperty("radius", NEW_AREA_RADIUS);
            } else if (next.equals("trace")) {
                owner.remove("marker");
                owner.add("trace", new JsonArray());
            }
            if (kind.equals("trace")) {
                owner.remove("marker");
            }
            host.changed();
        }).bounds(x, rowY(LABEL_H + CONTROL_H) + LABEL_H, w, CONTROL_H).build();
        button.setTooltip(Tooltip.create(DevUi.text("form.waypoint_kind.tooltip")));
        screen.add(button);
    }

    /**
     * A recorded trail's points, on its "Points at" page: how many and how long, and a button to record them by walking
     * ({@link DevTraceRecorder}). The recording writes the points and puts the target, a point, at its end.
     */
    void traceField(List<Object> path, @Nullable JsonObject waypoint) {
        if (!row(LABEL_H + CONTROL_H)) {
            return;
        }
        labelRow(DevUi.text("form.waypoint_trace"));
        JsonArray points = waypoint != null && waypoint.get("trace") instanceof JsonArray array ? array : new JsonArray();
        Component shown = points.size() < 2 ? DevUi.text("form.trace.record")
                : DevUi.text("form.trace.summary", points.size(), Math.round(traceLength(points)));
        Button button = Button.builder(shown, b -> DevTraceRecorder.start(screen, (trail, dimension) -> {
            JsonObject owner = ensureObject(path);
            JsonArray written = new JsonArray();
            for (Vec3 point : trail) {
                written.add(position(point));
            }
            owner.add("trace", written);
            JsonObject target = new JsonObject();
            target.add("pos", position(trail.getLast()));
            target.addProperty("dimension", dimension.toString());
            owner.add("target", target);
            host.changed();
        })).bounds(x, rowY(LABEL_H + CONTROL_H) + LABEL_H, w, CONTROL_H).build();
        button.setTooltip(Tooltip.create(DevUi.text("form.trace.tooltip")));
        screen.add(button);
    }

    /** A point as a file writes it, to a tenth of a block. */
    private static JsonArray position(Vec3 point) {
        JsonArray array = new JsonArray();
        array.add(Math.round(point.x * 10) / 10.0);
        array.add(Math.round(point.y * 10) / 10.0);
        array.add(Math.round(point.z * 10) / 10.0);
        return array;
    }

    private static double traceLength(JsonArray points) {
        double length = 0;
        double[] previous = null;
        for (JsonElement element : points) {
            if (!(element instanceof JsonArray xyz) || xyz.size() != 3) {
                continue;
            }
            double[] point = {xyz.get(0).getAsDouble(), xyz.get(1).getAsDouble(), xyz.get(2).getAsDouble()};
            if (previous != null) {
                length += Math.sqrt(Math.pow(point[0] - previous[0], 2) + Math.pow(point[1] - previous[1], 2) + Math.pow(point[2] - previous[2], 2));
            }
            previous = point;
        }
        return length;
    }

    /** A waypoint's targets known in advance: a point, one entity followed. */
    private static final List<String> FIXED_KINDS = List.of("pos", "entity");
    /** And those found as the game goes: the nearest structure, the nearest mob, an NPC by its node, a boss. */
    private static final List<String> DYNAMIC_KINDS = List.of("structure", "nearest", "npc", "boss");

    /**
     * Where a waypoint points: "Rule: Known place / Dynamic", then "What" among that rule's two or three; then that
     * kind's fields, with their hints. What a switch took away comes back on switching to it again, while the node is
     * open.
     */
    void waypointTargetPage(List<Object> path, List<Node> variants, int current) {
        if (host.root().has("trace")) {
            trailTargetPage(path, variants, current);
            return;
        }
        String kind = Schemas.required(variants.get(current)).iterator().next();
        boolean dynamic = DYNAMIC_KINDS.contains(kind);
        String rule = dynamic ? "dynamic" : "fixed";
        // As every switch of the forms (an NPC's "Rule", "How many"): its label, one button "Value ▸" going round, a
        // tooltip saying what it is.
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
            // It finds only what is loaded near the player.
            text(DevUi.text("waypoint.kind.nearest.note"), DevUi.MUTED);
        } else if (kind.equals("npc") || kind.equals("boss")) {
            text(DevUi.text("waypoint.kind." + kind + ".note"), DevUi.MUTED);
        }
        objectPage(path, variants.get(current), Set.of(), null);
    }

    /** The looks a waypoint's marker offers: a target's or an area's, and a trail's. */
    private static final List<String> MARKERS = List.of("point", "event", "question");
    private static final List<String> TRAIL_MARKERS = List.of("point", "scent");

    /**
     * A waypoint's "Marker", the looks of its kind only: a target or an area the usual icon, the event's "!!!" or the
     * question's "?"; a trail its tracks (sparks from the steps) or its scent (wisps along it). One button goes round
     * them; the usual one is written by leaving the field out.
     */
    void markerField(List<Object> path, @Nullable JsonObject waypoint, boolean trail) {
        List<String> values = trail ? TRAIL_MARKERS : MARKERS;
        String current = waypoint != null && waypoint.get("marker") instanceof JsonPrimitive written ? written.getAsString() : "point";
        int index = Math.max(0, values.indexOf(current));
        String shown = trail && values.get(index).equals("point") ? "tracks" : values.get(index);
        switchRow(DevUi.text("field.marker"), DevUi.text("value.marker." + shown),
                Component.empty().append(DevUi.text("value.marker." + shown + ".tooltip")).append(" ").append(DevUi.text("form.click_to_switch")), () -> {
                    JsonObject owner = ensureObject(path);
                    String next = values.get((index + 1) % values.size());
                    if (next.equals("point")) {
                        owner.remove("marker");
                    } else {
                        owner.addProperty("marker", next);
                    }
                    host.changed();
                });
    }

    /** Whose tracks a trail can follow: the nearest of a kind, an NPC, a boss, one entity. */
    private static final List<String> TRAIL_KINDS = List.of("nearest", "npc", "boss", "entity");

    /**
     * Where a trail points: "Rule: Recorded / Dynamic". Recorded: the button that records it by walking. Dynamic: "What"
     * among {@link #TRAIL_KINDS}, then that kind's fields: the creature's real tracks. Switching keeps the other rule's
     * target, and the recorded points, to give back while the node is open.
     */
    private void trailTargetPage(List<Object> path, List<Node> variants, int current) {
        JsonObject root = host.root();
        boolean recorded = root.get("trace") instanceof JsonArray;
        String kind = Schemas.required(variants.get(current)).iterator().next();
        switchRow(DevUi.text("waypoint.rule"), DevUi.text(recorded ? "trace.rule.record" : "waypoint.rule.dynamic"), DevUi.text("trace.rule.tooltip"), () -> {
            if (recorded) {
                switchedAway.put(path + "#trace", root.get("trace").deepCopy());
                root.add("trace", new JsonObject());
                switchTargetKind(path, variants, kind, entityModes.getOrDefault(path + "#trail", TRAIL_KINDS.getFirst()));
            } else {
                entityModes.put(path + "#trail", kind);
                JsonElement walked = switchedAway.remove(path + "#trace");
                root.add("trace", walked instanceof JsonArray ? walked : new JsonArray());
                switchTargetKind(path, variants, kind, "pos");
            }
            host.changed();
        });
        if (recorded) {
            traceField(List.of(), root);
            return;
        }
        int index = TRAIL_KINDS.indexOf(kind);
        switchRow(DevUi.text("waypoint.what"), DevUi.text("waypoint.what." + kind),
                Component.empty().append(DevUi.text("waypoint.what." + kind + ".tooltip")).append(" ").append(DevUi.text("form.click_to_switch")),
                () -> switchTargetKind(path, variants, kind, TRAIL_KINDS.get((index + 1) % TRAIL_KINDS.size())));
        if (index >= 0) {
            text(DevUi.text("trace.tracks.note"), DevUi.MUTED);
            objectPage(path, variants.get(current), Set.of(), null);
        }
    }

    /** The "Points at" row of a trail: recorded or not, or whose tracks. */
    static String trailSummary(JsonObject waypoint) {
        if (waypoint.get("trace") instanceof JsonArray points) {
            return points.size() < 2 ? DevUi.str("trace.summary.empty")
                    : DevUi.str("trace.summary.recorded", points.size(), Math.round(traceLength(points)));
        }
        return DevUi.str("trace.summary.tracks", targetSummary(waypoint.get("target")));
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
            // target was { }, read as a point again, and the button seemed to do nothing). A point starts without a
            // position rather than at 0 64 0; the status line says what to fill in.
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
    void switchRow(Component label, Component value, Component tooltip, Runnable next) {
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
    static final Set<String> ADD_WAYPOINT_TARGET = Set.of("pos", "dimension", "follow", "structure", "search_radius", "npc", "boss", "nearest", "range");
    /** add_waypoint's places known in advance: a point, the one talked to (the context entity), where it stands now. */
    private static final List<String> ADD_FIXED_KINDS = List.of("pos", "follow", "here");
    /** Each kind's fields, in the order asked: the dimension before the structure it lists. */
    private static final Map<String, List<String>> ADD_KIND_FIELDS = Map.of(
            "pos", List.of("pos", "dimension"),
            "follow", List.of(),
            "here", List.of(),
            "structure", List.of("dimension", "structure", "search_radius"),
            "nearest", List.of("nearest", "range"),
            "npc", List.of("npc", "range"),
            "boss", List.of("boss", "range"));

    /** Which kind an add_waypoint is, as its run reads it: a point, a structure, an NPC, the nearest mob, the one talked to, else here. */
    private static String addWaypointKind(@Nullable JsonObject action) {
        if (action == null) {
            return "here";
        }
        for (String kind : List.of("pos", "structure", "npc", "boss", "nearest")) {
            if (action.has(kind)) {
                return kind;
            }
        }
        return flag(action, "follow", false) ? "follow" : "here";
    }

    /**
     * Where add_waypoint points: "Rule" and "What" as a waypoint's target page has them, over the action's own fields
     * (it can also follow the one talked to, or mark where it stands now), then that kind's fields and hints.
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
        } else if (kind.equals("npc") || kind.equals("boss")) {
            text(DevUi.text("waypoint.kind." + kind + ".note"), DevUi.MUTED);
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
                case "structure", "npc", "boss" -> action.addProperty(to, "");
                default -> { }
            }
        }
        host.changed();
    }

    /**
     * A waypoint's target in words: "point 120, 64, -30", "· the Nether" when not the overworld; "nearest village";
     * "nearest Wolf · within 64"; "entity …a1b2".
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
        if (target.get("boss") instanceof JsonPrimitive boss) {
            String id = boss.getAsString();
            return DevUi.str("waypoint.target.boss", id.substring(id.indexOf(':') + 1));
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
