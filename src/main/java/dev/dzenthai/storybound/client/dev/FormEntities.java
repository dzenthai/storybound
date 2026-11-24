package dev.dzenthai.storybound.client.dev;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.dzenthai.storybound.client.dev.Catalog.Entry;
import dev.dzenthai.storybound.client.dev.Schemas.Kind;
import dev.dzenthai.storybound.client.dev.Schemas.Node;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * The form's entities ({@link FormView}): selectors, a mob's variants, its place and its data.
 * <p>
 * One layer of the form, split by topic, each seeing the ones below it; the rest of the editor uses FormView:
 * FormCore &larr; FormPickers &larr; FormInputs &larr; FormEntities &larr; FormNpc &larr; FormWaypoints &larr; FormDialogues &larr; FormStory &larr; FormView.
 */
abstract class FormEntities extends FormInputs {
    FormEntities(Host host) {
        super(host);
    }

    /**
     * A dynamic NPC's variants: a row each, "Villager · Smith, male ▸" opening its page ({@link #variantPage}), ✕
     * taking it out; "+ Add" asks the kind first and opens the new one. One is written alone, several as a list, as the
     * game reads them; the same kind as often as wanted.
     *
     * @param one a "one" NPC: a new variant that can be young is written grown-up
     */
    void variantRows(List<Object> entityPath, @Nullable Node selectorNode, boolean one) {
        JsonElement entity = get(entityPath);
        List<JsonObject> variants = SelectorEdits.variants(entity);
        if (variants.size() == 1 && variants.getFirst().isEmpty()) {
            // An empty one (an older switch of the rule): no variant yet; "+ Add" takes its place.
            variants = List.of();
        }
        if (row(LABEL_H)) {
            labels.add(new Label(DevUi.text("npc.variants"), x, rowY(LABEL_H), DevUi.LABEL));
        }
        for (int i = 0; i < variants.size(); i++) {
            int index = i;
            List<Object> at = entity instanceof JsonArray ? with(entityPath, i) : entityPath;
            if (!row(CONTROL_H)) {
                continue;
            }
            int rowTop = rowY(CONTROL_H);
            String line = variantSummary(variants.get(i));
            Component shown = Component.literal(screen.font().plainSubstrByWidth(line, w - 36) + (screen.font().width(line) > w - 36 ? "…" : "") + " ▸");
            screen.add(Button.builder(shown, b -> {
                if (selectorNode != null) {
                    push(at, selectorNode, DevUi.str("npc.variant"), false);
                }
            }).tooltip(Tooltip.create(Component.literal(line))).bounds(x, rowTop, w - 20, CONTROL_H).build());
            screen.add(removeButton(rowTop, DevUi.text("form.remove"), b -> {
                JsonElement next = SelectorEdits.withoutVariant(host.root().get("entity"), index);
                if (next == null) {
                    host.root().remove("entity");
                } else {
                    host.root().add("entity", next);
                }
                host.changed();
            }));
        }
        if (variants.isEmpty()) {
            text(DevUi.text("npc.variants.none"), DevUi.MUTED);
        }
        if (row(CONTROL_H)) {
            Picker kinds = new Picker(Catalog.Kind.MOB, null, List.of(), DevUi.text("entity.pick"));
            screen.add(Button.builder(Component.literal("+ ").append(DevUi.text("form.add")), b -> pick(kinds, entry -> {
                JsonObject variant = new JsonObject();
                variant.addProperty("type", entry.id());
                if (one && MobFields.canBeYoung(kindsOf(variant))) {
                    variant.addProperty("baby", false);
                }
                JsonElement next = SelectorEdits.withVariant(host.root().get("entity"), variant);
                host.root().add("entity", next);
                host.changed();
                if (selectorNode != null) {
                    // Straight to it: what this variant is like is asked next.
                    push(next.isJsonArray() ? with(entityPath, next.getAsJsonArray().size() - 1) : entityPath, selectorNode, DevUi.str("npc.variant"), false);
                }
            })).tooltip(Tooltip.create(DevUi.text("npc.variants.add"))).bounds(x, rowY(CONTROL_H), w, CONTROL_H).build());
        }
    }

