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
import java.util.function.Function;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

/**
 * What a mob's saved data must hold (November 9, 2026): an entity selector's {@code data}, a field to one value or a
 * list of them, any matching: {@code { "VillagerData/profession": ["minecraft:weaponsmith", "minecraft:armorer"],
 * "Stage": 2 }}. The path goes into a field with {@code /}. Nothing of any kind or mod is known here: the fields are
 * whatever the mob saves (a villager's data, a Born in Chaos zombie's stage), read as the game writes them.
 *
 * @param fields path → the values it may hold
 */
public record DataFilter(Map<String, List<Value>> fields) {
    /** One value or a list of them, written back as one when it is one. */
    private static final Codec<List<Value>> VALUES = Codec.either(Value.CODEC, Value.CODEC.listOf())
            .xmap(either -> either.map(List::of, Function.identity()), list -> list.size() == 1 ? Either.left(list.getFirst()) : Either.right(list));

    public static final Codec<DataFilter> CODEC = Codec.unboundedMap(Codec.STRING, VALUES)
            .xmap(map -> new DataFilter(new LinkedHashMap<>(map)), DataFilter::fields);

    /** Whether every field holds one of its values. */
    public boolean matches(CompoundTag saved) {
        return fields.entrySet().stream().allMatch(field -> holds(saved, field.getKey(), field.getValue()));
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
        return has != null && values.stream().anyMatch(value -> value.matches(has));
    }

    /** The tag at a path ("VillagerData/profession"), null when it is not there. */
    public static Tag at(CompoundTag saved, String path) {
        Tag here = saved;
        for (String part : path.split("/")) {
            if (!(here instanceof CompoundTag compound) || !compound.contains(part)) {
                return null;
            }
            here = compound.get(part);
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
