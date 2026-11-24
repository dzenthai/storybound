package dev.dzenthai.storybound.client.dev;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.dzenthai.storybound.client.dev.Catalog.Entry;
import dev.dzenthai.storybound.client.dev.Schemas.Kind;
import dev.dzenthai.storybound.client.dev.Schemas.Logic;
import dev.dzenthai.storybound.client.dev.Schemas.Node;
import dev.dzenthai.storybound.core.story.StoryRenames;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

/**
 * A form for a JSON object, built from its schema ({@link Schemas}): a row per field, one level at a time. Nested
 * objects, lists and the logic ("when / if / then": triggers, conditions, actions) open in place, with breadcrumbs
 * back. Fields get a picker by what they name: an item, a mob, a structure, a biome, a point of interest, a quest, a
 * dialogue, a document, a boss, a Codex entry, a waypoint, a flag, a dialogue's node or speaker; positions get
 * "here". Text boxes change the JSON as typed ({@link Host#edited}); buttons change it and save ({@link Host#changed}).

 * <p>
 * One layer of the form, split by topic, each seeing the ones below it; the rest of the editor uses FormView:
 * FormCore &larr; FormPickers &larr; FormInputs &larr; FormEntities &larr; FormNpc &larr; FormWaypoints &larr; FormDialogues &larr; FormStory &larr; FormScenes &larr; FormView.
 */
final class FormView extends FormScenes {
    FormView(Host host) {
        super(host);
    }

    void page(List<Object> path, Node schema) {
        if (!path.isEmpty() && MORE_DATA.equals(path.getLast())) {
            moreDataPage(path.subList(0, path.size() - 1));
            return;
        }
        if (isEntitySelector(schema)) {
            entityPage(path);
            return;
        }
        if (entityListItems(schema) != null) {
            listPage(path, entityListItems(schema));
            return;
        }
        switch (Schemas.kind(schema)) {
            case CONDITIONS -> listPage(path, Schemas.logic("condition"));
            case ACTIONS -> listPage(path, Schemas.logic("action"));
            case LIST -> listPage(path, Schemas.items(schema));
            case MAP -> mapPage(path, Schemas.values(schema));
            case TRIGGER -> logicPage(path, Logic.TRIGGER);
            case CONDITION -> logicPage(path, Logic.CONDITION);
            case ACTION -> logicPage(path, Logic.ACTION);
            case VARIANTS -> variantsPage(path, schema);
            default -> objectPage(path, schema, Set.of(), null);
        }
    }

