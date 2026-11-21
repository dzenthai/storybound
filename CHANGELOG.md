# Changelog

## 0.1.0 — first release

Storybound is a data-driven story engine for NeoForge 1.21.1 modpacks. Stories are JSON files in
`config/storybound/` (or in datapacks) with JSON schemas for VS Code, or are built in the in-game editor.
Required on both client and server.

### Story logic
- Story flags and variables in player, party and world scopes; parties with shared progress.
- One "when / if / then" language for everything: triggers (kills, items, places, structures, biomes, dialogues,
  advancements, dimensions, levels, statistics, entities seen...), conditions and typed actions (spawn an entity,
  teleport, effects, titles, sounds, waypoints, documents...).
- Story events, delays and schedules that survive a restart, KubeJS handlers with arguments (`ctx.args`).
- `logged_in` trigger: when a player joins (or only on their first visit).
- `observed` trigger: the player looks at an entity, a block or an item (on the ground, in an item frame) for a
  while; a bar under the crosshair fills meanwhile, only while it would do something.
- `time` trigger: night falls, day breaks (and at once when the stage begins at that time of day).
- Scripts of vanilla commands (`scripts/`, like an `.mcfunction`) run by `run_script` at the player, an NPC or a mob
  the selector finds; `wait 2` pauses, even across a restart. Every command is checked when the story loads, with the
  permission level scripts run at, so a typo is an error naming its line.
- `fade` action: the screen fades to a colour and back (waking up, a time cut, the start of a scene).

### Quests
- Stages, objectives (triggers or items to have or hand in), a Witcher-style log, failure conditions and time limits.
  Item objectives show how many the player carries before handing them in.
- Rewards as in FTB Quests: items, one of several to choose, loot tables, experience, claimed in the Journal, which
  then shows what was received. Party members who were away are paid when they join.
- Party quests: what follows a party quest (a personal quest, a story event) happens to every member online, and so
  does what follows a party flag. What follows a world quest happens to every player online.
- Joining a party brings your progress: each quest goes on from whoever got further, and flags the party has not
  set come along; whoever skips a completed quest that way gets its rewards. Leaving keeps your own under the
  party's.
- Changing a quest's `scope` (for example from `player` to `party`) keeps the progress players already have.
- A finished quest made active again starts over (completed) or goes on where it failed (failed; reset it first to
  run its `on_start` again); a quest left at a stage that was removed moves to the first stage instead of getting
  stuck.
- Objectives and quest starts do not wait for ever when what they wait for happened first: a trigger that
  announces a state (in a place, a document read, a quest completed, a level reached, ...) counts when that state
  already holds (`since_stage` asks for a new event); `once` story events run by their state too.
- `spawn_entity` with `"unique": true` spawns an NPC only if it is not there yet.
- The validator warns about what would repeat in multiplayer: a story NPC spawned without `unique` where each player
  or party would get a copy, and a story event on a party change that repeats a spawn, a sound, a command or a shared
  counter once per member.
- Repeatable quests with a cooldown, difficulty (stars 1–5 or coloured levels), objective hints that appear when the
  player is stuck, up to five quests pinned on the HUD; optionally every quest given is pinned by itself.
- A quest's title on the screen when it starts, is completed or fails, with a subtitle of your own.

### Dialogues
- Monologues in subtitles let the player fight and talk: a hit does not cut them, talking to an NPC takes over. A
  dialogue started while the player talks waits its turn. NPCs greet a player standing near once they have something
  to say.
- Dialogue graphs with choices, conditions, one-time options, timers and calls into other dialogues.
- A cinematic camera (shot / reverse shot, walls respected), speech bubbles for players nearby, a "thinking" pause.
  While a line plays the mouse turns the camera a little, as in The Witcher 3, and it glides back for the answers.
- Who an NPC is: one mob by its tag, or every mob of a kind narrowed down by where it is (dimension, biome,
  structure, a village), its age and its own saved data (a villager's profession, a modded mob's variant). The
  choices are read from the world and the mob itself, so modded mobs, biomes and structures work with no setup.
  Several NPCs add up on one mob in layers (every villager, smiths, the elder); `one` lets the mod pick one mob for
  good, `share` a per cent of them; names from a list. NPCs that talk to one player at a time; different lines
  before, during and after a quest.
- Phrases: chatter nearby, answers to a hit or a click, at random or in order. A story paused by a fight picks up
  again ("Where was I"), or the talk goes on through the fight.