    /** "Villager · Smith, male · The Nether": the kind, the values of its data, where it is; a dash with no kind. */
    private static String variantSummary(JsonObject selector) {
        List<String> parts = new ArrayList<>();
        String kinds = entitySummary(selector.has("type") ? typeOnly(selector) : null);
        parts.add(kinds.equals("—") ? DevUi.str("npc.variant.no_kind") : kinds);
        List<String> data = new ArrayList<>();
        if (selector.get("data") instanceof JsonObject written) {
            written.entrySet().forEach(field -> {
                for (JsonElement value : field.getValue().isJsonArray() ? field.getValue().getAsJsonArray().asList() : List.of(field.getValue())) {
                    data.add(writtenLabel(value));
                }
            });
        }
        if (selector.get("baby") instanceof JsonPrimitive baby && baby.isBoolean()) {
            data.add(DevUi.str(baby.getAsBoolean() ? "npc.age.yes" : "npc.age.no"));
        }
        if (!data.isEmpty()) {
            parts.add(String.join(", ", data));
        }
        List<String> where = new ArrayList<>();
        if (selector.get("dimension") instanceof JsonPrimitive dimension) {
            where.add(dimensionName(dimension.getAsString()));
        }
        for (String field : List.of("biome", "structure")) {
            List<String> ids = stringsOf(selector.get(field) instanceof JsonPrimitive one ? asList(one) : selector.get(field));
            ids.forEach(id -> where.add(DevUi.humanize(id.substring(id.indexOf(':') + 1))));
        }
        if (!where.isEmpty()) {
            parts.add(String.join(", ", where));
        }
        return String.join(" · ", parts);
    }

    private static JsonObject typeOnly(JsonObject selector) {
        JsonObject only = new JsonObject();
        only.add("type", selector.get("type"));
        return only;
    }

    /** A value written in {@code data} as the author reads it: a game id by its name, yes / no, else as written. */
    private static String writtenLabel(JsonElement value) {
        if (value instanceof JsonPrimitive primitive && primitive.isBoolean()) {
            return DevUi.yesNo(primitive.getAsBoolean());
        }
        String text = value.getAsString();
        ResourceLocation id = text.contains(":") ? ResourceLocation.tryParse(text) : null;
        return id != null ? DevUi.humanize(id.getPath()) : text;
    }

    /**
     * One variant of a mob (an NPC's, a waypoint's nearest mob, a boss, an observed creature), as the author laid it
     * out: who, the kind; where, the base every mob has (dimension → biome → structure, each list only what fits the
     * one before); its data, read from the mob itself. Older parts (a profession, an outfit, a variant, "in a village",
     * SNBT) show as a line with ✕. An NPC's variant has no tag: "only those tagged" is for the others.
     */
    private void variantPage(List<Object> path, boolean tagFilter) {
        JsonObject selector = objectAt(path);
        if (selector != null && (selector.has("uuid") || selector.has("name"))) {
            String known = selector.has("uuid") ? DevUi.str("entity.summary.uuid", tail(selector.get("uuid").getAsString()))
                    : DevUi.str("entity.summary.name", selector.get("name").getAsString());
            text(DevUi.text("npc.known_from_file", known), DevUi.MUTED);
        }
        // Who: the kind, and for the others than an NPC's only those tagged; no heading over them, the page's name says it.
        variantKind(path, selector, !tagFilter);
        if (tagFilter) {
            tagFilterRow(path, selector);
        }
        subgroup(DevUi.text("variant.where"));
        placeRows(path, true);
        subgroup(DevUi.text("mobdata"));
        mobDataRows(path, selector);
        olderRows(path, selector);
    }

    /** "Only those tagged (optional)": a tag the story put on them (spawn_entity, /tag). */
    private void tagFilterRow(List<Object> path, @Nullable JsonObject selector) {
        if (row(LABEL_H + CONTROL_H)) {
            labelRow(DevUi.text("entity.tag_filter"));
            EditBox tag = new EditBox(screen.font(), x, rowY(LABEL_H + CONTROL_H) + LABEL_H, w, CONTROL_H - 2, DevUi.text("entity.tag_filter"));
            tag.setMaxLength(256);
            tag.setValue(selector != null && selector.has("tag") ? selector.get("tag").getAsString() : "");
            tag.setResponder(text -> {
                JsonObject target = ensureObject(path);
                if (text.isBlank()) {
                    target.remove("tag");
                } else {
                    target.addProperty("tag", text.trim());
                }
                host.edited();
            });
            tag.setTooltip(Tooltip.create(DevUi.text("entity.tag_filter.tooltip")));
            screen.add(tag);
        }
    }

