package dev.dzenthai.storybound.client.dev;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.dzenthai.storybound.client.dev.NodeTemplates.Template;
import dev.dzenthai.storybound.core.story.StoryLinks.NodeKey;
import dev.dzenthai.storybound.core.story.StoryRenames;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

/**
 * The story editor's editing ({@link StoryView}): selecting, renaming, saving, creating, duplicating, linking,
 * deleting and unlinking nodes.
 * <p>
 * One layer of the story editor, split by topic, each seeing the ones below it; the rest of the editor uses StoryView:
 * StoryCards &larr; StoryChapters &larr; StoryEditing &larr; StoryDrawing &larr; StoryView.
 */
abstract class StoryEditing extends StoryChapters {
    // --- selection and saving

    /** Selects one card (or none); unsaved inspector edits of the previous one are saved first. */
    void select(DevModeScreen screen, @Nullable NodeKey key) {
        saveEdits(screen);
        selection.clear();
        if (key != null) {
            selection.add(key);
            screen.showInspector();
        }
        selected = key;
        editing = key != null ? ClientDevStore.parseObject(ClientDevStore.getJson(key.type(), key.id())) : null;
    }

    /**
     * Gives the node a new id (see {@link StoryRenames}): unsaved edits go first, a
     * node still being filled in is not renamed. One undoable step: undo renames it back.
     */
    void rename(DevModeScreen screen, NodeKey key, ResourceLocation to) {
        saveEdits(screen);
        if (dirty) {
            screen.status(DevUi.text("rename.incomplete"), DevUi.WARN);
            return;
        }
        NodeKey renamed = new NodeKey(key.type(), to);
        requestRename(screen, key, to);
        screen.remember(DevUi.str("history.rename", key.id().toString(), to.toString()),
                () -> requestRename(screen, renamed, key.id()),
                () -> requestRename(screen, key, to));
    }

    /**
     * Renames here at once, as the server will (the node, an NPC's relationship scale, what names them), so the card
     * keeps its place and stays selected; the server's snapshot follows, or puts the old name back if it refuses.
     */
    private void requestRename(DevModeScreen screen, NodeKey key, ResourceLocation to) {
        NodeKey renamed = new NodeKey(key.type(), to);
        List<StoryRenames.Rename> renames = StoryRenames.cascade(
                new StoryRenames.Rename(key.type(), key.id(), to), (type, id) -> ClientDevStore.getJson(type, id) != null);
        for (StoryRenames.Rename rename : renames) {
            String json = ClientDevStore.getJson(rename.type(), rename.from());
            if (json != null) {
                ClientDevStore.removeLocal(rename.type(), rename.from());
                ClientDevStore.putLocal(rename.type(), rename.to(), json);
            }
        }
        ClientDevStore.all().forEach((type, definitions) -> {
            if (StoryRenames.REFERRING.contains(type)) {
                definitions.forEach((id, raw) -> {
                    JsonObject json = ClientDevStore.parseObject(raw);
                    if (StoryRenames.rewrite(type, json, renames) > 0) {
                        ClientDevStore.putLocal(type, id, DevUi.GSON.toJson(json));
                    }
                });
            }
        });
        if (selection.remove(key)) {
            selection.add(renamed);
        }
        if (key.equals(selected)) {
            selected = renamed;
        }
        editing = selected != null ? ClientDevStore.parseObject(ClientDevStore.getJson(selected.type(), selected.id())) : null;
        dirty = false;
        if (key.type().equals(ClientDevStore.TYPE_DIALOGUE)) {
            screen.dialogueRenamed(key.id(), to);
        }
        DevRequests.rename(key.type(), key.id(), to);
        loadChapter();
        rebuildCards();
        screen.status(DevUi.text("rename.sent", key.id().toString(), to.toString()), DevUi.OK);
        screen.rebuild();
    }