- NPCs that come back (`"respawn": true`): the same NPC returns where it died or disappeared, and drops nothing.
- How an NPC moves: its kind's own walk, staying where it stands (its head turned to the nearest player), or walking
  around its home. It stands still and looks at the player while it talks.
- Relationships: an NPC's scale from -50 to 50 shows on the HUD as stars and skulls when it changes.
- A line's camera can look at an entity or a place (`look_at`) and fade the screen (`fade`).
- Music of the story: a dialogue's, a quest stage's, a place's; it pauses Minecraft's own music. Voice-over: a line's
  sound, an NPC's voice, which stops when the player moves on.
- Sounds of your own: `.ogg` files in `config/storybound/sounds/<namespace>/` play as `<namespace>:<name>`, picked
  and heard in the editor; files that would not play are named with the reason.

### Journal, Codex, bosses, waypoints
- Readable documents (books, letters, notes, contracts) in their own markup, kept in the Journal; a book can be put
  on a lectern and read there.
- Signed books a player reads are kept in their Journal under "Your books".
- A "Companions" tab of the Adventure Menu on servers and LAN worlds: the party, invites, the players online.
- Sounds of the interface: a quest started, updated, completed or failed, a Codex entry, a boss met and defeated,
  reputation up or down, each with a subtitle.
- A hint under the quest tracker which key opens the Adventure Menu, until it has been opened once.
- Codex entries that open on sight, kills and reading; bosses with fight records. A book read before the entry (or
  the section) existed opens it on the next join; a player who fought a boss and left before the end gets the victory
  when they come back.
- One kill or boss victory counts once for a party quest, however many members it is credited to.
- Search areas show their radius by the name and their edge on the ground near the player (each can be turned off;
  players can turn the edges off in the client settings).
- A waypoint's colour paints its marker, name, edge arrow and search ring, and its place on the maps; the editor offers the
  10 colours that look the same in the world and on every map, and a dark colour reads in the world too.
- Server waypoints shown in Xaero's Minimap, JourneyMap and as in-world markers, pointing at places, structures, an
  NPC, a boss or the nearest matching entity (which stays where it was last seen when the player goes far away); a waypoint
  in another dimension shows in the tracker with the dimension's name. Players choose which markers get an arrow at
  the screen edge while they look away (the tracked quest's, all, none).

### For authors
- In-game editor (the "Dev Mode" button of the Adventure Menu, for operators): a quest map like FTB Quests, a
  library, dialogue graphs, forms built from the schemas; files are written as the author wrote them.
- Several stories in one story folder, each turned on and off, made and deleted in the editor (a deleted one goes to
  `.deleted`, nothing erased).
- A script node in the editor says when it runs, makes a quest, a talk or a story event run it, runs it at you and
  shows how its commands parsed. KubeJS script blocks show only where KubeJS is installed.
- Working without the game: `/storybound debug trace`, `dump`, `export`; a headless check for CI
  (`-Dstorybound.validate`, JSON report, exit code); `/storybound debug lang extract` to move texts written in JSON
  into language files.
- Debug scenarios: `/storybound debug scenario <name>` installs a small story that shows one mechanic (a companion's
  story paused by a fight, a village of NPCs, an NPC's reputation); `clear` removes them.
- The validator says when a place can never be: a biome that is not in the chosen dimension, a structure that is in
  neither.
- Migrations (`migrations/*.json`) that carry players' progress over when a quest, flag or dialogue is renamed.
- Renaming any node in the editor: references, its texts, an NPC's relationship scale and players' saved progress
  follow it (the migration is written for you).
- Editor: places and entities picked in the world, long texts (documents with a preview, Codex sections, boss lore)
  in a text window, a view inside each quest, a player's progress on the quest map, relationship scales and NPC
  schedules, field tooltips in the game's language; the story's settings (difficulty scale, tracking a quest when
  given) in one window.
- `/storybound debug [player]`: a live panel of a player's story — dialogue, quests, and why the last conditions failed.
- `/storybound_client validate`: missing translations, long texts and document styles in the game's language.

### Integrations (all optional)
KubeJS, JEI (what quests ask for and give), Cutscene API, Xaero's Minimap and World Map, JourneyMap, Shoulder Surfing,
Epic Fight. Easy NPC entities work as NPCs as they are.
