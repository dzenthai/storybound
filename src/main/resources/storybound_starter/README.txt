Storybound story folder
=======================

Everything here is story data for Storybound: no datapack or resource pack needed. It is read when a world
starts and on every /reload, on the server (in singleplayer, your own game), and sent to every player.

Folders (one per kind of definition; the JSON is the same as in a datapack, see the mod's schemas):

  quests/  dialogues/  npc_bindings/  documents/  waypoints/  codex/  bosses/  events/  flags/

The dev mode (the Adventure Menu's Dev Mode button, for operators) writes here too. Its own files only lay out
the editor and are never read by the game:

  chapters/  dialogue_layouts/  scripts/  project.json (the story's languages)

scripts/ are the blocks that show where KubeJS code (kubejs/server_scripts/) enters the story; the code itself
stays in KubeJS.

Ids come from file paths. Inside a folder, the first subfolder is the namespace:
  quests/mypack/wolf_hunt.json   ->  mypack:wolf_hunt
  quests/wolf_hunt.json          ->  storybound:wolf_hunt   (a file right in the folder)
Use lowercase letters, digits, _ - . and / in names. A file here replaces a datapack entry with the same id.

Texts:
  lang/<language>.json                 translations, like a resource pack's lang file ("key": "text")
  texts/<language>/<namespace>/x.sbt   long texts of documents, Codex entries and bosses (same naming as above)
The player's language is used first, then en_us.

The four blank documents (book, scroll, note, letter) are here to show how it works; change or delete them.

The demo story (namespace "demo") starts when a player joins: a village elder, a night ambush, a lost cat and a
festival, five quests in two chapters. Play it, open it in the dev mode, change it: it is yours.
  To remove it: delete every "demo" folder here (quests/demo, dialogues/demo, ..., texts/<language>/demo) and the
  "demo." lines in lang/, then /reload. On a server, the demo starts for every player who joins.
  To bring it back:  /storybound demo install
project.json turns on the players' chapter map and sets the difficulty to stars; change it as you like.

Writing a story from files (without watching the game), these commands write for you to read:
  /storybound trace on [players]    what the story logic does, line by line: logs/storybound-trace.log
  /storybound dump <player>         the player's story state: logs/storybound-dump-<player>.json
  /storybound export                every type, id, stage, node and flag a story can name: logs/storybound-export.json
  /storybound capture pos <name>    where you stand, into captures.json here (capture entity <name>: what you look at)

This folder is created once, when it does not exist. Delete it to get the starter files back.
