# Storybound

Data-driven story engine for Minecraft 1.21.1 modpacks (NeoForge): story flags, quests with stages and rewards,
dialogues with a cinematic camera, readable documents, a Codex, bosses and server waypoints, written as JSON or in an
in-game editor. Required on both client and server.

**For modpack authors:** JSON schemas for VS Code are in [`schemas/`](schemas)
(copy `schemas/vscode-settings.example.json` into your workspace settings). What changed in each version is in the
[changelog](CHANGELOG.md).

## Quick start

1. Install the mod on the server and the clients and start the game once: `config/storybound/` is created with a
   demo story, which starts when you join a world. It is yours to play, change or delete (its files are in `demo/`
   folders; `/storybound demo install` brings it back).
2. Write your story in `config/storybound/` (or in datapacks under `data/<namespace>/storybound/`) and run `/reload`.
   Problems are listed by `/storybound validate` and in `logs/storybound-validation.txt`.

## Build

Requires JDK 21.

```
./gradlew build          # jar in build/libs, runs unit tests
./gradlew runClient      # dev client
./gradlew runServer      # dev server, game directory: run/server
./gradlew runGameTestServer               # GameTests
./gradlew runValidate -PstoryFolder=...   # check a story folder without playing
```

## Project language

Use English for source code, comments, documentation and development tools.
Keep Russian text in localization resources only, including localized test
fixtures. Code comments must not contain an em dash.

## License

All Rights Reserved, see [LICENSE](LICENSE) and [NOTICE](NOTICE).