    void objectPage(List<Object> path, Node schema, Set<String> skip, @Nullable String typeField) {
        if (!skip.contains(SCENE_PAGE) && isSceneShot(schema)) {
            sceneShotPage(path, schema, skip);
            return;
        }
        if (!skip.contains(SCENE_PAGE) && isSceneCue(schema)) {
            sceneCuePage(path, schema, skip);
            return;
        }
        Set<String> required = Schemas.required(schema);
        boolean npc = path.isEmpty() && host.npcBinding() != null;
        boolean waypoint = path.isEmpty() && Schemas.properties(schema).containsKey("target") && Schemas.properties(schema).containsKey("show_ring");
        boolean dialogue = path.isEmpty() && isDialogue(schema);
        boolean participant = path.size() == 2 && "participants".equals(path.getFirst()) && Schemas.properties(schema).containsKey("portrait");
        JsonObject inDialogue = host.dialogue();
        String node = inDialogue != null && path.isEmpty() && host.root().get("type") instanceof JsonPrimitive type ? type.getAsString() : null;
        boolean option = inDialogue != null && path.size() == 2 && "options".equals(path.getFirst()) && Schemas.properties(schema).containsKey("locked");
        boolean document = path.isEmpty() && Schemas.properties(schema).containsKey("journal") && Schemas.properties(schema).containsKey("kind");
        boolean journal = path.size() == 1 && "journal".equals(path.getFirst()) && Schemas.properties(schema).containsKey("add_on_read");
        boolean quest = path.isEmpty() && Schemas.properties(schema).containsKey("stages") && Schemas.properties(schema).containsKey("fail_when");
        String inside = Schemas.properties(schema).containsKey("objectives") || Schemas.properties(schema).containsKey("since_stage") ? insideQuest(path) : null;
        boolean boss = path.isEmpty() && Schemas.properties(schema).containsKey("participation");
        boolean codex = path.isEmpty() && Schemas.properties(schema).containsKey("show_kills");
        boolean section = path.size() == 2 && "sections".equals(path.getFirst()) && Schemas.properties(schema).containsKey("unlock");
        boolean unlock = Schemas.properties(schema).containsKey("on") && Schemas.properties(schema).containsKey("count");
        boolean observed = Schemas.properties(schema).containsKey("time") && Schemas.properties(schema).containsKey("distance")
                && Schemas.properties(schema).containsKey("block");
        String rootForm = path.isEmpty() && inDialogue == null && host.node() != null ? ROOT_FORMS.get(host.node().type()) : null;
        // A scene's shot and its camera: what the shot is against first, then how the camera flies.
        boolean shot = Schemas.properties(schema).containsKey("camera") && Schemas.properties(schema).containsKey("transition");
        boolean camera = Schemas.properties(schema).containsKey("from") && Schemas.properties(schema).containsKey("speed");
        String form = node != null ? "node." + node : option ? "option" : document ? "document" : journal ? "journal" : quest ? "quest"
                : inside != null ? inside : boss ? "boss" : codex ? "codex" : section ? "codex.section" : unlock ? "unlock" : observed ? "observed"
                : shot ? "scene.shot" : camera ? "scene.camera" : rootForm;
        Map<String, String> groups = npc ? NPC_GROUPS : waypoint ? WAYPOINT_GROUPS : dialogue ? DIALOGUE_GROUPS
                : form != null ? NODE_GROUPS.getOrDefault(form, Map.of()) : Map.of();
        JsonObject object = objectAt(path);
        if (npc) {
            // Who it is, under its heading: the rule, the kind, the filters and the name, in that order.
            group(DevUi.text("npc.group.who"));
            npcWho(schema, object);
        }
        List<String> order = npc ? NPC_ORDER : form != null ? NODE_ORDER.getOrDefault(form, List.of()) : List.of();
        // add_waypoint: where it points, as a waypoint's target page asks it, over its own fields.
        boolean addWaypoint = Schemas.properties(schema).containsKey("follow") && Schemas.properties(schema).containsKey("structure")
                && Schemas.properties(schema).containsKey("show_ring");
        boolean targetDrawn = false;
        // The player's condition "location": where, as a mob's variant asks it, dimension → biome → structure, each
        // list only what fits.
        Map<String, Node> own = Schemas.properties(schema);
        boolean location = own.containsKey("dimension") && own.containsKey("biome") && own.containsKey("area") && own.containsKey("structure");
        for (Map.Entry<String, Node> property : ordered(Schemas.properties(schema), order)) {
            String name = property.getKey();
            if (npc && NPC_WHO.contains(name)) {
                continue;
            }
            if (location && (name.equals("dimension") || name.equals("biome") || name.equals("structure"))) {
                if (name.equals("dimension")) {
                    placeRows(path, false);
                }
                continue;
            }
            if (addWaypoint && ADD_WAYPOINT_TARGET.contains(name)) {
                if (!targetDrawn) {
                    addWaypointTarget(path, schema, object);
                    targetDrawn = true;
                }
                continue;
            }
            // "Hits forgiven" and their warnings only for an NPC that fights back: the others never take revenge
            // anyway. "Calms down after" not for one that does not react: it never gets angry.
            String reaction = object != null && object.get("reaction") instanceof JsonPrimitive written ? written.getAsString() : "ignore";
            boolean hiddenByReaction = Schemas.properties(schema).containsKey("calm_after")
                    && ((name.equals("patience") || name.equals("warning")) && !reaction.equals("fight") || name.equals("calm_after") && reaction.equals("ignore"));
            if (hiddenByReaction || skip.contains(name) || name.equals(typeField) || npc && !npcFieldShown(name, object) || waypoint && !waypointFieldShown(name, object)
                    || dialogue && !dialogueFieldShown(name, object) || participant && !participantFieldShown(name, object)
                    || form != null && !nodeFieldShown(form, name, object, inDialogue)) {
                continue;
            }
            if (groups.containsKey(name)) {
                group(DevUi.text(groups.get(name)));
            }
            if (npc && NPC_SUBGROUPS.containsKey(name)) {
                subgroup(DevUi.text(NPC_SUBGROUPS.get(name)));
            }
            if (npc && name.equals("proximity")) {
                proximityField(path, property.getValue(), object);
            } else if ("node.choice".equals(form) && name.equals("timeout")) {
                timeoutField(path, property.getValue(), object);
            } else if ("document".equals(form) && name.equals("journal")) {
                // Two settings: shown here, not behind a row of their own.
                objectPage(with(path, "journal"), property.getValue(), Set.of(), null);
            } else if ("document".equals(form) && name.equals("custom_model_data")) {
                iconField(path, name, object);
            } else if ("quest".equals(form) && name.equals("screen_text")) {
                screenTextFields(path);
            } else if ("quest".equals(form) && name.equals("repeatable")) {
                repeatField(path, property.getValue(), object);
            } else if ("quest".equals(form) && name.equals("category")) {
                questTypeField(path, object);
            } else if ("boss".equals(form) && BOSS_PARTS.contains(name)) {
                bossPartField(path, name, property.getValue(), object);
            } else if ("boss".equals(form) && name.equals("participation")) {
                percentField(path, name, property.getValue(), object, fieldLabel(name, false, schema));
            } else if ("codex".equals(form) && name.equals("category")) {
                codexCategoryField(path, object);
            } else if ("codex".equals(form) && name.equals("sections")) {
                codexSectionsField(path, property.getValue(), object);
            } else if ("unlock".equals(form) && name.equals("on")) {
                unlockSourcesField(path, object, fieldLabel(name, false, schema));
            } else if ("node.jump".equals(form) && name.equals("dialogue")) {
                if (jumpModeField(path, object)) {
                    field(path, name, property.getValue(), required.contains(name), schema);
                }
            } else {
                field(path, name, property.getValue(), required.contains(name), schema);
            }
            if (npc && name.equals("dialogues")) {
                relationshipField(host.npcBinding());
            }
            if (npc && name.equals("busy") && (object == null || !object.has("busy"))) {
                // Without it, others get nothing while it talks, not even the talk hint.
                text(DevUi.text("npc.busy_missing"), DevUi.MUTED);
            }
        }
        if (npc && isOneNode(object)) {
            npcPicked();
        }
    }

