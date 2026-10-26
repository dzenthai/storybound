"""Validates the starter story folder (with the demo story) and good/bad cases against the Storybound JSON schemas.

Usage: python check_schemas.py <repository root>   (needs: pip install jsonschema)
"""
import json
import sys
from pathlib import Path

from jsonschema import Draft7Validator
from referencing import Registry, Resource

root = Path(sys.argv[1])
schemas = {p.name: json.loads(p.read_text(encoding="utf-8")) for p in (root / "schemas").glob("*.schema.json")}
registry = Registry().with_resources(
    [(f"storybound/{name}", Resource.from_contents(s)) for name, s in schemas.items()]
)


def validator(name):
    Draft7Validator.check_schema(schemas[name])
    return Draft7Validator(schemas[name], registry=registry)


flag = validator("flag.schema.json")
event = validator("story_event.schema.json")
document = validator("document.schema.json")
style = validator("document_style.schema.json")
quest = validator("quest.schema.json")
project = validator("project.schema.json")
theme = validator("menu_theme.schema.json")
dialogue = validator("dialogue.schema.json")
binding = validator("npc_binding.schema.json")
codex = validator("codex.schema.json")
boss = validator("boss.schema.json")
waypoint = validator("waypoint.schema.json")
chapter = validator("chapter.schema.json")
dialogue_layout = validator("dialogue_layout.schema.json")
script = validator("script.schema.json")
migration = validator("migration.schema.json")
marker_style = validator("waypoint_style.schema.json")
failures = 0
# The story folder a new install gets: blank documents and the demo story, in the story folder's layout.
STARTER = root / "src/main/resources/storybound_starter"


def expect(v, doc, ok, label):
    global failures
    errors = list(v.iter_errors(doc))
    if bool(errors) == ok:
        failures += 1
        print("FAIL", label, [e.message for e in errors][:2])
    else:
        print("ok  ", label)


for path in (STARTER / "flags").rglob("*.json"):
    expect(flag, json.loads(path.read_text(encoding="utf-8")), True, f"starter {path.relative_to(STARTER).as_posix()}")
for path in (STARTER / "events").rglob("*.json"):
    expect(event, json.loads(path.read_text(encoding="utf-8")), True, f"starter {path.relative_to(STARTER).as_posix()}")

for path in (STARTER / "documents").rglob("*.json"):
    expect(document, json.loads(path.read_text(encoding="utf-8")), True, f"starter {path.relative_to(STARTER).as_posix()}")
for path in (root / "src/main/resources/assets").rglob("storybound/styles/*.json"):
    expect(style, json.loads(path.read_text(encoding="utf-8")), True, f"style {path.name}")

expect(document, {"kind": "diary", "title": "a"}, False, "document: unknown kind")
expect(document, {"kind": "book"}, False, "document: missing title")
expect(document, {"kind": "note", "title": {"key": "a", "args": ["{player}"]}, "on_read": [{"type": "give_document", "document": "a:b"}]}, True, "document: full title and on_read")
expect(style, {"texture": "a:b.png", "width": 100}, False, "style: missing height")
expect(event, {"trigger": {"type": "document_read", "first_time": True}, "actions": {"type": "journal_entry", "id": "a:b", "title": "t", "text": {"literal": "x"}}}, True, "event: document_read + journal_entry")
expect(event, {"trigger": {"type": "custom", "id": "a:b"}, "actions": {"type": "journal_entry", "id": "a:b", "text": "x"}}, False, "event: journal_entry without title")
expect(flag, {"type": "int", "default": "five"}, False, "flag: wrong default type")
expect(flag, {"default": 1}, False, "flag: missing type")
expect(flag, {"type": "bool", "scope": "guild"}, False, "flag: bad scope")