    /**
     * A variant's kind: one. One button, "Villager ▸", picking another in its place; another kind is another variant.
     * An older file with several kinds in one variant shows them as rows with ✕, nothing to add.
     */
    private void variantKind(List<Object> path, @Nullable JsonObject selector, boolean npc) {
        Picker kinds = new Picker(Catalog.Kind.MOB, null, List.of(), DevUi.text("entity.pick"));
        List<String> ids = selector == null ? List.of() : stringsOf(selector.get("type") instanceof JsonPrimitive one ? asList(one) : selector.get("type"));
        if (ids.size() > 1) {
            idListRows(path, "type", kinds, kinds, DevUi.text("npc.kind"), ids.size(), id -> null);
            text(DevUi.text("npc.variant.many_kinds"), DevUi.MUTED);
            return;
        }
        if (!row(LABEL_H + CONTROL_H)) {
            return;
        }
        labelRow(DevUi.text("npc.kind"));
        String shown = ids.isEmpty() ? DevUi.str("npc.variant.pick_kind") : idName(ids.getFirst(), kinds);
        Button button = Button.builder(Component.literal(shown + " ▸"), b -> pick(kinds, entry -> writeIds(path, "type", List.of(entry.id()))))
                .bounds(x, rowY(LABEL_H + CONTROL_H) + LABEL_H, w, CONTROL_H).build();
        button.setTooltip(Tooltip.create(DevUi.text(npc ? "npc.variant.kind.tooltip" : "entity.kind.tooltip")));
        screen.add(button);
    }

    /**
     * Where: dimension → biome → structure, each list holding only what fits the parts before, as the world's map the
     * server sent says ({@link WorldMap}; mods' alike). A part that no longer fits (the dimension changed) is not taken
     * out: it shows red, with why, and ✕. The same for a mob's variant and for the player's condition "location", whose
     * structure is one.
     */
    void placeRows(List<Object> path, boolean manyStructures) {
        JsonObject owner = objectAt(path);
        String dimension = owner != null && owner.get("dimension") instanceof JsonPrimitive written ? written.getAsString() : null;
        List<String> biomes = owner == null ? List.of() : stringsOf(owner.get("biome") instanceof JsonPrimitive one ? asList(one) : owner.get("biome"));
        List<String> structures = owner == null ? List.of() : stringsOf(owner.get("structure") instanceof JsonPrimitive one ? asList(one) : owner.get("structure"));
        if (row(LABEL_H + CONTROL_H)) {
            labelRow(DevUi.text("field.dimension"));
            boolean unknown = dimension != null && WorldMap.known() && !WorldMap.dimensions().contains(dimension);
            String name = dimension == null ? DevUi.str("place.any_dimension") : dimensionName(dimension);
            List<Entry> choices = new ArrayList<>();
            choices.add(new Entry("", DevUi.str("place.any_dimension"), ItemStack.EMPTY));
            choices.addAll(Catalog.dimensions());
            Button button = Button.builder(unknown ? Component.literal("⚠ " + name).withColor(DevUi.ERROR) : Component.literal(name + " ▸"),
                    b -> pick(Picker.rows(choices, DevUi.text("form.pick_title.dimension"), DevUi.text("pick.empty")), entry -> {
                        if (entry.id().isEmpty()) {
                            ensureObject(path).remove("dimension");
                        } else {
                            ensureObject(path).addProperty("dimension", entry.id());
                        }
                        host.changed();
                    })).bounds(x, rowY(LABEL_H + CONTROL_H) + LABEL_H, w, CONTROL_H).build();
            button.setTooltip(Tooltip.create(unknown ? DevUi.text("place.unknown_dimension", dimension) : DevUi.text("place.dimension.tooltip")));
            screen.add(button);
        }
        String in = dimension == null ? null : dimensionName(dimension);
        idListRows(path, "biome", Picker.rows(Catalog.biomesFitting(dimension, structures), DevUi.text("form.pick_title.biome"), DevUi.text("place.no_biomes")),
                new Picker(Catalog.Kind.BIOME, null, List.of(), DevUi.text("form.pick_title.biome")), DevUi.text("field.biome"), 0,
                id -> WorldMap.biomeFits(id, dimension) ? null : DevUi.text("place.not_in", in));
        idListRows(path, "structure", Picker.rows(Catalog.structuresFitting(dimension, biomes), DevUi.text("form.pick_title.structure"), DevUi.text("place.no_structures")),
                new Picker(Catalog.Kind.STRUCTURE, null, List.of(), DevUi.text("form.pick_title.structure")), DevUi.text("field.structure"),
                manyStructures ? 0 : 1, id -> !WorldMap.structureFits(id, dimension, null) ? DevUi.text("place.not_in", in)
                        : !WorldMap.structureFits(id, null, biomes) ? DevUi.text("place.not_in_biomes") : null);
    }