    /**
     * Renames a stage of the quest ({@code objective} empty) or an objective of a stage: unsaved edits go first, a quest
     * still being filled in is not renamed. One undoable step: undo renames it back.
     */
    void renameInQuest(DevModeScreen screen, NodeKey quest, String stage, String objective, String to) {
        saveEdits(screen);
        if (dirty) {
            screen.status(DevUi.text("rename.incomplete"), DevUi.WARN);
            return;
        }
        requestRenameInQuest(screen, quest, stage, objective, to);
        boolean ofStage = objective.isEmpty();
        screen.remember(DevUi.str("history.rename", ofStage ? stage : objective, to),
                () -> requestRenameInQuest(screen, quest, ofStage ? to : stage, ofStage ? "" : to, ofStage ? stage : objective),
                () -> requestRenameInQuest(screen, quest, stage, objective, to));
    }

    /** Renames here at once, as the server will; its snapshot follows, or puts the old name back if it refuses. */
    private void requestRenameInQuest(DevModeScreen screen, NodeKey quest, String stage, String objective, String to) {
        JsonObject json = ClientDevStore.parseObject(ClientDevStore.getJson(quest.type(), quest.id()));
        if (objective.isEmpty()) {
            StoryRenames.renameStage(json, stage, to);
            ClientDevStore.putLocal(quest.type(), quest.id(), DevUi.GSON.toJson(json));
            ClientDevStore.all().forEach((type, definitions) -> {
                if (StoryRenames.REFERRING.contains(type)) {
                    definitions.forEach((id, raw) -> {
                        JsonObject other = ClientDevStore.parseObject(raw);
                        if (StoryRenames.rewriteStage(other, quest.id(), stage, to) > 0) {
                            ClientDevStore.putLocal(type, id, DevUi.GSON.toJson(other));
                        }
                    });
                }
            });
        } else {
            StoryRenames.renameObjective(json, stage, objective, to);
            ClientDevStore.putLocal(quest.type(), quest.id(), DevUi.GSON.toJson(json));
        }
        if (quest.equals(selected)) {
            editing = ClientDevStore.parseObject(ClientDevStore.getJson(quest.type(), quest.id()));
            dirty = false;
        }
        DevRequests.renameInQuest(quest.id(), stage, objective, to);
        rebuildCards();
        screen.status(DevUi.text("rename.sent", objective.isEmpty() ? stage : objective, to), DevUi.OK);
        screen.rebuild();
    }

    void renameCodexSection(DevModeScreen screen, NodeKey entry, String from, String to) {
        saveEdits(screen);
        if (dirty) {
            screen.status(DevUi.text("rename.incomplete"), DevUi.WARN);
            return;
        }
        requestRenameCodexSection(screen, entry, from, to);
        screen.remember(DevUi.str("history.rename", from, to),
                () -> requestRenameCodexSection(screen, entry, to, from),
                () -> requestRenameCodexSection(screen, entry, from, to));
    }

    /** Renames here at once, as the server will; its snapshot follows, or puts the old name back if it refuses. */
    private void requestRenameCodexSection(DevModeScreen screen, NodeKey entry, String from, String to) {
        JsonObject json = ClientDevStore.parseObject(ClientDevStore.getJson(entry.type(), entry.id()));
        StoryRenames.renameCodexSection(json, from, to);
        ClientDevStore.putLocal(entry.type(), entry.id(), DevUi.GSON.toJson(json));
        ClientDevStore.all().forEach((type, definitions) -> {
            if (StoryRenames.REFERRING.contains(type)) {
                definitions.forEach((id, raw) -> {
                    JsonObject other = ClientDevStore.parseObject(raw);
                    if (StoryRenames.rewriteCodexSection(other, entry.id(), from, to) > 0) {
                        ClientDevStore.putLocal(type, id, DevUi.GSON.toJson(other));
                    }
                });
            }
        });
        if (entry.equals(selected)) {
            editing = ClientDevStore.parseObject(ClientDevStore.getJson(entry.type(), entry.id()));
            dirty = false;
        }
        DevRequests.renameCodexSection(entry.id(), from, to);
        rebuildCards();
        screen.status(DevUi.text("rename.sent", from, to), DevUi.OK);
        screen.rebuild();
    }

    void namespaceMoved(String from, String to) {
        movedNamespace = new String[]{from, to};
    }