custom = {"type": "custom", "id": "a:b"}
expect(event, {"trigger": custom, "actions": []}, True, "event: empty action list")
expect(event, {"trigger": custom, "actions": {"type": "storybound:set_flag", "key": "a:b"}, "once": "party"}, True, "event: full ids, single action")
expect(event, {"trigger": custom, "actions": [], "conditions": {"type": "var", "key": "a:b", "op": "=>", "value": 1}}, False, "event: bad comparison")
expect(event, {"trigger": custom, "conditions": {"type": "kubejs", "id": "a:near", "args": {"radius": 8}}, "actions": {"type": "kubejs", "id": "a:spawn", "args": {"boss": "a:wraith", "at": [1, 64, 2]}}}, True, "event: script args")
expect(event, {"trigger": custom, "actions": {"type": "kubejs", "id": "a:spawn", "args": "a:wraith"}}, False, "event: script args not an object")
expect(event, {"trigger": custom, "actions": [
    {"type": "spawn_entity", "entity": "minecraft:zombie", "offset": [0, 0, 3], "tag": ["a_kairen", "a_boss"], "name": {"literal": "Kairen"}, "nbt": {"NoAI": True}},
    {"type": "teleport", "pos": [0, 64, 0], "dimension": "minecraft:the_nether", "yaw": 90},
    {"type": "effect", "effect": "minecraft:blindness", "duration": 5},
    {"type": "title", "title": "a.title", "subtitle": "a.subtitle", "stay": 60},
    {"type": "sound_at", "sound": "minecraft:entity.wither.spawn", "at": "entity"}]}, True, "event: scene actions")
expect(event, {"trigger": custom, "actions": {"type": "title", "stay": 60}}, False, "event: title without text")
expect(event, {"trigger": custom, "actions": {"type": "effect", "effect": "minecraft:speed", "duration": 0}}, False, "event: effect for 0 seconds")
expect(event, {"trigger": custom, "actions": {"type": "teleport", "at": "npc"}}, False, "event: teleport to an unknown anchor")
expect(event, {"trigger": custom, "actions": [{"type": "wait", "seconds": 5}, {"type": "schedule", "seconds": 1.5, "actions": {"type": "title", "title": "a.t"}}]}, True, "event: wait and schedule")
expect(event, {"trigger": custom, "actions": {"type": "schedule", "seconds": 5}}, False, "event: schedule without actions")
expect(event, {"trigger": {"type": "advancement_earned", "advancement": "calamity:chapter_one"}, "conditions": [{"type": "level", "min": 30}, {"type": "stat", "stat_type": "minecraft:killed", "stat": "minecraft:zombie", "min": 50}, {"type": "advancement", "advancement": "minecraft:story/mine_stone", "done": False}], "actions": []}, True, "event: progress trigger and conditions")
expect(event, {"trigger": {"type": "dimension_changed", "to": "twilightforest:twilight_forest"}, "actions": []}, True, "event: dimension_changed")
expect(event, {"trigger": {"type": "stat_reached", "stat": "minecraft:jump"}, "actions": []}, False, "event: stat_reached without value")
expect(event, {"trigger": {"type": "level_reached", "level": 30}, "conditions": {"type": "level"}, "actions": []}, False, "event: level condition without bounds")
expect(event, {"trigger": {"type": "cutscene_finished", "cutscene": "a:prologue", "reason": "finish"}, "conditions": {"type": "not", "condition": {"type": "in_cutscene"}}, "actions": {"type": "play_cutscene", "cutscene": "a:kairen", "offset": [0, 0, 5], "rotation": [90, 0, 0]}}, True, "event: cutscenes")
expect(event, {"trigger": {"type": "cutscene_finished", "reason": "ended"}, "actions": []}, False, "event: unknown cutscene ending")
expect(event, {"trigger": custom, "actions": [{"type": "take_item", "count": 2}]}, False, "event: take_item without item or tag")
expect(event, {"trigger": custom, "actions": [{"type": "set_flag"}]}, False, "event: set_flag without key")
expect(event, {"trigger": {"type": "no_such_trigger"}, "actions": []}, False, "event: unknown short trigger type")
expect(event, {"trigger": custom, "actions": [{"type": "othermod:explode", "power": 3}]}, True, "event: another mod's action type")
expect(event, {"trigger": {"type": "area_entered", "area": {"center": [0, 64, 0], "radius": 5}, "fire_on_login": True}, "actions": []}, True, "event: area_entered with fire_on_login")
expect(event, {"trigger": {"type": "structure_entered", "structure": "#minecraft:village", "fire_on_login": True}, "actions": []}, True, "event: structure_entered")
expect(event, {"trigger": {"type": "biome_entered", "biome": "#minecraft:is_forest"}, "actions": []}, True, "event: biome_entered")
expect(event, {"trigger": {"type": "poi_entered", "poi": "minecraft:meeting", "radius": 16}, "actions": []}, True, "event: poi_entered at a village bell")
expect(event, {"trigger": {"type": "poi_entered", "poi": "minecraft:meeting", "radius": 500}, "actions": []}, False, "event: poi_entered radius too large")
expect(chapter, {"title": "Act I", "order": 1, "icon": "minecraft:map", "nodes": [{"type": "storybound:quest", "id": "mypack:wolf_hunt", "x": 10, "y": -20}]}, True, "chapter: nodes on the canvas")
expect(chapter, {"title": "Act I", "nodes": [{"type": "quest", "id": "mypack:wolf_hunt", "x": 1.5, "y": 0}]}, False, "chapter: positions are whole numbers")
expect(chapter, {"order": 1}, False, "chapter: no title")
expect(dialogue_layout, {"nodes": {"greet": {"x": 40, "y": 40}, "ask": {"x": 260, "y": -36}}}, True, "dialogue layout: node positions")
expect(dialogue_layout, {"nodes": {"greet": {"x": 40}}}, False, "dialogue layout: a position needs x and y")
expect(dialogue_layout, {"greet": {"x": 40, "y": 40}}, False, "dialogue layout: positions go under nodes")
expect(script, {"title": "mypack.script.burn", "file": "storybound/mypack/burn.js", "hook": "mypack:burn_village", "leads_to": [{"type": "quest", "id": "mypack:wolf_hunt"}]}, True, "script block: file, handler and what it leads to")
expect(script, {}, True, "script block: everything is optional")
expect(script, {"leads_to": [{"type": "flag", "id": "mypack:x"}]}, False, "script block: leads only to story nodes")
expect(event, {"trigger": {"type": "structure_entered"}, "actions": []}, False, "event: structure_entered without structure")
expect(event, {"trigger": {"type": "custom", "id": "a:b"}, "conditions": {"type": "location", "structure": "minecraft:stronghold", "dimension": "minecraft:overworld"}, "actions": []}, True, "event: location in a structure")
expect(event, {"trigger": {"type": "custom", "id": "a:b"}, "conditions": {"type": "location", "structure": "Not An Id"}, "actions": []}, False, "event: location with a bad structure id")
expect(event, {"trigger": custom, "actions": [], "on_fail": "retry"}, False, "event: bad on_fail")