    /**
     * A mob's data: a row per field the kind keeps ({@link MobFields}), "Any" or the values chosen, any of which
     * matches; a value met is picked from the list (a click on a chosen one takes it out), one not met yet typed
     * ("Other value..."). "Age" when the kind can be young. A field written that the kind is not seen to have shows
     * with ✕.
     */
    private void mobDataRows(List<Object> path, @Nullable JsonObject selector) {
        List<net.minecraft.world.entity.EntityType<?>> kinds = kindsOf(selector);
        if (kinds.isEmpty()) {
            text(DevUi.text("mobdata.no_kind"), DevUi.MUTED);
            return;
        }
        boolean young = MobFields.canBeYoung(kinds);
        if (young || selector != null && selector.has("baby")) {
            npcTriState(path, "baby", "npc.age", selector);
        }
        List<MobFields.Field> fields = MobFields.of(kinds, young, screen::rebuild);
        Set<String> shown = new java.util.HashSet<>();
        fields.forEach(field -> shown.add(field.path()));
        // Words first (a profession, a type, a coat) and what is set; numbers and yes / no folded under "More fields",
        // unless there are no words at all (raiders: all flags), when nothing is folded.
        boolean fold = fields.stream().anyMatch(MobFields.Field::words);
        List<MobFields.Field> first = !fold ? fields
                : fields.stream().filter(field -> field.words() || !SelectorEdits.dataValues(selector, field.path()).isEmpty()).toList();
        List<MobFields.Field> more = fields.stream().filter(field -> !first.contains(field)).toList();
        for (MobFields.Field field : first) {
            dataFieldRow(path, selector, field);
        }
        if (!more.isEmpty() && row(LABEL_H + CONTROL_H)) {
            labelRow(DevUi.text("mobdata.more"));
            String names = String.join(" · ", more.stream().map(MobFields.Field::label).toList());
            Component summary = Component.literal(screen.font().plainSubstrByWidth(names, w - 16) + (screen.font().width(names) > w - 16 ? "…" : "") + " ▸");
            screen.add(Button.builder(summary, b -> push(with(path, MORE_DATA), Schemas.logic("condition"), DevUi.str("mobdata.more"), false))
                    .tooltip(Tooltip.create(Component.literal(names).append("\n").append(DevUi.text("mobdata.more.tooltip"))))
                    .bounds(x, rowY(LABEL_H + CONTROL_H) + LABEL_H, w, CONTROL_H).build());
        }
        if (selector != null && selector.get("data") instanceof JsonObject data) {
            for (String written : new ArrayList<>(data.keySet())) {
                if (shown.contains(written) || !row(CONTROL_H)) {
                    continue;
                }
                int top = rowY(CONTROL_H);
                String values = String.join(", ", SelectorEdits.dataValues(selector, written).stream().map(FormEntities::writtenLabel).toList());
                String line = MobFields.label(written, "") + " = " + values + " · " + DevUi.str("mobdata.not_seen");
                screen.add(Button.builder(Component.literal(screen.font().plainSubstrByWidth(line, w - 30)).withColor(DevUi.ERROR), b -> { })
                        .tooltip(Tooltip.create(Component.literal(written + " = " + values))).bounds(x, top, w - 20, CONTROL_H).build());
                screen.add(removeButton(top, DevUi.text("form.remove"), b -> {
                    SelectorEdits.clearData(ensureObject(path), written);
                    host.changed();
                }));
            }
        }
        if (fields.isEmpty() && !young) {
            text(DevUi.text("mobdata.nothing"), DevUi.MUTED);
        }
        if (kinds.stream().anyMatch(kind -> !MobFields.probed(kind))) {
            text(DevUi.text("mobdata.no_probe"), DevUi.MUTED);
        }
    }