    void field(List<Object> path, String name, Node node, boolean required, Node owner) {
        Kind kind = Schemas.kind(node);
        JsonObject object = objectAt(path);
        JsonElement value = object != null ? object.get(name) : null;
        Component label = fieldLabel(name, required, owner);
        if (name.equals("phrases") && CHATTER.equals(context(owner)) && object != null
                && object.get("variants") instanceof JsonArray variants && !variants.isEmpty()) {
            // Beside the variants, the chatter's own phrases are what it says when none of them holds.
            label = DevUi.text("field.chatter.usual");
        }
        Tooltip tooltip = node.description().isEmpty() && Schemas.resolve(node).description().isEmpty() ? null
                : Tooltip.create(Component.literal(described(!node.description().isEmpty() ? node.description() : Schemas.resolve(node).description())));
        // A quest's difficulty; in the story's settings "difficulty" is the scale itself, an ordinary page.
        if (name.equals("difficulty") && path.isEmpty() && !Schemas.properties(owner).containsKey("track_given_quests")) {
            difficultyField(name, value, label);
            return;
        }
        if (isSceneAnchor(name, owner)) {
            sceneAnchorField(with(path, name), node, true, "scene.anchor");
            return;
        }
        if (isSceneLook(name, owner)) {
            sceneLookField(with(path, name), node);
            return;
        }
        if (isEntitySelector(node)) {
            entityField(path, name, node, value, label, required);
            return;
        }
        if (isTypesOrSelector(node)) {
            Node selector = Schemas.variants(node).stream().filter(FormView::isEntitySelector).findFirst().orElseThrow();
            JsonElement written = value;
            if (written != null && !written.isJsonObject()) {
                // Types alone are a selector of only a type: the same in game, and editable on the entity's page.
                JsonObject wrapped = new JsonObject();
                wrapped.add("type", written);
                ensureObject(path).add(name, wrapped);
                written = wrapped;
                host.edited();
            }
            entityField(path, name, selector, written, label, required);
            return;
        }
        if (entityListItems(node) != null) {
            entityListField(path, name, node, value, required);
            return;
        }
        if (name.equals("id") && host.quest() != null && insideQuest(path) != null) {
            insideNameField(path, insideQuest(path));
            return;
        }
        if (name.equals("id") && host.node() != null && host.node().type().equals(ClientDevStore.TYPE_CODEX)
                && path.size() == 2 && "sections".equals(path.getFirst())) {
            JsonObject entry = host.root();
            String current = value instanceof JsonPrimitive id ? id.getAsString() : "";
            nameRow(path.toString(), DevUi.text("rename.section"), DevUi.text("rename.section_tooltip", current), DevUi.text("rename.section_apply"),
                    current, null, typed -> StoryRenames.codexSectionProblem(entry, current, typed), to -> host.renameCodexSection(current, to));
            return;
        }
        if (name.equals("section") && Schemas.properties(owner).containsKey("entry") && kind == Kind.STRING) {
            codexSectionField(path, name, value, label);
            return;
        }
        if (name.equals("text") && kind == Kind.ID && longTextFile(path) != null) {
            longTextFileField(path, name, label, longTextFile(path));
            return;
        }
        if (name.equals("unlock") && Schemas.properties(node).containsKey("on")) {
            unlockField(path, name, node, value, label, tooltip);
            return;
        }
        if (host.dialogue() != null && "target".equals(Schemas.resolve(node).ref())) {
            targetField(path, name, value, label, required);
            return;
        }
        // A waypoint (or add_waypoint): "target, search area or trail" first; an area's radius and looks only for an
        // area; a trail's recording in place of where it points and of the marker it does not have.
        if (Schemas.properties(owner).containsKey("show_ring")) {
            if (name.equals("color")) {
                colorField(path, object);
                return;
            }
            boolean traceOffered = Schemas.properties(owner).containsKey("trace");
            boolean trace = traceOffered && object != null && object.has("trace");
            boolean area = !trace && object != null && object.get("radius") instanceof JsonPrimitive radius && radius.isNumber() && radius.getAsDouble() > 0;
            if (name.equals("trace")) {
                return;
            }
            if (name.equals("marker") && Schemas.properties(owner).containsKey("trace")) {
                markerField(path, object, trace);
                return;
            }
            if (name.equals("radius")) {
                waypointKindField(path, trace ? "trace" : area ? "area" : "target", traceOffered);
                if (!area) {
                    return;
                }
            } else if ((name.equals("show_radius") || name.equals("show_ring")) && !area) {
                return;
            }
        }
        Map<String, Node> ownerFields = Schemas.properties(owner);
        boolean phrasesOwner = ownerFields.containsKey("calm_after") || ownerFields.containsKey("min_interval") || ownerFields.containsKey("priority");
        if (name.equals("dialogue") && phrasesOwner && ownerFields.containsKey("phrases")) {
            // A click entry's or a hit's dialogue is the third way of its phrases' switch, shown there.
            return;
        }
        // An NPC's busy line is said as its phrases are: its own text or a phrases node.
        boolean busyLine = name.equals("busy") && path.isEmpty() && host.npcBinding() != null;
        if ((name.equals("phrases") || name.equals("warning")) && phrasesOwner || busyLine) {
            phrasesField(path, name, value, label, owner);
            return;
        }
        if (kind == Kind.ID_LIST) {
            Picker picker = picker(name, owner, path);
            if (picker != null) {
                idListRows(path, name, picker, label);
                return;
            }
        }
        switch (kind) {
            // A dialogue line's text carries its voice-over beside it (its sound has no row of its own).
            case TEXT -> textField(path, name, label, tooltip, longText(name, owner),
                    name.equals("text") && Schemas.properties(owner).containsKey("speaker") && Schemas.properties(owner).containsKey("sound"));
            case ENUM -> enumField(path, name, node, required, value, label, tooltip);
            case BOOLEAN -> booleanField(path, name, node, value, needsKubeJs(path, name) ? DevUi.text("form.needs_kubejs", label) : label, tooltip);
            case ID -> {
                if (name.equals("sound") || name.equals("music") || name.equals("track")) {
                    soundField(path, name, value, label);
                } else {
                    inputField(path, name, node, kind, value, label, tooltip, owner);
                }
            }
            case ID_LIST, STRING, INTEGER, NUMBER, LITERAL, POSITION, RAW -> inputField(path, name, node, kind, value, label, tooltip, owner);
            default -> {
                // Under its label, as every other field.
                if (row(LABEL_H + CONTROL_H)) {
                    labelRow(label);
                    Component shown = name.equals("target") && path.isEmpty() && Schemas.properties(owner).containsKey("show_ring")
                            ? Component.literal(object != null && object.has("trace") && Schemas.properties(owner).containsKey("trace")
                                    ? trailSummary(object) : targetSummary(value))
                            : name.equals("participants") && path.isEmpty() && isDialogue(owner) ? Component.literal(participantsSummary(value))
                            : questSummary(name, owner, value) != null ? Component.literal(questSummary(name, owner, value))
                            : name.equals("sections") && Schemas.properties(owner).containsKey("show_kills") ? Component.literal(sectionsSummary(value))
                            : summary(kind, node, value);
                    Component summary = Component.empty().append(shown).append(" ▸");
                    Button button = Button.builder(summary, b -> {
                                if (Schemas.isOldActionList(node, value)) {
                                    // Written the older way (only actions, given at once): the same as the object with them and no claim.
                                    JsonArray actions = new JsonArray();
                                    if (value.isJsonArray()) {
                                        actions = value.getAsJsonArray();
                                    } else {
                                        actions.add(value);
                                    }
                                    JsonObject form = new JsonObject();
                                    form.add("actions", actions);
                                    form.addProperty("claim", false);
                                    ensureObject(path).add(name, form);
                                    host.edited();
                                }
                                push(with(path, name), node, fieldLabel(name, false, owner).getString(), !required);
                            })
                            .bounds(x, rowY(LABEL_H + CONTROL_H) + LABEL_H, w, CONTROL_H).build();
                    if (tooltip != null) {
                        button.setTooltip(tooltip);
                    }
                    screen.add(button);
                }
            }
        }
    }

