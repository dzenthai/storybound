package dev.dzenthai.storybound.client.dev;

import dev.dzenthai.storybound.network.DevModePayloads;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

/** What the dev mode asks the server to do; the server checks permissions and answers with a new snapshot. */
final class DevRequests {
    private DevRequests() {}

    /** Saves a definition together with the texts edited in the active language since the last save. */
    static void save(ResourceLocation type, ResourceLocation id, String json, Optional<String> text) {
        String language = ClientDevStore.activeLanguage();
        Map<String, String> translations = new HashMap<>(ClientDevStore.pendingTranslationsFor(language));
        PacketDistributor.sendToServer(new DevModePayloads.SaveDefinition(type, id, json, text, language, translations));
        ClientDevStore.clearPendingTranslations(language);
    }

    /** Saves a definition as it was (undo and redo): its texts stay as they are. */
    static void restore(ResourceLocation type, ResourceLocation id, String json, Optional<String> text) {
        PacketDistributor.sendToServer(new DevModePayloads.SaveDefinition(type, id, json, text, ClientDevStore.activeLanguage(), Map.of()));
    }

    /** A long text on its own, in the active language (a Codex section, a boss's lore); documents go with their definition. */
    static void saveText(ResourceLocation textId, String text) {
        PacketDistributor.sendToServer(new DevModePayloads.SaveText(textId, ClientDevStore.activeLanguage(), text));
    }

    static void delete(ResourceLocation type, ResourceLocation id) {
        PacketDistributor.sendToServer(new DevModePayloads.DeleteDefinition(type, id));
    }

    static void quickAction(String action, ResourceLocation target) {
        PacketDistributor.sendToServer(new DevModePayloads.QuickAction(action, target));
    }

    static void requestSnapshot() {
        PacketDistributor.sendToServer(new DevModePayloads.RequestSnapshot());
    }

    /** The server's KubeJS scripts, for script blocks; the answer goes to {@link ClientDevStore#setScripts}. */
    static void requestScripts() {
        PacketDistributor.sendToServer(new DevModePayloads.RequestScripts());
    }

    static void requestProgress(String player) {
        PacketDistributor.sendToServer(new DevModePayloads.RequestProgress(player));
    }

    /** A new script file registering the handler; the server reloads and sends the scripts again. */
    static void createScript(ResourceLocation hook, boolean condition) {
        PacketDistributor.sendToServer(new DevModePayloads.CreateScript(hook, condition));
    }

    static void saveLanguages(String source, List<String> languages) {
        PacketDistributor.sendToServer(new DevModePayloads.SaveProjectSettings(source, languages));
    }
}