for path in (STARTER / "quests").rglob("*.json"):
    expect(quest, json.loads(path.read_text(encoding="utf-8")), True, f"starter {path.relative_to(STARTER).as_posix()}")
for path in (root / "src/main/resources/assets").rglob("storybound/menu/theme.json"):
    expect(theme, json.loads(path.read_text(encoding="utf-8")), True, f"theme {path.name}")

kill = {"type": "entity_killed", "entity": "minecraft:wolf"}
expect(quest, {"title": "q", "stages": [{"id": "a", "objectives": [{"trigger": kill, "count": 3, "optional": True}], "next": "@complete"}]}, True, "quest: minimal stage")
expect(quest, {"stages": []}, False, "quest: missing title")
expect(quest, {"title": "q", "stages": [{"id": "a", "objectives": [{"items": {"item": "minecraft:leather", "consume": True}, "count": 10}, {"items": {"tag": "c:gems"}, "trigger": {"type": "dialogue_ended", "dialogue": "a:tanner"}}]}]}, True, "quest: item objectives")
expect(quest, {"title": "q", "stages": [{"id": "a", "objectives": [{"count": 10}]}]}, False, "quest: objective with neither trigger nor items")
expect(quest, {"title": "q", "stages": [{"id": "a", "objectives": [{"items": {"item": "a:b", "tag": "c:d"}}]}]}, False, "quest: items with item and tag")
expect(quest, {"title": "q", "repeatable": {"cooldown": 1200, "times": 5}, "difficulty": "hard", "time_limit": 600}, True, "quest: repeatable, difficulty, time limit")
expect(quest, {"title": "q", "repeatable": True, "difficulty": 3}, True, "quest: repeatable true, stars")
expect(quest, {"title": "q", "difficulty": 6}, False, "quest: at most 5 stars")
expect(quest, {"title": "q", "map_visibility": "secret"}, True, "quest: secret on the chapter map")
expect(quest, {"title": "q", "map_visibility": "hidden"}, False, "quest: unknown visibility")
expect(quest, {"title": "q", "repeatable": False}, False, "quest: repeatable false")
expect(quest, {"title": "q", "rewards": {"items": [{"item": "minecraft:emerald", "count": 5}], "choice": [{"item": "minecraft:iron_sword"}, {"id": "minecraft:bow"}], "loot": "minecraft:chests/village/village_weaponsmith", "xp": 50, "actions": {"type": "set_flag", "key": "a:b"}}}, True, "quest: rewards object")
expect(quest, {"title": "q", "rewards": [{"type": "give_item", "item": "minecraft:emerald"}]}, True, "quest: rewards as actions")
expect(quest, {"title": "q", "rewards": {"items": [{"count": 3}]}}, False, "quest: reward item without an item")
expect(quest, {"title": "q", "stages": [{"id": "a", "objectives": [{"trigger": kill, "hints": [{"text": "a.night", "conditions": {"type": "time", "period": "day"}}, {"text": "a.stuck", "after": 300}]}]}]}, True, "quest: objective hints")
expect(quest, {"title": "q", "stages": [{"id": "a", "objectives": [{"trigger": kill, "hints": [{"after": 300}]}]}]}, False, "quest: hint without text")
expect(project, {"source_language": "ru_ru", "languages": ["ru_ru", "en_us"], "difficulty": {"style": "stars"}}, True, "project: stars")
expect(project, {"difficulty": {"style": "stars", "max": 10}}, False, "project: no max, always 5 stars")
expect(project, {"difficulty": {"levels": [{"id": "a"}, {"id": "b"}, {"id": "c"}, {"id": "d"}, {"id": "e"}, {"id": "f"}]}}, False, "project: at most 5 levels")
expect(project, {"difficulty": {"levels": [{"id": "trivial", "color": "#AAAAAA", "name": "Trivial"}, {"id": "doom", "color": "8B1A1A"}]}}, True, "project: own levels")
expect(project, {"difficulty": {"style": "hearts"}}, False, "project: unknown style")
expect(project, {"chapter_map": True}, True, "project: chapter map on")
expect(project, json.loads((STARTER / "project.json").read_text(encoding="utf-8")), True, "starter project.json")
for path in (STARTER / "chapters").rglob("*.json"):
    expect(chapter, json.loads(path.read_text(encoding="utf-8")), True, f"starter {path.relative_to(STARTER).as_posix()}")
