"""Validates the example data and good/bad cases against the Storybound JSON schemas.

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
theme = validator("menu_theme.schema.json")
dialogue = validator("dialogue.schema.json")
binding = validator("npc_binding.schema.json")
codex = validator("codex.schema.json")
boss = validator("boss.schema.json")
waypoint = validator("waypoint.schema.json")
failures = 0


def expect(v, doc, ok, label):
    global failures
    errors = list(v.iter_errors(doc))
    if bool(errors) == ok:
        failures += 1
        print("FAIL", label, [e.message for e in errors][:2])
    else:
        print("ok  ", label)


for path in (root / "examples").rglob("storybound/flags/*.json"):
    expect(flag, json.loads(path.read_text(encoding="utf-8")), True, f"example {path.name}")
for path in (root / "examples").rglob("storybound/events/*.json"):
    expect(event, json.loads(path.read_text(encoding="utf-8")), True, f"example {path.name}")

for path in (root / "examples").rglob("storybound/documents/*.json"):
    expect(document, json.loads(path.read_text(encoding="utf-8")), True, f"example {path.name}")
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
expect(event, {"trigger": custom, "actions": [{"type": "take_item", "count": 2}]}, False, "event: take_item without item or tag")
expect(event, {"trigger": custom, "actions": [{"type": "set_flag"}]}, False, "event: set_flag without key")
expect(event, {"trigger": {"type": "no_such_trigger"}, "actions": []}, False, "event: unknown short trigger type")
expect(event, {"trigger": custom, "actions": [{"type": "othermod:explode", "power": 3}]}, True, "event: another mod's action type")
expect(event, {"trigger": {"type": "area_entered", "area": {"center": [0, 64, 0], "radius": 5}, "fire_on_login": True}, "actions": []}, True, "event: area_entered with fire_on_login")
expect(event, {"trigger": custom, "actions": [], "on_fail": "retry"}, False, "event: bad on_fail")

for path in (root / "examples").rglob("storybound/quests/*.json"):
    expect(quest, json.loads(path.read_text(encoding="utf-8")), True, f"example {path.name}")
for path in (root / "src/main/resources/assets").rglob("storybound/menu/theme.json"):
    expect(theme, json.loads(path.read_text(encoding="utf-8")), True, f"theme {path.name}")

kill = {"type": "entity_killed", "entity": "minecraft:wolf"}
expect(quest, {"title": "q", "stages": [{"id": "a", "objectives": [{"trigger": kill, "count": 3, "optional": True}], "next": "@complete"}]}, True, "quest: minimal stage")
expect(quest, {"stages": []}, False, "quest: missing title")
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

for path in (root / "examples").rglob("storybound/dialogues/*.json"):
    expect(dialogue, json.loads(path.read_text(encoding="utf-8")), True, f"example {path.name}")
for path in (root / "examples").rglob("storybound/npc_bindings/*.json"):
    expect(binding, json.loads(path.read_text(encoding="utf-8")), True, f"example binding {path.name}")

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
expect(binding, {"entity": {"type": "minecraft:villager", "tag": "elder"}, "dialogues": [{"dialogue": "a:b", "priority": 2}],
    "proximity": {"radius": 5, "once": True}}, True, "binding: full")
expect(binding, {"dialogues": [{"dialogue": "a:b"}]}, False, "binding: missing entity")
expect(binding, {"entity": {}}, False, "binding: empty selector")
expect(binding, {"entity": {"uuid": "not-a-uuid"}}, False, "binding: bad uuid")
expect(binding, {"entity": {"tag": "x"}, "proximity": {"radius": 0}}, False, "binding: zero radius")
expect(event, {"trigger": {"type": "dialogue_ended", "dialogue": "a:b", "node": "bye"}, "actions": [{"type": "start_dialogue", "dialogue": "a:c"}, {"type": "end_dialogue"}]}, True, "event: dialogue trigger and actions")
expect(event, {"trigger": custom, "actions": {"type": "start_dialogue"}}, False, "event: start_dialogue without dialogue")

for path in (root / "examples").rglob("storybound/codex/*.json"):
    expect(codex, json.loads(path.read_text(encoding="utf-8")), True, f"example codex {path.name}")
for path in (root / "examples").rglob("storybound/bosses/*.json"):
    expect(boss, json.loads(path.read_text(encoding="utf-8")), True, f"example boss {path.name}")

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

for path in (root / "examples").rglob("storybound/waypoints/*.json"):
    expect(waypoint, json.loads(path.read_text(encoding="utf-8")), True, f"example waypoint {path.name}")

expect(waypoint, {"name": "w", "target": {"entity": "123e4567-e89b-12d3-a456-426614174000"}, "quest": "a:b", "stage": "s", "beam": True}, True, "waypoint: follow an entity, stage binding")
expect(waypoint, {"name": "w", "target": {"pos": [1, 2]}}, False, "waypoint: two coordinates")
expect(waypoint, {"name": "w", "target": {"pos": [1, 2, 3], "entity": "123e4567-e89b-12d3-a456-426614174000"}}, False, "waypoint: pos and entity together")
expect(waypoint, {"target": {"pos": [1, 2, 3]}}, False, "waypoint: no name")
expect(waypoint, {"name": "w", "target": {"pos": [1, 2, 3]}, "icon": "a:b"}, False, "waypoint: icon is gone")
expect(waypoint, {"name": "w", "target": {"pos": [1, 2, 3]}, "color": "red"}, False, "waypoint: bad colour")
expect(quest, {"title": "q", "stages": [{"id": "a", "waypoints": ["a:den"]}]}, True, "quest: stage waypoints")
expect(event, {"trigger": custom, "actions": [{"type": "add_waypoint", "id": "a:b", "name": "n", "follow": True, "beam": True, "visibility": "party"}, {"type": "remove_waypoint", "id": "a:c", "visibility": "world"}]}, True, "event: waypoint actions")
expect(event, {"trigger": custom, "actions": {"type": "add_waypoint", "id": "a:b"}}, False, "event: add_waypoint without name")

print("failures:", failures)
sys.exit(1 if failures else 0)
