package dev.dzenthai.storybound.client;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.data.DefinitionType;
import dev.dzenthai.storybound.core.data.DefinitionTypes;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = Storybound.MOD_ID, dist = Dist.CLIENT)
public final class StoryboundClient {
    public StoryboundClient(IEventBus modBus, ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);

        NeoForge.EVENT_BUS.addListener(StoryboundClient::onLoggingOut);
        NeoForge.EVENT_BUS.addListener(StoryboundClientCommands::register);
    }

    // Data from one server must not leak into the next session.
    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        for (DefinitionType<?> type : DefinitionTypes.all()) {
            type.clientStore().clear();
        }
        ClientStoryState.clear();
    }
}