    private void inputField(List<Object> path, String name, Node node, Kind kind, @Nullable JsonElement value, Component label,
                            @Nullable Tooltip tooltip, Node owner) {
        if (!row(LABEL_H + CONTROL_H)) {
            return;
        }
        labelRow(label);
        int rowY = rowY(LABEL_H + CONTROL_H) + LABEL_H;
        boolean uuid = kind == Kind.STRING && Schemas.isUuid(node);
        Picker picker = !uuid && (kind == Kind.ID || kind == Kind.ID_LIST || kind == Kind.STRING) ? picker(name, owner, path) : null;
        boolean here = kind == Kind.POSITION;
        // A position has two buttons (where I stand, pick in the world); a picker or a uuid one.
        int boxW = w - (here ? 40 : picker != null || uuid ? 20 : 0);
        EditBox box = new EditBox(screen.font(), x, rowY, boxW, CONTROL_H - 2, label);
        box.setMaxLength(4096);
        box.setValue(display(kind, value));
        JsonElement fallback = node.defaultValue() != null ? node.defaultValue() : Schemas.resolve(node).defaultValue();
        Component byKind = documentDefault(name, path) != null ? documentDefault(name, path) : stageDefault(name, path);
        if (byKind != null) {
            box.setHint(byKind.copy().withStyle(style -> style.withColor(DevUi.HINT)));
        } else if (fallback != null) {
            box.setHint(Component.literal(display(kind, fallback)).withStyle(style -> style.withColor(DevUi.HINT)));
        }
        box.setResponder(text -> {
            JsonObject target = ensureObject(path);
            if (text.isBlank()) {
                target.remove(name);
                box.setTextColor(DevUi.TEXT);
                host.edited();
                return;
            }
            JsonElement parsed = parse(kind, text);
            box.setTextColor(parsed != null ? DevUi.TEXT : DevUi.ERROR);
            if (parsed != null) {
                target.add(name, parsed);
                host.edited();
            }
        });
        if (tooltip != null) {
            box.setTooltip(tooltip);
        }
        screen.add(box);
        if (picker != null) {
            screen.add(Button.builder(Component.literal("…"), b -> pick(picker, entry -> {
                JsonObject target = ensureObject(path);
                if (kind == Kind.ID_LIST && target.has(name) && !display(kind, target.get(name)).isBlank()) {
                    target.add(name, parse(Kind.ID_LIST, display(kind, target.get(name)) + ", " + entry.id()));
                } else {
                    target.addProperty(name, entry.id());
                }
                if (entry.held()) {
                    held(target, owner, entry.icon());
                }
                host.changed();
            })).tooltip(Tooltip.create(DevUi.text("form.pick"))).bounds(x + w - 18, rowY - 1, 18, CONTROL_H).build());
        } else if (uuid) {
            screen.add(Button.builder(Component.literal("🎯"), b -> DevWorldPicker.pickEntity(screen, DevUi.text("picker.entity_prompt"), entity -> {
                JsonObject target = ensureObject(path);
                target.addProperty(name, entity.getUUID().toString());
                // In an entity selector the type goes along, so the binding reads well and survives a typo in the uuid.
                if (Schemas.properties(owner).containsKey("type") && !target.has("type")) {
                    target.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
                }
                host.changed();
            })).tooltip(Tooltip.create(DevUi.text("form.pick_entity"))).bounds(x + w - 18, rowY - 1, 18, CONTROL_H).build());
        } else if (here) {
            screen.add(Button.builder(Component.literal("📍"), b -> {
                Player player = Minecraft.getInstance().player;
                if (player != null) {
                    writePosition(path, name, node, owner, new double[]{player.getX(), player.getY(), player.getZ()},
                            player.level().dimension().location());
                }
            }).tooltip(Tooltip.create(DevUi.text("form.here"))).bounds(x + w - 38, rowY - 1, 18, CONTROL_H).build());
            screen.add(Button.builder(Component.literal("🎯"), b -> DevWorldPicker.pickBlock(screen, DevUi.text("picker.block_prompt"),
                    (pos, dimension) -> writePosition(path, name, node, owner, new double[]{pos.getX(), pos.getY(), pos.getZ()}, dimension)))
                    .tooltip(Tooltip.create(DevUi.text("form.pick_block"))).bounds(x + w - 18, rowY - 1, 18, CONTROL_H).build());
        }
    }

