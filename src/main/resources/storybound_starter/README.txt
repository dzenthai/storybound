Storybound story folder
=======================

Everything here is story data for Storybound: no datapack or resource pack needed. It is read when a world
starts and on every /reload, on the server (in singleplayer, your own game), and sent to every player.

Folders (one per kind of definition; the JSON is the same as in a datapack, see the mod's schemas):

  quests/  dialogues/  npc_bindings/  documents/  waypoints/  codex/  bosses/  events/  flags/

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
