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

print("failures:", failures)
sys.exit(1 if failures else 0)
