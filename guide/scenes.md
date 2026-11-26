# Scenes

A scene is a cutscene: the camera flies through shots one after another, and things happen while it does: a chapter
title, a caption, a line in subtitles with its voice-over, a sound, other music, a fade, a camera shake impulse, or any
actions. A shot can also be a talk: a dialogue plays in the middle of the scene, and the scene goes on after it. By
default the player can skip a scene with Space (`skippable: true`), cannot move while it plays, nothing can hurt them,
and the mobs nearby leave them and the villagers alone.

Scenes don't use coordinates. Every shot is placed against an **anchor**: the player, an NPC, an entity, a point of
interest (`poi`), a structure or a waypoint — with an optional `fallback` anchor when the main one is not nearby. So
the same scene works in any world, wherever the village or the NPC happens to be.

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

The scene's inspector has quick controls to wire **Before & after** (**+ Before…** picks or creates the story event,
dialogue, quest or scene that plays this scene; **+ After…** picks or creates the quest, dialogue, scene, command script
or document that follows its end — any link can be removed with **✕** in **Links** below) and two preview buttons:

- **3D editor** sets the shots right in your world. The editor becomes a collapsible panel on the left (hide or show
  it with the **⏴ / ⏵** button or **H**) with top-left **←** (Menu / back to editor), **↶** (Undo) and **↷** (Redo)
  buttons matching Dev Mode, organized into three tabs (**Point**, **Shot**, **Cues**), with a pinned playback bar
  (**Shot**, **Scene**, **Grid**, **Hotkeys**) and **Save** / **Close** at the bottom.
  - **Framing directly through the camera:** opening a shot or picking any control point (**Start**, **#1/#2…**, **End**,
    or **[ / ]**) puts you straight inside that point's lens with its real FOV and horizon roll. Fly it live with
    **WASD**, **Space / E** (up), **Shift / Q** (down), **Ctrl** (3× speed); drag **LMB** to orbit the anchor (or aim
    directly in `free` look), **Shift+LMB / MMB** to pan, **RMB** to look freely (switches `look` to `free` without
    moving the picture), **Wheel** to dolly, and **Alt+Wheel** to zoom FOV.
  - **Stepping outside (Tab):** press **Tab** (or **Step out of camera**) to pull back and see the gold Catmull-Rom
    spline (with one-second ticks), roll-tilted camera pyramids (green start, cyan intermediate points, red end) with
    forward aim rays, and the white anchor post. Drag any camera pyramid to move it across the view (with **Shift**,
    along the ground); fly freely with **WASD + Space/Shift** without shifting points; press **📷** or **K** to capture
    both your exact position and viewing angle onto the selected point, or **F** to focus.
  - **Point tab:** add (**+**), swap (**⇄**), capture (**📷**), or remove (**✕**) intermediate spline points (`via`),
    fine-tune right (→), up (↑) and forward (↗) offsets with sliders or scroll-nudged numbers (Shift for whole blocks),
    and adjust FOV (`fov` / `fov_to`), horizon roll (`roll` / `roll_to`), and either aim offset (`look_offset`) or
    per-point yaw/pitch (`rotations`).
  - **Shot tab:** pick the shot's anchor in-viewport, set duration or speed (with a live `blk/s` cinema pace readout),
    ease curve, look mode (`anchor`, `free`, `path`, `player`), transition (`cut`, `fade`, `ease`), `above_ground`
    clearance, continuous handheld `shake`, and `safe_angle`.
  - **Cues tab & timeline:** the timeline at the bottom shows the whole scene as shot blocks and coloured cue marks.
    Click or drag along it to scrub; **right-click an empty spot** to add a cue there (or press **+** on the Cues tab);
    click a mark to edit its text, sound/music, fade or shake impulse; drag it in time, or right-click to remove it.
  - **Save** (**Ctrl+S**) writes the scene and keeps the view open; **Ctrl+Z / Ctrl+Y** undo and redo up to 50 edits;
    **Enter** plays the shot from any mode (**Space** also plays in preview), **← / →** step through time (**Shift**
    by a second), **G** toggles the Rule of Thirds grid, and **F1** opens the **Hotkeys** overlay.
  - If the scene can't be saved as it is, it stays a draft and the panel says why above **Save**.
  - The anchor is found the way the scene will find it. If it is not near you, you stand in for it: the numbers mean
    the same around the real one.
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
| `letterbox` | `false` | Black bars above and below while the scene plays (eased smoothly in and out). |
| `skippable` | `true` | Whether the player can skip the scene with Space. |
| `invulnerable` | `true` | Nothing hurts the player, except the void and `/kill`. |
| `world` | `calm` | `calm`: mobs nearby don't go for the player or the villagers, and no monsters spawn nearby. `live`: the world goes on as usual. |
| `music` | none | A track that plays while the scene does. It comes before a dialogue's, a stage's and a place's. |
| `on_end` | none | Actions run when the scene has played to its end. |

## Shots

| Field | Default | What it does |
| --- | --- | --- |
| `anchor` | `player` | What the shot is placed against (see below). |
| `fallback` | none | Optional backup anchor used if `anchor` is not found nearby. |
| `camera` | one of them | The camera's flight (see below). |
| `dialogue` | one of them | A talk instead of a flight: this dialogue plays with its own camera, an NPC or entity anchor as the one the player talks to (with the player, a structure or a waypoint as the anchor, the talk has no partner, as when `start_dialogue` starts it). The scene waits until the talk is over, however it ends, then goes on. A talk shot has no `during`. |
| `during` | none | What happens while the shot plays (see below). |
| `transition` | `cut` | How the shot follows the one before it: `cut`, `fade` (through black) or `ease` (the camera glides over from where it was). |

**Anchors:**

- `"player"`: the player, as they face when the scene starts;
- `{ "npc": "mypack:elder" }`: an NPC, the one the mod picked or the nearest one within 64 blocks;
- `{ "entity": { "tag": "mypack_raider" } }`: the nearest entity a selector matches, within 64 blocks;
- `{ "poi": "minecraft:meeting", "radius": 64 }`: the nearest point of interest matching a POI id or `#tag` within `radius` blocks (4–128, default 64);
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
| `via` | none | Optional intermediate points (`[[x, y, z], ...]`) for a smooth Catmull-Rom curve from `from` to `to`. |
| `to` | `from` | Where the flight ends. |
| `look` | `anchor` | Where the camera looks: `anchor` (smoothly tracks moving entities), `path` (along the flight tangent), `player`, `free` (uses `rotations`), or another anchor. |
| `rotations` | none | Per-control-point `[yaw, pitch]` pairs (yaw relative to the anchor) used when `look` is `free`. |
| `look_offset` | `[0, 0, 0]` | `[right, up, forward]` offset applied to the aim target for Rule of Thirds framing and headroom. |
| `fov` / `fov_to` | player FOV | Custom camera field of view in degrees (20–120), optionally animated smoothly to `fov_to` (dolly zoom). |
| `roll` / `roll_to` | `0` | Horizon tilt (Dutch angle) in degrees (-30 to +30), optionally animated smoothly to `roll_to`. |
| `shake` | `0` | Continuous subtle handheld camera breathing (0–1; 0.02–0.05 feels natural). |
| `above_ground` | `0` | Minimum clearance (0–64 blocks) above terrain, trees, and roofs along the whole flight. |
| `safe_angle` | `true` | Automatically rotates around the anchor or pulls clear of walls/trees when a ground shot is blocked. |
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
- `shake`: `intensity` (0–2) and `duration` (seconds): a decaying camera shake impulse (horn blast, heavy step, impact);
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
