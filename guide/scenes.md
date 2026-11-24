# Scenes

A scene is a cutscene: the camera flies through shots one after another, and things happen while it does: a chapter
title, a caption, a line in subtitles with its voice-over, a sound, other music, a fade, any actions. A shot can also
be a talk: a dialogue plays in the middle of the scene, and the scene goes on after it. The player watches it whole. They cannot
move or skip it, nothing can hurt them, and the mobs nearby leave them and the villagers alone.

Scenes don't use coordinates. Every shot is placed against an **anchor**: the player, an NPC, an entity, a structure
or a waypoint. So the same scene works in any world, wherever the village or the NPC happens to be.

Try `/storybound debug scenario scene` to see one.

## In the editor

Create a scene from the **+** menu, or from a quest, a story event or a dialogue: its menu has **+ Scene**, which
creates the scene and makes that node play it (when the quest starts, among the event's actions, at the end of the
talk). On the map a scene is a badge under the node that plays it; in **All links** it is a node of its own, with arrows
to what plays it and to what its end moves on. The scene's menu there has **+ Quest after the scene** and **+ Talk
after the scene**: they add a quest or a dialogue that its end starts. Dragging an arrow from the scene to a quest or a
dialogue does the same.

In a dialogue, **Scene in the talk** adds a scene node: the talk pauses, the scene plays, and the talk goes on at the
node's next one.

In the scene's form, **Placed against** picks the anchor from a list (the player, an NPC, an entity, a structure, a
waypoint), and below it the NPC, the structure or the waypoint from the story's own. **The camera looks** works the same
way. **The shot is** switches a shot between a camera flight and a talk; **What happens** picks the kind of a thing
during the shot. What you switch away from is kept until the form closes, so switching back brings it back.

The scene's inspector has two buttons:

- **Camera view** sets the shots right in your world. The editor becomes a panel on the left, and the rest of the
  screen shows what the scene's camera sees.
  - The shot is drawn in the world: its flight as a gold line with a tick at every second, a small green camera at
    its start and a red one at its end, each pointing where it looks, and a white post at the anchor. **Drag a small
    camera** to move that point across the view; with Shift it moves over the ground instead (forward and back).
  - **The free view** goes anywhere: drag to turn around its centre (the anchor, or the point you focused with F),
    drag with the right button to look around, fly with your movement keys (jump and sneak for up and down, sprint for
    speed), turn the wheel to come closer. **📷** next to From or To puts that point where the free view is.
  - Each point has a row for each axis, right (→), up (↑) and forward (↗) of the anchor: a slider to drag and the
    exact number beside it, to type or to nudge with the wheel (Shift for whole blocks). A slider spans ±16 blocks and
    widens (32, 64, 128) when you let it go at its edge. **⇄** swaps From and To. The wheel over the rest of the panel
    scrolls it on a small screen.
  - The buttons below set how long the flight takes (seconds or blocks a second), how it eases, where the camera
    looks and how the shot follows the one before. The slider shows any moment of the shot. **Shot** plays it,
    **Scene** plays the whole scene with its cuts, fades and glides.
  - **Grid** draws the lines of thirds over the picture; the scene's black bars show too. **H** hides the panel, so
    you see the whole frame.
  - At the top: **◀ ▶** go from shot to shot, **+** adds a shot that starts where this one ends, **⧉** copies it,
    **✕** removes it.
  - **The time line** at the bottom shows the whole scene: a block for each shot, as long as it lasts (a talk is a
    short block with 💬), the things during each shot as coloured marks, and a gold line where the view is. Click a
    block to go to that shot and that moment, drag along it to scrub. **Right-click an empty place** to add a thing
    there (a line at first). Click a mark to edit that thing, drag it to move it in time, click it with the right
    button to remove it.
  - **Things in the shot**, under the shot's settings, shows the thing picked on the time line: its kind (a button that
    switches it), its text, for a title its subtitle, for a line who says it, for a caption where it stands and whether
    it is on black, for a sound or a music change its id, picked from the list with **…** as in the form and played
    with the button beside it. A fade and actions are set in the form.
  - If the scene can't be saved as it is (the game would not load it), it stays a draft and the panel says why above
    **Save**.
  - A talk shot shows which dialogue it plays; it has no camera of its own to set.
  - **Save** (Ctrl+S) writes the scene and keeps the view open. **Close** with unsaved changes asks first.
  - Keys while no number is being typed: Space plays, the arrows step through the time (Shift: by a second), Tab
    switches between the shot and the free view, F focuses on the point under the cursor or the anchor, G the grid.
  - The anchor is found the way the scene will find it. If it is not near you (an NPC in another part of the world),
    you stand in for it: the numbers mean the same around the real one.
- **Watch** saves the scene and plays it to you as players will see it; the editor comes back after it.

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
| `music` | none | A track that plays while the scene does. It comes before a dialogue's, a stage's and a place's. |
| `on_end` | none | Actions run when the scene has played to its end. |

## Shots

| Field | Default | What it does |
| --- | --- | --- |
| `anchor` | `player` | What the shot is placed against (see below). |
| `camera` | one of them | The camera's flight (see below). |
| `dialogue` | one of them | A talk instead of a flight: this dialogue plays with its own camera, an NPC or entity anchor as the one the player talks to (with the player, a structure or a waypoint as the anchor, the talk has no partner, as when `start_dialogue` starts it). The scene waits until the talk is over, however it ends, then goes on. A talk shot has no `during`. |
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
- `caption`: `text`, `position` (`bottom_left`, the default, `top_left` or `center`), `black` (`true` puts it on a
  black screen, like "Three days later"), `duration` (seconds; by default as long as the words take to read). For a
  place, a time, a date;
- `line`: `text`, an optional `speaker` (the name shown above it), `sound` (its voice-over) and `duration` (seconds;
  by default as long as the words take to read), in subtitles above the lower bar;
- `sound`: `sound`, with optional `source`, `volume`, `pitch`: played once at that moment, over the music (a door, thunder, a howl);
- `music`: a track id: from then on the scene plays that track instead, looping, faded in over the one before;
- `fade`: `in`, `stay`, `out`, `color`, like the `fade` action;
- `actions`: any actions.

## Playing a scene

The `play_scene` action starts a scene for the player: `{ "type": "play_scene", "scene": "mypack:arrival" }`. If
the player is talking or watching another scene, the new one waits its turn. Put it in a stage's `on_enter`, a story
event, or a dialogue's action node.

A dialogue can also play a scene in the middle of the talk with a `scene` node: `{ "type": "scene", "scene":
"mypack:arrival", "next": "after" }`. The talk's screen closes, the scene plays, and the talk goes on at `next` with
the same partner. If the scene cannot play (it doesn't exist), the talk goes straight on.

When the scene ends, the `scene_finished` trigger fires, with the `reason` `finish` (played to the end), `died` or
`moved` (teleported or into another dimension). A quest objective can wait for it, or the scene's own `on_end` can move
the quest on. The `in_scene` condition holds while the player watches a scene.

If the player leaves the game in the middle of a scene, it plays again from the start when they come back, and
`scene_finished` doesn't fire in between.
