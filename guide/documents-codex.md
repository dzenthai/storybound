# Documents, the Journal, the Codex and bosses

## Documents

Books, scrolls, notes, letters and contracts are one item, `storybound:document`, that says which document it is.
The text comes from the definition, so editing a document changes the copies players already hold.

```json
// config/storybound/documents/mypack/elder_letter.json
{
  "kind": "letter",
  "title": "mypack.document.elder_letter.title",
  "journal": { "category": "letters", "add_on_read": true },
  "on_read": { "type": "set_flag", "key": "mypack:read_elder_letter" }
}
```

The text is a file per language: `config/storybound/texts/en_us/mypack/elder_letter.sbt` (or
`assets/mypack/storybound/texts/en_us/elder_letter.sbt` in a resource pack). See [the markup](languages.md#long-texts).

| Field | Default | Meaning |
| --- | --- | --- |
| `kind` | required | `book` (a two-page spread with page turning), `scroll`, `note`, `letter`, `contract` (one scrolling sheet) |
| `title` | required | also the item's name |
| `content` | the document's id | which text file to show |
| `style` | by kind | the look: `book_leather`, `scroll`, `parchment`, `note_torn` or your own |
| `journal.category` | by kind | the Journal section (`books`, `scrolls`, `notes`, `letters`, `contracts` or your own: key `<ns>.journal.category.<path>`) |
| `journal.add_on_read` | `true` | archive it in the Journal on the first read |
| `on_read` | — | actions on the **first** read |
| `contract` | — | `{ "quest"?, "conditions"?, "reward"?, "hint"? }`: a notice from a board; reading it starts the quest, and the reward and hint texts are printed under it |
| `custom_model_data` | — | for a model of its own from a resource pack |

**Giving documents:** the `give_document` action, `/storybound document give|open|mark_read <players> <document>`,
loot tables (`set_components` with `storybound:document`), or KubeJS
`Item.of('storybound:document', '[storybound:document={id:"mypack:x"}]')`. The Storybound creative tab lists every
document.

**Styles** live in resource packs (`assets/<ns>/storybound/styles/<name>.json`, schema `document_style.schema.json`):
a texture with its size, the padding to the text, the font, the colour, the line height and the sounds of opening and
turning a page.

## The Journal

The Journal tab keeps what the player has read, by category, and notes written by the `journal_entry` action
(writing the same note id again replaces it). Each player has their own Journal, even in a party.

## The Codex

A bestiary and encyclopedia whose entries open part by part: seen it — the description, killed it — its weaknesses,
read a treatise — its lore.

```json
// config/storybound/codex/mypack/drowner.json
{
  "category": "monsters",
  "entity": "mypack:drowner",
  "title": "mypack.codex.drowner.title",
  "sections": [
    { "id": "base",     "unlock": { "on": ["seen", "killed"] } },
    { "id": "weakness", "title": "mypack.codex.drowner.weakness", "unlock": { "on": ["killed"], "count": 5 } },
    { "id": "lore",     "title": "mypack.codex.drowner.lore", "unlock": { "on": ["read:mypack:bestiary_vol1"] } }
  ]
}
```

| Field | Default | Meaning |
| --- | --- | --- |
| `category` | `monsters` | `monsters`, `animals`, `characters`, `places`, `lore` or your own (key `<ns>.codex.category.<path>`) |
| `entity` | — | what `seen` and `killed` mean (an id, `#tag` or list); a single id also gives the portrait |
| `title` | required | |
| `sections` | required | in order: `id`, `title?`, `text?` (by default the text `<ns>:codex/<entry>/<section>`) and `unlock` |
| `notify` | `true` | toasts when the entry or a section opens |
| `show_kills` | `true` | "Killed: N" in the entry |

**Unlock rules** (`unlock`): `on` lists what opens the section — `seen`, `killed`, `read:<document>`, or `manual`
(only actions, commands and scripts); any of them reaching `count` opens it, if `conditions` hold then. Kills by party
members nearby count too. Closed sections show "??? Not yet discovered".

A player "sees" a creature after looking at it for a second within 24 blocks with nothing in between
(`detection.seenRadius`, `seenAngle`, `seenTicks` in the common config).

## Bosses

A boss entry records the fights with a particular mob: met, defeated, how many times, the fastest fight.

```json
// config/storybound/bosses/mypack/drowned_king.json
{
  "entity": { "type": "mypack:drowner", "tag": "drowned_king" },
  "title": "mypack.boss.drowned_king.title",
  "lore": {},
  "tactics": { "unlock": { "on": ["killed", "read:mypack:bestiary_vol1"] } },
  "rewards": { "items": [ "minecraft:trident", "minecraft:prismarine_shard" ] },
  "on_first_kill": { "type": "unlock_codex", "entry": "mypack:drowner", "target": "party" },
  "on_kill": { "type": "give_item", "item": "minecraft:prismarine_shard", "count": 8 },
  "participation": 0.05
}
```

- **Which mob:** `entity` selects it like an NPC binding (`type`, `tag`, `name`, `uuid`), so a named mob from a
  command or a script can be a boss.
- **Sections:** `lore` (opens on `seen` by default), `tactics` and `rewards` (on `killed`); `lore` and `tactics` are
  texts (`<ns>:bosses/<boss>/lore`, `.../tactics`), `rewards` a list of items to show. The real rewards are the
  `on_kill` actions.
- **Who wins:** every player who dealt at least `participation` of the boss's health, the killer, and their party
  members nearby. `on_first_kill` runs on each one's first victory, `on_kill` on every victory.

The demo's Pumpkin Reaper (`demo:pumpkin_reaper`) appears in its Night Watch quest; to fight it at once:

```
/summon minecraft:zombie ~ ~ ~ {Tags:["demo_reaper"],CustomName:'"Pumpkin Reaper"',ArmorItems:[{},{},{},{id:"minecraft:jack_o_lantern",count:1}],attributes:[{id:"minecraft:generic.max_health",base:60}],Health:60f}
```
