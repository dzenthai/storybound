# Logic: flags, triggers, conditions, actions

Every part of a Storybound story works the same way. A trigger says when something happens, conditions say whether it
counts, and actions say what happens next. Quests, dialogues, documents, the Codex, bosses, waypoints and story
events all use these same three pieces, so once you know them you can read any file.

In the tables below, a field marked `?` is optional. Fields such as `entity` and `biome` accept an id, a `#tag` or a
list of them.

## Flags and variables

The story keeps track of what has happened by remembering named values. There are three kinds:

- a flag is true or false, for example `mypack:met_elder`;
- a number, for example `mypack:reputation`;
- a text, for example `mypack:path`.

Each value belongs to a scope, which decides who shares it:

| Scope | Who shares the value | Example |
| --- | --- | --- |
| `player` | One player | They read a letter. Their reputation with a merchant. |
| `party` | The player's party | Progress in the main story. |
| `world` | Everyone on the server | The dragon is dead. The city gates are open. |

A value that nobody has set yet counts as false, 0 or an empty text.

### Declaring a flag

You don't have to declare a flag before using it, but declaring one has advantages. Put a file in
`flags/<namespace>/<name>.json`:

```json
// config/storybound/my_story/flags/mypack/reputation.json
{ "type": "int", "default": 0, "scope": "party", "sync": true, "description": "Village reputation" }
```

`type` is required and is one of `bool`, `int` or `string`. A declared flag always has the same scope and type. If
something tries to write it in another scope or with another type, that write fails, so conditions, scripts and the
client always read the same value.

With `"sync": true` the value is also sent to the players' clients, for scripts and other mods that want to show it.
Without it the value stays on the server, so players can't peek at it.

Flags you don't declare work in any scope. The validator warns when a story event uses an undeclared flag, because it
is usually a typo.

### Parties

Players manage parties with `/storybound party`, and any player can use it:

```
/storybound party create [name]
/storybound party invite <player>
/storybound party accept
/storybound party decline
/storybound party leave
/storybound party kick <player>
/storybound party disband
/storybound party info
```

A player who is not in a party still has a party of their own, so values in the `party` scope always work.

When a party is created, the leader's own progress becomes the party's progress. When someone joins, their progress
is merged in. For each quest, the party keeps whoever got further: a completed quest beats one in progress, and one in
progress beats a failed one. Flags the party hasn't set yet are taken from the new member. A player who was behind
skips ahead, but doesn't get the rewards for what they skipped.

When a player leaves, is kicked or the party is disbanded, they keep a copy of the party's progress on top of their
own. If you set `party.copyStateOnLeave = false` in the config, they get back the progress they had before joining
instead.

When a party flag or a party quest changes, the `flag_changed`, `quest_state_changed` and `quest_stage_changed`
triggers fire for every member who is online. If something must happen only once, such as spawning a mob, ringing a
bell or running a command, put it in the quest's own actions or give the story event `"once": "party"`. The validator
warns you about story events that would repeat this way.

When the leader leaves, the member who has been in the party longest becomes the leader. When the last member leaves,
the party is disbanded.

## Triggers

A trigger fires when something changes in the game. Some triggers describe a state rather than a moment: the player
is in a place, a flag has a value, a document has been read, a quest is completed. For these, it is enough that the
state is already true:

- A quest objective counts if the player is already in the place or did the thing earlier. If you want only a new
  event to count, add `"since_stage": true`. For example, "come back to the village" should not count just because
  the player is standing in the village when the stage begins.
- A quest that is not repeatable starts as soon as its start trigger's state and conditions hold, even if that
  happened before the quest existed.
- A story event with `once` runs as soon as its state holds, if it has not run yet.

These triggers count as states: entering a place, `dimension_changed` with only `to`, `level_reached`, `flag_changed`
with a `value`, `party_joined`, `quest_state_changed` with a `state`, and `quest_stage_changed` with a `quest` and a
`stage`. So do things that stay done once they happen: `document_read`, `boss_killed` and `advancement_earned` (when
they name what they wait for), and `stat_reached`. In a repeatable quest, this last group never counts from the past.

