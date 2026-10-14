// Exercised by KubeJSGameTests; runGameTestServer copies it into run/gametest/kubejs when dev_with_kubejs=true.

StoryboundEvents.registerConditions(e => {
  e.add('gametest:script_condition', ctx => ctx.player != null && ctx.level != null)
})

StoryboundEvents.registerActions(e => {
  e.add('gametest:script_action', ctx => {
    Storybound.flags(ctx.player).set('gametest:script_action_ran', true)
  })
})

StoryboundEvents.flagChanged('gametest:watched', e => {
  Storybound.flags(e.player).set('gametest:watched_seen', e.newValue)
})

StoryboundEvents.customTrigger('gametest:script_trigger', e => {
  Storybound.triggers // touch the binding getter
  Storybound.flags(e.player).add('gametest:script_trigger_count', 1)
})

StoryboundEvents.questStateChanged('gametest:kjs_quest', e => {
  Storybound.flags(e.player).set('gametest:kjs_quest_state', e.newState)
})

StoryboundEvents.questStageChanged('gametest:kjs_quest', e => {
  Storybound.flags(e.player).set('gametest:kjs_quest_stage', e.newStage)
})

StoryboundEvents.customTrigger('gametest:kjs_complete', e => {
  let quests = Storybound.quests(e.player)
  quests.complete('gametest:kjs_quest')
  Storybound.flags(e.player).set('gametest:kjs_read_state', quests.state('gametest:kjs_quest'))
})
