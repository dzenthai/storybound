# Texts and languages

Every text a player reads is a translation key, so the same story can be played in several languages. While you are
writing, you can also put the text straight into the JSON and turn it into keys later.

## Text fields

A text field can be written in several ways:

```json
"title": "mypack.quest.wolf_hunt.title"
"title": { "key": "mypack.npc.greet", "args": ["{player}", "{var:mypack:gold}"] }
"title": { "key": "mypack.npc.greet", "fallback": "Hello, traveler" }
"title": { "key": "mypack.npc.greet", "variant_by": "mypack:gender" }
"title": { "literal": "Draft" }
```

A plain string is a translation key.

`args` fill in the `%s` placeholders of the text, for each player separately:

- `{player}` is the player's name;
- `{party}` is the party's name;
- `{var:namespace:key}` and `{flag:namespace:key}` are the current value of a variable or flag;
- `{key:some.key}` is another translated text;
- anything else is used as written.

`fallback` is shown when the key has no translation.

`variant_by` picks a version of the text by a variable. If the variable's value is `f`, the key `mypack.npc.greet.f`
is used when the language has it, and `mypack.npc.greet` otherwise. Use this for grammatical gender, for example in
languages where verbs change with the speaker's gender.

`literal` is the text itself. Use it for drafts.

Keys are usually named `<namespace>.<kind>.<id>.<field>`, for example `mypack.document.elder_letter.title`.

## Language files

Each story has a `lang/` folder with one file per language, such as `config/storybound/my_story/lang/en_us.json` and
`ru_ru.json`. A resource pack can provide them too, in `assets/<namespace>/lang/`. The files map keys to texts:

```json
{ "mypack.quest.wolf_hunt.title": "The Wolf Hunt", "mypack.npc.greet": "Hello, %s!" }
```

The server sends the story's texts to every player, so players don't need a resource pack. When a translation is
missing, the text in the story's source language (set in `project.json`) is shown, and if that is missing too, the
`en_us` text.

## Long texts

Books, letters, Codex sections and boss lore are `.sbt` files, one per language, in
`config/storybound/my_story/texts/<language>/<namespace>/<path>.sbt`. A resource pack can provide them in
`assets/<namespace>/storybound/texts/<language>/<path>.sbt`.

An `.sbt` file is plain text, where each line is shown as a line, with a little markup:

| Markup | What it does |
| --- | --- |
| `**text**` | Bold. |
| `*text*` | Italic. |
| `{color:#7a1f1f}…{/}` | A colour, as `#rrggbb` or a colour name. |
| `{font:mypack:runes}…{/}` | A font from `assets/<namespace>/font/`. |
| `{key:some.translation.key}` | A translated text. |
| `{img:mypack:textures/doc/seal.png 40x20}` | An image, on a line of its own. The size is optional. |
| `---page---` | A page break in books, on a line of its own. |
| `\*`, `\{`, `\\` | The characters themselves. |

If you make a typo in the markup, it shows up as plain text instead of breaking the page.

## Writing first, translating later

You can write the first version with `{ "literal": "..." }` and move the texts into a language file later. Run
`/storybound debug lang extract`. Every literal is moved to `lang/<source language>.json`, under a key named after
where it was, such as `mypack.quest.wolf_hunt.title` or `mypack.dialogue.elder.node.greet.text`, and the JSON now
holds the key. Keys that have a `fallback` but are missing from the language file get the fallback as their text.

`/storybound debug lang preview` shows what would change, without writing anything.

After that, translate the language file, or switch the language in the editor and translate there.
