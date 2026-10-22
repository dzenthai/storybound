# Storybound

Data-driven story engine for Minecraft 1.21.1 modpacks (NeoForge): story flags, quests with stages and rewards,
dialogues with a cinematic camera, readable documents, a Codex, bosses and server waypoints, written as JSON or in an
in-game editor. Required on both client and server.

**For modpack authors:** start with the [guide](guide/README.md). JSON schemas for VS Code are in [`schemas/`](schemas)
(copy `schemas/vscode-settings.example.json` into your workspace settings). What changed in each version is in the
[changelog](CHANGELOG.md).

## Quick start

1. Install the mod on the server and the clients and start the game once: `config/storybound/` is created.
2. `/storybound example install` copies a small example story there and reloads.
3. Write your story in `config/storybound/` (or in datapacks under `data/<namespace>/storybound/`) and run `/reload`.
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

## License

GPL-3.0-only, see [LICENSE](LICENSE) and [NOTICE](NOTICE).