expect(event, {"trigger": {"type": "logged_in", "first_time": True}, "actions": []}, True, "event: logged_in, first visit only")
expect(event, {"trigger": {"type": "logged_in", "player": "Steve"}, "actions": []}, False, "event: logged_in has no player field")
expect(quest, {"title": "q", "scope": "guild"}, False, "quest: bad scope")
expect(quest, {"title": "q", "stages": [{"id": "@x"}]}, False, "quest: stage id starting with @")
expect(quest, {"title": "q", "stages": [{"id": "a", "objectives": [{"text": "t"}]}]}, False, "quest: objective without trigger")
expect(quest, {"title": "q", "stages": [{"id": "a", "objectives": [{"trigger": kill, "count": 0}]}]}, False, "quest: zero count")
expect(quest, {"title": "q", "fail_when": {"type": "quest", "quest": "a:b", "state": "failed"}, "rewards": {"type": "notify", "title": "t"}}, True, "quest: fail_when and notify reward")
expect(event, {"trigger": {"type": "quest_state_changed", "quest": "a:b", "state": "completed"}, "actions": {"type": "set_quest_stage", "quest": "a:c", "stage": "s"}}, True, "event: quest trigger and stage action")
expect(event, {"trigger": {"type": "quest_stage_changed"}, "actions": [{"type": "track_quest"}, {"type": "set_quest_state", "quest": "a:b", "state": "unknown"}]}, True, "event: track nothing, reset")
expect(event, {"trigger": custom, "actions": [], "conditions": {"type": "quest", "quest": "a:b"}}, False, "event: quest condition without state or stage")
expect(event, {"trigger": custom, "actions": {"type": "set_quest_state", "quest": "a:b", "state": "done"}}, False, "event: bad quest state")
expect(theme, {"ink": "#12345"}, False, "theme: bad colour")
expect(theme, {"hud_background": "#80000000", "panel": "mypack:gui/panel"}, True, "theme: alpha colour and own sprite")

for path in (STARTER / "dialogues").rglob("*.json"):
    expect(dialogue, json.loads(path.read_text(encoding="utf-8")), True, f"starter {path.relative_to(STARTER).as_posix()}")
