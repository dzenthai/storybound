# Texts and languages

Every text a player reads is a translation key, so a story can be played in several languages. During writing a text
may also be written straight into the JSON and turned into keys later.

## Text fields

```json
"title": "mypack.quest.wolf_hunt.title"
"title": { "key": "mypack.npc.greet", "args": ["{player}", "{var:mypack:gold}"] }
"title": { "key": "mypack.npc.greet", "fallback": "Hello, traveler" }
"title": { "key": "mypack.npc.greet", "variant_by": "mypack:gender" }
"title": { "literal": "Draft" }
```

- **A string** is a key.
- **`args`** fill `%s` in the text, for each player: `{player}` (the name), `{party}` (the party's name),
  `{var:ns:key}` / `{flag:ns:key}` (a value now), `{key:some.key}` (another translated text); anything else as written.
- **`fallback`** shows when the key has no translation.
- **`variant_by`** picks a variant by a variable: with the value `f`, `mypack.npc.greet.f` is used when the language has
  it, else `mypack.npc.greet`. Handy for grammatical gender ("пришёл" / "пришла").
- **`literal`** is the text itself, for drafts.

Keys are usually named `<ns>.<kind>.<id>.<field>`: `mypack.document.elder_letter.title`.

## Language files

`config/storybound/lang/en_us.json`, `ru_ru.json`… (or `assets/<ns>/lang/` in a resource pack) map keys to texts:

```json
{ "mypack.quest.wolf_hunt.title": "The Wolf Hunt", "mypack.npc.greet": "Hello, %s!" }
```

The server sends the story folder's texts to every player, so no resource pack is needed. A missing translation falls
back to the story's source language (`project.json`), then to `en_us`.

## Long texts

Books, letters, Codex sections and boss lore are `.sbt` files, one per language:
`config/storybound/texts/<language>/<ns>/<path>.sbt` (or `assets/<ns>/storybound/texts/<language>/<path>.sbt`).
Plain text, each line a line; with a little markup:

| Markup | Meaning |
| --- | --- |
| `**text**` | bold |
| `*text*` | italic |
| `{color:#7a1f1f}…{/}` | colour (`#rrggbb` or a colour name) |
| `{font:mypack:runes}…{/}` | a font from `assets/<ns>/font/` |
| `{key:some.translation.key}` | a translated text |
| `{img:mypack:textures/doc/seal.png 40x20}` | an image, alone on its line (size optional) |
| `---page---` | a page break in books, alone on its line |
| `\*`, `\{`, `\\` | the characters themselves |

A typo in the markup stays visible as text instead of breaking the page.

## Writing first, translating later

Write the first version with `{ "literal": "..." }`, then run `/storybound lang extract`: every literal moves to
`lang/<source language>.json` under a key named after where it stands (`mypack.quest.wolf_hunt.title`,
`mypack.dialogue.elder.node.greet.text`), and the JSON keeps the key. Keys with a `fallback` missing from the language
file get the fallback as their text. `/storybound lang preview` shows what would change without writing anything.
Then translate the language file, or use the editor's language switch.
