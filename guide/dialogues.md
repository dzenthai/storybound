# Dialogues and NPCs

A dialogue is a conversation made of nodes: lines, choices, branches and actions. The server walks through the nodes,
and the player sees one line or one set of answers at a time. Several players can talk to the same NPC at once, each
in their own conversation.

Players talk with the Talk key, H by default. When an NPC has something to say, "[H] Talk" shows under the crosshair.

```json
// config/storybound/my_story/dialogues/mypack/elder.json
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

The elder greets the player and offers three answers. Asking about the wolves starts the wolf hunt quest. The bribe
answer is greyed out until the player has five emeralds, and can only be picked once.

## Dialogue fields

| Field | Default | What it does |
| --- | --- | --- |
| `participants` | `{ "npc": { "type": "bound" } }` | Who speaks besides `player`. A `bound` participant is the NPC the dialogue was started with, and there can be only one. A `virtual` participant is a narrator, with a `name` and an optional `portrait` texture. A `name` replaces the entity's own name. |
| `start` | required | The first node. |
| `nodes` | required | The nodes, by id. |
| `presentation` | `screen` | How the dialogue is shown: `screen`, `hud` or `bubble`. See [Presentation](#presentation). |
| `closable` | `true` | Whether Esc leaves the dialogue. |
| `broadcast` | `true` | Whether players nearby see the lines as speech bubbles. Use `false` for private scenes. |
| `on_abort` | none | Actions that run when the dialogue is interrupted. |
| `camera` | `auto` | `auto` uses the cinematic camera on the dialogue screen. `off` keeps the player's own view. |
| `combat` | `continue` | What a fight does to a dialogue in subtitles or bubbles. See [Fights](#fights). |
| `npc_stops` | `true` | Whether the NPC stands still and faces the player. Use `false` to let a companion keep walking while it talks (it still has to stay within `dialogues.maxDistance`). |

## Nodes

| Node | Fields | What it does |
| --- | --- | --- |
| `line` | `speaker`, `text`, `next?`, `sound?`, `duration?`, `shot?`, `transition?`, `fade?`, `look_at?` | A line said by `player` or by a participant. With `duration` (seconds) it moves on by itself. `shot`, `transition`, `fade` and `look_at` direct the camera, see [The camera](#the-camera). |
| `choice` | `options`, `timeout?` | The player picks an answer. With `timeout: { "seconds", "next" }` the dialogue picks for them when time runs out, for tense scenes. |
| `condition` | `conditions`, `then`, `else` | Goes one way or the other without showing anything. |
| `action` | `actions`, `next?`, `on_fail?` | Runs actions. If the list stops because an action failed, the dialogue is interrupted. |
| `random` | `options: [{ "weight"?, "next" }]` | Goes down a random branch. |
| `jump` | `node` and/or `dialogue` | Continues at another node or dialogue, without coming back. |
| `call` | `dialogue`, `node?`, `next?` | Runs another dialogue, such as a shared greeting or trading, then continues at `next`. |
| `end` | `actions?` | Ends the dialogue. |

`next` is `@end` by default, which ends the dialogue, or returns to the caller after a `call`. `@exit` ends the whole
conversation, including any dialogue that called this one.

### Answers

An answer in a `choice` has these fields:

- `text`, the answer itself, and an optional `id`;
- `conditions`, which decide whether the answer is available;
- `locked`, which says what happens to an unavailable answer: `hide` (the default) hides it, `show` shows it greyed
  out with `locked_text` as a tooltip;
- `once`, which removes the answer for this player after they pick it;
- `actions` and `next`;
- `think`, see below.

If an answer's actions stop because one failed, for example when the price can't be taken, the choice is shown
again and `once` is not used up.

Before the NPC answers, it thinks for a moment: dots show for 1.5 to 4 seconds, longer after longer answers.
`"think": 0` on an answer makes the NPC reply at once, and `"think": 3` makes it wait three seconds. Players can make
the pause shorter or longer in their settings.

## Presentation

`screen` shows the dialogue in a panel at the bottom of the screen, as in a visual novel: a portrait, the speaker's
name, the line typed out, and the answers above it. The game keeps running. Click, Space or Enter moves on, and the
keys 1 to 9 pick an answer.

`hud` shows the lines as subtitles above the hotbar, and the player can keep moving. A dialogue whose only speaker is
`player` works as an inner monologue. When a choice comes, the dialogue screen opens.

`bubble` is for small talk. The NPC's lines appear over its head and the player keeps walking. It suits a line like
"Come back in the morning, the shop is closed", but not a long conversation.

## The camera

On the dialogue screen, the camera films the conversation the way a film would: it switches between the speakers,
showing each one over the listener's shoulder. It keeps clear of walls. In a cramped room it moves in front of the
listener, and if nothing fits it stays in first person.

You can direct each line:

- `shot` picks the framing: `auto`, `two_shot` (both speakers from the side), `close_up` (the speaker's face) or
  `first_person`.
- `transition` is `cut` or `ease`. With `ease` the camera glides to the new shot.
- `fade` fades the screen when the line shows, with `in`, `stay` and `out` in seconds and a `color`, just like the
  `fade` action. Use it for "Three hours later" on black, or for waking up.
- `look_at` turns the camera to something other than the speakers. The next line without `look_at` brings the camera
  back to them. If the target can't be found, the line uses its usual shot.

`look_at` can point at three kinds of target:

```json
"look_at": { "entity": { "tag": "mypack_boss" } }
"look_at": { "waypoint": "mypack:tower" }
"look_at": { "offset": [0, 2, 6], "from": "speaker" }
```

An `entity` is the nearest matching entity within 64 blocks, and the camera follows it. A `waypoint` is a waypoint the
player can see, such as a structure it has found. An `offset` is a point to the right, up and forward of the speaker,
or of the player with `"from": "player"`.

The camera can be turned off in three places: the server can forbid it (`camera.allowed`), a dialogue can turn it off
(`"camera": "off"`), and each player can turn it off for themselves (`camera.enabled`). Shoulder Surfing and Epic Fight
are paused while the camera is in use.

While a line plays, the player can look around a little, as in The Witcher 3. The cursor hides, and moving the mouse
turns the shot by a few degrees, with up and down inverted. Left alone, the view drifts slowly back. When the answers
appear, it glides back to the shot and the cursor returns to the middle of the screen. Players can turn this off with
`camera.floating`.

## When a dialogue is interrupted

A dialogue is interrupted when:

- the player walks farther from the NPC than `dialogues.maxDistance`;
- the NPC dies, or the player dies or logs out;
- the player takes damage, if `dialogues.interruptOnDamage` is on;
- the player leaves with Esc;
- an operator runs `/storybound dialogue stop`.

Then `on_abort` runs, and the `dialogue_ended` trigger does not fire.

A dialogue in subtitles or bubbles doesn't hold the player in place. Damage doesn't stop it, unless it is showing a
choice. If the player talks to an NPC meanwhile, the subtitles end and the NPC's dialogue starts. A dialogue that
`start_dialogue` starts while the player is already talking waits until that conversation is over.

## Fights

Think of a companion who tells a story on the road, as in God of War: when a fight starts, the story stops, and when
the fight is over, it goes on. You choose this for each dialogue. It only applies to subtitles and bubbles, because a
hit interrupts the dialogue screen anyway.

```json
"combat": { "mode": "pause", "resume": "where_was_i", "quiet": 5, "forget_after": 120 }
```

`mode` says what a fight does:

- `continue`, the default, changes nothing. Use it for callouts such as "Left!".
- `pause` hides the subtitles until the fight is over.
- `stop` interrupts the dialogue and runs `on_abort`, as in "Oh, a fight, I'm hiding!".

`resume` is a node to play after a pause, before the interrupted line comes back, such as "So. Where was I...". When
that node reaches `@end`, the interrupted line is shown again from its start. A `line` can have a `resume` of its own,
which is used instead, so that a story about the war and a story about the family can pick up in different ways.
Without any `resume`, the line simply comes back.

`quiet` is how many seconds without fighting end a fight. The default is 5.

`forget_after` is how long a pause may last, in seconds. The default is 120. After that, the dialogue is interrupted
and `on_abort` runs.

A fight is a hit between the player, or the NPC they are talking to, and any living thing. A mob nearby going after
either of them also counts.

A few more details:

- A choice that was showing when the fight started goes away and comes back afterwards.
- A dialogue with `pause` that starts during a fight, from a story event or a quest stage, waits for the fight to
  end and then starts from the beginning.
- Callouts with `continue` play while a story is paused, and the story goes on after them.
- The NPC is free to move during the pause, so it can fight. If it dies, or runs off and doesn't come back within
  `forget_after`, the dialogue is interrupted.

Mobs ignore players in creative mode, so test fights in survival. `/storybound debug scenario campfire` shows all of
this in action.

## NPC bindings

A binding says which mobs are NPCs and which dialogues they start. Bindings live in
`npc_bindings/<namespace>/<name>.json`:

```json
// config/storybound/my_story/npc_bindings/mypack/elder.json
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

