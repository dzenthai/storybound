package dev.dzenthai.storybound.client.dev;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

/**
 * An NPC's relationship scale: a number flag {@code <ns>:rep/<npc>} per player, starting at 0. Once made, the lists of
 * actions and conditions offer "+10 / -10" and "at least 20 / at most -20" for it. In the NPC binding's form, a yes/no
 * field: yes makes it, no deletes it unless the story uses it (October 29, 2026; before, a button under the form made
 * it and a second click only wrote a hint).
 */
final class RelationshipScales {
    private RelationshipScales() {}

    static ResourceLocation of(ResourceLocation npc) {
        return ResourceLocation.fromNamespaceAndPath(npc.getNamespace(), FormView.RELATIONSHIP_PREFIX + npc.getPath());
    }

    static boolean exists(ResourceLocation npc) {
        return ClientDevStore.getJson(ClientDevStore.TYPE_FLAG, of(npc)) != null;
    }

    static void create(DevModeScreen screen, ResourceLocation npc) {
        ResourceLocation scale = of(npc);
        JsonObject flag = new JsonObject();
        flag.addProperty("type", "int");
        flag.addProperty("default", 0);
        flag.addProperty("scope", "player");
        flag.addProperty("sync", true);
        flag.addProperty("description", DevUi.str("relationship.description", npc.getPath()));
        String json = DevUi.GSON.toJson(flag);
        ClientDevStore.putLocal(ClientDevStore.TYPE_FLAG, scale, json);
        DevRequests.save(ClientDevStore.TYPE_FLAG, scale, json, Optional.empty());
        screen.status(DevUi.text("relationship.created", scale.toString()), DevUi.OK);
    }

    /** Deletes the scale, unless the story uses it: then it says where, and the scale stays. */
    static void remove(DevModeScreen screen, ResourceLocation npc) {
        ResourceLocation scale = of(npc);
        List<String> users = usersOf(scale);
        if (!users.isEmpty()) {
            String shown = String.join(", ", users.subList(0, Math.min(3, users.size())))
                    + (users.size() > 3 ? " " + DevUi.str("relationship.more", users.size() - 3) : "");
            screen.status(DevUi.text("relationship.used", shown), DevUi.BAD);
            return;
        }
        ClientDevStore.removeLocal(ClientDevStore.TYPE_FLAG, scale);
        DevRequests.delete(ClientDevStore.TYPE_FLAG, scale);
        screen.status(DevUi.text("relationship.removed", scale.toString()), DevUi.OK);
    }

    /** The definitions that name the flag anywhere in them (an action changing it, a condition checking it...), by id. */
    static List<String> usersOf(ResourceLocation flag) {
        String id = flag.toString();
        List<String> users = new ArrayList<>();
        ClientDevStore.all().forEach((type, definitions) -> definitions.forEach((definition, json) -> {
            if (!(type.equals(ClientDevStore.TYPE_FLAG) && definition.equals(flag)) && json.contains(id) && names(JsonParser.parseString(json), id)) {
                users.add(definition.toString());
            }
        }));
        return users;
    }

    private static boolean names(JsonElement element, String id) {
        if (element.isJsonPrimitive()) {
            return element.getAsString().equals(id);
        }
        if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                if (names(item, id)) {
                    return true;
                }
            }
        } else if (element.isJsonObject()) {
            for (var entry : element.getAsJsonObject().entrySet()) {
                if (names(entry.getValue(), id)) {
                    return true;
                }
            }
        }
        return false;
    }
}
