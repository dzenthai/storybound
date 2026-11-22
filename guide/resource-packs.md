# Resource packs

A resource pack can change how Storybound looks: the colours and the book of the Adventure Menu, the interface texts,
readable documents and their items, and the waypoint markers. A pack needs no code and works on any server, like any
other resource pack. Press F3+T to reload it while you work.

All paths below are inside the pack, under `assets/`. Files in Storybound's own namespace replace the mod's files.
Files in your own namespace add new things, such as a new document style or a new texture.

| What | Where | Schema |
| --- | --- | --- |
| Adventure Menu colours | `assets/storybound/storybound/menu/theme.json` | `menu_theme.schema.json` |
| Adventure Menu book | `assets/storybound/textures/gui/journal/adventure_book.png` | |
| Pin and sparks | `assets/storybound/textures/gui/journal/pin.png`, `spark.png` | |
| Interface texts | `assets/storybound/lang/<language>.json` | |
| Adventure Menu fonts | `assets/storybound/font/journal.json`, `journal_heading.json` | |
| Document styles | `assets/<namespace>/storybound/styles/<name>.json` | `document_style.schema.json` |
| Document sheets | `assets/<namespace>/textures/gui/document/*.png` | |
| Document items | `assets/storybound/models/item/document*.json`, `textures/item/document_*.png` | |
| Waypoint markers | `assets/storybound/storybound/waypoint_styles/default.json` and three sprites | `waypoint_style.schema.json` |
| Sounds | `assets/<namespace>/sounds.json`, as in any pack | |

