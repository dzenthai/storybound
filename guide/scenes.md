# Scenes

A scene is a cutscene: the camera flies through shots one after another, and things happen while it does: a chapter
title, a line in subtitles with its voice-over, a sound, a fade, any actions. The player watches it whole. They cannot
move or skip it, nothing can hurt them, and the mobs nearby leave them and the villagers alone.

Scenes don't use coordinates. Every shot is placed against an **anchor**: the player, an NPC, an entity, a structure
or a waypoint. So the same scene works in any world, wherever the village or the NPC happens to be.

Try `/storybound debug scenario scene` to see one.

## A scene's file

Scenes live in `scenes/<namespace>/<name>.json`:

```json
// config/storybound/my_story/scenes/mypack/arrival.json
{
  "letterbox": true,
  "shots": [
    {
      "anchor": { "structure": "#minecraft:village" },
      "camera": { "from": [-30, 25, -30], "to": [10, 18, -20], "duration": 7 },
      "during": [
        { "at": 1.5, "title": { "text": "mypack.chapter.one", "subtitle": "mypack.chapter.one.place" } }
      ],
      "transition": "fade"
    },
    {
      "anchor": { "npc": "mypack:elder" },
      "camera": { "from": [1.5, 1.6, 3], "to": [0.8, 1.6, 2], "duration": 4 },
      "during": [
        { "at": 0.5, "line": { "speaker": "mypack.elder.name", "text": "mypack.scene.arrival.elder", "sound": "mypack:elder/arrival" } }
      ]
    }
  ],
  "on_end": { "type": "set_quest_stage", "quest": "mypack:arrival", "stage": "talk" }
}
```

| Field | Default | What it does |
| --- | --- | --- |
| `shots` | required | The shots, in order. |
| `letterbox` | `false` | Black bars above and below while the scene plays. |
| `invulnerable` | `true` | Nothing hurts the player, except the void and `/kill`. |
| `world` | `calm` | `calm`: mobs nearby don't go for the player or the villagers, and no monsters spawn nearby. `live`: the world goes on as usual. |
| `on_end` | none | Actions run when the scene has played to its end. |

## Shots

| Field | Default | What it does |
| --- | --- | --- |
| `anchor` | `player` | What the shot is placed against (see below). |
| `camera` | required | The camera's flight (see below). |
| `during` | none | What happens while the shot plays (see below). |
| `transition` | `cut` | How the shot follows the one before it: `cut`, `fade` (through black) or `ease` (the camera glides over from where it was). |

**Anchors:**

- `"player"`: the player, as they face when the scene starts;
- `{ "npc": "mypack:elder" }`: an NPC, the one the mod picked or the nearest one within 64 blocks;
- `{ "entity": { "tag": "mypack_raider" } }`: the nearest entity a selector matches, within 64 blocks;
- `{ "structure": "#minecraft:village" }`: the structure the player stands in, or else the nearest one the waypoints
  have found. Its middle, on the ground, facing south;
- `{ "waypoint": "mypack:camp" }`: where the player sees that waypoint.

If an anchor is not found, is in another dimension or is farther than the player's world is loaded, that shot is left
out, and `/storybound debug trace` says why. A scene with no shots left still ends, so whatever waits for it doesn't
wait forever.

**The camera:**

| Field | Default | What it does |
| --- | --- | --- |
| `from` | required | Where the flight starts: `[right, up, forward]` of the anchor, turned the way the anchor faces. |
| `to` | `from` | Where the flight ends. |
| `look` | `anchor` | Where the camera looks: `anchor` (an entity is followed as it moves), `path` (the way it flies), `player`, or another anchor. |
| `duration` or `speed` | one of them | Seconds the flight takes, or blocks a second (then it lasts as long as the way takes). |
| `ease` | `in_out` | How the flight speeds up and slows down: `linear`, `in`, `out`, `in_out`. |

**During a shot**, each entry has `at` (seconds from the shot's start; past its end means at its end) and one of:

- `title`: `text` and an optional `subtitle`, big in the middle of the screen, like a quest's title when it starts;
- `line`: `text`, an optional `speaker` (the name shown above it), `sound` (its voice-over) and `duration` (seconds;
  by default as long as the words take to read), in subtitles above the lower bar;
- `sound`: `sound`, with optional `source`, `volume`, `pitch`;
- `fade`: `in`, `stay`, `out`, `color`, like the `fade` action;
- `actions`: any actions.

## Playing a scene

The `play_scene` action starts a scene for the player: `{ "type": "play_scene", "scene": "mypack:arrival" }`. If
the player is talking or watching another scene, the new one waits its turn. Put it in a stage's `on_enter`, a story
event, or a dialogue's action node.

When the scene ends, the `scene_finished` trigger fires, with the `reason` `finish` (played to the end), `died` or
`moved` (teleported or into another dimension). A quest objective can wait for it, or the scene's own `on_end` can move
the quest on. The `in_scene` condition holds while the player watches a scene.

If the player leaves the game in the middle of a scene, it plays again from the start when they come back, and
`scene_finished` doesn't fire in between.
