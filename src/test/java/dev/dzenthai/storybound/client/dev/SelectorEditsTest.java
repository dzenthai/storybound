package dev.dzenthai.storybound.client.dev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.Test;

/**
 * The editor's changes to a mob's variants and data (November 9, 2026), each one click of the form. Expected, on the
 * author's example of one NPC that is a Villagers Reborn man smith and a woman farmer: a second variant turns the one
 * selector into a list of two, taking one out of two writes the one left alone again, taking the last leaves nothing;
 * a value picked joins the field's list, picked again it leaves, the last one leaving takes the field and "data" out;
 * switching the rule keeps the kind and changes nothing else by surprise, and switching back gives back what was there.
 */
final class SelectorEditsTest {
    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    @Test
    void variantsAreOneOrAList() {
        JsonObject smith = json("{ \"type\": \"villagersreborn:villager\" }");
        assertEquals(1, SelectorEdits.variants(smith).size(), "one selector is one variant");
        assertEquals(0, SelectorEdits.variants(null).size(), "nothing written, none");
        JsonElement two = SelectorEdits.withVariant(smith, json("{ \"type\": \"villagersreborn:villager\", \"data\": { \"Gender\": \"female\" } }"));
        assertTrue(two.isJsonArray(), "a second variant makes a list");
        assertEquals(2, SelectorEdits.variants(two).size(), "of two, the same kind twice");
        assertEquals(smith, two.getAsJsonArray().get(0), "the first as it was");
        assertEquals(3, SelectorEdits.variants(SelectorEdits.withVariant(two, json("{ \"type\": \"minecraft:wolf\" }"))).size(), "a third joins the list");
        assertEquals(json("{ \"type\": \"minecraft:wolf\" }"), SelectorEdits.withVariant(null, json("{ \"type\": \"minecraft:wolf\" }")),
                "the first variant is written alone");
    }

    @Test
    void takingOneOutWritesWhatIsLeftAsTheGameReadsIt() {
        JsonElement two = JsonParser.parseString("[ { \"type\": \"a:smith\" }, { \"type\": \"a:farmer\" } ]");
        assertEquals(json("{ \"type\": \"a:farmer\" }"), SelectorEdits.withoutVariant(two, 0), "one of two left: alone, not a list of one");
        JsonElement three = JsonParser.parseString("[ { \"type\": \"a:smith\" }, { \"type\": \"a:farmer\" }, { \"type\": \"a:guard\" } ]");
        assertEquals(2, SelectorEdits.variants(SelectorEdits.withoutVariant(three, 1)).size(), "two of three left: a list");
        assertNull(SelectorEdits.withoutVariant(json("{ \"type\": \"a:smith\" }"), 0), "the last one taken: nothing");
    }

    @Test
    void byHandIsEveryVariantNamingParticularOnes() {
        assertTrue(SelectorEdits.byHand(json("{ \"type\": \"minecraft:iron_golem\", \"tag\": \"scenario_brann\" }")), "a tag: by hand");
        assertFalse(SelectorEdits.byHand(json("{ \"type\": \"minecraft:villager\" }")), "a kind only: dynamic");
        assertFalse(SelectorEdits.byHand(null), "nothing: not by hand");
        assertFalse(SelectorEdits.byHand(JsonParser.parseString("[ { \"tag\": \"a\" }, { \"type\": \"minecraft:villager\" } ]")), "one dynamic of two: dynamic");
    }

    @Test
    void aValuePickedJoinsAndPickedAgainLeaves() {
        JsonObject smith = json("{ \"type\": \"minecraft:villager\" }");
        String path = "VillagerData/profession";
        JsonPrimitive weaponsmith = new JsonPrimitive("minecraft:weaponsmith");
        JsonPrimitive armorer = new JsonPrimitive("minecraft:armorer");
        SelectorEdits.toggleData(smith, path, weaponsmith, weaponsmith::equals);
        assertEquals(json("{ \"type\": \"minecraft:villager\", \"data\": { \"VillagerData/profession\": \"minecraft:weaponsmith\" } }"), smith, "one value alone");
        SelectorEdits.toggleData(smith, path, armorer, armorer::equals);
        assertEquals(2, SelectorEdits.dataValues(smith, path).size(), "a second makes a list: weaponsmith or armorer");
        SelectorEdits.toggleData(smith, path, weaponsmith, weaponsmith::equals);
        assertEquals(json("{ \"type\": \"minecraft:villager\", \"data\": { \"VillagerData/profession\": \"minecraft:armorer\" } }"), smith,
                "picked again it leaves, the one left alone");
        SelectorEdits.toggleData(smith, path, armorer, armorer::equals);
        assertEquals(json("{ \"type\": \"minecraft:villager\" }"), smith, "the last leaving takes the field and data out");
        SelectorEdits.toggleData(smith, "Gender", new JsonPrimitive("male"), value -> false);
        SelectorEdits.clearData(smith, "Gender");
        assertEquals(json("{ \"type\": \"minecraft:villager\" }"), smith, "Any: the field out");
    }

    @Test
    void theRuleSwitchesTheWholeEntity() {
        JsonObject dynamic = json("{ \"type\": \"minecraft:villager\", \"data\": { \"VillagerData/profession\": \"minecraft:farmer\" } }");
        JsonElement hand = SelectorEdits.switchedRule(dynamic, null, "elder");
        assertEquals(json("{ \"type\": \"minecraft:villager\", \"tag\": \"elder\" }"), hand, "by hand: the kind and the node's tag, no filter narrowing it unseen");
        assertEquals(json("{ \"type\": \"minecraft:villager\" }"), SelectorEdits.switchedRule(hand, null, "elder"), "dynamic: the kind, no tag");
        assertEquals(dynamic, SelectorEdits.switchedRule(hand, dynamic, "elder"), "switching back gives back what was there");
        assertEquals(json("{ \"tag\": \"elder\" }"), SelectorEdits.switchedRule(null, null, "elder"), "nothing yet: the tag alone");
    }

    @Test
    void valuesAreWrittenAndComparedAsTheGameDoes() {
        assertEquals(new JsonPrimitive(true), MobFields.toJson(ByteTag.valueOf((byte) 1), true), "a yes / no field writes true");
        assertEquals(new JsonPrimitive(2L), MobFields.toJson(IntTag.valueOf(2), false), "a number, whole");
        assertEquals(new JsonPrimitive("female"), MobFields.toJson(StringTag.valueOf("female"), false), "a text");
        assertTrue(MobFields.holds(new JsonPrimitive(true), ByteTag.valueOf((byte) 1)), "true is a 1");
        assertTrue(MobFields.holds(new JsonPrimitive(2), ByteTag.valueOf((byte) 2)), "2 is any number of that value");
        assertFalse(MobFields.holds(new JsonPrimitive("2"), IntTag.valueOf(2)), "a text is not a number");
        assertEquals(IntTag.valueOf(7), MobFields.typed("7", java.util.List.of(IntTag.valueOf(1))), "typed as the values met: a whole number");
        assertNull(MobFields.typed("seven", java.util.List.of(IntTag.valueOf(1))), "not a number where numbers are met");
    }
}
