package dev.dzenthai.storybound;

import com.mojang.logging.LogUtils;
import dev.dzenthai.storybound.command.StoryboundCommands;
import dev.dzenthai.storybound.config.StoryboundConfig;
import dev.dzenthai.storybound.core.CoreDefinitions;
import dev.dzenthai.storybound.core.data.StoryboundDataEvents;
import dev.dzenthai.storybound.core.logic.LogicEvents;
import dev.dzenthai.storybound.core.logic.LogicRegistries;
import dev.dzenthai.storybound.core.state.StateEvents;
import dev.dzenthai.storybound.core.state.StoryAttachments;
import dev.dzenthai.storybound.module.boss.BossEvents;
import dev.dzenthai.storybound.module.boss.BossRegistries;
import dev.dzenthai.storybound.module.codex.CodexEvents;
import dev.dzenthai.storybound.module.codex.CodexRegistries;
import dev.dzenthai.storybound.module.dialogue.DialogueEvents;
import dev.dzenthai.storybound.module.dialogue.DialogueRegistries;
import dev.dzenthai.storybound.module.document.DocumentEvents;
import dev.dzenthai.storybound.module.document.DocumentRegistries;
import dev.dzenthai.storybound.module.journal.Journal;
import dev.dzenthai.storybound.module.quest.QuestEvents;
import dev.dzenthai.storybound.module.quest.QuestRegistries;
import dev.dzenthai.storybound.module.waypoint.WaypointEvents;
import dev.dzenthai.storybound.module.waypoint.WaypointRegistries;
import dev.dzenthai.storybound.network.StoryboundNetwork;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

@Mod(Storybound.MOD_ID)
public final class Storybound {
    public static final String MOD_ID = "storybound";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Storybound(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, StoryboundConfig.COMMON_SPEC);

        LogicRegistries.register(modBus);
        CoreDefinitions.bootstrap();
        StoryAttachments.register(modBus);
        DocumentRegistries.register(modBus);
        Journal.register(modBus);
        QuestRegistries.register(modBus);
        DialogueRegistries.register(modBus);
        CodexRegistries.register(modBus);
        BossRegistries.register(modBus);
        WaypointRegistries.register(modBus);

        modBus.addListener(StoryboundNetwork::register);
        StoryboundDataEvents.register(NeoForge.EVENT_BUS);
        StateEvents.register(NeoForge.EVENT_BUS);
        LogicEvents.register(NeoForge.EVENT_BUS);
        DocumentEvents.register(NeoForge.EVENT_BUS);
        QuestEvents.register(NeoForge.EVENT_BUS);
        DialogueEvents.register(NeoForge.EVENT_BUS);
        CodexEvents.register(NeoForge.EVENT_BUS);
        BossEvents.register(NeoForge.EVENT_BUS);
        WaypointEvents.register(NeoForge.EVENT_BUS);
        NeoForge.EVENT_BUS.addListener(StoryboundCommands::register);
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
