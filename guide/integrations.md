# Integrations

Storybound only needs NeoForge. Everything on this page is optional and turns on by itself when the other mod is
installed.

| Mod | What it adds |
| --- | --- |
| [KubeJS](#kubejs) | Conditions and actions written in JavaScript, story events in scripts, and a `Storybound` object to drive the story from scripts. |
| [JEI](#jei) | Shows what quests ask for and what they give, as recipes. |
| [Cutscene API](#cutscene-api) | Cutscenes as part of the story. |
| Xaero's Minimap, JourneyMap | Storybound's waypoints on the minimap and the full map. |
| Shoulder Surfing, Epic Fight | Paused while the dialogue camera is in use. |
| [Easy NPC](#easy-npc) | Its NPCs work as Storybound NPCs without changes. |

## KubeJS

A story calls a KubeJS script with `{ "type": "kubejs", "id": "..." }`, as a condition or as an action. With `args`,
one handler can serve the whole story:

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

A handler receives:

- `ctx.player`, `ctx.level` and `ctx.server`;
- `ctx.entity`, the entity of the moment, which may be null;
- `ctx.args`, and `ctx.arg(name, fallback)` to read one argument with a default.

Handlers are registered again on every `/reload`. A condition that no script registered is false, and an action that
no script registered does nothing. The validator and the trace both point this out.

### Events

You can listen to what happens in the story. The id is optional, and without it the listener hears every event of
that kind.

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

### Driving the story from scripts

The `Storybound` object reads and changes the story:

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

Changes made from scripts follow the same rules as changes made from JSON. Actions, rewards, triggers, toasts and
syncing to clients all happen the same way.

### KubeJS in the editor

Where KubeJS is installed, every handler your scripts register shows up in the editor as a "KubeJS script" block. Its
file is kept in the story's `kubejs/` folder. A comment such as `// @storybound leads_to quest:mypack:chapter_2` in a
script draws an arrow from the block to what the script leads to.

Deleting a block whose handler is still in your scripts only removes it from the canvas, by writing `"hidden": true`
in its file. Your code stays as it is. Delete that file to show the block again.

## JEI

JEI gets a "Storybound quests" category. Press U on an item to see the quests that ask for it ("Hand in ×12 (taken)",
"Have ×4"), and R to see the quests that give it as a reward. Only quests the player has already started are listed,
so JEI doesn't spoil anything.

## Cutscene API

With [Cutscene API](https://modrinth.com/mod/cutscene-api), cutscenes become part of the story. You get the
`play_cutscene` action, the `cutscene_finished` trigger (with a `reason`: `finish`, `interrupt`, `command` or `error`)
and the `in_cutscene` condition. While a cutscene plays, NPCs don't start dialogues, and the quest tracker and
waypoint markers are hidden.

To play a cutscene, then a dialogue, then start a quest: use `play_cutscene`, add a story event on
`cutscene_finished` that starts the dialogue, and give the dialogue an action that starts the quest.

## Easy NPC

Easy NPC's NPCs are ordinary entities. NPC bindings can pick them by type (`easy_npc:humanoid`), `uuid` or `tag`, and
the dialogue portrait shows the NPC as it looks. Easy NPC's "run command" action can start a Storybound dialogue:

```
/storybound dialogue start @p mypack:elder @s
```

Easy NPC's own dialogue system still works, so you can choose for each NPC which one to use.

## Map and camera mods

Storybound's waypoints appear in Xaero's Minimap and in JourneyMap. They aren't saved in those mods' files, and the
markers in the world are still drawn by Storybound.

Shoulder Surfing and Epic Fight (its target lock and battle mode) step aside while the dialogue camera is in use. A
player can keep the dialogue camera off whenever certain mods are installed, with `camera.disableWith` in
`config/storybound-client.toml`.
