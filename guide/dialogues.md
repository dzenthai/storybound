# Dialogues and NPCs

A dialogue is a graph of nodes the server walks; the player sees one line or one set of options at a time. Several
players can talk to the same NPC at once, each in their own conversation. Players talk with the **Talk** key (H by
default); "[H] Talk" shows under the crosshair when an NPC has something to say.

```json
// config/storybound/dialogues/mypack/elder.json
{
  "participants": { "elder": { "type": "bound", "name": "mypack.npc.elder" } },
  "start": "greet",
  "nodes": {
    "greet": { "type": "line", "speaker": "elder", "text": "mypack.dialogue.elder.greet", "next": "ask" },
    "ask": { "type": "choice", "options": [
      { "text": "mypack.dialogue.elder.opt_wolves", "next": "wolves" },
      { "text": "mypack.dialogue.elder.opt_bribe",
        "conditions": { "type": "has_item", "item": "minecraft:emerald", "count": 5 },
        "locked": "show", "locked_text": "mypack.dialogue.elder.need_emeralds", "once": true,
        "actions": { "type": "take_item", "item": "minecraft:emerald", "count": 5 }, "next": "bribed" },
      { "text": "mypack.dialogue.common.bye", "next": "@end" }
    ] },
    "wolves": { "type": "line", "speaker": "elder", "text": "mypack.dialogue.elder.wolves", "next": "start_quest" },
    "start_quest": { "type": "action", "actions": { "type": "set_quest_state", "quest": "mypack:wolf_hunt", "state": "active" } },
    "bribed": { "type": "line", "speaker": "elder", "text": "mypack.dialogue.elder.bribed" }
  }
}
```

## Dialogue fields

| Field | Default | Meaning |
| --- | --- | --- |
| `participants` | `{ "npc": { "type": "bound" } }` | who speaks besides `player`: `bound` (the NPC the dialogue was started with; at most one) or `virtual` (a narrator, with `name` and an optional `portrait` texture). `name` replaces the entity's name |
| `start` | required | the first node |
| `nodes` | required | node id → node |
| `presentation` | `screen` | `screen`, `hud` or `bubble`, see below |
| `closable` | `true` | ESC leaves the dialogue |
| `broadcast` | `true` | players nearby see the lines as speech bubbles; `false` for private scenes |
| `on_abort` | — | actions when the dialogue is interrupted |
| `camera` | `auto` | `auto`: the cinematic camera on the dialogue screen; `off`: the player's own view |

## Nodes

`next` defaults to `@end`, which ends the dialogue (or returns from a `call`); `@exit` ends the whole conversation.

| Node | Fields | Does |
| --- | --- | --- |
| `line` | `speaker`, `text`, `next?`, `sound?`, `duration?`, `shot?`, `transition?` | a line by `player` or a participant; `duration` (seconds) moves on by itself; `shot` and `transition` direct the camera |
| `choice` | `options`, `timeout?` | the player picks; `timeout: { "seconds", "next" }` picks for them, for tense scenes |
| `condition` | `conditions`, `then`, `else` | branches without showing anything |
| `action` | `actions`, `next?`, `on_fail?` | runs actions; an aborted list interrupts the dialogue |
| `random` | `options: [{ "weight"?, "next" }]` | a random branch |
| `jump` | `node` and/or `dialogue` | continues there without coming back |
| `call` | `dialogue`, `node?`, `next?` | runs another dialogue (greetings, trading), then continues at `next` |
| `end` | `actions?` | ends the dialogue |

**Options** have `text`, `id?`, `conditions`, `locked` (`hide`, the default, or `show` greyed out with `locked_text`
as a tooltip), `once` (gone for this player once chosen), `actions`, `next` and `think`. If an option's actions abort
(its price could not be taken), the choice shows again and `once` is not spent.

The NPC thinks before answering an option: dots for 1.5–4 seconds, longer for longer options. `"think": 0` on an
option answers at once, `"think": 3` waits three seconds. Players can scale the pause in their settings.

