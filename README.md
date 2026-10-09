# Storybound

Data-driven story engine for Minecraft 1.21.1 modpacks (NeoForge): story flags, quests with states,
dialogues, readable documents, codex, bosses and server waypoints. Required on both client and server.

## Build

Requires JDK 21.

```
./gradlew build          # jar in build/libs, runs unit tests
./gradlew runClient      # dev client
./gradlew runServer      # dev server, game directory: run/
```

## Story data

Definitions live in datapacks under `data/<namespace>/storybound/...`. A working example is in
[`examples/storybound_example`](examples/storybound_example); copy it into a world's `datapacks/` folder.

- `/storybound validate` re-checks all loaded data and prints problems; the full report is written
  to `logs/storybound-validation.txt`.
- `/storybound_client definitions` shows what the client received from the server.

## License

GPL-3.0-only, see [LICENSE](LICENSE) and [NOTICE](NOTICE).
