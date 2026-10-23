# Changelog

## 0.1.0 — first release

Storybound is a data-driven story engine for NeoForge 1.21.1 modpacks. Stories are JSON files in
`config/storybound/` (or in datapacks) with JSON schemas for VS Code, or are built in the in-game editor.
Required on both client and server.

### The demo story
- A new story folder comes with a short demo story in the nearest village: five quests in two chapters, an elder
  with a relationship scale and day and night lines, a boss, a cat to find, a festival with fireworks. It starts
  when a player joins; change it or delete it (`/storybound demo install` brings it back).

### Story logic
- Story flags and variables in player, party and world scopes; parties with shared progress.
- One "when / if / then" language for everything: triggers (kills, items, places, structures, biomes, dialogues,
  advancements, dimensions, levels, statistics, entities seen...), conditions and typed actions (spawn an entity,
  teleport, effects, titles, sounds, waypoints, documents...).
- Story events, delays and schedules that survive a restart, KubeJS handlers with arguments (`ctx.args`).
- `logged_in` trigger: when a player joins (or only on their first visit).

### Quests
- Stages, objectives (triggers or items to have or hand in), a Witcher-style log, failure conditions and time limits.
- Rewards as in FTB Quests: items, one of several to choose, loot tables, experience, claimed in the Journal.
- Repeatable quests with a cooldown, difficulty (stars 1–5 or coloured levels), objective hints that appear when the
  player is stuck, up to five quests pinned on the HUD.
- Chapter map for players (opt-in): the editor's chapters with arrows from quest to quest, coloured by progress,
  with secret and invisible quests.

### Dialogues
- Dialogue graphs with choices, conditions, one-time options, timers and calls into other dialogues.
- A cinematic camera (shot / reverse shot, walls respected), speech bubbles for players nearby, a "thinking" pause.
- NPC bindings by type, tag or uuid; NPCs that talk to one player at a time; different lines before, during and
  after a quest.

### Journal, Codex, bosses, waypoints
- Readable documents (books, letters, notes, contracts) in their own markup, kept in the Journal.
- Codex entries that open on sight, kills and reading; bosses with fight records.
- Server waypoints shown in Xaero's Minimap, JourneyMap and as in-world markers, pointing at places, structures or
  the nearest matching entity.

### For authors
- In-game editor (the "Dev Mode" button of the Adventure Menu, for operators): a quest map like FTB Quests, a
  library, dialogue graphs, forms built from the schemas; files are written as the author wrote them.
- Working without the game: `/storybound trace`, `dump`, `export`, `capture`; a headless check for CI
  (`-Dstorybound.validate`, JSON report, exit code); `/storybound lang extract` to move texts written in JSON into
  language files.
- Migrations (`migrations/*.json`) that carry players' progress over when a quest, flag or dialogue is renamed.
- Editor: places and entities picked in the world, long texts (documents with a preview, Codex sections, boss lore)
  in a text window, a view inside each quest, a player's progress on the quest map, relationship scales and NPC
  schedules, field tooltips in the game's language.
- `/storybound debug [player]`: a live panel of a player's story — dialogue, quests, and why the last conditions failed.
- `/storybound_client validate`: missing translations, long texts and document styles in the game's language.

### Integrations (all optional)
KubeJS, JEI (what quests ask for and give), Cutscene API, Xaero's Minimap and World Map, JourneyMap, Shoulder Surfing,
Epic Fight. Easy NPC entities work as NPCs as they are.