    /** The page of a mob's folded fields, under its variant: not a field of the file, the form's own. */
    static final String MORE_DATA = "$more_data";

    /** "More fields": the numbers and yes / no of a mob's data, nothing set among them yet; a field set goes back up. */
    void moreDataPage(List<Object> path) {
        JsonObject selector = objectAt(path);
        List<net.minecraft.world.entity.EntityType<?>> kinds = kindsOf(selector);
        List<MobFields.Field> fields = MobFields.of(kinds, MobFields.canBeYoung(kinds), screen::rebuild);
        text(DevUi.text("mobdata.more.note"), DevUi.MUTED);
        for (MobFields.Field field : fields) {
            if (!field.words()) {
                dataFieldRow(path, selector, field);
            }
        }
    }

    /** One field of a mob's data: its name, then "Any ▸" or the values chosen, opening the values to pick from. */
    private void dataFieldRow(List<Object> path, @Nullable JsonObject selector, MobFields.Field field) {
        List<JsonPrimitive> chosen = SelectorEdits.dataValues(selector, field.path());
        List<String> names = new ArrayList<>();
        for (JsonPrimitive value : chosen) {
            names.add(field.values().values().stream().filter(tag -> MobFields.holds(value, tag)).findFirst()
                    .map(tag -> MobFields.valueLabel(tag, field.flag())).orElse(writtenLabel(value)));
        }
        String typingKey = path + "#" + field.path();
        if (row(LABEL_H + CONTROL_H)) {
            labels.add(new Label(Component.literal(field.label()), x, rowY(LABEL_H + CONTROL_H), DevUi.LABEL));
            List<Entry> choices = new ArrayList<>();
            choices.add(new Entry("", DevUi.str("mobdata.any"), ItemStack.EMPTY));
            field.values().forEach((text, tag) -> {
                boolean on = chosen.stream().anyMatch(value -> MobFields.holds(value, tag));
                choices.add(new Entry(text, (on ? "✓ " : "") + MobFields.valueLabel(tag, field.flag()), ItemStack.EMPTY));
            });
            // Values written that none met is (typed as "Other value..."): listed too, chosen, so each can be taken out
            // alone.
            for (int i = 0; i < chosen.size(); i++) {
                JsonPrimitive value = chosen.get(i);
                if (field.values().values().stream().noneMatch(tag -> MobFields.holds(value, tag))) {
                    choices.add(new Entry(WRITTEN_VALUE + i, "✓ " + writtenLabel(value), ItemStack.EMPTY));
                }
            }
            if (!field.flag()) {
                choices.add(new Entry(OTHER_VALUE, DevUi.str("mobdata.other"), ItemStack.EMPTY));
            }
            String value = chosen.isEmpty() ? DevUi.str("mobdata.any") : String.join(", ", names);
            screen.add(Button.builder(Component.literal(screen.font().plainSubstrByWidth(value, w - 16) + " ▸"),
                            b -> pick(Picker.rows(choices, Component.literal(field.label()), DevUi.text("npc.pick_empty")), entry -> {
                                if (entry.id().equals(OTHER_VALUE)) {
                                    typingOther.add(typingKey);
                                    screen.rebuild();
                                    return;
                                }
                                // A value picked instead: the box for one not met yet closes.
                                typingOther.remove(typingKey);
                                JsonObject target = ensureObject(path);
                                if (entry.id().isEmpty()) {
                                    SelectorEdits.clearData(target, field.path());
                                } else if (entry.id().startsWith(WRITTEN_VALUE)) {
                                    JsonPrimitive written = chosen.get(Integer.parseInt(entry.id().substring(WRITTEN_VALUE.length())));
                                    SelectorEdits.toggleData(target, field.path(), written, written::equals);
                                } else {
                                    net.minecraft.nbt.Tag tag = field.values().get(entry.id());
                                    SelectorEdits.toggleData(target, field.path(), MobFields.toJson(tag, field.flag()), written -> MobFields.holds(written, tag));
                                }
                                host.changed();
                            }))
                    .tooltip(Tooltip.create(Component.literal(field.path()).append("\n").append(DevUi.text("mobdata.tooltip"))))
                    .bounds(x, rowY(LABEL_H + CONTROL_H) + LABEL_H, w, CONTROL_H).build());
        }
        if (typingOther.contains(typingKey) && row(CONTROL_H)) {
            // A value not met yet: typed, of the type of the values met (a text, a whole number), added with ✓; ✕ closes
            // the box with nothing written.
            int top = rowY(CONTROL_H);
            EditBox box = new EditBox(screen.font(), x, top + 1, w - 42, CONTROL_H - 2, Component.literal(field.label()));
            box.setMaxLength(64);
            box.setHint(DevUi.text("mobdata.other_hint").copy().withStyle(style -> style.withColor(DevUi.HINT)));
            box.setResponder(text -> box.setTextColor(text.isBlank() || MobFields.typed(text, field.values().values()) != null ? DevUi.TEXT : DevUi.ERROR));
            screen.add(box);
            screen.add(Button.builder(Component.literal("✓"), b -> {
                net.minecraft.nbt.Tag typed = MobFields.typed(box.getValue(), field.values().values());
                if (typed == null) {
                    screen.status(DevUi.text("mobdata.other_bad"), DevUi.WARN);
                    return;
                }
                typingOther.remove(typingKey);
                JsonObject target = ensureObject(path);
                if (SelectorEdits.dataValues(target, field.path()).stream().noneMatch(written -> MobFields.holds(written, typed))) {
                    SelectorEdits.toggleData(target, field.path(), MobFields.toJson(typed, false), written -> false);
                }
                host.changed();
            }).tooltip(Tooltip.create(DevUi.text("mobdata.add_typed"))).bounds(x + w - 40, top, 18, CONTROL_H).build());
            screen.add(removeButton(top, DevUi.text("mobdata.other_cancel"), b -> {
                typingOther.remove(typingKey);
                screen.rebuild();
            }));
        }
    }