for path in (STARTER / "npc_bindings").rglob("*.json"):
    expect(binding, json.loads(path.read_text(encoding="utf-8")), True, f"starter {path.relative_to(STARTER).as_posix()}")

line = {"type": "line", "speaker": "npc", "text": "t"}
expect(dialogue, {"start": "a", "nodes": {"a": line}}, True, "dialogue: one line")
expect(dialogue, {"nodes": {"a": line}}, False, "dialogue: missing start")
expect(dialogue, {"start": "a", "nodes": {"a": {"type": "line", "text": "t"}}}, False, "dialogue: line without speaker")
expect(dialogue, {"start": "a", "nodes": {"a": {"type": "choice", "options": []}}}, False, "dialogue: choice without options")
expect(dialogue, {"start": "a", "nodes": {"a": {"type": "jump"}}}, False, "dialogue: jump without node or dialogue")
expect(dialogue, {"start": "a", "nodes": {"a": {"type": "monologue"}}}, False, "dialogue: unknown node type")
expect(dialogue, {"start": "a", "participants": {"player": {"type": "virtual"}}, "nodes": {"a": line}}, False, "dialogue: participant called player")
expect(dialogue, {"start": "a", "presentation": "hud", "nodes": {"a": {"type": "choice", "timeout": {"seconds": 5, "next": "@end"},
    "options": [{"text": "o", "locked": "show", "locked_text": "h", "once": True, "think": 1.5, "actions": {"type": "take_item", "item": "a:b"}}]}}}, True, "dialogue: full choice")
expect(dialogue, {"start": "a", "nodes": {"a": {"type": "choice", "options": [{"text": "o", "locked": "grey"}]}}}, False, "dialogue: bad locked")
expect(dialogue, {"start": "a", "nodes": {"a": {"type": "choice", "options": [{"text": "o", "think": -1}]}}}, False, "dialogue: negative think")
expect(dialogue, {"start": "a", "camera": "off", "nodes": {"a": dict(line, shot="close_up", transition="ease")}}, True, "dialogue: camera fields")
expect(dialogue, {"start": "a", "camera": "cinematic", "nodes": {"a": line}}, False, "dialogue: bad camera")
expect(dialogue, {"start": "a", "nodes": {"a": dict(line, shot="over_shoulder")}}, False, "dialogue: bad shot")
expect(dialogue, {"start": "a", "nodes": {"a": dict(line, transition="fade")}}, False, "dialogue: bad transition")
expect(binding, {"entity": {"type": "minecraft:villager", "tag": "elder"}, "dialogues": [{"dialogue": "a:b", "priority": 2}],
    "proximity": {"radius": 5, "once": True}}, True, "binding: full")
expect(binding, {"dialogues": [{"dialogue": "a:b"}]}, False, "binding: missing entity")
expect(binding, {"entity": {}}, False, "binding: empty selector")
expect(binding, {"entity": {"uuid": "not-a-uuid"}}, False, "binding: bad uuid")
expect(binding, {"entity": {"tag": "x"}, "proximity": {"radius": 0}}, False, "binding: zero radius")
expect(event, {"trigger": {"type": "dialogue_ended", "dialogue": "a:b", "node": "bye"}, "actions": [{"type": "start_dialogue", "dialogue": "a:c"}, {"type": "end_dialogue"}]}, True, "event: dialogue trigger and actions")
expect(event, {"trigger": custom, "actions": {"type": "start_dialogue"}}, False, "event: start_dialogue without dialogue")

for path in (STARTER / "codex").rglob("*.json"):
    expect(codex, json.loads(path.read_text(encoding="utf-8")), True, f"starter {path.relative_to(STARTER).as_posix()}")
for path in (STARTER / "bosses").rglob("*.json"):
    expect(boss, json.loads(path.read_text(encoding="utf-8")), True, f"starter {path.relative_to(STARTER).as_posix()}")