The schemas are in the repository's [`schemas/`](https://github.com/dzenthai/storybound/tree/main/schemas) folder.
In VS Code they give you completion and error marks as you type (see
[Schemas for your editor](README.md#schemas-for-your-editor)).

## Adventure Menu

### Colours

`theme.json` sets the colours of the menu's text, its marks and the quest tracker on the HUD. Leave out a key to keep
the mod's colour. A colour is `#RRGGBB`, or `#AARRGGBB` with transparency. These are the mod's own colours:

```json
{
  "title": "#483020",
  "ink": "#483020",
  "muted": "#806747",
  "accent": "#865332",
  "unseen": "#B08031",
  "completed": "#415418",
  "failed": "#86232A",
  "selection": "#60CDA269",
  "hover": "#30AF7846",
  "divider": "#AF7846",
  "hud_background": "#A62A221C",
  "hud_title": "#FFE08A",
  "hud_text": "#F5DFB2"
}
```

| Key | What it colours |
| --- | --- |
| `title` | Titles on the pages. |
| `ink` | The text. |
| `muted` | Secondary text: categories, hints, "(optional)". |
| `accent` | Buttons and highlighted words. |
| `unseen` | The dot on things the player hasn't opened yet. |
| `completed`, `failed` | Completed and failed quests. |
| `selection`, `hover` | The selected row and the row under the mouse, usually see-through. |
| `divider` | Lines between parts of a page. |
| `hud_background`, `hud_title`, `hud_text` | The quest tracker on the HUD. |

### The book

`adventure_book.png` is the whole open book, 384 × 256 pixels. The menu places its text on the pages by coordinates,
so a new book has to keep the same size and put the pages in the same places:

- the left page goes from x 23 to 183, and the right page from x 200 to 360, both from y 32 to 208;
- inside them, the text needs room from x 41 to 169 on the left and from x 215 to 343 on the right.

The menu always draws the book at a whole number of screen pixels per book pixel, so pixel art stays sharp.

### Pin and sparks

Both are optional. Without them, the mod draws its own.

- `pin.png`, 8 × 8, is the pin of a tracked quest, shown on its button and in the list.
- `spark.png`, 36 × 9, holds four sparks of 9 × 9 side by side, smallest first. They glitter around a reward that is
  ready to claim.

### Fonts

The menu's text uses the font `storybound:journal`, and its headings use `storybound:journal_heading`, drawn in bold.
Both are the game's default font until a pack replaces them, so you can give the menu a font of its own without
changing the rest of the game. They are ordinary Minecraft fonts, with `bitmap`, `ttf` or `reference` providers:

```json
{ "providers": [ { "type": "reference", "id": "mypack:old_hand" } ] }
```

A font that is much wider or taller than the game's may not fit on the pages. The menu wraps and cuts text to the
width of the pages, but its line spacing is fixed.

### Texts

Every word of the interface is a translation key starting with `storybound.`, in
`assets/storybound/lang/<language>.json`. A pack can rename tabs and buttons, or add a new language. A pack's file
only needs the keys it changes. The mod's English and Russian files list all of them.

## Documents

A document's look is called its style: the sheet, where the text goes, the font and the colours. Six styles come with
the mod: `book_leather`, `scroll`, `parchment`, `note`, `note_torn` and `contract`.

To change one, put a file with the same name in `assets/storybound/storybound/styles/`. To add a new one, put it in
your own namespace and name it in the document's `style`. For example, `"style": "mypack:old_map"` uses
`assets/mypack/storybound/styles/old_map.json`.

```json
{
  "texture": "mypack:textures/gui/document/old_map.png",
  "width": 320,
  "height": 384,
  "padding": { "left": 64, "top": 64, "right": 64, "bottom": 64 },
  "font": "minecraft:default",
  "color": "#483020",
  "paper": "#F7E4C2",
  "line_height": 13,
  "show_title": true,
  "open_sound": "minecraft:item.book.page_turn"
}
```

| Key | What it does |
| --- | --- |
| `texture` | The sheet. For a book, it is the whole two-page spread. |
| `width`, `height` | The sheet's size on screen, in GUI pixels. |
| `texture_width`, `texture_height` | The image's size, when it differs from the sheet's size. |
| `padding` | The margins around the text inside the sheet. For a book, inside each page. |
| `font` | A font from `assets/<namespace>/font/`. This is where your own font goes. |
| `color` | The colour of the text. |
| `paper` | The colour of the paper behind the text. Long texts fade into it near the scroll arrows. |
| `line_height` | The distance from one line to the next, in pixels. |
| `show_title` | Prints the document's title above its text. |
| `open_sound`, `turn_sound` | The sounds for opening the document and turning a page. |

The editor lists the styles by name. `/storybound_client validate` lists styles that documents ask for but no pack
provides.

### Document items

All documents are one item, and its icon depends on the document's kind. The model `storybound:item/document` picks
`document_book`, `document_scroll`, `document_note`, `document_letter` or `document_contract` using the item property
`storybound:kind` (0.1 to 0.5, in that order). To change an icon, replace its texture in `textures/item/`.

To give one particular document an icon of its own, add an override with `custom_model_data` to your copy of
`models/item/document.json`, and give the document the same number.

Icons for a single story also work without a resource pack. Put square PNG files in the story folder's
`icons/<namespace>/`, and the editor offers them in the document's "How it looks" section.

## Waypoint markers

All markers share one look, defined in `default.json`. Leave out a key to keep the mod's value:

```json
{
  "icon": "storybound:waypoint/marker",
  "area_icon": "storybound:waypoint/area",
  "event_icon": "storybound:waypoint/event",
  "question_icon": "storybound:waypoint/question",
  "arrow": "storybound:waypoint/arrow",
  "size": 11,
  "text_color": "#F0E6D2",
  "distance_color": "#C9B48A",
  "name": "tracked",
  "distance": true,
  "fade_start": 96,
  "max_distance": 768,
  "hide_within": 4
}
```

| Key | What it does |
| --- | --- |
| `icon`, `area_icon`, `arrow` | GUI sprites (`assets/<namespace>/textures/gui/sprites/<path>.png`) for a point (a diamond), a search area (a magnifier) and the arrow at the edge of the screen. Draw them in light colours on a transparent background, because the mod tints them with each waypoint's colour. The mod's own icons have a dark outline and a hard shadow 2 pixels (of 32) to the right and down; draw the shadow into `icon` and `area_icon`, but not into `arrow`: the arrow turns, so the mod draws its shadow itself. |
| `event_icon` | The sprite of an event marker: one "!" filling the height of the square, its dot at the bottom edge. The mod draws it three times on one line, the middle one larger, and makes them hop, the middle one first and the side ones together after it, squashing and stretching like a cartoon. Draw it in greys with a dark outline, like the other icons: the waypoint's colour is laid over it. To add a glint that runs over it, draw the frames one under another in the same PNG and add a `.png.mcmeta` with an `animation` section, as for any animated sprite. |
| `question_icon` | The sprite of a question marker: one "?" filling the height of the square, its dot at the bottom edge. The mod shakes it now and then, turning it around its dot, with a small hop. Draw it in greys with a dark outline, like the other icons. |
| `size` | The icon's size in GUI pixels, before the player's own scale. |
| `text_color`, `distance_color` | The colours of the name and the distance under the icon. |
| `name` | When the name shows: `always`, `near_crosshair`, `tracked` (always for the tracked quest, near the crosshair for the rest) or `never`. |
| `distance` | Whether to show the distance. |
| `fade_start`, `max_distance` | Markers farther away than `fade_start` blocks start to fade. Markers farther than `max_distance` are hidden, unless their quest is tracked. `0` means never hidden. |
| `hide_within` | Markers closer than this fade out, because the player has arrived. |

Whatever the pack says, players can still make markers bigger or smaller, change their opacity or turn them off in
the client settings. The pack draws the edge arrow, but whether it shows (for the tracked quest's markers, for all of
them, or never) is up to the player.

## What a pack can't change yet

- The menu's layout: where the pages, the list and the buttons are is fixed, which is why the book must keep its
  size.
- The bookmarks, which are the menu's tabs, and their emblems. The mod draws them in its own palette, not from a
  texture.
- A story's own texts. Translations in the story folder come before a pack's, so a pack can add a language to a story
  but can't rewrite what its author wrote. Keys and long texts that the story folder doesn't have are taken from packs
  as usual (see [Texts and languages](languages.md)).
