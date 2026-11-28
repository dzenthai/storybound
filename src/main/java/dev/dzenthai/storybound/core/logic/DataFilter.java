package dev.dzenthai.storybound.core.logic;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.mojang.datafixers.util.Either;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.ZombieVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.entity.npc.VillagerDataHolder;
import org.jetbrains.annotations.Nullable;

/**
 * What a mob's saved data must hold: an entity selector's {@code data}, a field to one value or a list of them, any
 * matching: {@code { "VillagerData/profession": ["minecraft:weaponsmith", "minecraft:armorer"], "Stage": 2 }}. The path
 * goes into a field with {@code /}. Nothing of any kind or mod is known here: the fields are whatever the mob saves (a
 * villager's data, a Born in Chaos zombie's stage), read as the game writes them.
 *
 * @param fields path → the values it may hold
 */
public record DataFilter(Map<String, List<Value>> fields) {
    /** One value or a list of them, written back as one when it is one. */
    private static final Codec<List<Value>> VALUES = Codec.either(Value.CODEC, Value.CODEC.listOf())
            .xmap(either -> either.map(List::of, Function.identity()), list -> list.size() == 1 ? Either.left(list.getFirst()) : Either.right(list));

    public static final Codec<DataFilter> CODEC = Codec.unboundedMap(Codec.STRING, VALUES)
            .xmap(map -> new DataFilter(new LinkedHashMap<>(map)), DataFilter::fields);

    private static final int MAX_PATH_CACHE = 1024;
    private static final ConcurrentHashMap<String, String[]> PATH_CACHE = new ConcurrentHashMap<>();

    private static String[] splitPath(String path) {
        String[] cached = PATH_CACHE.get(path);
        if (cached != null) {
            return cached;
        }
        String[] parts = path.split("/");
        if (PATH_CACHE.size() >= MAX_PATH_CACHE) {
            PATH_CACHE.clear();
        }
        PATH_CACHE.put(path, parts);
        return parts;
    }