Kills, dialogues, right-clicks, items obtained, logins and `custom` triggers are only moments. They count when they
happen and never from the past.

| Type | Fields | Fires when |
| --- | --- | --- |
| `flag_changed` | `key`, `value?` | The flag changes, or changes to `value` if you give one. |
| `entity_killed` | `entity?`, `shared?`, `by_anyone?` | The player kills the entity. With `shared`, party members nearby count too. With `by_anyone`, every player nearby counts, whoever or whatever killed it: an iron golem, another mob, a fall, lava. Use it for "drive them off" rather than "kill them yourself". |
| `entity_seen` | `entity?` | The player looks at the entity for a moment. It fires once per entity, until that entity leaves the area. |
| `entity_interacted` | `entity?` | The player right-clicks the entity. |
| `observed` | `entity`, `block` (with `pos?`) or `item`, `time?`, `distance?`, `text?` | The player keeps looking at the target for `time` seconds. A bar under the crosshair fills meanwhile, but only while it would do something. Looking away pauses the bar, and after two seconds it starts over. An item counts lying on the ground or in an item frame. |
| `area_entered` | `area`, `fire_on_login?` | The player walks into the area. It is checked every half second. |
| `structure_entered` | `structure` (an id or a `#tag`), `fire_on_login?` | The player walks into such a structure. |
| `biome_entered` | `biome`, `fire_on_login?` | The player walks into such a biome. |
| `time` | `period` (`day`, `sunset`, `night` or `sunrise`) | That time of day begins where the player is. If a stage begins when it is already night, a `night` trigger counts at once. It never fires in the Nether or the End. |
| `poi_entered` | `poi`, `radius?` (12), `fire_on_login?` | The player comes near such a point of interest: a village bell (`minecraft:meeting`), a job site block, a bed. |
| `item_obtained` | `item` or `tag` | The player picks up or crafts the item. |
| `party_joined`, `party_left` | none | The player joins or leaves a party. |
| `logged_in` | `first_time?` | The player joins the server or opens the world. With `first_time`, only on their first visit. |
| `custom` | `id` | Something fires it: `/storybound debug trigger fire`, a script or another mod. |
| `document_read` | `document?`, `first_time?` | The player reads a document. With `first_time`, only the first time. |
| `quest_state_changed` | `quest?`, `state?` | A quest starts, is completed, fails or is reset (`unknown`). |
| `quest_stage_changed` | `quest?`, `stage?` | A quest moves to a stage, including its first one. |
| `dialogue_ended` | `dialogue?`, `node?` | A dialogue finishes without being interrupted. `node` is the last node it passed. |
| `boss_killed` | `boss?`, `first_time?` | The player takes part in a victory over a boss. |
| `advancement_earned` | `advancement?` | The player earns the advancement, with or without a toast. |
| `dimension_changed` | `from?`, `to?` | The player goes to another dimension. |
| `level_reached` | `level` | The player's experience level reaches the value. |
| `stat_reached` | `stat`, `stat_type?`, `value` | A vanilla statistic reaches the value (see the `stat` condition below). |
| `cutscene_finished` | `cutscene?`, `reason?` | A cutscene ends, with the reason `finish`, `interrupt`, `command` or `error`. Needs Cutscene API. |

An area is a sphere:

```json
{ "center": [120, 64, -340], "radius": 24, "dimension": "minecraft:overworld" }
```

`dimension` is optional.

The place triggers don't fire for a player who is already inside the place after a `/reload`. They also don't fire
right after a player logs in, unless you add `"fire_on_login": true`. Use that for a scene like "the player wakes up
inside the cursed zone".

An action can change a flag, which fires another trigger, which runs more actions. Storybound stops such a chain at a
depth of 16 and writes an error to the log.

