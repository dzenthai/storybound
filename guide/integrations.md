# Integrations

Storybound needs only NeoForge. Everything below is optional and turns on when the mod is installed.

| Mod | What it adds |
| --- | --- |
| [KubeJS](#kubejs) | script conditions and actions, story events in scripts, a `Storybound` binding |
| [JEI](#jei) | what quests ask for and give, as recipes |
| [Cutscene API](#cutscene-api) | cutscenes in story chains |
| Xaero's Minimap, JourneyMap | Storybound's waypoints on the minimap and the full map |
| Shoulder Surfing, Epic Fight | paused while the dialogue camera frames a shot |
| [Easy NPC](#easy-npc) | its NPCs work as Storybound NPCs as they are |

## KubeJS

Storybound's JSON calls scripts with `{ "type": "kubejs", "id": "..." }` as a condition or an action. One handler can
serve the whole story through `args`:

```js
// kubejs/server_scripts/story.js
StoryboundEvents.registerConditions(e => {
  e.add('mypack:is_night', ctx => ctx.level.isNight())
})
StoryboundEvents.registerActions(e => {
  // { "type": "kubejs", "id": "mypack:summon", "args": { "entity": "minecraft:zombie", "count": 3 } }
  e.add('mypack:summon', ctx => {
    for (let i = 0; i < ctx.arg('count', 1); i++) ctx.player.runCommandSilent(`summon ${ctx.args.entity} ~ ~ ~`)
  })
})
```

A handler gets `ctx.player`, `ctx.level`, `ctx.entity` (the entity of the moment, may be null), `ctx.server` and
`ctx.args` (with `ctx.arg(name, fallback)`). Handlers are registered again on every `/reload`. A condition nobody
registered is false and an action does nothing (the validator and the trace say so).

**Events** (the id is optional; without it the listener hears every one):

```js
StoryboundEvents.flagChanged('mypack:met_ciri', e => { /* e.key, e.scope, e.oldValue, e.newValue, e.player */ })
StoryboundEvents.customTrigger('mypack:bell_rung', e => { /* e.player, e.id */ })
StoryboundEvents.partyJoined(e => { /* e.player, e.playerId, e.partyId */ })
StoryboundEvents.partyLeft(e => { /* ... e.reason: 'left' | 'kicked' | 'disbanded' */ })
StoryboundEvents.questStateChanged('mypack:wolf_hunt', e => { /* e.quest, e.oldState, e.newState, e.oldStage, e.newStage, e.player */ })
StoryboundEvents.questStageChanged('mypack:wolf_hunt', e => { /* the same */ })
StoryboundEvents.dialogueStarted('mypack:elder', e => { /* e.player, e.dialogue, e.speaker */ })
StoryboundEvents.dialogueEnded('mypack:elder', e => { /* ... e.node, e.aborted, e.reason */ })
StoryboundEvents.npcInteract('mypack:elder', e => { /* a binding with "custom": true; e.player, e.entity, e.binding */ })
StoryboundEvents.codexUnlocked('mypack:drowner', e => { /* e.player, e.entry, e.section, e.newEntry */ })
StoryboundEvents.bossDefeated('mypack:drowned_king', e => { /* e.player, e.boss, e.entity, e.first, e.fightSeconds */ })
```

**The binding:**

```js
Storybound.flags(player).get('mypack:gold')                  // the value, or null
Storybound.flags(player).set('mypack:road', 'north', 'party') // the scope is optional
Storybound.flags(player).add('mypack:gold', 5)
Storybound.flags(player).clear('mypack:met_ciri')
Storybound.triggers.fire('mypack:bell_rung', player)
Storybound.quests(player).state('mypack:wolf_hunt')          // 'unknown' | 'active' | 'completed' | 'failed'
Storybound.quests(player).start('mypack:wolf_hunt')          // also setStage, complete, fail, reset, track, stage
Storybound.dialogues(player).start('mypack:elder', npc)      // also current(), end(), stop()
Storybound.codex(player).unlock('mypack:drowner', 'weakness') // also lock, isUnlocked
Storybound.bosses(player).victories('mypack:drowned_king')    // also isEncountered, unlock, reset
Storybound.waypoints(player).add('mypack:camp', 'mypack.waypoint.camp', x, y, z, 'party')
Storybound.waypoints(player).structure('mypack:village', 'mypack.waypoint.village', '#minecraft:village')
Storybound.waypoints(player).remove('mypack:camp', 'party')
```

Changes made from scripts follow the same rules as from JSON: actions, rewards, triggers, toasts and syncing all
happen. In the editor, every handler your scripts register shows as a script block; a comment
`// @storybound leads_to quest:mypack:chapter_2` in a script draws an arrow to what the script leads to.
Deleting a block whose handler is still in your scripts only takes it off the canvas (`"hidden": true` in its
file); the code stays. Delete that file to show the block again.

## JEI

A "Storybound quests" category: press U on an item to see the quests that ask for it ("Hand in ×12 (taken)",
"Have ×4") and R to see the quests that give it as a reward. Only quests the player has started are listed, so JEI
spoils nothing.

## Cutscene API

With [Cutscene API](https://modrinth.com/mod/cutscene-api) a story chains cutscenes: the `play_cutscene` action, the
`cutscene_finished` trigger (with `reason`: `finish`, `interrupt`, `command`, `error`) and the `in_cutscene` condition.
While a cutscene plays, NPCs start no dialogue and the quest tracker and waypoint markers hide.

"Cutscene, then a dialogue, then a quest" is `play_cutscene`, a story event on `cutscene_finished` that starts the
dialogue, and the dialogue's action that starts the quest.

## Easy NPC

Easy NPC's NPCs are ordinary entities, so NPC bindings select them by type (`easy_npc:humanoid`), `uuid` or `tag`, the
portrait is the NPC as it looks, and its "run command" action can start a dialogue:
`/storybound dialogue start @p mypack:elder @s`. Easy NPC's own dialogue system stays available: choose per NPC.

## Map and camera mods

Storybound's waypoints appear in Xaero's Minimap and JourneyMap (not saved to their files) and keep the in-world
markers to Storybound. Shoulder Surfing and Epic Fight (its target lock and battle mode) step aside while the dialogue
camera frames a shot. A client can keep the dialogue camera off with certain mods: `camera.disableWith` in
`config/storybound-client.toml`.
