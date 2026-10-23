# Storybound guide for modpack authors

Storybound runs a story written as JSON files: flags, quests, dialogues, readable documents, a Codex, bosses and
waypoints, all driven by one language of triggers, conditions and actions. The server runs it; players see it in the
Adventure Menu (**J**), on the HUD and in dialogue screens. The mod is needed on both the server and the clients.

| Page | What it covers |
| --- | --- |
| [Logic](logic.md) | flags and variables, parties, triggers, conditions, actions, story events |
| [Quests](quests.md) | stages, objectives, rewards, difficulty, time limits, hints, the chapter map |
| [Dialogues](dialogues.md) | dialogue graphs, NPC bindings, the camera, speech bubbles |
| [Documents, Codex and bosses](documents-codex.md) | books and letters, the Journal, Codex entries, boss records |
| [Waypoints](waypoints.md) | waypoints for quests and places, map mods, in-world markers |
| [Texts and languages](languages.md) | translation keys, language files, long texts, `lang extract` |
| [Tools](tools.md) | the editor, commands, the trace, checking without playing, migrations, settings |
| [Integrations](integrations.md) | KubeJS, JEI, Cutscene API, map and camera mods, Easy NPC |

## Getting started

1. Install Storybound on the server and every client (NeoForge 1.21.1). Start the game once: the **story folder**
   `config/storybound/` is created, with a folder per kind of definition, four blank documents and the demo story
   (below).
2. Join a world: the demo starts by itself. Play it, then open it in the editor (Adventure Menu, **J** → Dev Mode).
3. Write your own files in the story folder and run `/reload`. Problems are listed in the log, in
   `logs/storybound-validation.txt` and by `/storybound validate`.

## The demo story

A new story folder comes with a short story set in whatever village is nearest, so every part of Storybound can be
seen working before writing anything. It is ordinary story data in the namespace `demo`: change it, build on it or
delete it.

| Quest | What it shows |
| --- | --- |
| Smoke on the Horizon | a scene on joining (`logged_in`), a waypoint on the nearest village, a scene on arrival that spawns the elder, who starts talking when you come close |
| Night Watch | items to gather, a book that unlocks a Codex section, an NPC who says other things by day and by night, a choice against the clock, a scripted ambush and a boss, rewards to choose in the Journal |
| Our Daily Bread | a contract notice, wheat handed in by talking, repeatable every day |
| Skittish Murka | a time limit, a search area around a cat, a cat you talk to, a Journal note |
| Festival by the Bell | a quest waiting for two others, a letter, fireworks, a book for authors about the demo itself |

Every kind of dialogue node is used (lines, choices with locked and one-time options, conditions, actions, random,
jump and call), in all three presentations (screen, subtitles, bubbles), with camera shots and a relationship scale
(`demo:rep/elder`). The editor shows the quests in two chapters, and players get the chapter map
(`"chapter_map": true` in `project.json`).

- **Remove it:** delete the `demo` folders inside the story folder's type folders and `texts/<language>/`, and the
  `demo.` keys in `lang/`; then `/reload`.
- **Bring it back:** `/storybound demo install` copies its files again (your own files and keys stay) and reloads.
- **On a server:** the demo starts for every player who joins. Remove it before players arrive unless you mean to keep it.

## Where files go

Every definition is a JSON file. Its folder says what it is, the path gives its id:

```
config/storybound/
  quests/mypack/wolf_hunt.json     -> quest mypack:wolf_hunt
  quests/wolf_hunt.json            -> quest storybound:wolf_hunt (no namespace folder)
  dialogues/  npc_bindings/  documents/  codex/  bosses/  waypoints/
  events/  flags/  migrations/
  chapters/  dialogue_layouts/  scripts/        (written by the editor)
  lang/en_us.json  lang/ru_ru.json             (short texts)
  texts/en_us/mypack/elder_letter.sbt          (long texts)
  project.json                                 (languages, difficulty scale, chapter map)
```

The same files also load from datapacks (`data/<namespace>/storybound/<kind>/...`) and texts from resource packs
(`assets/<namespace>/lang/`, `assets/<namespace>/storybound/texts/<language>/`). A file in the story folder replaces a
datapack file with the same id. On a dedicated server the story folder is the server's; in singleplayer, your game's.

Ids are lowercase (`a-z 0-9 _ . - /`). Logic types default to Storybound's namespace: `"type": "flag"` is
`"type": "storybound:flag"`; another mod's types need their namespace.

## Schemas for your editor

The `schemas/` folder of the repository has a JSON schema for every kind of file. With VS Code, copy
`schemas/vscode-settings.example.json` into your workspace settings: completion, descriptions and errors appear as
you type. The same schemas build the forms of the in-game editor.

## project.json

```json
{
  "source_language": "en_us",
  "languages": ["en_us", "ru_ru"],
  "difficulty": { "style": "stars" },
  "chapter_map": true
}
```

| Key | Meaning |
| --- | --- |
| `source_language` | the language authors write in; shown under untranslated texts in the editor |
| `languages` | the languages the editor edits |
| `difficulty` | how quests show their difficulty (see [Quests](quests.md#difficulty)) |
| `chapter_map` | show players a Map tab with the editor's chapters (see [Quests](quests.md#chapter-map)) |

## Modules

Each part of the mod can be turned off in `config/storybound-common.toml` (`modules.quests`, `journal`, `codex`,
`bosses`, `waypoints`, `dialogues`). A disabled module does nothing and hides its tab; its files still load, so the
validator keeps checking them. Other settings are listed in [Tools](tools.md#settings).
