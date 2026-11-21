# Quests

A quest in Storybound tells a small story of its own. It has a state and goes through stages, and every stage adds a
new entry to the quest's log, the way quest logs work in The Witcher: new entries are added under the old ones
instead of replacing them.

A quest starts out `unknown`, which means the player doesn't see it yet. It becomes `active`, and ends up `completed`
or `failed`. Players see their quests in the Quests tab of the Adventure Menu, and can pin up to five of them to the
HUD.

Here is a complete quest. The sections below explain each part.

```json
// config/storybound/my_story/quests/mypack/wolf_hunt.json
{
  "category": "contract",
  "scope": "party",
  "title": "mypack.quest.wolf_hunt.title",
  "text": { "active": "mypack.quest.wolf_hunt.active", "completed": "mypack.quest.wolf_hunt.completed" },
  "start": { "trigger": { "type": "document_read", "document": "mypack:elder_letter" } },
  "difficulty": "normal",
  "stages": [
    {
      "id": "hunt",
      "log": "mypack.quest.wolf_hunt.log.hunt",
      "objectives": [
        { "id": "wolves", "trigger": { "type": "entity_killed", "entity": "minecraft:wolf" }, "count": 3,
          "text": "mypack.quest.wolf_hunt.objective.wolves",
          "hints": [ { "text": "mypack.quest.wolf_hunt.hint.night", "conditions": { "type": "time", "period": "day" } } ] },
        { "id": "hides", "items": { "item": "minecraft:leather", "consume": true }, "count": 5,
          "text": "mypack.quest.wolf_hunt.objective.hides" }
      ],
      "on_enter": { "type": "give_document", "document": "mypack:hunters_oath" },
      "waypoints": [ "mypack:wolf_den" ]
    },
    {
      "id": "report",
      "log": "mypack.quest.wolf_hunt.log.report",
      "objectives": [ { "trigger": { "type": "dialogue_ended", "dialogue": "mypack:elder_report" },
                        "text": "mypack.quest.wolf_hunt.objective.report" } ]
    }
  ],
  "fail_when": { "type": "flag", "key": "mypack:elder_dead" },
  "on_complete": { "type": "add_var", "key": "mypack:village_reputation", "amount": 10 },
  "rewards": { "items": [ { "item": "minecraft:emerald", "count": 5 } ], "xp": 50 }
}
```

The quest starts when the player reads the elder's letter. In the first stage, the player kills three wolves and
hands in five pieces of leather. In the second, they report back to the elder. If the elder dies, the quest fails.

## Quest fields

