# Logic: flags, triggers, conditions, actions

Everything in Storybound is built from the same pieces: a **trigger** says when something happens, **conditions**
say whether it counts, **actions** say what follows. Quests, dialogues, documents, the Codex, bosses, waypoints and
story events all use them the same way.

Fields marked `?` are optional. Fields like `entity` and `biome` take an id, a `#tag` or a list of them.

## Flags and variables

The story remembers named values: `mypack:met_elder` (a flag, true or false), `mypack:reputation` (a number),
`mypack:path` (a text). Each lives in a **scope**:

| Scope | Shared by | Example |
| --- | --- | --- |
| `player` | one player | read a letter, reputation with a merchant |
| `party` | the player's party | the main story |
| `world` | everyone | the dragon is slain, the city gates are open |

A value nobody wrote yet counts as false, 0 or an empty text.

**Declaring a flag** is optional (`flags/<ns>/<name>.json`):

```json
// config/storybound/flags/mypack/reputation.json
{ "type": "int", "default": 0, "scope": "party", "sync": true, "description": "Village reputation" }
```

`type` (`bool`, `int`, `string`) is required. A declared flag has a fixed scope and type: writing it elsewhere or
with another type fails, so conditions, scripts and the client always read the same value. `sync: true` sends it to
the players' clients (for scripts and other mods that show it); others stay on the server, so they spoil nothing.
Undeclared flags work in any scope; the validator warns when a story event uses one (usually a typo).

### Parties

Players form parties with `/storybound party create [name] | invite <player> | accept | leave | kick <player> |
disband | info` (open to every player). A player without a party has their own "solo party", so the `party` scope
always works.

- The leader's solo progress goes into a new party; a player joining sees the party's progress.
- A player who leaves (or is kicked, or whose party is disbanded) keeps a copy of the party's progress.
  With `party.copyStateOnLeave = false` they get their own earlier progress back instead.
- When the leader leaves, the longest-standing member leads; the last one to leave disbands the party.

## Triggers

| Type | Fields | Fires when |
| --- | --- | --- |
| `flag_changed` | `key`, `value?` | the value changes (to `value`, if given) |
| `entity_killed` | `entity?`, `shared?` | the player dealt the killing blow; with `shared`, party members nearby count too |
| `entity_seen` | `entity?` | the player looked at the entity for a moment; once per entity until it is gone from around |
| `entity_interacted` | `entity?` | right-click on an entity |
| `area_entered` | `area`, `fire_on_login?` | the player walks into the area (checked every half second) |
| `structure_entered` | `structure` (id or `#tag`), `fire_on_login?` | the player comes into such a structure |
| `biome_entered` | `biome`, `fire_on_login?` | the player walks into such a biome |
| `poi_entered` | `poi`, `radius?` (12), `fire_on_login?` | the player comes near such a point of interest (a village bell is `minecraft:meeting`, job sites, beds) |
| `item_obtained` | `item` or `tag` | picked up or crafted |
| `party_joined` / `party_left` | — | the player joined / left a party |
| `custom` | `id` | fired by `/storybound trigger fire`, a script or a mod |
| `document_read` | `document?`, `first_time?` | the player reads a document (`first_time`: only the first time) |
| `quest_state_changed` | `quest?`, `state?` | a quest starts, completes, fails or is reset (`unknown`) |
| `quest_stage_changed` | `quest?`, `stage?` | a quest moves to a stage, the first one included |
| `dialogue_ended` | `dialogue?`, `node?` | a dialogue finished (not interrupted), with the last node it passed |
| `boss_killed` | `boss?`, `first_time?` | the player took part in a boss victory |
| `advancement_earned` | `advancement?` | the player earns the advancement (any with a toast, without one) |
| `dimension_changed` | `from?`, `to?` | the player goes to another dimension |
| `level_reached` | `level` | the experience level reaches the value |
| `stat_reached` | `stat`, `stat_type?`, `value` | a vanilla statistic reaches the value (see the `stat` condition) |
| `cutscene_finished` | `cutscene?`, `reason?` | a cutscene ended (`finish`, `interrupt`, `command`, `error`); needs Cutscene API |

An area is a sphere: `{ "center": [x, y, z], "radius": r, "dimension"?: "minecraft:overworld" }`.

The place triggers do not fire for a player who is already inside after `/reload`, nor after logging in unless
`"fire_on_login": true` ("the player woke up inside the cursed zone"). Trigger chains (an action changes a flag, which
fires another event) stop at depth 16 with an error in the log.

## Conditions

| Type | Fields | Holds when |
| --- | --- | --- |
| `flag` | `key`, `scope?`, `value?` (true) | the flag equals `value` |
| `var` | `key`, `scope?`, `op?` (`=`, `!=`, `>`, `>=`, `<`, `<=`), `value` | the variable compares so; texts and booleans only `=` / `!=` |
| `has_item` | `item` or `tag`, `count?` (1) | the inventory holds that many |
| `location` | `dimension?`, `biome?`, `area?`, `structure?` | every given part matches |
| `in_party` | `min_size?` (1) | the player is in a real party of at least that many |
| `time` | `period?` (`day`, `sunset`, `night`, `sunrise`), `min?`, `max?` (ticks 0–24000) | the time of day fits; `min` above `max` wraps over midnight |
| `weather` | `weather` (`clear`, `rain`, `thunder`) | in the player's dimension (`rain` includes thunder and snow) |
| `chance` | `value` (0–1) | at random |
| `all` / `any` | `conditions` | all / any of them hold |
| `not` | `condition` | it does not hold |
| `document_read` | `document` | the player has read it |
| `quest` | `quest`, `state?`, `stage?` | the quest is in that state (`unknown`, `active`, `completed`, `failed`) or active at that stage |
| `codex_unlocked` | `entry`, `section?` | the entry (or that section) is open |
| `boss_killed` | `boss`, `count?` (1) | the player has that many victories over the boss |
| `advancement` | `advancement`, `done?` (true) | the player has (or has not) the advancement |
| `level` | `min?`, `max?` | the experience level is in range |
| `stat` | `stat`, `stat_type?`, `min?` (1) | a statistic is at least `min`: `"stat": "minecraft:mob_kills"`, or `"stat_type": "minecraft:killed", "stat": "minecraft:zombie"` (also `mined`, `crafted`, `used`, `broken`, `picked_up`, `dropped`, `killed_by`) |
| `in_cutscene` | `cutscene?` | the player is watching a cutscene; needs Cutscene API |
| `kubejs` | `id`, `args?` | a script's predicate says so (see [Integrations](integrations.md#kubejs)) |