    // --- nested pages

    private void listPage(List<Object> path, @Nullable Node items) {
        if (items == null) {
            return;
        }
        JsonElement value = get(path);
        JsonArray array = value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
        if (value != null && value.isJsonObject()) {
            array.add(value);
        }
        Kind itemKind = Schemas.kind(items);
        // Values, not objects: edited in the row itself (RAW: an id or an object, as JSON or plain text).
        boolean primitive = switch (itemKind) {
            case ID, STRING, ID_LIST, INTEGER, NUMBER, LITERAL, RAW -> true;
            default -> false;
        };
        // Lines of text: each edited in its row, in the active language, under a key of its own.
        boolean texts = itemKind == Kind.TEXT;
        String field = path.getLast() instanceof String name ? name : "";
        // A phrases node's lines: each a text written in its row, with the text window at its right.
        boolean phraseLines = field.equals("lines") && path.size() == 1 && host.node() != null && host.node().type().equals(ClientDevStore.TYPE_PHRASES);
        Picker picker = primitive ? picker(field.equals("waypoints") ? "waypoint" : field, items) : null;
        for (int i = 0; i < array.size(); i++) {
            int index = i;
            if (!row(CONTROL_H)) {
                continue;
            }
            int rowY = rowY(CONTROL_H);
            int buttonsW = (i > 0 ? 18 : 0) + 18;
            if (phraseLines) {
                phraseRow(path, field, array, index, rowY, w - buttonsW - 2);
            } else if (texts) {
                EditBox box = new EditBox(screen.font(), x, rowY + 1, w - buttonsW - 2, CONTROL_H - 2, Component.empty());
                box.setMaxLength(1024);
                box.setValue(ClientDevStore.readStoryTextAt(array, i, ClientDevStore.activeLanguage()));
                if (!ClientDevStore.activeLanguage().equalsIgnoreCase(ClientDevStore.sourceLanguage())) {
                    box.setHint(Component.literal(ClientDevStore.readStoryTextAt(array, i, ClientDevStore.sourceLanguage())));
                }
                String key = listKey(path, field) + "." + i;
                box.setResponder(text -> {
                    JsonArray list = ensureArray(path);
                    if (index < list.size()) {
                        ClientDevStore.writeStoryTextAt(list, index, text, key);
                        host.edited();
                    }
                });
                screen.add(box);
            } else if (primitive) {
                EditBox box = new EditBox(screen.font(), x, rowY + 1, w - buttonsW - 2, CONTROL_H - 2, Component.empty());
                box.setMaxLength(512);
                box.setValue(display(itemKind, array.get(i)));
                box.setResponder(text -> {
                    JsonArray list = ensureArray(path);
                    JsonElement parsed = parse(itemKind, text);
                    box.setTextColor(parsed != null ? DevUi.TEXT : DevUi.ERROR);
                    if (index < list.size() && parsed != null) {
                        list.set(index, parsed);
                        host.edited();
                    }
                });
                screen.add(box);
            } else {
                Component summary = Component.literal(itemSummary(array.get(i), items, i) + " ▸");
                screen.add(Button.builder(summary, b -> push(with(path, index), items, itemSummary(array.get(index), items, index), false))
                        .bounds(x, rowY, w - buttonsW - 2, CONTROL_H).build());
            }
            if (i > 0) {
                screen.add(Button.builder(Component.literal("↑"), b -> {
                    JsonArray list = ensureArray(path);
                    JsonElement moved = list.get(index);
                    list.set(index, list.get(index - 1));
                    list.set(index - 1, moved);
                    host.changed();
                }).tooltip(Tooltip.create(DevUi.text("form.move_up"))).bounds(x + w - 36, rowY, 18, CONTROL_H).build());
            }
            screen.add(removeButton(rowY, DevUi.text("form.remove"), b -> {
                ensureArray(path).remove(index);
                host.changed();
            }));
        }
        if (array.isEmpty() && row(LABEL_H)) {
            labels.add(new Label(DevUi.text("form.empty"), x, rowY(LABEL_H), DevUi.MUTED));
        }
        if (!row(CONTROL_H)) {
            return;
        }
        Logic logic = switch (itemKind) {
            case CONDITION -> Logic.CONDITION;
            case ACTION -> Logic.ACTION;
            case TRIGGER -> Logic.TRIGGER;
            default -> null;
        };
        boolean entities = isEntitySelector(items);
        boolean stacks = stackList(field, items);
        screen.add(Button.builder(Component.literal("+ ").append(DevUi.text("form.add")), b -> {
            if (stacks) {
                // Rewards: straight to the items (the one in hand first, with its count and components), not a blank row.
                screen.catalog().open(DevUi.text("form.pick_title.item"), Catalog.Kind.ITEM, (kind, entry) -> {
                    ensureArray(path).add(stack(items, entry));
                    host.changed();
                });
                screen.rebuild();
            } else if (entities) {
                // Straight to its page: how it is known (type, name, one entity) is chosen there.
                JsonArray list = ensureArray(path);
                list.add(new JsonObject());
                frames.push(new Frame(with(path, list.size() - 1), items, DevUi.str("entity.new"), false));
                resetScroll();
                host.changed();
            } else if (logic != null) {
                pickType(logic, element -> {
                    String type = element.get("type").getAsString();
                    ensureArray(path).add(element);
                    frames.push(new Frame(with(path, ensureArray(path).size() - 1), items, typeName(logic, type), false));
                    resetScroll();
                    host.changed();
                });
            } else if (picker != null) {
                pick(picker, entry -> {
                    ensureArray(path).add(entry.id());
                    host.changed();
                });
            } else if (primitive || texts || phraseLines) {
                ensureArray(path).add("");
                host.changed();
            } else {
                JsonArray list = ensureArray(path);
                JsonObject element = itemKind == Kind.OBJECT ? Schemas.skeleton(items) : new JsonObject();
                // A quest's objective gets a name too, though the schema does not ask for one: by position, players'
                // counters would move to another objective when the list is reordered.
                boolean named = Schemas.required(items).contains("id") || Schemas.properties(items).containsKey("since_stage");
                if (named && !element.has("id")) {
                    element.addProperty("id", freeId(list, field));
                }
                // A Codex section must say how it opens: when its creature is seen, or (without one) by an action.
                if (element.get("unlock") instanceof JsonObject opens && !opens.has("on")) {
                    JsonArray on = new JsonArray();
                    on.add(host.root().has("entity") ? "seen" : "manual");
                    opens.add("on", on);
                }
                list.add(element);
                frames.push(new Frame(with(path, list.size() - 1), items, itemSummary(element, items, list.size() - 1), false));
                resetScroll();
                host.changed();
            }
        }).bounds(x, rowY(CONTROL_H), w, CONTROL_H).build());
    }