    /** Re-reads the primary selection after the selection set changed. */
    void selectFromSet(DevModeScreen screen, @Nullable NodeKey primary) {
        saveEdits(screen);
        selected = primary;
        editing = primary != null ? ClientDevStore.parseObject(ClientDevStore.getJson(primary.type(), primary.id())) : null;
    }

    @Override
    void saveEdits(DevModeScreen screen) {
        if (dirty) {
            save(screen);
        }
    }

    /** Sends the selected definition as edited, with its document text, as one undoable step. */
    void save(DevModeScreen screen) {
        if (selected == null || editing == null) {
            return;
        }
        Optional<String> problem = DevChecks.problem(selected.type(), editing);
        if (problem.isPresent()) {
            // Still being filled in: kept here (over the snapshots too), sent once it loads.
            dirty = true;
            NodeKey drafted = selected;
            String draftBefore = ClientDevStore.getJson(drafted.type(), drafted.id());
            String draft = DevUi.GSON.toJson(editing);
            lastSaved = draft;
            ClientDevStore.putDraft(drafted.type(), drafted.id(), draft);
            // Undoable as any edit.
            if (draftBefore != null && !draftBefore.equals(draft)) {
                screen.remember(DevUi.str("history.save", drafted.id().getPath()),
                        () -> DevRequests.restore(drafted.type(), drafted.id(), draftBefore, Optional.empty()),
                        () -> DevRequests.restore(drafted.type(), drafted.id(), draft, Optional.empty()));
            }
            screen.status(DevUi.text("status.incomplete", DevChecks.shorten(problem.get())), DevUi.WARN);
            return;
        }
        NodeKey target = selected;
        String before = ClientDevStore.getJson(target.type(), target.id());
        Optional<String> beforeText = documentText(target.id());
        String after = DevUi.GSON.toJson(editing);
        Optional<String> afterText = inspector.documentText();
        dirty = false;
        lastSaved = after;
        ClientDevStore.putLocal(target.type(), target.id(), after);
        DevRequests.save(target.type(), target.id(), after, afterText);
        inspector.textSaved();
        // A script block's handler changed: what ran the old one runs the new one, in the same undoable step.
        List<Unlinked> renamed = new ArrayList<>();
        if (target.type().equals(ClientDevStore.TYPE_SCRIPT) && before != null) {
            String oldHook = StoryEdits.hookOf(target.id(), ClientDevStore.parseObject(before));
            String newHook = StoryEdits.hookOf(target.id(), editing);
            if (!oldHook.equals(newHook)) {
                renamed = renameHook(oldHook, newHook);
                if (!renamed.isEmpty()) {
                    screen.status(DevUi.text("status.hook_renamed", renamed.size(), newHook), DevUi.LINK);
                }
            }
        }
        List<Unlinked> alsoChanged = renamed;
        if (before != null && !before.equals(after) || !beforeText.equals(afterText) && afterText.isPresent()) {
            screen.remember(DevUi.str("history.save", target.id().getPath()),
                    () -> {
                        DevRequests.restore(target.type(), target.id(), before != null ? before : after, beforeText);
                        alsoChanged.forEach(change -> restore(change.key(), change.before()));
                    },
                    () -> {
                        DevRequests.restore(target.type(), target.id(), after, afterText);
                        alsoChanged.forEach(change -> restore(change.key(), change.after()));
                    });
        }
        if (!renamed.isEmpty()) {
            rebuildCards();
            return;
        }
        rebuildCards();
        screen.status(DevUi.text("status.saved", target.id().toString()), DevUi.OK);
    }

    /** A toggle in the inspector changed the definition: saved at once, so nothing is lost when switching cards. */
    @Override
    void beforeHistoryReload() {
        // A draft keeps the inspector "dirty"; with nothing typed since it was kept, the undone state shows.
        if (dirty && editing != null && DevUi.GSON.toJson(editing).equals(lastSaved)) {
            dirty = false;
        }
    }

    void changed(DevModeScreen screen) {
        dirty = true;
        save(screen);
        screen.rebuild();
    }