## Presentation

- `screen` — a visual-novel panel at the bottom: portrait, name, the line typed out, options above. The game is not
  paused. Click, Space or Enter go on; 1–9 pick an option.
- `hud` — subtitles above the hotbar while the player keeps moving. A dialogue whose only speaker is `player` is an
  inner monologue. A choice opens the screen.
- `bubble` — small talk: the NPC's lines appear over its head and the player keeps walking. For "Come back in the
  morning, the shop is closed", not for long conversations.

## The camera

On the dialogue screen the camera frames the conversation as a film would: shot and reverse shot over the
listener's shoulder, keeping clear of walls (in a cramped room it moves in front of the listener, and when nothing
fits it stays in first person). Per line, `shot` picks `auto`, `two_shot` (both from the side), `close_up` (the
speaker's face) or `first_person`, and `transition` is `cut` or `ease`.

The server may forbid it (`camera.allowed`), a dialogue may turn it off (`"camera": "off"`), a player may turn it off
(`camera.enabled`). Shoulder Surfing and Epic Fight are paused during a shot.

## When a dialogue is interrupted

The player walks farther than `dialogues.maxDistance` from the NPC, the NPC dies, the player dies, logs out or takes
damage (`dialogues.interruptOnDamage`), leaves with ESC, or `/storybound dialogue stop` is used. Then `on_abort` runs
and `dialogue_ended` does not fire.

## NPC bindings

A binding says which entities start which dialogue (`npc_bindings/<ns>/<name>.json`):

```json
// config/storybound/npc_bindings/mypack/elder.json
{
  "entity": { "type": "minecraft:villager", "tag": "elder" },
  "dialogues": [
    { "dialogue": "mypack:elder_after_hunt", "priority": 20, "conditions": { "type": "quest", "quest": "mypack:wolf_hunt", "state": "completed" } },
    { "dialogue": "mypack:elder_during_hunt", "priority": 10, "conditions": { "type": "quest", "quest": "mypack:wolf_hunt", "state": "active" } },
    { "dialogue": "mypack:elder" }
  ],
  "interact": true,
  "proximity": { "radius": 4, "cooldown": 30, "once": false, "line_of_sight": true }
}
```

- **Which entities:** `type` (an id, `#tag` or list), `tag` (a scoreboard tag: `/tag @e[type=villager,limit=1,sort=nearest] add elder`),
  `name` (only an entity with exactly this name from a name tag), `uuid` (one entity). Every given part must match.
- **Which dialogue:** entries are tried by `priority`, highest first; the first whose conditions pass starts. That is
  how an NPC says something else before, during and after a quest: an entry per phase with a `quest` condition.
  When none passes, the click does what it normally does (trading).
- **How:** `interact` (the Talk key), `proximity` (coming within `radius`; `cooldown` seconds; `once` per player),
  `custom` (a script decides, see [Integrations](integrations.md#kubejs)).
- **Busy NPCs:** `"exclusive": true` lets the NPC talk with one player at a time; `busy_dialogue` is what others hear
  meanwhile ("Wait, I am busy").
- **Merchants:** `"override_interact": true` makes right-click talk instead of opening trades, for dialogues with
  "Show me your wares" (`open_trade`). When no dialogue passes (the shop is closed at night), the click trades.
- **One entity by command:** `/storybound npc bind <entity> <dialogue> [interact|proximity|both]`, `unbind`, `info`.

Right-click is left to the game and other mods (villagers trade, Easy NPC opens its screens) unless the server sets
`dialogues.rightClickTalks = true` or a binding sets `override_interact`.

## Speech bubbles

Players within 16 blocks of a conversation see its lines over the speakers' heads, and the chosen option over the
player's. `"broadcast": false` keeps a dialogue private; each player can turn bubbles off (`dialogue.speechBubbles`).

## Several players, one NPC

Each conversation is separate. Things written to the `world` scope are shared, so guard them with conditions
("the reward was already given").
