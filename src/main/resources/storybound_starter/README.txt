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
To add the example story (wolves, a village, an elder), run in game:  /storybound example install

This folder is created once, when it does not exist. Delete it to get the starter files back.