    /** The document's text in the active language, else the source language, else English. */
    static Optional<String> documentText(ResourceLocation document) {
        return ClientDevStore.document(document, ClientDevStore.activeLanguage())
                .or(() -> ClientDevStore.document(document, ClientDevStore.sourceLanguage()))
                .or(() -> ClientDevStore.document(document, "en_us"));
    }

    // --- creating, linking and deleting

    private int freeNumber() {
        int count = 0;
        for (Map.Entry<ResourceLocation, Map<ResourceLocation, String>> entry : ClientDevStore.all().entrySet()) {
            if (KINDS.contains(entry.getKey())) {
                count += entry.getValue().size();
            }
        }
        return count + 1;
    }

    /** Makes the template's definitions at the context menu's place; returns the one the inspector shows. */
    @Nullable NodeKey create(DevModeScreen screen, Template template) {
        List<NodeTemplates.Created> created = fresh(template);
        NodeKey last = null;
        NodeKey shown = null;
        for (NodeTemplates.Created definition : created) {
            place(screen, definition, menuWorldX + definition.offsetX(), menuWorldY);
            last = definition.key();
            shown = shows(last) ? last : shown;
        }
        if (shown != null && !shown.equals(last)) {
            // A contract on the map: its quest is what the map shows, the notice is in the library.
            select(screen, shown);
        }
        if (last != null) {
            ask(screen, last, NodeTemplates.ask(template));
        }
        return last;
    }

    /** A new node's first question: who it is (its entity's page), which creature (the mobs), when it fires (the triggers). */
    void ask(DevModeScreen screen, NodeKey key, NodeTemplates.Ask ask) {
        switch (ask) {
            case ENTITY, TRIGGER -> inspector.ask(key, ask);
            case CREATURE -> screen.catalog().open(DevUi.text("template.ask_creature"), Catalog.Kind.MOB, (kind, entry) -> {
                if (!key.equals(selected) || editing == null) {
                    return;
                }
                editing.addProperty("entity", entry.id());
                // Its section now opens on sight instead of by an action alone.
                if (editing.get("sections") instanceof com.google.gson.JsonArray sections && !sections.isEmpty()
                        && sections.get(0) instanceof JsonObject first && first.get("unlock") instanceof JsonObject unlock) {
                    com.google.gson.JsonArray on = new com.google.gson.JsonArray();
                    on.add("seen");
                    unlock.add("on", on);
                }
                changed(screen);
            });
            default -> {
            }
        }
    }

    /** The template's definitions with ids nothing has yet, placed where the author stands (for waypoints). */
    private List<NodeTemplates.Created> fresh(Template template) {
        Player player = Minecraft.getInstance().player;
        NodeTemplates.Place place = player != null
                ? new NodeTemplates.Place(new double[]{player.getX(), player.getY(), player.getZ()}, player.level().dimension().location().toString())
                : new NodeTemplates.Place(null, "minecraft:overworld");
        int number = freeNumber();
        List<NodeTemplates.Created> created = NodeTemplates.create(template, number, place, DevUi::str);
        while (created.stream().anyMatch(c -> ClientDevStore.getJson(c.key().type(), c.key().id()) != null)) {
            created = NodeTemplates.create(template, ++number, place, DevUi::str);
        }
        return created;
    }

