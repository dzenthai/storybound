# Changelog

## 0.2.0

- Waypoints and `add_waypoint` take `"marker": "event"`: three animated "!" instead of the icon, the middle one larger,
  for something happening at that place. Resource packs draw its sprite as `event_icon`; Xaero's Minimap shows "!".
- `"marker": "question"`: one animated "?" that shakes and hops a little, for something unknown at that place. Its sprite
  is `question_icon`; Xaero's Minimap shows "?".
- A search area's marker is a magnifier. All markers share one look: a dark outline and a hard shadow.
- A search area's edge lies level at the player's height instead of following the blocks, bolder, over a band on the
  ground. It fades out far above the area and is cut off 30 blocks under its waypoint; "Search the area" shows only
  where the edge does.
- Trails: a waypoint with `"trace"` has no icon. It shows near the player as glowing motes rising from the steps, or
  with `"marker": "scent"` as wisps and strands drifting along it. A trail is recorded by walking it in the editor, or
  found as the game goes: a creature's real tracks, shown only on blocks in `storybound:takes_tracks`.
- Story events take `"cooldown": { "seconds": 3600, "scope": "world" }`: after a run, not again in that scope until
  that much game time has passed. With `chance`, an encounter that comes back now and then.
- `entities` condition: how many living entities that match are within a radius of the player or a place (`min`,
  `max`; `"max": 0` when none are left), for "the raiders are driven off".
- `entity_killed`, `entity_seen` and `entity_interacted` take a selector in `entity` as well as types:
  `{ "type": "minecraft:pillager", "tag": "night_raider" }` counts only the mobs an event spawned with that tag.
- Debug scenario `raid`: a night raid that comes back after a cooldown and is done when none of its raiders is left.
- A place in actions takes a selector in `at`: `"at": { "tag": "prologue_elder" }` is by the nearest such entity.
- `has_item` with `"worn": true` counts only the armour the player has on.
- `spawn_entity` with `leashed_to`: each spawned mob on a lead held by the nearest matching entity (a trader's llamas).
- Scenes: cutscenes of our own in `scenes/`, shots placed against an anchor (the player, an NPC, an entity, a structure,
  a waypoint) so they work in any world; titles, lines with voice-over, sounds, fades and actions at given seconds;
  black bars; the player kept safe and the mobs nearby calm; `play_scene`, `scene_finished`, `in_scene`. A scene left
  unfinished plays again when the player comes back. Debug scenario `scene`.
- Scenes in the editor: a scene node (a badge under what plays it on the map), "+ Scene" from a quest, an event or a
  dialogue, "Watch", and a camera view that sets the shots in the world: the editor as a side panel, the screen showing
  what the camera sees, the flight drawn as a line, a free view turned around the anchor with the mouse, "📷" to put a
  point where the view is, a slider through the shot's time and a play button.
- Scenes: a shot can be a talk (`"dialogue"` instead of `"camera"`): the dialogue plays, the scene goes on after it.
  A dialogue can play a scene in the middle of the talk with a `scene` node. Captions (`caption`: a place, a time) in a
  corner, in the middle or on black. A scene's own `music`, changed by a `music` thing during a shot; it comes before
  every other track.
- Scenes in the editor: "Placed against" and "The camera looks" are pickers (the player, an NPC, an entity, a
  structure, a waypoint), shots switch between a flight and a talk; "+ Quest after the scene" and "+ Talk after the
  scene"; "Scene in the talk" in the dialogue editor. The camera view is drawn like the editor and has a time line of
  the whole scene: shots as blocks, things as marks to pick, drag in time or remove, a right click on an empty place
  to add one there.
- Scenes: cinematography and procedural safety upgrades — POI anchors (`{ "poi": "minecraft:meeting", "radius": 64 }`)
  and shot `fallback` anchors, smooth 3D `via` splines, lens zoom (`fov` / `fov_to`), Rule of Thirds framing
  (`look_offset`), horizon tilt (`roll` / `roll_to`), continuous handheld breathing (`shake`) and impact `shake` cues,
  6-DOF free look (`look: "free"` with per-point `rotations`), `above_ground` terrain/roof clearance, `safe_angle`
  obstacle avoidance, spring-damped entity tracking, and smooth eased letterbox bars.
- Scenes in the editor: upgraded 3D camera view with direct live framing through the selected point's lens (`Start` /
  `#1…` / `End`, `WASD` + `Space`/`Shift` or `E`/`Q`, Blender-style orbit & `Shift+LMB`/`MMB` pan, `RMB` free look,
  `Alt+wheel` FOV zoom, and `[`/`]` point switching), one-key `Tab` toggle to step outside and see the full curve and
  roll-tilted camera frustums with forward aim rays, 1-to-1 6-DOF frame capture (`📷` / `K`), collapsible left panel
  (`H`) with top-left Menu/Back (`←`) and Undo/Redo (`↶`/`↷`) controls matching Dev Mode, three tabs
  (`Point` | `Shot` | `Cues`), in-viewport anchor picker, live cinema speed readout (`blk/s`), 50-step Undo/Redo
  (`Ctrl+Z` / `Ctrl+Y`), `F1` Hotkeys overlay, native 11×11 pixel icons, full-screen centered overlay parity, and
  inspector **Before & after** (`+ Before…` / `+ After…`) wiring to connect events, dialogues, quests, chained scenes,
  scripts, or documents directly on a Scene node.
- NPCs: `follow` behaviour on NPC bindings (`conditions`, `distance`, `teleport_distance`, `speed`) so a companion
  follows the player, pauses for fights or talks, teleports safely when falling behind, and settles at their destination
  when following ends; also configurable in Dev Mode.
- Dialogues: `start_dialogue` accepts an optional `speaker` entity selector so dialogues launched from a cutscene's
  `on_end` or a quest stage bind to a live 3D NPC speaker.
- Dialogues with several NPCs: a participant `"type": "entity"` is another NPC standing nearby, found by its NPC
  binding (`npc`) or by a selector (`entity`) within `radius` blocks. If it is not there, its lines are skipped. A line's
  `to` says who it is said to: the speaker turns to them and the camera films the two; the others look at the speaker.
  `shot: "group_shot"` shows everyone in the talk at once. An NPC talking with another player is not taken. In the
  editor: the participant's type and "NPC / selector" switches, "rowan → vex" on a line's card, an arrow from the
  dialogue to its NPCs. Debug scenario `council`.
- `location` condition takes `"outdoors": true`: only sky and leaves above the player, not a cave, a roof or water.
  For something that should happen in the open, such as an ambush in the woods.
- Less work every tick on servers and in the game when a story does not use a feature. Codex images and photos free
  their memory when you leave a world.
- Fixed: `note_torn` document style now uses valid texture dimensions and readable default font metrics.
- A Codex entry's or a document's text that is not there shows in red ("Missing text: …") only to whoever may open the
  dev mode; a player sees nothing in its place.
- The client and the server must both be on this version: what they send each other has changed.
- Fixed: a selector naming an entity tag (`"#minecraft:zombies"` in a waypoint, an objective or an NPC binding) failed
  with "Missing tag" when a world loaded, and its definition was left out.

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
- `entity_killed` with `by_anyone`: a mob that dies near the player counts whoever or whatever killed it (a village's
  iron golem, a fall), for objectives like "drive them off".
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
  story paused by a fight, an enemy who talks all through a duel, a village of NPCs, an NPC's reputation, waypoints in other dimensions); `clear`
  removes them.
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
