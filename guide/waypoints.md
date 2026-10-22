# Waypoints

Waypoints live on the server; each player gets only the ones they should see. They show as markers in the world (an
icon with the distance, an arrow at the screen edge for the tracked quest) and as waypoints in Xaero's Minimap and
JourneyMap. No map mod is needed.

```json
// config/storybound/waypoints/mypack/wolf_den.json
{
  "name": "mypack.waypoint.wolf_den",
  "target": { "pos": [120, 70, -340], "dimension": "minecraft:overworld" },
  "radius": 24,
  "color": "#C0392B",
  "quest": "mypack:wolf_hunt",
  "stage": "hunt"
}
```

| Field | Default | Meaning |
| --- | --- | --- |
| `name` | required | the waypoint's name |
| `target` | required | where it points, see below |
| `radius` | 0 | a search area of that radius instead of a point, as in The Witcher |
| `color` | `#E8C66A` | `#RRGGBB` or `#AARRGGBB` |
| `conditions` | always | shown while they hold |
| `quest`, `stage` | — | shown only while the quest is active (at that stage) |
| `style` | `storybound:default` | the marker's look (`storybound:npc` is smaller, for people) |

## Targets

| Target | Points at |
| --- | --- |
| `{ "pos": [x, y, z], "dimension"? }` | a place |
| `{ "entity": "<uuid>" }` | one entity, followed as it moves |
| `{ "structure": "#minecraft:village", "dimension"?, "search_radius"?, "origin"? }` | the nearest such structure, searched like `/locate` in the background near each player, and again as they travel |
| `{ "nearest": "minecraft:wolf", "range"? }` | the nearest matching living entity around each player (an id, `#tag`, list, or a selector like `{ "type": "minecraft:wolf", "tag": "alpha" }`); `range` 64 blocks by default |

Only loaded entities can be found: for "kill the pack leader", spawn the leader with a tag (`spawn_entity` with `tag`)
and point the waypoint at that tag.

## In quests

A stage lists the waypoints it shows: `"waypoints": ["mypack:wolf_den"]`. By default players see quest waypoints only
for pinned quests (`waypoints.untrackedQuestWaypoints`: `HIDE`, `DIM` or `SHOW` in the client settings). Waypoints
that belong to no quest (a person, a shared place) always show.

```json
// two stages of a quest
{ "id": "hunt", "waypoints": ["mypack:wolves"],
  "objectives": [ { "trigger": { "type": "entity_killed", "entity": "minecraft:wolf" }, "count": 3, "text": "mypack.quest.wolf_hunt.objective.wolves" } ] },
{ "id": "village", "waypoints": ["mypack:village"],
  "objectives": [ { "trigger": { "type": "structure_entered", "structure": "#minecraft:village" }, "text": "mypack.quest.wolf_hunt.objective.village" } ] }

// waypoints/mypack/wolves.json
{ "name": "mypack.waypoint.wolves", "target": { "nearest": "minecraft:wolf" } }
// waypoints/mypack/village.json
{ "name": "mypack.waypoint.village", "target": { "structure": "#minecraft:village" } }
```

## Added by actions

`add_waypoint` adds a waypoint for the player, the party or everyone (`visibility`: `player`, `party`, `world`):

| Field | Meaning |
| --- | --- |
| `id`, `name` | required; the same id and visibility replaces the waypoint |
| `pos`, `dimension` | at a place |
| `follow` | following the entity of the moment (the NPC talked to) |
| `structure`, `search_radius` | at the nearest structure |
| (none of them) | where the entity of the moment, else the player, stands |
| `color`, `radius`, `style`, `visibility` | as above |

`remove_waypoint` takes `id` and `visibility`. Operators can share waypoints by hand:
`/storybound waypoint add <id> <pos> <name>`, `remove <id>`, `list <player>`.

## Marker styles

Resource packs restyle markers in `assets/<ns>/storybound/waypoint_styles/<name>.json` (schema
`waypoint_style.schema.json`): the icons, size, colours, when the name shows, fading distances and which waypoints get
an arrow at the screen edge. Players can scale markers and their opacity, or turn them off, in the client settings.