    /**
     * The quest giver's dialogue for a phase of the quest (see {@link QuestGiver}): a new dialogue, an entry for it in
     * the NPC's binding, one undoable step; then its graph opens to be written.
     */
    void addGiverDialogue(DevModeScreen screen, ResourceLocation quest, QuestGiver.Giver giver, QuestGiver.Phase phase) {
        saveEdits(screen);
        NodeKey npc = giver.npc();
        String bindingBefore = ClientDevStore.getJson(npc.type(), npc.id());
        if (bindingBefore == null) {
            return;
        }
        NodeTemplates.Created dialogue = fresh(Template.DIALOGUE_SCREEN).getFirst();
        NodeKey key = dialogue.key();
        String dialogueJson = DevUi.GSON.toJson(dialogue.json());
        JsonObject binding = ClientDevStore.parseObject(bindingBefore);
        QuestGiver.addEntry(binding, key.id(), quest, phase);
        String bindingAfter = DevUi.GSON.toJson(binding);
        ClientDevStore.putLocal(key.type(), key.id(), dialogueJson);
        DevRequests.save(key.type(), key.id(), dialogueJson, dialogue.text());
        restore(npc, bindingAfter);
        if (npc.equals(selected)) {
            editing = binding;
            dirty = false;
        }
        screen.remember(DevUi.str("history.giver", key.id().getPath()),
                () -> {
                    DevRequests.delete(key.type(), key.id());
                    restore(npc, bindingBefore);
                },
                () -> {
                    ClientDevStore.putLocal(key.type(), key.id(), dialogueJson);
                    DevRequests.restore(key.type(), key.id(), dialogueJson, dialogue.text());
                    restore(npc, bindingAfter);
                });
        rebuildCards();
        screen.openDialogue(key.id());
        screen.status(DevUi.text("status.giver_created", key.id().getPath(), DevUi.str("giver." + phase.name().toLowerCase(Locale.ROOT))), DevUi.OK);
    }

    /** "+ New" in the library: the template's definitions, nowhere on a canvas. */
    void createFromList(DevModeScreen screen, Template template) {
        menuWorldX = 20;
        menuWorldY = 20;
        create(screen, template);
        screen.rebuild();
    }

    void duplicateFromList(DevModeScreen screen, NodeKey source) {
        duplicate(screen, source);
        screen.rebuild();
    }

    /** Saves a new definition, puts it on the canvas (and the open chapter, when the canvas shows it) and selects it. */
    void place(DevModeScreen screen, NodeTemplates.Created definition, int pickedX, int pickedY) {
        NodeKey key = definition.key();
        int x = DevUi.snap(pickedX);
        int y = DevUi.snap(pickedY);
        String json = DevUi.GSON.toJson(definition.json());
        select(screen, null);
        positions.put(key, new int[]{x, y});
        ClientDevStore.putLocal(key.type(), key.id(), json);
        DevRequests.save(key.type(), key.id(), json, definition.text());
        addToChapter(key, x, y);
        selection.add(key);
        selected = key;
        editing = definition.json();
        screen.showInspector();
        screen.remember(DevUi.str("history.create", key.id().getPath()),
                () -> {
                    positions.remove(key);
                    selection.remove(key);
                    if (key.equals(selected)) {
                        selected = null;
                        editing = null;
                    }
                    DevRequests.delete(key.type(), key.id());
                },
                () -> {
                    positions.put(key, new int[]{x, y});
                    ClientDevStore.putLocal(key.type(), key.id(), json);
                    DevRequests.restore(key.type(), key.id(), json, definition.text());
                    addToChapter(key, x, y);
                });
        rebuildCards();
        screen.status(DevUi.text("status.created", key.id().getPath()), DevUi.OK);
    }

    /** Creates a node next to another and links them; with {@code reverse} the new node links to the old one. */
    void createLinked(DevModeScreen screen, NodeKey source, Template template, boolean reverse) {
        int[] from = positions.getOrDefault(source, new int[]{menuWorldX, menuWorldY});
        int x = reverse ? from[0] - 5 * DevUi.GRID : from[0] + 5 * DevUi.GRID;
        int y = from[1];
        for (int attempt = 0; attempt < 12 && occupied(x, y); attempt++) {
            y += 4 * DevUi.GRID;
        }
        menuWorldX = x;
        menuWorldY = y;
        NodeKey created = create(screen, template);
        if (created != null) {
            link(screen, reverse ? created : source, reverse ? source : created);
        }
    }

    void duplicate(DevModeScreen screen, NodeKey source) {
        String raw = ClientDevStore.getJson(source.type(), source.id());
        if (raw == null) {
            return;
        }
        int number = freeNumber();
        ResourceLocation id = source.id().withPath(source.id().getPath() + "_copy_" + number);
        while (ClientDevStore.getJson(source.type(), id) != null) {
            id = source.id().withPath(source.id().getPath() + "_copy_" + ++number);
        }
        int[] from = positions.getOrDefault(source, new int[]{menuWorldX, menuWorldY});
        place(screen, new NodeTemplates.Created(new NodeKey(source.type(), id), ClientDevStore.parseObject(raw), documentText(source.id()), 0),
                from[0] + 24, from[1] + 24);
    }

