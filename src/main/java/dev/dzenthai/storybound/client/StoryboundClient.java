package dev.dzenthai.storybound.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.codex.BossesView;
import dev.dzenthai.storybound.client.codex.CodexView;
import dev.dzenthai.storybound.client.dialogue.ClientDialogue;
import dev.dzenthai.storybound.client.dialogue.DialogueHud;
import dev.dzenthai.storybound.client.dialogue.SpeechBubbles;
import dev.dzenthai.storybound.client.document.DocumentStyles;
import dev.dzenthai.storybound.client.journal.JournalView;
import dev.dzenthai.storybound.client.menu.AdventureMenu;
import dev.dzenthai.storybound.client.menu.MenuTab;
import dev.dzenthai.storybound.client.menu.MenuThemes;
import dev.dzenthai.storybound.client.quest.QuestHud;
import dev.dzenthai.storybound.client.quest.QuestsView;
import dev.dzenthai.storybound.client.waypoint.ClientWaypoints;
import dev.dzenthai.storybound.client.waypoint.MapIntegrations;
import dev.dzenthai.storybound.client.waypoint.WaypointBeams;
import dev.dzenthai.storybound.core.data.DefinitionType;
import dev.dzenthai.storybound.core.data.DefinitionTypes;
import dev.dzenthai.storybound.module.document.DocumentDefinition;
import dev.dzenthai.storybound.module.document.DocumentRef;
import dev.dzenthai.storybound.module.document.DocumentRegistries;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

@Mod(value = Storybound.MOD_ID, dist = Dist.CLIENT)
public final class StoryboundClient {
    private static final String KEY_CATEGORY = "key.categories.storybound";

    /** Opens the Adventure Menu on the last used tab; also closes it. */
    public static final KeyMapping OPEN_MENU = new KeyMapping("key.storybound.adventure_menu",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, KEY_CATEGORY);
    public static final KeyMapping OPEN_QUESTS = new KeyMapping("key.storybound.quests",
            InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), KEY_CATEGORY);
    public static final KeyMapping OPEN_JOURNAL = new KeyMapping("key.storybound.journal",
            InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), KEY_CATEGORY);
    public static final KeyMapping OPEN_CODEX = new KeyMapping("key.storybound.codex",
            InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), KEY_CATEGORY);
    public static final KeyMapping OPEN_BOSSES = new KeyMapping("key.storybound.bosses",
            InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), KEY_CATEGORY);

    public StoryboundClient(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, StoryboundClientConfig.SPEC);
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);

        modBus.addListener(StoryboundClient::onClientSetup);
        modBus.addListener(StoryboundClient::onRegisterKeys);
        modBus.addListener(StoryboundClient::onRegisterReloadListeners);
        modBus.addListener(StoryboundClient::onRegisterGuiLayers);
        NeoForge.EVENT_BUS.addListener(StoryboundClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(StoryboundClient::onLoggingOut);
        NeoForge.EVENT_BUS.addListener(SpeechBubbles::onRenderLevel);
        NeoForge.EVENT_BUS.addListener(WaypointBeams::render);
        NeoForge.EVENT_BUS.addListener(StoryboundClientCommands::register);
    }

    private static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            // The item model picks the icon by document kind through this property.
            ItemProperties.register(DocumentRegistries.DOCUMENT_ITEM.get(), Storybound.id("kind"), (stack, level, entity, seed) -> {
                DocumentRef ref = stack.get(DocumentRegistries.DOCUMENT_COMPONENT.get());
                DocumentDefinition definition = ref != null ? DocumentRegistries.DOCUMENTS.clientStore().getOrNull(ref.id()) : null;
                return definition != null ? definition.kind().modelPredicate() : 0f;
            });
            AdventureMenu.registerTab(new MenuTab(AdventureMenu.QUESTS, Component.translatable("storybound.quests.title"), 100,
                    () -> ClientModules.isEnabled("quests"), QuestsView::hasUnseen, QuestsView::new));
            AdventureMenu.registerTab(new MenuTab(AdventureMenu.JOURNAL, Component.translatable("storybound.journal.title"), 200,
                    () -> ClientModules.isEnabled("journal") && ClientJournal.isEnabled(), JournalView::hasUnseen, JournalView::new));
            AdventureMenu.registerTab(new MenuTab(AdventureMenu.CODEX, Component.translatable("storybound.codex.title"), 300,
                    () -> ClientModules.isEnabled("codex"), CodexView::hasUnseen, CodexView::new));
            AdventureMenu.registerTab(new MenuTab(AdventureMenu.BOSSES, Component.translatable("storybound.bosses.title"), 400,
                    () -> ClientModules.isEnabled("bosses"), BossesView::hasUnseen, BossesView::new));
            MapIntegrations.register();
        });
    }

    private static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.register(OPEN_MENU);
        event.register(OPEN_QUESTS);
        event.register(OPEN_JOURNAL);
        event.register(OPEN_CODEX);
        event.register(OPEN_BOSSES);
    }

    private static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(DocumentStyles.INSTANCE);
        event.registerReloadListener(MenuThemes.INSTANCE);
    }

    // Below chat and titles, above the vanilla HUD; hidden by F1 and F3 like the rest of it.
    private static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.BOSS_OVERLAY, Storybound.id("quest_tracker"), QuestHud::render);
        event.registerBelow(VanillaGuiLayers.CHAT, Storybound.id("dialogue_subtitles"), DialogueHud::render);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        openOnKey(OPEN_MENU, null);
        openOnKey(OPEN_QUESTS, AdventureMenu.QUESTS);
        openOnKey(OPEN_JOURNAL, AdventureMenu.JOURNAL);
        openOnKey(OPEN_CODEX, AdventureMenu.CODEX);
        openOnKey(OPEN_BOSSES, AdventureMenu.BOSSES);
        ClientWaypoints.tick();
        ClientDialogue.tick();
    }

    private static void openOnKey(KeyMapping key, ResourceLocation tab) {
        Minecraft minecraft = Minecraft.getInstance();
        while (key.consumeClick()) {
            if (minecraft.screen == null && minecraft.player != null) {
                AdventureMenu.open(tab);
            }
        }
    }

    // Data from one server must not leak into the next session.
    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        for (DefinitionType<?> type : DefinitionTypes.all()) {
            type.clientStore().clear();
        }
        ClientStoryState.clear();
        ClientJournal.clear();
        ClientQuests.clear();
        ClientCodex.clear();
        ClientBosses.clear();
        ClientWaypoints.clear();
        ClientModules.clear();
        ClientDialogue.clear();
        SpeechBubbles.clear();
        SeenMarks.reset();
        AdventureMenu.reset();
    }
}