| Field | Default | What it does |
| --- | --- | --- |
| `title` | required | The quest's name. |
| `category` | `side` | The group the quest is listed under in the Quests tab: `main`, `side`, `contract` or a name of your own. A category of your own takes its display name from the key `<namespace>.quest.category.<name>`. |
| `scope` | `player` | Whose quest it is. `player`: one player's. `party`: shared by the party, and any member's progress counts. `world`: one quest for the whole server. See [Shared quests](#shared-quests). |
| `description` | none | Shown when `text` has nothing for the current state. |
| `text` | none | A description for each state: `active`, `completed`, `failed`. |
| `start` | none | A `trigger` and optional `conditions` that start the quest while it is still unknown. Without `start`, the quest is started by an action, a command or a script. |
| `stages` | none | The stages, in order. The quest begins at the first one. |
| `fail_when` | none | A condition checked every second while the quest is active. When it holds, the quest fails. |
| `on_start`, `on_complete`, `on_failed` | none | Actions that run once, when the quest starts, is completed or fails. |
| `rewards` | none | See [Rewards](#rewards). |
| `notify` | `true` | Shows a toast when the quest starts, moves to another stage or ends. |
| `screen_text` | none | Text in the middle of the screen when the quest starts, is completed or fails. See below. |
| `repeatable` | none | `true`, or `{ "cooldown": seconds, "times": n }`. Once the quest is finished, its start trigger can start it again after the cooldown, up to `times` completions. |
| `difficulty` | none | A number of stars from `1` to `5`, or a level such as `"hard"`. See [Difficulty](#difficulty). |
| `time_limit` | none | Seconds of game time from the start. When they run out, the quest fails. The Journal and the HUD show the time left. |

### Text on the screen

`screen_text` shows the quest's title big in the middle of the screen, as The Witcher does, with a line of your own
under it:

```json
"screen_text": { "started": "mypack.quest.wolf_hunt.started", "completed": "mypack.quest.wolf_hunt.done" }
```

Each of `started`, `completed` and `failed` is a text or a translation key, and only the ones you write are shown. An
empty text, `""`, shows the title alone. The title turns green when the quest is completed and red when it fails.
Keep each line short, because the game doesn't wrap it.

Everyone who shares the quest sees the text, whatever `notify` says, and the toast is shown as well. In the editor
this is "Text on the screen", with "+ Show on the screen" for each moment.

## Shared quests

A `party` or `world` quest has a single progress for everyone who shares it: all members of the party, or every
player on the server.

| | `party` | `world` |
| --- | --- | --- |
| Who starts it | The first member whose start trigger fires. | The first player whose start trigger fires. It starts for everyone. |
| Objectives | Any member's actions count. Items are counted in each player's own inventory. | The same, for any player. |
| `on_start`, `on_complete`, `on_failed`, `on_enter` | Run once. | Run once. |
| Story events and quests that react to a change | Run for every member who is online. | Run for every player who is online. |
| Rewards | Every member gets them. Members who are away get them when they next join, and so does a player who skips the quest by joining the party. | The players who are online when the quest is completed. Nobody who joins later. |
| `fail_when` | Checked for each member. If it holds for one of them, the quest fails for all. | Checked for each player. If it holds for one, the quest fails for the server. |
| `time_limit` | One timer for the party. | One timer for the server. |

Because a change reaches everyone online, a story event that reacts to it runs once per player. A bell, a spawned mob
or a command in that event would repeat. Put such things in the quest's own actions instead, or give the event
`"once": "party"` (`"once": "world"` for a world quest). The validator warns you about this.

## Stages

| Field | What it does |
| --- | --- |
| `id` | Required. It must not start with `@`. |
| `log` | The stage's entry in the quest's log. |
| `objectives` | What the player has to do in this stage. |
| `next` | The stage that comes next: a stage id, `@complete` or `@fail`. By default it is the next stage in the list, and after the last one the quest is completed. |
| `on_enter` | Actions that run when the quest reaches this stage. |
| `waypoints` | Waypoints shown while the quest is at this stage (see [Waypoints](waypoints.md)). |

When all the required objectives of a stage are done, the quest moves on. A stage without objectives waits until an
action, a command or a script moves the quest on. That is how you write stages that a dialogue or a story event ends.

## Objectives

| Field | Default | What it does |
| --- | --- | --- |
| `id` | its position | A name for the objective, used by hints and migrations. |
| `trigger` | none | What counts. Required unless `items` is given. |
| `conditions` | none | Checked together with the trigger, and can look at what the trigger was about: the mob that was killed, the NPC. |
| `items` | none | `{ "item" or "tag", "consume"? }`. See below. |
| `count` | 1 | How many times it has to happen. |
| `text` | none | The line shown in the Quests tab and on the HUD. An objective without text isn't shown. |
| `hidden` | false | Counted, but not shown. |
| `optional` | false | Shown and counted, but not needed to finish the stage. |
| `hint` | none | A tip in italics under the objective, shown until it is done. |
| `hints` | none | Tips that appear only when they are needed. See below. |

A trigger only counts while the quest is at the objective's stage, and never beyond `count`.

### Items

`"items": { "item": "minecraft:leather" }` with `"count": 5` means "have 5 leather". It counts the leather the player
carries, including what they had before, and once reached it never goes back down.

With `"consume": true` it means "hand in 5 leather". The items are taken from the player as they get them, until 5
have been handed in.

Without a trigger, the inventory is checked every second. With a trigger, it is checked when the trigger fires. So
"bring 10 hides to the tanner" is an `items` objective with `consume` and the trigger `dialogue_ended`. Meanwhile, the
Journal and the HUD show how many the player carries ("7/10"), so they know when they have enough.

### Things already done

When an objective's trigger is a state, such as being in a place, having read a document, having reached a level or
a quest being completed, it also counts if that is already true when the objective becomes current. The player may
have walked into the village before the quest got to that stage, or read the letter earlier.

If you want a new event after the stage began, add `"since_stage": true`.

In repeatable quests, things done in the past, such as a document read or a boss beaten, never count. Otherwise
every repetition would be finished the moment it started.

### Hints

Hints appear under an objective that is in progress, when they are useful. A hint with `conditions` shows while they
hold. A hint with `after` shows once the objective hasn't moved for that many seconds, which usually means the player
is stuck.

```json
"hints": [
  { "text": "mypack.hint.wolves_at_night", "conditions": { "type": "time", "period": "day" } },
  { "text": "mypack.hint.east_forest", "after": 300 }
]
```

## Rewards

```json
"rewards": {
  "items":  [ { "item": "minecraft:emerald", "count": 5 } ],
  "choice": [ { "item": "minecraft:iron_sword" }, { "item": "minecraft:bow" } ],
  "loot": "minecraft:chests/village/village_weaponsmith",
  "xp": 50,
  "actions": [ { "type": "set_flag", "key": "mypack:paid" } ],
  "claim": true
}
```

Rewards wait in the Journal until the player presses Claim. The player picks one item from `choice` there. If the
rewards don't fit in the inventory, the claim is refused and the player is told how many slots to free, so rewards
never end up on the ground.

`loot` is rolled when the quest is completed, so claiming later can't roll it again. With `"claim": false`, the
rewards are given right away, as long as there is nothing to choose and they fit.

The Journal shows what a quest gives before it is done. After the player has claimed it, the Journal shows what they
actually received, including the chosen item and the rolled loot.

Every player who shares the quest and is online gets their own rewards. A party member who is away when the party
finishes the quest gets them when they next join.

A player who joins a party that has already completed a quest, or whose progress lets the party skip ahead, gets the
rewards for it once, as if they had been there. This doesn't apply to repeatable quests, and the quest's
`on_complete` actions don't run again for them. If every player must end up with something, such as a key or a
document, put it in the rewards and not in `on_complete`.

An item is written as `{ "item", "count"?, "components"? }`, or just as its id. An older format, a plain list of
actions instead of the rewards object, still works: the actions run as soon as the quest is completed.

## Difficulty

The modpack chooses one difficulty scale for all quests, in `project.json`. Stars show as `★★★☆☆`, from 1 to 5:

```json
{ "difficulty": { "style": "stars" } }
```

Or you can use named levels with colours:

```json
{ "difficulty": { "style": "levels", "levels": [
  { "id": "easy", "color": "#5DBB63", "name": "mypack.difficulty.easy" },
  { "id": "doom", "color": "#8B1A1A", "name": "Doom" } ] } }
```

You can have up to five levels. `name` is a translation key or the text itself. Without this setting, the levels are
`easy` (green), `normal` (yellow), `hard` (red) and `deadly` (dark red).

A quest gives either a level or a number of stars. The first level equals one star, the second two stars, and so on,
so you can switch styles later without editing every quest.

## Pinned quests

Players pin up to five quests to the HUD from the Quests tab. The most recently pinned one is the main one. A quest
that starts while nothing is pinned is pinned automatically.

`"track_given_quests"` in `project.json` is on unless you set it to `false`. In the editor it is "Track a quest when
it is given" under ⚙ Settings. While it is on, every quest the player gets is pinned as the main one, unless they
already have five pinned, in which case they keep their five.

The `track_quest` action pins a quest from the story. Waypoints of pinned quests are shown as tracked.

## Commands

These commands are for operators, to test quests by hand. All commands are listed in [Tools](tools.md#commands).

```
/storybound quest state <players> <quest> <unknown|active|completed|failed>
/storybound quest stage <players> <quest> <stage>
/storybound quest reset <players> <quest>
/storybound quest track <players> [quest]
/storybound quest list <player>
```