## Conditions

| Type | Fields | Holds when |
| --- | --- | --- |
| `flag` | `key`, `scope?`, `value?` (true) | The flag equals `value`. |
| `var` | `key`, `scope?`, `op?` (`=`, `!=`, `>`, `>=`, `<`, `<=`), `value` | The variable compares to `value` this way. Texts and true/false values only work with `=` and `!=`. |
| `has_item` | `item` or `tag`, `count?` (1) | The player carries at least that many. |
| `location` | `dimension?`, `biome?`, `area?`, `structure?` | The player is in a place where every part you gave matches. |
| `in_party` | `min_size?` (1) | The player is in a real party with at least that many members. |
| `time` | `period?` (`day`, `sunset`, `night`, `sunrise`), `min?`, `max?` (ticks from 0 to 24000) | The time of day fits. If `min` is larger than `max`, the range goes over midnight. |
| `weather` | `weather` (`clear`, `rain` or `thunder`) | The weather in the player's dimension matches. `rain` also covers thunder and snow. |
| `chance` | `value` (from 0 to 1) | A random roll succeeds. `0.25` holds one time in four. |
| `all`, `any` | `conditions` | All of the conditions hold, or any one of them does. |
| `not` | `condition` | The condition does not hold. |
| `document_read` | `document` | The player has read the document. |
| `quest` | `quest`, `state?`, `stage?` | The quest is in that state (`unknown`, `active`, `completed` or `failed`), or is active at that stage. |
| `codex_unlocked` | `entry`, `section?` | The Codex entry, or that section of it, is open. |
| `boss_killed` | `boss`, `count?` (1) | The player has beaten the boss at least that many times. |
| `advancement` | `advancement`, `done?` (true) | The player has the advancement, or doesn't have it when `done` is false. |
| `level` | `min?`, `max?` | The player's experience level is in the range. |
| `stat` | `stat`, `stat_type?`, `min?` (1) | A statistic is at least `min`. See below. |
| `in_cutscene` | `cutscene?` | The player is watching a cutscene. Needs Cutscene API. |
| `kubejs` | `id`, `args?` | A KubeJS script says yes (see [Integrations](integrations.md#kubejs)). |

A `stat` is either a general statistic, such as `"stat": "minecraft:mob_kills"`, or a statistic about one thing, such
as `"stat_type": "minecraft:killed", "stat": "minecraft:zombie"`. Other statistic types are `mined`, `crafted`,
`used`, `broken`, `picked_up`, `dropped` and `killed_by`.

A `conditions` field takes one condition or a list. A list holds only when all of its conditions hold.

Values in `var`, `set_var` and `flag_changed` are plain JSON: `true`, `5` or `"north"`. Numbers must be whole.

## Actions

| Type | Fields | What it does |
| --- | --- | --- |
| `set_flag` | `key`, `scope?`, `value?` (true) | Sets a flag. |
| `clear_flag` | `key`, `scope?` | Forgets a value, as if it had never been set. |
| `set_var`, `add_var` | `key`, `scope?`, `value` or `amount` | Sets a variable, or adds to it. |
| `give_item` | `item`, `count?`, `components?` | Gives the player items. What doesn't fit in the inventory is dropped at their feet. |
| `take_item` | `item` or `tag`, `count?` | Takes items from the player. If they don't have enough, it takes nothing and fails. |
| `give_document` | `document`, `count?` | Gives the player a document item. |
| `journal_entry` | `id`, `category?`, `title`, `text` | Writes a note in the player's Journal, or replaces the note with that id. |
| `notify` | `title`, `text?` | Shows a toast in the corner of the screen. |
| `title` | `title?`, `subtitle?`, `actionbar?`, `fade_in?`, `stay?`, `fade_out?` | Shows a title in the middle of the screen. |
| `play_sound` | `sound`, `source?`, `volume?`, `pitch?` | Plays a sound that only this player hears. |
| `sound_at` | `sound`, a place¹, `source?`, `volume?`, `pitch?` | Plays a sound at a place, heard by everyone nearby. |
| `fade` | `in?` (0.5), `stay?` (1, at most 60), `out?` (0.5), `color?` (`#000000`) | Fades the player's screen to a colour and back. Times are in seconds. Use it for a time skip, waking up or the start of a scene. Titles and subtitles still show on top of it. |
| `effect` | `effect`, `target?`, `duration?` (30 seconds, -1 for forever), `amplifier?`, `particles?`, `clear?` | Gives an effect, or takes it away. |
| `spawn_entity` | `entity`, `count?`, a place¹, `tag?`, `name?`, `nbt?`, `persistent?` (true), `unique?` | Spawns entities. See below. |
| `teleport` | `target?` (`player` or `entity`), a place¹, `yaw?`, `pitch?` | Moves the player, or the entity, also to another dimension. |
| `set_quest_state` | `quest`, `state` | Starts a quest (`active`), completes it, fails it or resets it (`unknown`). See below. |
| `set_quest_stage` | `quest`, `stage` | Moves a quest to a stage. |
| `track_quest` | `quest?`, `pinned?` (true) | Pins a quest to the HUD, or unpins it. |
| `start_dialogue`, `end_dialogue` | `dialogue`, or nothing | Starts a dialogue, or ends the current one. The NPC is the entity of the moment. |
| `open_trade` | none | Ends the dialogue and opens the NPC's trades. |
| `unlock_codex` | `entry`, `section?`, `target?` (`player` or `party`) | Opens a Codex section, or the whole entry. |
| `unlock_boss_section` | `boss`, `section`, `target?` | Opens a section of a boss entry: `lore`, `tactics` or `rewards`. |
| `add_waypoint`, `remove_waypoint` | See [Waypoints](waypoints.md#added-by-actions) | Adds or removes a waypoint for the player, the party or everyone. |
| `wait` | `seconds` | Runs the rest of the list that much later. |
| `schedule` | `seconds`, `actions`, `on_fail?` | Runs these actions later. The rest of the list goes on right away. |
| `play_cutscene` | `cutscene`, a place¹, `rotation?`, `path_rotation?` | Plays a cutscene. Needs Cutscene API. |
| `run_command` | `command` | Runs a command as the player (`@s`), at the permission level set by `actions.commandPermissionLevel`. |
| `run_script` | `script`, `at?` | Runs a script of vanilla commands. See [Scripts of vanilla commands](#scripts-of-vanilla-commands). |
| `kubejs` | `id`, `args?` | Runs a KubeJS handler (see [Integrations](integrations.md#kubejs)). |

¹ A place is either a position, `pos` (`[x, y, z]`) with an optional `dimension`, or an `offset` (`[dx, dy, dz]`)
from `at`. `at` is `player` (the default) or `entity`, the entity of the moment: the NPC being talked to or the mob
that was killed.

An `actions` field takes one action or a list, and the actions run in order. `wait` and `schedule` count game time,
keep counting after a server restart, and run when the player is online.

Use the typed actions instead of `run_command` when you can. The validator and the editor understand them and can
point out mistakes; a command is just text to them.

A few actions need more explanation.

`spawn_entity` can give the mob tags, so that waypoints and NPC bindings can find it. With `"unique": true` it spawns
nothing while a mob with those tags already exists. Use this for a story NPC, because otherwise every player's scene
would spawn another copy. `"unique": false` says that each player should get their own. If you leave `unique` out for
an NPC that a binding talks to, the validator asks which one you meant.

`set_quest_state` with `active` on a failed quest continues the quest where it failed and does not run its `on_start`
again. To start it over from the beginning, reset it to `unknown` first.

### When an action fails

An action fails when it can't do its job: `take_item` when the player doesn't have enough, or a flag written with the
wrong type. The list's `on_fail` decides what happens next. With `"abort"`, the default, the list stops there. With
`"continue"`, it moves on to the next action.

Put the price first. If `take_item` comes before `give_item`, a player who can't pay gets nothing, and the trade is
never left half done.

## Story events

A story event is a general rule: when this happens, if this is true, do that. Story events live in
`events/<namespace>/<name>.json`:

```json
// config/storybound/my_story/events/mypack/den_reward.json
{
  "trigger": { "type": "area_entered", "area": { "center": [120, 64, -340], "radius": 24 } },
  "conditions": { "type": "flag", "key": "mypack:village_burned" },
  "actions": [
    { "type": "take_item", "item": "minecraft:leather", "count": 10 },
    { "type": "give_item", "item": "minecraft:iron_sword" }
  ],
  "once": "player"
}
```

| Field | Default | What it does |
| --- | --- | --- |
| `trigger` | required | Says when the event runs. |
| `conditions` | none | Checked when the trigger fires. The event runs only if they hold. |
| `actions` | required | Run in order. |
| `on_fail` | `abort` | What to do when an action fails (see above). |
| `once` | runs every time | `player`, `party` or `world`: the event runs at most once per player, per party or for the whole world. A run that stopped because an action failed doesn't count. |

To build a sequence such as "a cutscene plays, five seconds later Kairen appears, then he speaks", use one story event
on `cutscene_finished` with a `wait` and a `spawn_entity`, and start the dialogue or quest that follows from there.

## Scripts of vanilla commands

A script is a list of vanilla commands that run one after another, like an `.mcfunction` file. Scripts live in the
story's `scripts/` folder, and the `run_script` action runs them.

```json
// scripts/mypack/merchant_camp.json
{ "title": "mypack.script.merchant_camp.title",
  "lines": [ "# a camp around the merchant", "setblock ~1 ~ ~ minecraft:campfire keep", "wait 2", "setblock ~-1 ~ ~1 minecraft:barrel keep" ] }
```

```json
// in a quest, a dialogue or a story event
{ "type": "run_script", "script": "mypack:merchant_camp", "at": { "tag": "mypack_merchant" } }
```

Write each command on its own line, without the leading `/`. A line `wait 2` pauses for two seconds; the rest of the
script runs later, even after a restart. A line starting with `#` is a note and is skipped.

The commands run as the player, at the place `at` names, and `~ ~ ~` in the commands means that place:

- without `at`, the place is the player;
- with `"at": "entity"`, it is the entity of the moment: the NPC being talked to, the dialogue partner, or the mob
  that was killed;
- with a selector such as `"at": { "tag": "mypack_merchant" }`, it is the nearest entity that matches, within 64
  blocks. If there is none nearby, the script doesn't run.

Storybound checks every command when the story loads, at the permission level scripts run at
(`actions.commandPermissionLevel`, 2 by default). A typo, or a command that needs more permissions, shows up as an
error with its line number, instead of silently doing nothing in the game.

### Scripts in the editor

In the editor, a script is a node of its own called "Script". A script never runs by itself: something has to run
it. The inspector on the right helps with that:

- "When it runs" shows how many nodes run the script, with a warning if none do. Who they are is listed under
  "Links" at the bottom, where ✕ removes one.
- "+ Run it in…" lets you pick what runs the script: a quest when it starts, a conversation when it ends, or a story
  event, whose trigger and conditions decide when. You can pick an existing one or make a new one next to the script.
  The same choices are in the script's right-click menu, and quests, conversations and story events have "+ Script
  (commands)" in theirs. Drawing an arrow from one of them to the script does the same thing.
- "Run at me", under "Try in game", saves the script and runs it right away where you stand, so `~ ~ ~` is you. A
  node that has its own `at` will run it there instead.
- "Commands check" shows the result once you save: "Commands parsed: 5, no errors", or the first wrong line and how
  many more there are.
