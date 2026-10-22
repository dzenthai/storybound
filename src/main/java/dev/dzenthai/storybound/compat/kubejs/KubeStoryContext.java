package dev.dzenthai.storybound.compat.kubejs;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.dzenthai.storybound.core.logic.StoryContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

/**
 * {@link StoryContext} with bean getters, so scripts can write {@code ctx.player} and {@code ctx.level}, and the
 * JSON's {@code args} as plain values: {@code ctx.args.boss}, {@code ctx.args.count} (a whole number is an int, other
 * numbers doubles, lists Java lists, objects Java maps).
 */
public final class KubeStoryContext {
    private final StoryContext context;
    private final Map<String, Object> args;

    KubeStoryContext(StoryContext context, JsonObject args) {
        this.context = context;
        this.args = map(args);
    }

    public ServerPlayer getPlayer() {
        return context.player();
    }

    /** The killed mob, the interacted NPC or the dialogue partner, if any. */
    public @Nullable Entity getEntity() {
        return context.entity();
    }

    public ServerLevel getLevel() {
        return context.level();
    }

    public MinecraftServer getServer() {
        return context.server();
    }

    /** The {@code args} of the JSON that called the script; empty without them. */
    public Map<String, Object> getArgs() {
        return args;
    }

    /** One argument, or {@code fallback} when the JSON does not give it: {@code ctx.arg('count', 1)}. */
    public Object arg(String name, Object fallback) {
        Object value = args.get(name);
        return value != null ? value : fallback;
    }

    static Map<String, Object> map(JsonObject object) {
        Map<String, Object> map = new LinkedHashMap<>();
        object.entrySet().forEach(entry -> map.put(entry.getKey(), value(entry.getValue())));
        return map;
    }

    private static @Nullable Object value(JsonElement element) {
        if (element instanceof JsonObject object) {
            return map(object);
        }
        if (element instanceof JsonArray array) {
            List<Object> list = new ArrayList<>();
            array.forEach(item -> list.add(value(item)));
            return list;
        }
        if (element instanceof JsonPrimitive primitive) {
            if (primitive.isBoolean()) {
                return primitive.getAsBoolean();
            }
            if (primitive.isNumber()) {
                double number = primitive.getAsDouble();
                return number == Math.rint(number) && Math.abs(number) <= Integer.MAX_VALUE ? (Object) (int) number : number;
            }
            return primitive.getAsString();
        }
        return null;
    }
}
