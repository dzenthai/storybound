# Quests

A quest is a story with a state and stages, not a checklist. Its state goes `unknown` (not shown) → `active` →
`completed` or `failed`. Each stage adds an entry to the quest's log, Witcher style: entries pile up instead of
replacing each other. Players see quests in the Quests tab of the Adventure Menu and up to five pinned on the HUD.

```json
// config/storybound/quests/mypack/wolf_hunt.json
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

## Quest fields

| Field | Default | Meaning |
| --- | --- | --- |
| `title` | required | the quest's name |
| `category` | `side` | the group in the Quests tab: `main`, `side`, `contract` or your own (its name is the key `<ns>.quest.category.<path>`) |
| `scope` | `player` | whose quest it is: `player`, `party` (shared; any member's progress counts) or `world` |
| `description` | — | shown when `text` has nothing for the state |
| `text` | — | `active`, `completed`, `failed`: the description per state |
| `start` | — | `trigger` and `conditions?`: starts the quest when it is unknown. Without it a quest starts by an action, a command or a script |
| `stages` | — | in order; the quest starts at the first one |
| `fail_when` | — | checked every second while the quest is active; when it holds, the quest fails |
| `on_start`, `on_complete`, `on_failed` | — | actions, run once |
| `rewards` | — | see [Rewards](#rewards) |
| `notify` | `true` | toasts when the quest starts, moves on, ends |
| `repeatable` | — | `true` or `{ "cooldown": seconds, "times": n }`: once finished, its start trigger may start it again after the cooldown, at most `times` completions |
| `difficulty` | — | stars (`1`–`5`) or a level (`"hard"`), see [Difficulty](#difficulty) |
| `time_limit` | — | seconds of game time from the start; then the quest fails. The Journal and the HUD show the time left |
| `map_visibility` | `visible` | on the [chapter map](#chapter-map): `secret` shows "???" until the quests before it are done; `invisible` hides it until it starts |

## Stages

| Field | Meaning |
| --- | --- |
| `id` | required; must not start with `@` |
| `log` | the stage's entry in the quest's log |
| `objectives` | what the player does here |
| `next` | the stage that follows: an id, `@complete` or `@fail`. By default the next in the list, or completion after the last |
| `on_enter` | actions when the stage is reached |
| `waypoints` | waypoints shown while the quest is at this stage (see [Waypoints](waypoints.md)) |

When every required objective of the stage is done, the quest moves on. A stage without objectives waits for an
action, a command or a script: that is how stages driven by dialogues and story events are written.

## Objectives

| Field | Default | Meaning |
| --- | --- | --- |
| `id` | its position | names the objective (for hints and migrations) |
| `trigger` | — | what counts; required unless `items` is given |
| `conditions` | — | checked with the trigger's context (the killed mob, the NPC) |
| `items` | — | `{ "item" or "tag", "consume"? }`: see below |
| `count` | 1 | how many times |
| `text` | — | the line in the Quests tab and on the HUD; an objective without text is not shown |
| `hidden` | false | counted but not shown |
| `optional` | false | shown and counted, but not needed for the stage |
| `hint` | — | a tip in italics under the objective until it is done |
| `hints` | — | tips that appear when they are needed, see below |

A trigger counts only while the quest is at the objective's stage, and never beyond `count`.

**Items.** `"items": { "item": "minecraft:leather" }` with `"count": 5` counts the leather the player holds, what was
there before included, and never goes back down ("have 5"). With `"consume": true` the items are taken as they come
until there are 5 ("hand in 5"). Without a trigger, items are looked at every second; with a trigger they are looked at
when it fires: "bring 10 hides to the tanner" is `items` with `consume` and the trigger `dialogue_ended`.

**Hints** appear under an objective that is under way: while their `conditions` hold, and with `after` (seconds), once
the objective has not moved for that long — the player is stuck.

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

- Every online player sharing the quest is paid: each party member gets their own rewards.
- They wait in the Journal for the **Claim** button. `choice` is one item the player picks there. A claim that does
  not fit the inventory is refused with how many slots to free: rewards never drop on the ground.
- `loot` is rolled when the quest is completed, so claiming later cannot roll again.
- `"claim": false` gives them at once when nothing is to be chosen and they fit.
- The Journal shows what a quest gives before it is done.
- An item is `{ "item", "count"?, "components"? }` or just its id.
- An action list instead of the object is still accepted: it runs at once on completion.

## Difficulty

The modpack picks one scale for all quests in `project.json`:

```json
{ "difficulty": { "style": "stars" } }
```

shows `★★★☆☆`, from 1 to 5 stars. Or named levels in colours:

```json
{ "difficulty": { "style": "levels", "levels": [
  { "id": "easy", "color": "#5DBB63", "name": "mypack.difficulty.easy" },
  { "id": "doom", "color": "#8B1A1A", "name": "Doom" } ] } }
```

Up to five levels; `name` is a translation key or the text itself. Without the setting the levels are `easy`
(green), `normal` (yellow), `hard` (red) and `deadly` (dark red). A quest names a level or a number of stars; the n-th
level is n stars, so the style can change without editing quests.

## Pinned quests

Players pin up to five quests on the HUD from the Quests tab (the newest pinned is the main one); a quest that starts
when nothing is pinned is pinned by itself. The `track_quest` action pins one from the story. Waypoints of pinned
quests show as tracked.

## Chapter map

With `"chapter_map": true` in `project.json` players get a Map tab: a quest book like FTB Quests. It shows the
editor's chapters with their quests where you placed them and an arrow from each quest to the quests that follow it —
the same arrows as the editor's Story tab, so a quest that starts the next one, a quest the next one waits for, and the
dialogues, events and scripts in between all count. Quests placed in no chapter go to a last chapter, "Other quests".

Each player sees their own progress: completed ✔, active !, failed ✘, available ? (every quest before it is done) and
locked ·. `map_visibility` hides what should stay a surprise. A started quest opens in the Quests tab on click.

## Commands

For operators, to test by hand (see [Tools](tools.md#commands) for all commands):

```
/storybound quest state <players> <quest> <unknown|active|completed|failed>
/storybound quest stage <players> <quest> <stage>
/storybound quest reset <players> <quest>
/storybound quest track <players> [quest]
/storybound quest list <player>
```
