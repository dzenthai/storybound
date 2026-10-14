package dev.dzenthai.storybound.compat.kubejs;

import dev.latvian.mods.kubejs.event.EventGroup;
import dev.latvian.mods.kubejs.event.EventHandler;
import dev.latvian.mods.kubejs.event.EventTargetType;
import dev.latvian.mods.kubejs.event.TargetedEventHandler;
import net.minecraft.resources.ResourceLocation;

/** The {@code StoryboundEvents} group available to server scripts. */
public interface StoryboundJSEvents {
    EventGroup GROUP = EventGroup.of("StoryboundEvents");

    /** {@code StoryboundEvents.flagChanged('mypack:met_elder', e => ...)}; the id is optional. */
    TargetedEventHandler<ResourceLocation> FLAG_CHANGED = GROUP.server("flagChanged", () -> FlagChangedKubeEvent.class)
            .supportsTarget(EventTargetType.ID);

    /** {@code StoryboundEvents.customTrigger('mypack:bell_rung', e => ...)}. */
    TargetedEventHandler<ResourceLocation> CUSTOM_TRIGGER = GROUP.server("customTrigger", () -> CustomTriggerKubeEvent.class)
            .supportsTarget(EventTargetType.ID);

    /** {@code StoryboundEvents.questStateChanged('mypack:wolf_hunt', e => e.newState === 'completed' && ...)}. */
    TargetedEventHandler<ResourceLocation> QUEST_STATE_CHANGED = GROUP.server("questStateChanged", () -> QuestChangedKubeEvent.class)
            .supportsTarget(EventTargetType.ID);

    /** {@code StoryboundEvents.questStageChanged('mypack:wolf_hunt', e => e.newStage ...)}. */
    TargetedEventHandler<ResourceLocation> QUEST_STAGE_CHANGED = GROUP.server("questStageChanged", () -> QuestChangedKubeEvent.class)
            .supportsTarget(EventTargetType.ID);

    /** {@code StoryboundEvents.dialogueStarted('mypack:elder', e => ...)}; the id is optional. */
    TargetedEventHandler<ResourceLocation> DIALOGUE_STARTED = GROUP.server("dialogueStarted", () -> DialogueKubeEvent.class)
            .supportsTarget(EventTargetType.ID);

    /** {@code StoryboundEvents.dialogueEnded('mypack:elder', e => e.aborted ? ... : ...)}, also for interrupted dialogues. */
    TargetedEventHandler<ResourceLocation> DIALOGUE_ENDED = GROUP.server("dialogueEnded", () -> DialogueKubeEvent.class)
            .supportsTarget(EventTargetType.ID);

    /** {@code StoryboundEvents.npcInteract('mypack:elder', e => Storybound.dialogues(e.player).start('mypack:elder_night', e.entity))}; the binding id is optional. */
    TargetedEventHandler<ResourceLocation> NPC_INTERACT = GROUP.server("npcInteract", () -> NpcInteractKubeEvent.class)
            .supportsTarget(EventTargetType.ID);

    /** {@code StoryboundEvents.codexUnlocked('mypack:drowner', e => e.section ...)}; once per opened section; the id is optional. */
    TargetedEventHandler<ResourceLocation> CODEX_UNLOCKED = GROUP.server("codexUnlocked", () -> CodexKubeEvent.class)
            .supportsTarget(EventTargetType.ID);

    /** {@code StoryboundEvents.bossDefeated('mypack:drowned_king', e => e.first && ...)}; once per credited player; the id is optional. */
    TargetedEventHandler<ResourceLocation> BOSS_DEFEATED = GROUP.server("bossDefeated", () -> BossKubeEvent.class)
            .supportsTarget(EventTargetType.ID);

    EventHandler PARTY_JOINED = GROUP.server("partyJoined", () -> PartyKubeEvent.class);
    EventHandler PARTY_LEFT = GROUP.server("partyLeft", () -> PartyKubeEvent.class);

    /** Posted after server scripts load: {@code e.add('mypack:is_night', ctx => ctx.level.isNight())}. */
    EventHandler REGISTER_CONDITIONS = GROUP.server("registerConditions", () -> RegisterConditionsKubeEvent.class);

    /** Posted after server scripts load: {@code e.add('mypack:pay_bribe', ctx => ...)}. */
    EventHandler REGISTER_ACTIONS = GROUP.server("registerActions", () -> RegisterActionsKubeEvent.class);
}