expect(codex, {"title": "t", "entity": "#minecraft:raiders", "sections": [{"id": "a", "unlock": {"on": ["seen", "killed"], "count": 5, "conditions": {"type": "flag", "key": "a:b"}}}]}, True, "codex: tag entity, count, conditions")
expect(codex, {"title": "t", "sections": []}, False, "codex: no sections")
expect(codex, {"title": "t", "sections": [{"id": "a", "unlock": {"on": ["heard"]}}]}, False, "codex: unknown source")
expect(codex, {"title": "t", "sections": [{"id": "a", "unlock": {"on": ["read:mypack:book"]}}]}, True, "codex: read source")
expect(codex, {"title": "t", "sections": [{"id": "a"}]}, False, "codex: section without unlock")
expect(boss, {"entity": {"name": "Old White"}, "title": "t", "lore": {}, "rewards": {"items": ["minecraft:bone", {"id": "minecraft:emerald", "count": 3}]}}, True, "boss: name selector, rewards")
expect(boss, {"entity": {}, "title": "t"}, False, "boss: empty selector")
expect(boss, {"entity": {"tag": "x"}, "title": "t", "participation": 2}, False, "boss: participation above 1")
expect(boss, {"entity": {"tag": "x"}, "title": "t", "phases": []}, False, "boss: unknown field")
expect(event, {"trigger": {"type": "entity_seen", "entity": "minecraft:wolf"}, "actions": {"type": "unlock_codex", "entry": "a:b", "target": "party"}}, True, "event: entity_seen and unlock_codex")
expect(event, {"trigger": {"type": "entity_killed", "shared": True}, "actions": {"type": "unlock_boss_section", "boss": "a:b", "section": "tactics"}}, True, "event: shared kill and boss section")
expect(event, {"trigger": custom, "actions": {"type": "unlock_boss_section", "boss": "a:b", "section": "phases"}}, False, "event: unknown boss section")
expect(event, {"trigger": custom, "actions": [], "conditions": [{"type": "codex_unlocked", "entry": "a:b"}, {"type": "boss_killed", "boss": "a:c", "count": 2}]}, True, "event: codex and boss conditions")
expect(event, {"trigger": {"type": "entity_seen", "shared": True}, "actions": []}, False, "event: shared only on entity_killed")

for path in (STARTER / "waypoints").rglob("*.json"):
    expect(waypoint, json.loads(path.read_text(encoding="utf-8")), True, f"starter {path.relative_to(STARTER).as_posix()}")

expect(waypoint, {"name": "w", "target": {"entity": "123e4567-e89b-12d3-a456-426614174000"}, "quest": "a:b", "stage": "s"}, True, "waypoint: follow an entity, stage binding")
expect(waypoint, {"name": "w", "target": {"pos": [1, 2, 3]}, "beam": True}, False, "waypoint: beams are gone")
expect(waypoint, {"name": "w", "target": {"pos": [1, 2]}}, False, "waypoint: two coordinates")
expect(waypoint, {"name": "w", "target": {"pos": [1, 2, 3], "entity": "123e4567-e89b-12d3-a456-426614174000"}}, False, "waypoint: pos and entity together")
expect(waypoint, {"target": {"pos": [1, 2, 3]}}, False, "waypoint: no name")
expect(waypoint, {"name": "w", "target": {"pos": [1, 2, 3]}, "icon": "a:b"}, False, "waypoint: icon is gone")
expect(waypoint, {"name": "w", "target": {"pos": [1, 2, 3]}, "color": "red"}, False, "waypoint: bad colour")
expect(waypoint, {"name": "w", "target": {"pos": [1, 2, 3]}, "style": "storybound:npc"}, True, "waypoint: style")
expect(waypoint, {"name": "w", "target": {"structure": "#minecraft:village", "search_radius": 64}}, True, "waypoint: nearest structure of a tag")
expect(waypoint, {"name": "w", "target": {"structure": "minecraft:stronghold", "dimension": "minecraft:overworld", "origin": [0, 64, 0]}}, True, "waypoint: structure searched from a fixed origin")
expect(waypoint, {"name": "w", "target": {"structure": "minecraft:stronghold", "search_radius": 1000}}, False, "waypoint: search radius too large")
expect(waypoint, {"name": "w", "target": {"structure": "minecraft:stronghold", "pos": [1, 2, 3]}}, False, "waypoint: structure and pos together")
expect(waypoint, {"name": "w", "target": {"structure": "Bad Name"}}, False, "waypoint: bad structure id")
expect(waypoint, {"name": "w", "target": {"nearest": "minecraft:zombie"}}, True, "waypoint: nearest entity type")
expect(waypoint, {"name": "w", "target": {"nearest": ["minecraft:zombie", "#minecraft:skeletons"], "range": 32}}, True, "waypoint: nearest of a list")
expect(waypoint, {"name": "w", "target": {"nearest": {"type": "minecraft:wolf", "tag": "alpha"}, "range": 128}, "quest": "a:hunt"}, True, "waypoint: nearest by selector")
expect(waypoint, {"name": "w", "target": {"nearest": {}}}, False, "waypoint: empty nearest selector")
expect(waypoint, {"name": "w", "target": {"nearest": "minecraft:zombie", "range": 1000}}, False, "waypoint: nearest range too large")
expect(waypoint, {"name": "w", "target": {"nearest": "minecraft:zombie", "pos": [1, 2, 3]}}, False, "waypoint: nearest and pos together")
for path in (root / "src/main/resources/assets").rglob("storybound/waypoint_styles/*.json"):
    expect(marker_style, json.loads(path.read_text(encoding="utf-8")), True, f"marker style {path.name}")