    /** Whether every field holds one of its values. */
    public boolean matches(CompoundTag saved) {
        for (Map.Entry<String, List<Value>> entry : fields.entrySet()) {
            if (!holds(saved, entry.getKey(), entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Exact-class fast path for vanilla Villager / ZombieVillager {@code VillagerData/*} filters without calling
     * {@code entity.saveWithoutId(...)}. Returns null if any field is not a supported VillagerData path so the caller
     * falls back to full NBT serialization.
     */
    @Nullable Boolean matchVanillaVillager(Entity entity) {
        Class<?> clazz = entity.getClass();
        if (clazz != Villager.class
                && clazz != ZombieVillager.class) {
            return null;
        }
        for (String path : fields.keySet()) {
            if (!path.equals("VillagerData/profession") && !path.equals("VillagerData/type") && !path.equals("VillagerData/level")) {
                return null;
            }
        }
        VillagerData data = ((VillagerDataHolder) entity).getVillagerData();
        for (Map.Entry<String, List<Value>> entry : fields.entrySet()) {
            String path = entry.getKey();
            List<Value> values = entry.getValue();
            boolean matched = false;
            if (path.equals("VillagerData/profession")) {
                String actual = String.valueOf(BuiltInRegistries.VILLAGER_PROFESSION.getKey(data.getProfession()));
                for (int i = 0; i < values.size(); i++) {
                    if (values.get(i) instanceof Value.Text(String text) && text.equals(actual)) {
                        matched = true;
                        break;
                    }
                }
            } else if (path.equals("VillagerData/type")) {
                String actual = String.valueOf(BuiltInRegistries.VILLAGER_TYPE.getKey(data.getType()));
                for (int i = 0; i < values.size(); i++) {
                    if (values.get(i) instanceof Value.Text(String text) && text.equals(actual)) {
                        matched = true;
                        break;
                    }
                }
            } else {
                int level = data.getLevel();
                for (int i = 0; i < values.size(); i++) {
                    Value v = values.get(i);
                    if ((v instanceof Value.Number(double num) && num == level)
                            || (v instanceof Value.Flag(boolean on) && (level != 0) == on)) {
                        matched = true;
                        break;
                    }
                }
            }
            if (!matched) {
                return Boolean.FALSE;
            }
        }
        return Boolean.TRUE;
    }

    /** The fields that do not, for the trace: "Stage is 1, wanted 2". */
    public void mismatch(CompoundTag saved, List<String> parts) {
        fields.forEach((path, values) -> {
            if (!holds(saved, path, values)) {
                Tag has = at(saved, path);
                parts.add(path + " is " + (has == null ? "not there" : has.getAsString()) + ", wanted "
                        + String.join(" or ", values.stream().map(Value::toString).toList()));
            }
        });
    }

    /** How many fields are asked: each is a filter, so "smiths, men" is closer than "smiths". */
    public int count() {
        return fields.size();
    }

    private static boolean holds(CompoundTag saved, String path, List<Value> values) {
        Tag has = at(saved, path);
        if (has == null) {
            return false;
        }
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i).matches(has)) {
                return true;
            }
        }
        return false;
    }

    /** The tag at a path ("VillagerData/profession"), null when it is not there. */
    public static Tag at(CompoundTag saved, String path) {
        Tag here = saved;
        String[] segments = splitPath(path);
        for (int i = 0; i < segments.length; i++) {
            if (!(here instanceof CompoundTag compound)) {
                return null;
            }
            here = compound.get(segments[i]);
            if (here == null) {
                return null;
            }
        }
        return here;
    }

    /** A value a field may hold: a text, a number (any numeric tag of that value) or true / false (a 1 or a 0). */
    public sealed interface Value {
        Codec<Value> CODEC = new Codec<>() {
            @Override
            public <T> DataResult<Pair<Value, T>> decode(DynamicOps<T> ops, T input) {
                // Read as JSON, which keeps a text, a number and a yes/no apart (a NBT byte reads as a number).
                JsonElement json = ops.convertTo(JsonOps.INSTANCE, input);
                if (!(json instanceof JsonPrimitive primitive)) {
                    return DataResult.error(() -> "A data value is a text, a number or true / false: " + json);
                }
                Value value = primitive.isBoolean() ? new Flag(primitive.getAsBoolean())
                        : primitive.isNumber() ? new Number(primitive.getAsDouble()) : new Text(primitive.getAsString());
                return DataResult.success(Pair.of(value, input));
            }

            @Override
            public <T> DataResult<T> encode(Value value, DynamicOps<T> ops, T prefix) {
                return DataResult.success(switch (value) {
                    case Text text -> ops.createString(text.text());
                    case Flag flag -> ops.createBoolean(flag.on());
                    case Number number -> number.whole() ? ops.createLong((long) number.value()) : ops.createDouble(number.value());
                });
            }
        };

        boolean matches(Tag tag);

        record Text(String text) implements Value {
            @Override
            public boolean matches(Tag tag) {
                return tag instanceof StringTag string && string.getAsString().equals(text);
            }

            @Override
            public String toString() {
                return text;
            }
        }

        record Number(double value) implements Value {
            @Override
            public boolean matches(Tag tag) {
                return tag instanceof NumericTag numeric && numeric.getAsDouble() == value;
            }

            boolean whole() {
                return value == Math.rint(value) && Math.abs(value) < 1e15;
            }

            @Override
            public String toString() {
                return whole() ? Long.toString((long) value) : Double.toString(value);
            }
        }

        record Flag(boolean on) implements Value {
            @Override
            public boolean matches(Tag tag) {
                return tag instanceof NumericTag numeric && (numeric.getAsDouble() != 0) == on;
            }

            @Override
            public String toString() {
                return Boolean.toString(on);
            }
        }
    }
}
