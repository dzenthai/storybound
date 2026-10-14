package dev.dzenthai.storybound.compat.kubejs;

import dev.dzenthai.storybound.core.event.CustomTriggerEvent;
import dev.dzenthai.storybound.core.event.DialogueEvent;
import dev.dzenthai.storybound.core.event.NpcInteractEvent;
import dev.dzenthai.storybound.core.event.PartyEvent;
import dev.dzenthai.storybound.core.event.QuestChangedEvent;
import dev.dzenthai.storybound.core.event.StoryValueChangedEvent;
import java.util.Locale;
import net.neoforged.neoforge.common.NeoForge;

/** Forwards Storybound events from the NeoForge bus to server scripts. Posts only when a script listens. */
final class StoryboundKubeBridge {
    private StoryboundKubeBridge() {}

    static void register() {
        NeoForge.EVENT_BUS.addListener(StoryboundKubeBridge::onValueChanged);
        NeoForge.EVENT_BUS.addListener(StoryboundKubeBridge::onCustomTrigger);
        NeoForge.EVENT_BUS.addListener(StoryboundKubeBridge::onPartyJoined);
        NeoForge.EVENT_BUS.addListener(StoryboundKubeBridge::onPartyLeft);
        NeoForge.EVENT_BUS.addListener(StoryboundKubeBridge::onQuestChanged);
        NeoForge.EVENT_BUS.addListener(StoryboundKubeBridge::onDialogueStarted);
        NeoForge.EVENT_BUS.addListener(StoryboundKubeBridge::onDialogueEnded);
        NeoForge.EVENT_BUS.addListener(StoryboundKubeBridge::onNpcInteract);
    }

    private static void onValueChanged(StoryValueChangedEvent event) {
        if (StoryboundJSEvents.FLAG_CHANGED.hasListeners(event.getKey())) {
            StoryboundJSEvents.FLAG_CHANGED.post(new FlagChangedKubeEvent(event), event.getKey());
        }
    }

    private static void onCustomTrigger(CustomTriggerEvent event) {
        if (StoryboundJSEvents.CUSTOM_TRIGGER.hasListeners(event.getId())) {
            StoryboundJSEvents.CUSTOM_TRIGGER.post(new CustomTriggerKubeEvent(event.getPlayer(), event.getId()), event.getId());
        }
    }

    private static void onPartyJoined(PartyEvent.Joined event) {
        if (StoryboundJSEvents.PARTY_JOINED.hasListeners()) {
            StoryboundJSEvents.PARTY_JOINED.post(new PartyKubeEvent(event.getServer(), event.getPartyId(), event.getPlayer(), null));
        }
    }

    private static void onPartyLeft(PartyEvent.Left event) {
        if (StoryboundJSEvents.PARTY_LEFT.hasListeners()) {
            String reason = event.getReason().name().toLowerCase(Locale.ROOT);
            StoryboundJSEvents.PARTY_LEFT.post(new PartyKubeEvent(event.getServer(), event.getPartyId(), event.getPlayer(), reason));
        }
    }

    private static void onQuestChanged(QuestChangedEvent event) {
        if (event.statusChanged() && StoryboundJSEvents.QUEST_STATE_CHANGED.hasListeners(event.getQuest())) {
            StoryboundJSEvents.QUEST_STATE_CHANGED.post(new QuestChangedKubeEvent(event), event.getQuest());
        }
        if (event.stageChanged() && StoryboundJSEvents.QUEST_STAGE_CHANGED.hasListeners(event.getQuest())) {
            StoryboundJSEvents.QUEST_STAGE_CHANGED.post(new QuestChangedKubeEvent(event), event.getQuest());
        }
    }

    private static void onDialogueStarted(DialogueEvent.Started event) {
        if (StoryboundJSEvents.DIALOGUE_STARTED.hasListeners(event.getDialogue())) {
            StoryboundJSEvents.DIALOGUE_STARTED.post(new DialogueKubeEvent(event), event.getDialogue());
        }
    }

    private static void onDialogueEnded(DialogueEvent.Ended event) {
        if (StoryboundJSEvents.DIALOGUE_ENDED.hasListeners(event.getDialogue())) {
            StoryboundJSEvents.DIALOGUE_ENDED.post(new DialogueKubeEvent(event), event.getDialogue());
        }
    }

    private static void onNpcInteract(NpcInteractEvent event) {
        if (StoryboundJSEvents.NPC_INTERACT.hasListeners(event.getBinding())) {
            StoryboundJSEvents.NPC_INTERACT.post(new NpcInteractKubeEvent(event), event.getBinding());
        }
    }
}