    /** Parts of older files the form no longer offers: a line each, as written, with ✕. */
    private static final List<String> OLDER_PARTS = List.of("profession", "outfit", "variant", "in_village", "nbt");

    private void olderRows(List<Object> path, @Nullable JsonObject selector) {
        if (selector == null) {
            return;
        }
        for (String part : OLDER_PARTS) {
            if (!selector.has(part) || !row(CONTROL_H)) {
                continue;
            }
            JsonElement value = selector.get(part);
            String shown = part.equals("in_village") ? DevUi.str(value.getAsBoolean() ? "npc.village.yes" : "npc.village.no")
                    : String.join(", ", stringsOf(value.isJsonPrimitive() ? asList(value.getAsJsonPrimitive()) : value).stream()
                    .map(text -> part.equals("nbt") ? text : DevUi.humanize(text.substring(text.indexOf(':') + 1))).toList());
            String line = DevUi.str("mobdata.older", DevUi.str("field." + part), shown);
            int top = rowY(CONTROL_H);
            screen.add(Button.builder(Component.literal(screen.font().plainSubstrByWidth(line, w - 30)), b -> { })
                    .tooltip(Tooltip.create(Component.literal(line).append("\n").append(DevUi.text("mobdata.older.tooltip")))).bounds(x, top, w - 20, CONTROL_H).build());
            screen.add(removeButton(top, DevUi.text("form.remove"), b -> {
                ensureObject(path).remove(part);
                host.changed();
            }));
        }
    }