### Which mobs

`entity` picks the mobs. Every part you give must match:

- `type`: an entity type, a `#tag` or a list;
- `tag`: a scoreboard tag, added with a command such as `/tag @e[type=villager,limit=1,sort=nearest] add elder`;
- `name`: only a mob with exactly this custom name, from a name tag or `/summon`;
- `uuid`: one particular mob.

For a mob of any kind, vanilla or modded, you can narrow it down further: `dimension`, `biome`, `structure`,
`in_village`, `baby`, a villager's `profession` and `outfit`, a mob's `variant` (a wolf's coat, a cat's breed), and its
saved data (`nbt`). This works with modded mobs, biomes and structures without any setup, because the choices are read
from the world and from the mob itself.

`entity` can also be a list of selectors. The binding then applies to any mob that matches one of them.

### NPCs for every mob of a kind

A binding with a tag or a uuid is an NPC you place by hand. A binding without one turns every matching mob into an
NPC, for example every villager in a village. Three settings help with that:

- `names` gives each mob a name from a list. Each mob gets the name that is least used nearby, so a village has no
  two villagers with the same name while the list lasts. A mob keeps its name for life.
- `share` turns only this percentage of the mobs into this NPC. With `30`, about one villager in three becomes a
  guard. Each mob is decided once and for good.
