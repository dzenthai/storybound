package dev.dzenthai.storybound.client.dev;

import dev.dzenthai.storybound.network.DevModePayloads;
import dev.dzenthai.storybound.network.DevProjectPayloads;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

/** What the dev mode asks the server to do; the server checks permissions and answers with a new snapshot. */
final class DevRequests {
    private DevRequests() {}

    static void send(CustomPacketPayload payload) {
        // Counted: the snapshots say how many arrived, so the editor knows which of its writes they answer.
        ClientDevStore.sending();
        PacketDistributor.sendToServer(new DevProjectPayloads.Edit(ClientDevStore.projectId(), payload));
    }

    /**
     * Saves a definition together with the texts edited in the active language since the last save. One the game would
     * not load yet (a new NPC before it is told who it is, an NPC linked to a dialogue meanwhile) stays a draft here,
     * texts and all, and goes once it is complete, instead of the server refusing it.
     */
    static void save(ResourceLocation type, ResourceLocation id, String json, Optional<String> text) {
        if (DevChecks.problem(type, ClientDevStore.parseObject(json)).isPresent()) {
            ClientDevStore.putDraft(type, id, json);
            return;
        }
        ClientDevStore.dropDraft(type, id);
        String language = ClientDevStore.activeLanguage();
        Map<String, String> translations = new HashMap<>(ClientDevStore.pendingTranslationsFor(language));
        DevRequests.send(new DevModePayloads.SaveDefinition(type, id, json, text, language, translations));
        ClientDevStore.clearPendingTranslations(language);
    }

    /** Saves a definition as it was (undo and redo): its texts stay as they are. */
    static void restore(ResourceLocation type, ResourceLocation id, String json, Optional<String> text) {
        if (DevChecks.problem(type, ClientDevStore.parseObject(json)).isPresent()) {
            ClientDevStore.putDraft(type, id, json);
            return;
        }
        ClientDevStore.dropDraft(type, id);
        // Here at once, as a save does: the inspector reloads right after an undo, before the server's snapshot comes.
        ClientDevStore.putLocal(type, id, json);
        DevRequests.send(new DevModePayloads.SaveDefinition(type, id, json, text, ClientDevStore.activeLanguage(), Map.of()));
    }

    /** A long text on its own, in the active language (a Codex section, a boss's lore); documents go with their definition. */
    static void saveText(ResourceLocation textId, String text) {
        DevRequests.send(new DevModePayloads.SaveText(textId, ClientDevStore.activeLanguage(), text));
    }

    static void delete(ResourceLocation type, ResourceLocation id) {
        boolean draftOnly = ClientDevStore.isDraft(type, id) && !ClientDevStore.onServer(type, id);
        ClientDevStore.dropDraft(type, id);
        // Gone here at once, and kept gone over the snapshots answering earlier requests.
        ClientDevStore.removeLocal(type, id);
        if (draftOnly) {
            // The server never had it.
            return;
        }
        DevRequests.send(new DevModePayloads.DeleteDefinition(type, id));
    }

    static void rename(ResourceLocation type, ResourceLocation from, ResourceLocation to) {
        DevRequests.send(new DevModePayloads.RenameDefinition(type, from, to));
    }

    static void renameInQuest(ResourceLocation quest, String stage, String objective, String to) {
        DevRequests.send(new DevModePayloads.RenameInQuest(quest, stage, objective, to));
    }

    static void renameCodexSection(ResourceLocation entry, String from, String to) {
        DevRequests.send(new DevModePayloads.RenameCodexSection(entry, from, to));
    }

    static void renameInDialogue(ResourceLocation dialogue, String from, String to) {
        DevRequests.send(new DevModePayloads.RenameInDialogue(dialogue, from, to));
    }

    static void quickAction(String action, ResourceLocation target) {
        DevRequests.send(new DevModePayloads.QuickAction(action, target));
    }

    static void requestSnapshot() {
        PacketDistributor.sendToServer(new DevModePayloads.RequestSnapshot());
    }

    /** The server's KubeJS scripts, for script blocks; the answer goes to {@link ClientDevStore#setScripts}. */
    static void requestScripts() {
        DevRequests.send(new DevModePayloads.RequestScripts());
    }

    static void requestProgress(String player) {
        DevRequests.send(new DevModePayloads.RequestProgress(player));
    }

    /** A new script file registering the handler; the server reloads and sends the scripts again. */
    static void createScript(ResourceLocation hook, boolean condition) {
        DevRequests.send(new DevModePayloads.CreateScript(hook, condition));
    }

    static void saveLanguages(String source, List<String> languages) {
        DevRequests.send(new DevModePayloads.SaveProjectSettings(source, languages, ClientDevStore.namespace()));
    }

    static void saveNamespace(String namespace) {
        DevRequests.send(new DevModePayloads.SaveProjectSettings(ClientDevStore.sourceLanguage(), ClientDevStore.languages(), namespace));
    }

    static void moveNamespace(String from, String to) {
        DevRequests.send(new DevModePayloads.MoveNamespace(from, to));
    }
}