    private void mapPage(List<Object> path, @Nullable Node values) {
        if (values == null) {
            return;
        }
        JsonObject map = objectAt(path);
        if (map != null) {
            for (String key : List.copyOf(map.keySet())) {
                if (!row(CONTROL_H)) {
                    continue;
                }
                int rowY = rowY(CONTROL_H);
                // A dialogue's participant says who it is: "narrator (no entity «Narrator»)".
                String shown = path.size() == 1 && "participants".equals(path.getFirst()) && Schemas.properties(values).containsKey("portrait")
                        ? participantSummary(key, map.get(key)) : key;
                screen.add(Button.builder(Component.literal(shown + " ▸"), b -> push(with(path, key), values, key, false))
                        .bounds(x, rowY, w - 20, CONTROL_H).build());
                screen.add(removeButton(rowY, DevUi.text("form.remove"), b -> {
                    ensureObject(path).remove(key);
                    host.changed();
                }));
            }
        }
        if (!row(CONTROL_H)) {
            return;
        }
        int rowY = rowY(CONTROL_H);
        EditBox name = new EditBox(screen.font(), x, rowY + 1, w - 20, CONTROL_H - 2, DevUi.text("form.new_name"));
        name.setHint(DevUi.text("form.new_name"));
        name.setMaxLength(64);
        screen.add(name);
        screen.add(Button.builder(Component.literal("+"), b -> {
            String key = name.getValue().trim();
            if (!key.isEmpty() && (map == null || !map.has(key))) {
                ensureObject(path).add(key, Schemas.kind(values) == Kind.OBJECT ? Schemas.skeleton(values) : new JsonObject());
                frames.push(new Frame(with(path, key), values, key, false));
                resetScroll();
                host.changed();
            }
        }).tooltip(Tooltip.create(DevUi.text("form.add"))).bounds(x + w - 18, rowY, 18, CONTROL_H).build());
    }