expect(marker_style, {"name": "hover"}, False, "marker style: bad name mode")
expect(marker_style, {"edge": "everywhere"}, False, "marker style: bad edge mode")
expect(marker_style, {"size": 2}, False, "marker style: too small")
expect(marker_style, {"tracked_color": "gold"}, False, "marker style: bad colour")
expect(quest, {"title": "q", "stages": [{"id": "a", "waypoints": ["a:den"]}]}, True, "quest: stage waypoints")
expect(event, {"trigger": custom, "actions": [{"type": "add_waypoint", "id": "a:b", "name": "n", "follow": True, "visibility": "party", "style": "storybound:npc"}, {"type": "remove_waypoint", "id": "a:c", "visibility": "world"}]}, True, "event: waypoint actions")
expect(event, {"trigger": custom, "actions": {"type": "add_waypoint", "id": "a:b"}}, False, "event: add_waypoint without name")
expect(event, {"trigger": custom, "actions": {"type": "add_waypoint", "id": "a:b", "name": "n", "structure": "#minecraft:village", "search_radius": 32}}, True, "event: add_waypoint at a structure")
expect(event, {"trigger": custom, "actions": {"type": "add_waypoint", "id": "a:b", "name": "n", "structure": "minecraft:stronghold", "search_radius": 0}}, False, "event: add_waypoint search radius zero")

expect(migration, {"quests": {"mypack:hunt": "mypack:wolf_hunt"}, "stages": {"mypack:wolf_hunt": {"start": "tracks"}}, "flags": {"mypack:a": "mypack:b"}}, True, "migration: quests, stages, flags")
expect(migration, {"quests": {"mypack:hunt": "Wolf Hunt"}}, False, "migration: a new id must be an id")
expect(migration, {"spells": {"a:b": "a:c"}}, False, "migration: unknown kind")

# The guide's examples: a ```json block starting with "// config/storybound/<kind>/..." must be valid.
import re  # noqa: E402

guide_kinds = {"quests": quest, "dialogues": dialogue, "documents": document, "codex": codex, "bosses": boss,
               "waypoints": waypoint, "npc_bindings": binding, "events": event, "migrations": migration, "flags": flag}
for page in sorted((root / "guide").glob("*.md")):
    for block in re.findall(r"```json\n(.*?)```", page.read_text(encoding="utf-8"), re.S):
        kind = re.match(r"// config/storybound/(\w+)/", block)
        if kind and kind.group(1) in guide_kinds:
            body = "\n".join(line for line in block.splitlines() if not line.strip().startswith("//"))
            expect(guide_kinds[kind.group(1)], json.loads(body), True, f"guide: {page.name} {kind.group(1)}")

# Every description has its translation in each descriptions.<language>.json (the editor's tooltips), and nothing stale.
descriptions = set()


def collect(node):
    if isinstance(node, dict):
        if isinstance(node.get("description"), str):
            descriptions.add(node["description"])
        for value in node.values():
            collect(value)
    elif isinstance(node, list):
        for value in node:
            collect(value)


for schema in schemas.values():
    collect(schema)
for path in sorted((root / "schemas").glob("descriptions.*.json")):
    translated = json.loads(path.read_text(encoding="utf-8"))
    missing = sorted(descriptions - translated.keys())
    stale = sorted(translated.keys() - descriptions)
    if missing or stale:
        failures += 1
        print("FAIL", path.name, "missing:", missing[:3], "stale:", stale[:3])
    else:
        print("ok  ", path.name, len(translated), "descriptions")

print("failures:", failures)
sys.exit(1 if failures else 0)