    /** Writes the link into the first node's JSON (see {@link StoryEdits#link}). */
    void link(DevModeScreen screen, NodeKey from, NodeKey to) {
        String before = ClientDevStore.getJson(from.type(), from.id());
        if (from.equals(to) || before == null) {
            return;
        }
        JsonObject json = ClientDevStore.parseObject(before);
        if (!StoryEdits.link(json, from, to)) {
            screen.status(DevUi.text("status.no_link"), DevUi.WARN);
            return;
        }
        String after = DevUi.GSON.toJson(json);
        if (from.equals(selected)) {
            editing = json;
            dirty = false;
        }
        ClientDevStore.putLocal(from.type(), from.id(), after);
        DevRequests.save(from.type(), from.id(), after, Optional.empty());
        screen.remember(DevUi.str("history.link", from.id().getPath(), to.id().getPath()),
                () -> {
                    ClientDevStore.putLocal(from.type(), from.id(), before);
                    DevRequests.restore(from.type(), from.id(), before, Optional.empty());
                },
                () -> {
                    ClientDevStore.putLocal(from.type(), from.id(), after);
                    DevRequests.restore(from.type(), from.id(), after, Optional.empty());
                });
        rebuildCards();
        screen.status(DevUi.text("status.linked", from.id().getPath(), to.id().getPath()), DevUi.LINK);
    }

    /** Deletes the selected card, or the chapter whose title is being edited. */
    void deleteSelected(DevModeScreen screen) {
        if (selected != null && selected.type().equals(ClientDevStore.TYPE_CHAPTER)) {
            ResourceLocation id = selected.id();
            selected = null;
            selection.clear();
            editing = null;
            dirty = false;
            deleteChapter(screen, id);
            screen.rebuild();
            return;
        }
        deleteSelection(screen);
    }

    /**
     * Deletes a node's definition. A script block that comes from the code would come straight back from it, so it is
     * hidden instead ({@code "hidden": true} in its file; the code stays in KubeJS); returns whether it was. That is a
     * block whose handler the scripts register, or one the editor makes from a KubeJS file itself (without it, deleting
     * tried to delete a file there never was, and the block came back from the script file at once).
     */
    private static boolean removeNode(NodeKey key, @Nullable String json) {
        if (json != null && key.type().equals(ClientDevStore.TYPE_SCRIPT)
                && (ClientDevStore.scriptRegistered(key.id()) || ClientDevStore.isDerivedScript(key.id()))) {
            JsonObject hidden = JsonParser.parseString(json).getAsJsonObject();
            hidden.addProperty("hidden", true);
            DevRequests.restore(key.type(), key.id(), hidden.toString(), Optional.empty());
            return true;
        }
        DevRequests.delete(key.type(), key.id());
        return false;
    }