    /** A trigger, condition or action: its type (picked from a list), what the type does, then its fields. */
    private void logicPage(List<Object> path, Logic logic) {
        Node node = Schemas.logic(logic.name);
        JsonObject object = objectAt(path);
        String type = object != null && object.has("type") && object.get("type").isJsonPrimitive() ? object.get("type").getAsString() : null;
        if (row(LABEL_H + CONTROL_H)) {
            labelRow(DevUi.text("form.type"));
            screen.add(Button.builder(type != null ? Component.literal(typeName(logic, type)) : DevUi.text("form.pick_type"), b -> pickType(logic, element -> {
                put(ensureParent(path), path, path.size() - 1, element);
                host.changed();
            })).bounds(x, rowY(LABEL_H + CONTROL_H) + LABEL_H, w, CONTROL_H).build());
        }
        if (type == null || node == null) {
            return;
        }
        Node variant = Schemas.typed(node, type);
        if (variant == null) {
            // Another mod's type: no schema, so its fields are edited as JSON.
            text(DevUi.text("form.no_schema"), DevUi.MUTED);
            JsonObject rest = object.deepCopy();
            rest.remove("type");
            if (row(CONTROL_H)) {
                EditBox box = new EditBox(screen.font(), x, rowY(CONTROL_H), w, CONTROL_H - 2, Component.empty());
                box.setMaxLength(8192);
                box.setValue(rest.toString());
                box.setResponder(text -> {
                    try {
                        JsonObject fields = JsonParser.parseString(text).getAsJsonObject();
                        JsonObject element = new JsonObject();
                        element.addProperty("type", type);
                        fields.entrySet().forEach(entry -> element.add(entry.getKey(), entry.getValue()));
                        put(ensureParent(path), path, path.size() - 1, element);
                        box.setTextColor(DevUi.TEXT);
                        host.edited();
                    } catch (RuntimeException e) {
                        box.setTextColor(DevUi.ERROR);
                    }
                });
                screen.add(box);
            }
            return;
        }
        String help = typeHelp(logic, type, variant);
        if (!help.isEmpty()) {
            text(Component.literal(help), DevUi.MUTED);
        }
        objectPage(path, variant, Set.of(), "type");
    }

