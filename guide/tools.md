# Tools

## The in-game editor

Operators (permission level 2) get a **Dev Mode** button in the Adventure Menu: an editor in the manner of FTB
Quests' edit mode, writing the story folder's files.

- **Story** tab — a map of the quests: an arrow from each quest to the quests it leads to, worked out from the
  story's links (a dialogue or a script between two quests folds into the arrow). Chapters on the left group quests;
  the same chapters make the players' [chapter map](quests.md#chapter-map). Dragging a quest's port onto another makes
  the second start when the first is completed.
- **A player's progress** — "👤 Progress" on the Story tab shows where a player stands: quests they do not know are dimmed, the others marked done, at a stage, or failed.
- **Library** tab — every definition by kind, with a search; what nothing uses stands out.
- **All links** tab — every definition and every link.
- **The inspector** — a form for the selected definition, built from the JSON schemas: every field, the "when / if /
  then" lists with every type (other mods' too), pickers for items, mobs, structures, biomes, quests, dialogues…
  Positions take 📍 (where you stand) or 🎯 (click a place in the world); uuid fields take 🎯 (click an entity).
  Quick tests: start or complete a quest, start a dialogue, give a document, teleport to a waypoint.
- **Long texts** — documents, Codex sections, boss lore and tactics open in a text window with the markup at hand; a document can be previewed on its real reading screen before saving.
- **Inside a quest** — double-click a quest: what starts it, a column per stage with its objectives and everything the stage touches (dialogues, documents, waypoints, scripts, NPC lines, story events), and what follows. A click goes to the thing.
- **Dialogues** open as graphs of their own.
- **Quest givers** — a quest an NPC gives shows the NPC's dialogue before, during and after the quest, with "+ New" to
  write the missing one.
- **Scripts** — every KubeJS handler your scripts register shows as a block, with arrows to what runs it and what it
  leads to; "New file" writes a script from a template.
- Undo and redo; texts are written in the language you edit, with the source language shown beside them.

Saving checks the definition first (an invalid one is refused with the reason), then writes the file as you wrote it:
your field order and formatting stay, files you did not touch are not rewritten. No `/reload` is needed.

## Commands

All need permission level 2, except `party`.

```
/storybound flag get|clear <players> <key> [scope]
/storybound flag set <players> <key> <true|false> [scope]
/storybound var get|clear <players> <key> [scope]
/storybound var set <players> <key> <value> [scope]
/storybound var add <players> <key> <amount> [scope]
/storybound trigger fire <id> <players>
/storybound document give <players> <document> [count]
/storybound document open|mark_read <players> <document>
/storybound quest state <players> <quest> <unknown|active|completed|failed>
/storybound quest stage <players> <quest> <stage>
/storybound quest reset <players> <quest>
/storybound quest track <players> [quest]
/storybound quest list <player>
/storybound dialogue start <players> <dialogue> [speaker]
/storybound dialogue stop <players>
/storybound npc bind <entity> <dialogue> [interact|proximity|both]
/storybound npc unbind|info <entity>
/storybound codex unlock|lock <players> <entry> [section]
/storybound codex list <player>
/storybound boss unlock <players> <boss> <lore|tactics|rewards>
/storybound boss reset <players> <boss>
/storybound boss info <player> <boss>
/storybound waypoint add <id> <pos> <name> | remove <id> | list <player>
/storybound party create [name]|invite <player>|accept|leave|kick <player>|disband|info
/storybound validate
/storybound trace on [players] | off | status
/storybound dump <player>
/storybound export
/storybound capture pos <name> | entity <name> [entity]
/storybound lang extract [language] | preview [language]
/storybound example install
```

Commands work from command blocks, datapack functions and other mods' NPC actions, so a story needs no scripts.

## Finding out why something did not happen