- `one` picks a single mob near a player and keeps it for good, such as one elder in the first village the player
  visits.

For an NPC placed by hand, `display_name` sets its name. The name shows over its head unless `show_name` is off, and
dialogues use it too.

Several bindings can apply to the same mob, and their settings stack up in layers. A binding for a particular mob
wins over a binding for a tag or a name, and that wins over a binding for a whole kind. Among bindings for a kind, the
one with more filters wins. Each setting comes from the closest binding that sets it. That way, one binding can make
every villager an NPC, a second can give the smiths their own chatter, and a third can pick the elder.

To see which bindings apply to a mob and what they give it, run `/storybound debug npc <entity>`.

### Which dialogue

The entries in `dialogues` are tried in order of `priority`, highest first. The first entry whose conditions hold
starts. That is how an NPC says different things before, during and after a quest: one entry for each phase, each with
a `quest` condition. If no entry holds, the click does what it normally does, such as opening trades.

Entries can also follow the time of day and the player's standing with the NPC. An entry with a `time` condition can
give an NPC a daytime dialogue (the shop is open), an evening one (in the tavern) and a night one ("Come back in the
morning"). An entry with a `var` condition on a relationship number, such as `mypack:rep/elder`, which you change with
`add_var`, can open warmer lines for players the NPC likes.

### How a dialogue starts

- `interact` starts it with the Talk key.
- `proximity` starts it when the player comes within `radius`. It happens once per visit, including when the NPC only
  gets something to say while the player is already standing there. `cooldown` is in seconds, and `once` makes it
  happen once per player.
- `custom` leaves the decision to a KubeJS script (see [Integrations](integrations.md#kubejs)).

### More binding settings

`"exclusive": true` makes the NPC talk to one player at a time. `busy` is what other players hear meanwhile, such as
"Wait, I'm busy", said in a bubble.

`"override_interact": true` makes right-click talk instead of opening trades. Use it for merchants whose dialogue has
a "Show me your wares" answer with the `open_trade` action. When no dialogue holds, for example at night when the shop
is closed, right-click opens the trades as usual.

`"respawn": true` brings the NPC back 5 seconds after it dies or disappears, at the same place and unhurt. It is the
same NPC, with the same name, look, equipment, trades and uuid. It drops nothing when it dies, so players can't farm
it. To get rid of such an NPC for good, turn `respawn` off first.

`"invulnerable": true` protects the NPC from everything (mobs, fire, falling, players) except the void and `/kill`.
Mobs still attack it.

### When a player hits the NPC

Without any setting, the NPC does what its kind does: an iron golem fights back, a villager runs away. To change that:

```json
"when_attacked": { "reaction": "fight", "patience": 2, "warning": "mypack:smith_careful", "phrases": "mypack:smith_angry", "calm_after": 30 }
```

`reaction` is one of:

- `ignore`: the NPC doesn't take revenge;
- `flee`: it runs from the player for 5 seconds;
- `fight`: it attacks the player. This only works for mobs that can attack.

`patience` and `warning` only apply to `fight`. `patience` is how many hits the NPC forgives before fighting back, and
each forgiven hit says the next `warning` phrase ("Careful!", "Once more and..."). `phrases` is what the NPC says on
the hits it reacts to ("That's it!"). With `ignore` and `flee`, it says them on every hit. Both take a phrases node
(see [Phrases](#phrases)).

Instead of `phrases`, a hit can start a scene with `"dialogue": "mypack:smith_angry"`. The scene starts after any
conversation the player is already in. You can't use both.

After `calm_after` seconds without a hit, the NPC forgives everything and its phrases start over.

Only hits from players count for the reaction. With `"by_mobs": true`, the NPC also says its `phrases` when a mob
hurts it (a zombie, a wolf, a skeleton's arrow), to everyone nearby, but not while its last phrase is still showing. In
the editor this is "Also when a mob hurts it".

### Movement

`movement` decides how the NPC walks:

- `"stay"` keeps the NPC where it stands, with its head turned to the nearest player. If it is pushed away, it walks
  back.
- `"home"` with `"home_radius": 8` lets it walk around within 8 blocks of its place, and brings it back if it is led
  away.
- `"free"`, the default, lets it walk as its kind normally does. Write it to undo a setting from another binding: every
  guard stays at the gate, but the captain walks freely.

The NPC's place is where it stood when the binding first applied to it: where the story spawned it, or where it was
living in the world. A teleport moves its place too.

The NPC can still sleep, panic, flee, fight, talk and ride, and walks back afterwards. This is better than the `NoAI`
tag, which also freezes the mob's head and stops it from fleeing. In the editor, this is the "Movement" section.

### Chatter

Now and then, the NPC says one of its phrases to players near it:

```json
"chatter": { "phrases": "mypack:fishmonger", "min_interval": 20, "max_interval": 40, "radius": 8, "conditions": { "type": "time", "period": "day" } }
```

It waits a random time between the two intervals, in seconds, and speaks only while a player is within `radius` and
the conditions hold for the nearest one. It stays quiet while it is in a dialogue, while it is angry about a hit, and
when nobody is near.

To change what the NPC says as the story goes on, add `variants` ("As the story goes on" in the editor). While a
variant's conditions hold, its phrases are said instead of the usual ones, never alongside them. If several variants
hold, the one with the highest `priority` wins, and on equal priority the one higher in the list. If none hold, the
NPC says its usual `phrases`, or nothing.

```json
"chatter": { "phrases": "mypack:wolves_everywhere", "variants": [ { "phrases": "mypack:quiet_now", "conditions": { "type": "quest", "quest": "mypack:wolf_hunt", "state": "completed" } } ] }
```

### Phrases on a click

A `dialogues` entry can say phrases instead of starting a dialogue. The NPC answers in a bubble with the next line on
each click, and no conversation opens:

```json
"dialogues": [
  { "phrases": { "text": "mypack.smith.thanks" }, "priority": 10, "conditions": { "type": "quest", "quest": "mypack:wolf_hunt", "state": "completed" } },
  { "phrases": "mypack:smith_grumbles" }
]
```

## Phrases

Phrases are an NPC's short lines, said one at a time without opening a conversation. They live in
`phrases/<namespace>/<name>.json`:

```json
{ "order": "sequence", "lines": [ "mypack.smith.hey", "mypack.smith.once_more" ] }
```

`order` is `random`, which never repeats the same line twice in a row, or `sequence`, which goes from top to bottom
and then repeats the last line.

Each line is said in a bubble over the NPC. Phrases never start a dialogue themselves. Where an NPC can start a
dialogue instead, on a click or when hit, you write `dialogue` there.

An NPC uses phrases for clicks, for its chatter and for when it is hit. One phrases file can serve many NPCs, and each
goes through it on its own. In the editor, each line is a text field with ✎ at its right to open the text window.

An NPC's `phrases` and `warning` take either a phrases file or a text of its own, `{ "text": ... }`. In the editor you
switch between "Own text" and "Phrases node", and on a click or a hit also "Dialogue".

As a rule of thumb, a single remark is a phrase. Several lines in a row, actions or answers make a dialogue. A
dialogue is shown the same way from start to end (screen, subtitles or bubbles). To change how it is shown, jump to
another dialogue.

Right-click is left to the game and to other mods (villagers trade, Easy NPC opens its own screens), unless the server
sets `dialogues.rightClickTalks = true` or a binding sets `override_interact`.

## Voices

When an NPC speaks, it uses its own voice: each dialogue line without a `sound` of its own, and each phrase in a bubble,
plays the creature's own sound. A villager hums and a piglin grunts. A creature without such a sound, like an iron
golem, speaks in silence.

To give an NPC a voice of its own, add this to its binding:

```json
"voice": { "sound": "mypack:iva_hum", "pitch": 1.2 }
```

`sound` is an `.ogg` from the story's `sounds/` folder, or any sound the game knows. Without `sound`, the creature's
own sound plays at this `pitch`, from 0.5 to 2. In the editor this is "Voice".

### Voice-over

A recorded line plays instead of the voice. For a dialogue line, set its `sound` ("Voice-over" in the editor, or 🎤
beside the line's text). For a phrase, write it as `{ "text": "mypack.fish.fresh", "sound": "mypack:fish_fresh" }`
(🎤 beside the phrase in the editor).

In the sound picker, the chosen sound is highlighted, and "No sound (remove)" at the top removes it. This works the
same for voice-over, sound and music fields.

A line's voice-over stops when the player clicks past the line, when the next line starts and when the dialogue ends,
so voices never talk over each other. A line with `duration` moves on by itself, and that stops its voice-over too.
Set `duration` to the length of the recording in seconds, because the mod doesn't read it from the file.

## Music

You can play your own music tracks over the game. Put the `.ogg` file in the story's `sounds/` folder, where it becomes
the sound `<namespace>:<name>`. The file name may contain capitals, spaces and other characters. The id is the name in
lowercase, with `_` in place of anything else: `Ragnar Meets.ogg` becomes `ragnar_meets`. Use Latin letters only.

Then name the track where it should play:

- In a dialogue, `"music": "mypack:iva_theme"` plays while the conversation runs.
- In a quest stage, `"music": "mypack:night_watch"` plays while that is the current stage of the quest the player
  tracks.
- For a place, make a file of its own in the `music/` folder:

```json
{ "track": "mypack:deep_dark", "conditions": { "type": "location", "structure": "minecraft:ancient_city" }, "priority": 10 }
```

If several tracks apply, the dialogue's plays first, then the quest stage's, then the place with the highest
`priority`.

One track plays at a time. It loops, follows the game's Music volume, and fades into the next track. Minecraft's own
music stays silent while yours plays, and afterwards waits its usual pause before its next song. A track keeps playing
after a trip to the Nether or a reload of the sounds. The end credits keep their own music.

During a dialogue, music plays at 40% volume so the words can be heard. This includes the dialogue's own track, so
mix that track with this in mind.

In the editor, the music picker lists your own sounds and the music of Minecraft and other mods.

## Interface sounds

Quests starting, moving on, being completed and failing, a new Codex entry, a boss, a change in standing with an NPC,
the Journal and a dialogue answer each have a sound. By default these are vanilla sounds. To replace one, put an
`.ogg` with its name directly in the story's `sounds/` folder:

`quest_started`, `quest_updated`, `quest_completed`, `quest_failed`, `codex_new`, `codex_updated`, `boss_encountered`,
`boss_defeated`, `info`, `reputation_up`, `reputation_down`, `journal_open`, `journal_close`, `journal_tab`,
`dialogue_choice`.

## Speech bubbles

Players within 16 blocks of a conversation see its lines over the speakers' heads, and the chosen answer over the
player's head. `"broadcast": false` keeps a dialogue private. Each player can turn bubbles off with
`dialogue.speechBubbles`.

## Several players, one NPC

Each conversation is separate. Values in the `world` scope are shared by everyone, so guard them with conditions, for
example to check that a reward hasn't already been given.