    /** One of several shapes (a waypoint's target: a point, an entity, a structure, the nearest mob). */
    private void variantsPage(List<Object> path, Node schema) {
        List<Node> variants = Schemas.variants(schema);
        if (variants.isEmpty()) {
            return;
        }
        int current = Schemas.variantOf(schema, get(path));
        // A waypoint's target: "Points at: a point / one entity / the nearest structure / the nearest mob".
        boolean target = path.size() == 1 && "target".equals(path.getFirst()) && host.root().has("name") && Schemas.properties(variants.get(0)).containsKey("pos");
        if (target && !Schemas.required(variants.get(current)).isEmpty()) {
            waypointTargetPage(path, variants, current);
            return;
        }
        if (row(LABEL_H + CONTROL_H)) {
            labelRow(DevUi.text(target ? "waypoint.points_at" : "form.variant"));
            Node shown = variants.get(current);
            Component label = target && !Schemas.required(shown).isEmpty() ? DevUi.text("waypoint.kind." + Schemas.required(shown).iterator().next()) : variantLabel(shown);
            screen.add(Button.builder(Component.empty().append(label).append(" ▸"), b -> {
                Node next = variants.get((current + 1) % variants.size());
                put(ensureParent(path), path, path.size() - 1, Schemas.skeleton(next));
                host.changed();
            }).bounds(x, rowY(LABEL_H + CONTROL_H) + LABEL_H, w, CONTROL_H).build());
        }
        if (target && Schemas.required(variants.get(current)).contains("nearest")) {
            // It finds only what is loaded near the player.
            text(DevUi.text("waypoint.kind.nearest.note"), DevUi.MUTED);
        }
        objectPage(path, variants.get(current), Set.of(), null);
    }

    // --- drawing

    void render(GuiGraphics graphics, Font font) {
        if (barX >= 0 && contentHeight > 0) {
            // The track in the panel's edge colour, the thumb bronze, as long as the part shown is of the whole.
            int track = barBottom - barTop;
            int thumb = Math.max(12, track * track / contentHeight);
            int most = Math.max(1, contentHeight - track);
            int thumbTop = barTop + (int) Math.round((track - thumb) * Math.min(1.0, scrollShown / most));
            graphics.fill(barX, barTop, barX + 2, barBottom, DevUi.ROW_HOVER);
            graphics.fill(barX, thumbTop, barX + 2, thumbTop + thumb, DevUi.PANEL_EDGE);
        }
        for (Label label : labels) {
            if (label.line() != null) {
                graphics.drawString(font, label.line(), label.x(), label.y(), label.color(), false);
            } else if (label.text() != null) {
                graphics.drawString(font, font.plainSubstrByWidth(label.text().getString(), w), label.x(), label.y(), label.color(), false);
            }
        }
    }
}