    /** The kinds a selector names: its types, a #tag's members. */
    static List<net.minecraft.world.entity.EntityType<?>> kindsOf(@Nullable JsonObject selector) {
        JsonElement type = selector != null ? selector.get("type") : null;
        List<net.minecraft.world.entity.EntityType<?>> kinds = new ArrayList<>();
        for (String kind : stringsOf(type instanceof JsonPrimitive one ? asList(one) : type)) {
            ResourceLocation key = ResourceLocation.tryParse(kind.startsWith("#") ? kind.substring(1) : kind);
            if (key == null) {
                continue;
            }
            if (kind.startsWith("#")) {
                BuiltInRegistries.ENTITY_TYPE.getTag(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ENTITY_TYPE, key))
                        .ifPresent(tag -> tag.forEach(holder -> kinds.add(holder.value())));
            } else if (BuiltInRegistries.ENTITY_TYPE.containsKey(key)) {
                kinds.add(BuiltInRegistries.ENTITY_TYPE.get(key));
            }
        }
        return kinds;
    }

    /**
     * A filter that is yes, no or not given, as one switch going round: age ("Any / Grown-ups only / Children only") and
     * place ("Anywhere / Only in a village / Only outside villages").
     */
    private void npcTriState(List<Object> at, String field, String key, @Nullable JsonObject selector) {
        if (!row(LABEL_H + CONTROL_H)) {
            return;
        }
        labelRow(DevUi.text(key));
        Boolean value = selector != null && selector.get(field) instanceof JsonPrimitive written && written.isBoolean() ? written.getAsBoolean() : null;
        String state = value == null ? "any" : value ? "yes" : "no";
        Button button = Button.builder(Component.literal(DevUi.str(key + "." + state) + " ▸"), b -> {
            JsonObject target = ensureObject(at);
            // Round: not given, then yes, then no; age goes grown-ups first, as an NPC mostly is.
            boolean firstYes = !field.equals("baby");
            if (value == null) {
                target.addProperty(field, firstYes);
            } else if (value == firstYes) {
                target.addProperty(field, !firstYes);
            } else {
                target.remove(field);
            }
            host.changed();
        }).bounds(x, rowY(LABEL_H + CONTROL_H) + LABEL_H, w, CONTROL_H).build();
        button.setTooltip(Tooltip.create(DevUi.text(key + ".tooltip")));
        screen.add(button);
    }

    /**
     * A field written as entity types (an id, a #tag or a list) or as a selector (a waypoint's nearest entity): edited
     * as a selector, whose type is the same list.
     */
    static boolean isTypesOrSelector(Node node) {
        List<Node> variants = Schemas.variants(node);
        return variants.size() == 2 && variants.stream().anyMatch(FormEntities::isEntitySelector)
                && variants.stream().anyMatch(variant -> "id_list".equals(Schemas.resolve(variant).ref()));
    }

    /**
     * Several entities (an NPC binding is for any of them): one row naming them, which opens their list, as a dialogue's
     * nodes. There "+ Add" makes an empty one and opens its page ({@link #entityPage}), as a row opens a set one.
     */
    void entityListField(List<Object> path, String name, Node node, @Nullable JsonElement value, boolean required) {
        if (!row(LABEL_H + CONTROL_H)) {
            return;
        }
        labelRow(DevUi.text(required ? "entity.list_required" : "entity.list"));
        List<JsonElement> entities = value == null ? List.of() : value.isJsonArray() ? value.getAsJsonArray().asList() : List.of(value);
        List<String> names = new ArrayList<>();
        for (JsonElement entity : entities) {
            names.add(entitySummary(entity.isJsonObject() ? entity.getAsJsonObject() : null));
        }
        Component summary = Component.literal((names.isEmpty() ? "—" : String.join("; ", names)) + " ▸");
        screen.add(Button.builder(summary, b -> push(with(path, name), node, DevUi.str("entity.list"), false))
                .bounds(x, rowY(LABEL_H + CONTROL_H) + LABEL_H, w, CONTROL_H).build());
    }

    /**
     * Which entity (an NPC binding's, a boss's, a waypoint's nearest one): one row with what it is in words ("Villager ·
     * tag demo_elder"). A click opens its page ({@link #entityPage}), also when it is empty: how it is known is chosen
     * there.
     */
    void entityField(List<Object> path, String name, Node node, @Nullable JsonElement value, Component label, boolean required) {
        if (!row(LABEL_H + CONTROL_H)) {
            return;
        }
        labelRow(label);
        JsonObject selector = value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
        Component summary = Component.literal(entitySummary(selector)).append(" ▸");
        List<Object> at = with(path, name);
        String title = fieldLabel(name, false).getString();
        // Empty or set, straight to its page: how it is known is chosen there.
        screen.add(Button.builder(summary, b -> push(at, node, title, !required))
                .bounds(x, rowY(LABEL_H + CONTROL_H) + LABEL_H, w, CONTROL_H).build());
    }

    /**
     * An entity's page (a waypoint's mob, a boss, an observed creature, one of an NPC's several entities), as an NPC's
     * "Who it is" asks it: its kind, its filters, and only those carrying a tag the story put on them (spawn_entity,
     * /tag). A file still knowing it by a name or a uuid says so in a line; an NPC's own entity keeps its tag line and
     * 🎯 instead.
     */
    void entityPage(List<Object> path) {
        boolean npcEntity = host.npcBinding() != null && !path.isEmpty() && "entity".equals(path.getFirst());
        if (npcEntity && SelectorEdits.byHand(host.root().get("entity"))) {
            // One of an NPC's particular entities: its kind and its tag, put on with 🎯.
            JsonObject selector = objectAt(path);
            if (selector != null && (selector.has("uuid") || selector.has("name"))) {
                String known = selector.has("uuid") ? DevUi.str("entity.summary.uuid", tail(selector.get("uuid").getAsString()))
                        : DevUi.str("entity.summary.name", selector.get("name").getAsString());
                text(DevUi.text("npc.known_from_file", known), DevUi.MUTED);
            }
            idListRows(path, "type", new Picker(Catalog.Kind.MOB, null, List.of(), DevUi.text("entity.pick")), DevUi.text("npc.kind"));
            npcLink(path, selector);
            return;
        }
        // A variant of a dynamic NPC, a waypoint's mob, a boss, an observed creature: who, where, its data.
        variantPage(path, !npcEntity);
    }

    /**
     * An NPC's link to its entities: its tag is its node's name ("npc_3"; a folder's "/" as "_", which /tag does not
     * take), said in a line, not a field to fill; a button links an entity in the world, putting the tag on it. A tag
     * the file already has stays, also after the node is renamed: the entities linked before keep theirs.
     */
    void npcLink(List<Object> path, @Nullable JsonObject selector) {
        ResourceLocation npc = host.npcBinding();
        String tag = selector != null && selector.get("tag") instanceof JsonPrimitive written && !written.getAsString().isBlank()
                ? written.getAsString() : npc.getPath().replace('/', '_');
        if (row(LABEL_H)) {
            labels.add(new Label(DevUi.text("entity.tag_is", tag), x, rowY(LABEL_H), DevUi.MUTED));
        }
        if (!row(CONTROL_H)) {
            return;
        }
        Button link = Button.builder(Component.literal("🎯 ").append(DevUi.text("entity.link")), b -> DevWorldPicker.pickEntity(screen,
                DevUi.text("picker.entity_prompt"), entity -> {
                    JsonObject target = ensureObject(path);
                    target.addProperty("tag", tag);
                    if (!target.has("type") && !target.has("uuid") && !target.has("name")) {
                        target.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
                    }
                    if (Minecraft.getInstance().getConnection() != null) {
                        Minecraft.getInstance().getConnection().sendCommand("tag " + entity.getUUID() + " add " + tag);
                    }
                    host.changed();
                })).bounds(x, rowY(CONTROL_H), w, CONTROL_H).build();
        link.setTooltip(Tooltip.create(DevUi.text("entity.link.tooltip", tag)));
        screen.add(link);
    }

    /** A dimension by its name: the editor's own, else as the quest tracker names it (a mod's translation, its id made readable). */
    static String dimensionName(String dimension) {
        String key = "storybound.dev.dimension." + dimension.substring(dimension.indexOf(':') + 1);
        if (Language.getInstance().has(key)) {
            return Language.getInstance().getOrDefault(key);
        }
        ResourceLocation id = ResourceLocation.tryParse(dimension);
        return id == null ? dimension : dev.dzenthai.storybound.client.quest.QuestHud.dimensionName(id);
    }
}