    void deleteSelection(DevModeScreen screen) {
        Set<NodeKey> targets = new LinkedHashSet<>(selection);
        if (selected != null) {
            targets.add(selected);
        }
        if (targets.isEmpty()) {
            return;
        }
        record Deleted(NodeKey key, String json, Optional<String> text, int @Nullable [] pos) {}
        List<Deleted> deleted = new ArrayList<>();
        for (NodeKey key : targets) {
            String json = ClientDevStore.getJson(key.type(), key.id());
            if (json != null) {
                int[] pos = positions.get(key);
                deleted.add(new Deleted(key, json, documentText(key.id()), pos != null ? pos.clone() : null));
            }
            positions.remove(key);
            if (removeNode(key, json)) {
                screen.status(DevUi.text("status.script_hidden"), DevUi.OK);
            }
        }
        selected = null;
        selection.clear();
        editing = null;
        dirty = false;
        List<Unlinked> unlinked = unlinkEverywhere(targets);
        rebuildCardsWithout(targets);
        saveChapter();
        if (!deleted.isEmpty()) {
            String label = deleted.size() == 1 ? DevUi.str("history.delete", deleted.getFirst().key().id().getPath())
                    : DevUi.str("history.delete_many", deleted.size());
            screen.remember(label,
                    () -> {
                        for (Deleted node : deleted) {
                            if (node.pos() != null) {
                                positions.put(node.key(), node.pos().clone());
                                addToChapter(node.key(), node.pos()[0], node.pos()[1]);
                            }
                            DevRequests.restore(node.key().type(), node.key().id(), node.json(), node.text());
                        }
                        unlinked.forEach(change -> restore(change.key(), change.before()));
                    },
                    () -> {
                        for (Deleted node : deleted) {
                            positions.remove(node.key());
                            removeNode(node.key(), node.json());
                        }
                        unlinked.forEach(change -> restore(change.key(), change.after()));
                        rebuildCardsWithout(targets);
                        saveChapter();
                    });
        } else {
            screen.rebuild();
        }
        long left = ClientDevStore.all().entrySet().stream().filter(entry -> KINDS.contains(entry.getKey()))
                .flatMap(entry -> entry.getValue().entrySet().stream().filter(definition -> !targets.contains(new NodeKey(entry.getKey(), definition.getKey())))
                        .map(definition -> ClientDevStore.parseObject(definition.getValue())))
                .filter(json -> targets.stream().anyMatch(target -> StoryEdits.refersTo(json, target)))
                .count();
        screen.status(left > 0 ? DevUi.text("status.deleted_refs_left", targets.size(), unlinked.size(), left)
                : DevUi.text("status.deleted_unlinked", targets.size(), unlinked.size()), DevUi.BAD);
    }

    /** A definition changed because a node it pointed at was deleted. */
    private record Unlinked(NodeKey key, String before, String after) {}

    /** Takes the deleted nodes out of every other definition (see {@link StoryEdits#unlink}) and saves those. */
    private static List<Unlinked> unlinkEverywhere(Set<NodeKey> deleted) {
        List<Unlinked> changes = new ArrayList<>();
        for (ResourceLocation type : KINDS) {
            ClientDevStore.byType(type).forEach((id, before) -> {
                NodeKey key = new NodeKey(type, id);
                if (deleted.contains(key)) {
                    return;
                }
                JsonObject json = ClientDevStore.parseObject(before);
                boolean changed = false;
                for (NodeKey target : deleted) {
                    changed |= StoryEdits.unlink(json, target);
                }
                if (changed) {
                    changes.add(new Unlinked(key, before, DevUi.GSON.toJson(json)));
                }
            });
        }
        changes.forEach(change -> restore(change.key(), change.after()));
        return changes;
    }

    /** Points every action and condition "Script" of the old handler to the new one, and saves what changed. */
    private static List<Unlinked> renameHook(String oldHook, String newHook) {
        List<Unlinked> changes = new ArrayList<>();
        for (ResourceLocation type : KINDS) {
            ClientDevStore.byType(type).forEach((id, before) -> {
                JsonObject json = ClientDevStore.parseObject(before);
                if (StoryEdits.renameScriptHook(json, oldHook, newHook)) {
                    changes.add(new Unlinked(new NodeKey(type, id), before, DevUi.GSON.toJson(json)));
                }
            });
        }
        changes.forEach(change -> restore(change.key(), change.after()));
        return changes;
    }

    /** Removes what the selected definition has about the target (a link in the inspector's list). */
    void unlinkSelected(DevModeScreen screen, NodeKey target) {
        if (editing != null && StoryEdits.unlink(editing, target)) {
            changed(screen);
            screen.status(DevUi.text("status.unlinked", target.id().getPath()), DevUi.WARN);
        }
    }

    /** The cards before the server confirms the deletion, so the chapter is saved without them. */
    private void rebuildCardsWithout(Set<NodeKey> removed) {
        rebuildCards();
        cards.removeIf(card -> removed.contains(card.key()));
    }
}