A `conditions` field takes one condition or a list; a list means all of them. Values in `var`, `set_var` and
`flag_changed` are plain JSON: `true`, `5`, `"north"` (whole numbers only).

## Actions

| Type | Fields | Does |
| --- | --- | --- |
| `set_flag` | `key`, `scope?`, `value?` (true) | writes a flag |
| `clear_flag` | `key`, `scope?` | forgets a value |
| `set_var` / `add_var` | `key`, `scope?`, `value` / `amount` | writes / adds to a variable |
| `give_item` | `item`, `count?`, `components?` | gives items; what does not fit drops |
| `take_item` | `item` or `tag`, `count?` | takes items, **all or nothing**: with too few it takes none and fails |
| `give_document` | `document`, `count?` | gives a document item |
| `journal_entry` | `id`, `category?`, `title`, `text` | writes or replaces a Journal note |
| `notify` | `title`, `text?` | a toast |
| `title` | `title?`, `subtitle?`, `actionbar?`, `fade_in?`, `stay?`, `fade_out?` | a title on screen |
| `play_sound` | `sound`, `source?`, `volume?`, `pitch?` | a sound only this player hears |
| `sound_at` | `sound`, place¹, `source?`, `volume?`, `pitch?` | a sound everyone near the place hears |
| `effect` | `effect`, `target?`, `duration?` (30 s, -1 forever), `amplifier?`, `particles?`, `clear?` | gives (or takes away) an effect |
| `spawn_entity` | `entity`, `count?`, place¹, `tag?`, `name?`, `nbt?`, `persistent?` (true) | spawns entities; tags let waypoints and NPC bindings find them |
| `teleport` | `target?` (`player` / `entity`), place¹, `yaw?`, `pitch?` | moves the player (or the entity), across dimensions too |
| `set_quest_state` | `quest`, `state` | starts (`active`), completes, fails or resets (`unknown`) a quest |
| `set_quest_stage` | `quest`, `stage` | moves a quest to a stage |
| `track_quest` | `quest?`, `pinned?` (true) | pins a quest on the HUD (or unpins) |
| `start_dialogue` / `end_dialogue` | `dialogue` / — | starts a dialogue (with the entity of the moment as the NPC) / ends it |
| `open_trade` | — | ends the dialogue and opens the NPC's trades |
| `unlock_codex` | `entry`, `section?`, `target?` (`player` / `party`) | opens a Codex section (or all) |
| `unlock_boss_section` | `boss`, `section`, `target?` | opens a boss entry section (`lore`, `tactics`, `rewards`) |
| `add_waypoint` / `remove_waypoint` | see [Waypoints](waypoints.md#added-by-actions) | a waypoint for the player, the party or everyone |
| `wait` | `seconds` | the rest of the list runs that much later |
| `schedule` | `seconds`, `actions`, `on_fail?` | these actions run later; the list goes on at once |
| `play_cutscene` | `cutscene`, place¹, `rotation?`, `path_rotation?` | plays a cutscene; needs Cutscene API |
| `run_command` | `command` | runs a command as the player (`@s`), at permission level `actions.commandPermissionLevel` |
| `kubejs` | `id`, `args?` | runs a script's handler (see [Integrations](integrations.md#kubejs)) |

¹ A place is `pos` (`[x, y, z]`) with `dimension?`, or `offset?` (`[dx, dy, dz]`) from `at?`: `player` (default) or
`entity` — the entity of the moment: the NPC talked to, the mob killed.

An `actions` field takes one action or a list; they run in order. `wait` and `schedule` count game time and survive a
restart; they run when the player is online.

Prefer the typed actions to `run_command`: the validator and the editor understand them.

### When an action fails

An action fails when it cannot do its job: `take_item` without enough items, a flag written with the wrong type.
The list decides what happens next with `on_fail`: `"abort"` (default) stops there, `"continue"` goes on. Put the
price first — `take_item` before `give_item` — so an aborted trade leaves nothing half done.

## Story events

General "when, if, then" rules (`events/<ns>/<name>.json`):

```json
// config/storybound/events/mypack/den_reward.json
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

| Field | Default | Meaning |
| --- | --- | --- |
| `trigger` | required | what starts it |
| `conditions` | always | checked when the trigger fires |
| `actions` | required | run in order |
| `on_fail` | `abort` | see above |
| `once` | every time | `player`, `party` or `world`: at most once per player, party or world. An aborted run does not count. |

A chain such as "a cutscene, then five seconds later Kairen appears, then he speaks" is a story event on
`cutscene_finished` with `wait` and `spawn_entity`, and a second one on the dialogue or quest that follows.