**`/storybound trace on [players]`** writes what the story does to `logs/storybound-trace.log`, one JSON object per
line: which trigger fired, which condition failed and why ("flag mypack:gate is unset, wanted true", "has 2, wanted
5"), which action ran or failed, quest and objective changes, dialogues started and ended, and why an NPC did not talk.
`/storybound trace off` stops it.

**Files to read instead of watching the game:**

- `/storybound dump <player>` → `logs/storybound-dump-<player>.json`: the player's flags in every scope, quests with
  stages and counters, pins, Codex, bosses, Journal, the dialogue in progress.
- `/storybound export` → `logs/storybound-export.json`: every name a story can use — all condition, action and
  trigger types (other mods' too), the script handlers, every definition id, quests with their stages and objectives,
  dialogue nodes, Codex sections, declared and undeclared flags.
- `/storybound capture pos <name>` / `capture entity <name>` → `config/storybound/captures.json`: a place (with the
  block looked at and the biome) or an entity (uuid, type, name, tags), to copy into your files.

## Checking the story

Every data load checks the story: files that do not parse, unknown quests, stages, dialogues, documents, Codex entries
and bosses wherever they are named, dialogue graphs (unreachable nodes, missing targets), quest structure, undeclared
flags, script handlers nobody registers, and more. The report goes to the log, `logs/storybound-validation.txt` and
`logs/storybound-validation.json` (each issue with its file and, when known, the missing field). Operators with errors
pending get a chat line on joining; `/storybound validate` checks again.

**Without playing, for CI.** Start the pack's server with `-Dstorybound.validate`: it loads the story with every mod of
the pack, writes `logs/storybound-validation.json` (or the file named: `-Dstorybound.validate=report.json`) and stops
with exit code 1 when there are errors, 0 otherwise. `-Dstorybound.folder=<path>` checks another story folder, such as
your repository's. The server folder needs an accepted `eula.txt`.

```
java -Dstorybound.validate -Dstorybound.folder=../story -jar server.jar nogui
```

## Renaming after release: migrations

Players' saves keep ids. To rename a quest, a stage, a flag or a dialogue after people have played, add a migrations
file (`migrations/<name>.json`); progress moves to the new ids when the server starts, after `/reload` and when a
player joins:

```json
// config/storybound/migrations/renames.json
{
  "quests": { "mypack:hunt": "mypack:wolf_hunt" },
  "stages": { "mypack:wolf_hunt": { "start": "find_tracks" } },
  "objectives": { "mypack:wolf_hunt": { "0": "wolves" } },
  "flags": { "mypack:met_elder": "mypack:elder_met" }
}
```

Kinds: `flags`, `quests`, `stages` and `objectives` (inside a quest, named by its **new** id), `events`, `dialogues`,
`npc_bindings`, `documents` (document items in inventories included), `notes`, `codex`, `bosses`, `waypoints`.
Running again changes nothing; when the new id already has progress, it stays. A rename whose old id is still defined
is ignored, and the validator says so. Keep the file for as long as old saves may exist. Ids inside scheduled actions
and other mods' items are not rewritten.

## Settings

`config/storybound-common.toml` (server):

| Key | Default | Meaning |
| --- | --- | --- |
| `modules.quests`, `journal`, `codex`, `bosses`, `waypoints`, `dialogues` | `true` | turn parts of the mod off |
| `party.copyStateOnLeave` | `true` | a player leaving a party keeps a copy of its progress |
| `actions.commandPermissionLevel` | `2` | permission of `run_command` |
| `dialogues.maxDistance` | `8.0` | walking farther from the NPC interrupts the dialogue |
| `dialogues.interruptOnDamage` | `true` | taking damage interrupts it |
| `dialogues.rightClickTalks` | `false` | right-click talks to bound NPCs too (not only the Talk key) |
| `camera.allowed` | `true` | dialogues may move players' cameras |
| `detection.seenRadius`, `seenAngle`, `seenTicks` | `24`, `45`, `20` | what "seen" means: distance, half the field of view, time in view |
| `detection.killCreditRadius` | `48` | party members this close share a kill |

`config/storybound-client.toml` (each player):

| Key | Default | Meaning |
| --- | --- | --- |
| `hud.questTracker`, `questTrackerCorner`, `questTrackerOffsetY` | `true`, `TOP_RIGHT`, `70` | the pinned quests on the HUD |
| `dialogue.textSpeed` | `45` | characters per second; `0` prints at once |
| `dialogue.thinkPause` | `1.0` | the NPC's thinking pause, 0–2 times |
| `dialogue.speechBubbles` | `true` | other players' dialogue lines over heads |
| `waypoints.untrackedQuestWaypoints` | `HIDE` | waypoints of quests not pinned: `HIDE`, `DIM`, `SHOW` |
| `waypoints.markers`, `markerScale`, `markerOpacity` | `true`, `1.0`, `1.0` | in-world markers |
| `camera.enabled`, `camera.disableWith` | `true`, `[]` | the dialogue camera; mod ids that keep it off |
