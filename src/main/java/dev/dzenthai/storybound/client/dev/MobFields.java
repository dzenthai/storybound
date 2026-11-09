package dev.dzenthai.storybound.client.dev;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.dzenthai.storybound.Storybound;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.neoforged.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;

/**
 * What a kind of mob keeps of its own, found by reading mobs, nothing written per kind or mod (November 9, 2026, the
 * author: "the mod reads a villager's data and sees a profession, a level, an age; a Born in Chaos zombie's stages show
 * up though the mod knows nothing of them"). The fields of the mob's saved data that tell one mob of the kind from
 * another, each with the values met, from:
 * <ul>
 * <li>a test mob of the kind made on the client (never added to the world): every field, with the value a new one has;</li>
 * <li>the mobs of the kind the client sees;</li>
 * <li>in a world of one's own, the server's mobs of the kind, gathered once per kind and game, in the background (the
 * form is drawn again when they are in; before, the screen waited for them on every redraw);</li>
 * <li>what was met before, remembered on the author's disk ({@value #FILE}), so a woman of Villagers Reborn met once
 * stays offered in a new world.</li>
 * </ul>
 * Values that are ids of a registry of the game stand for the registry whole (all professions, mods' too).
 */
final class MobFields {
    /** Where what was met is remembered, under the game folder. */
    static final String FILE = "storybound_dev/mob_fields.json";
    /** At most this many mobs of a kind read on the server. */
    private static final int SAMPLES = 64;
    /** A field of more values than this met is not one to pick from (an id, a timer), unless a registry's. */
    private static final int MAX_VALUES = 16;
    /** A registry of at most this many entries is offered whole. */
    private static final int REGISTRY_VALUES = 64;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /**
     * A mob's fields that every mob has or that change every moment or say where it is: never what tells one kind of
     * mob from another. The base of every mob, not any kind's own.
     */
    private static final Set<String> NOISE = Set.of("Pos", "Motion", "Rotation", "FallDistance", "Fire", "Air", "OnGround", "Invulnerable",
            "PortalCooldown", "UUID", "Health", "HurtTime", "HurtByTimestamp", "DeathTime", "AbsorptionAmount", "Attributes", "active_effects",
            "FallFlying", "Brain", "ArmorItems", "HandItems", "ArmorDropChances", "HandDropChances", "body_armor_item", "body_armor_drop_chance",
            "LeftHanded", "CanPickUpLoot", "PersistenceRequired", "leash", "Tags", "CustomName", "CustomNameVisible", "Silent", "NoGravity",
            "Glowing", "TicksFrozen", "HasVisualFire", "Passengers", "neoforge:attachments", "NeoForgeData", "SleepingX", "SleepingY", "SleepingZ",
            "InLove", "LoveCause", "AngerTime", "AngryAt", "OwnerUUID", "Owner", "Sitting", "WanderTarget", "DespawnDelay");
    /** Left out where the form asks the age itself. */
    private static final Set<String> AGE = Set.of("Age", "ForcedAge", "IsBaby");

    /**
     * One field to pick from.
     *
     * @param path   as {@code data} writes it, {@code "VillagerData/profession"}
     * @param label  its name made readable, "Villager data › Profession"
     * @param values each value met by its SNBT text, with its tag
     * @param flag   every value a 0 or 1 byte: a yes / no
     */
    record Field(String path, String label, Map<String, Tag> values, boolean flag) {
        /**
         * Whether its values are words (a profession, a type, a coat, "male"): what a mob of the kind is. Numbers and
         * yes / no are as often what it is doing now (its experience, its food), so the form folds them under "More
         * fields"; nothing of any kind is known, only how the values are written.
         */
        boolean words() {
            return values.values().stream().allMatch(tag -> tag instanceof StringTag);
        }
    }

    /** Kind id → field path → values met (SNBT), as remembered and as met this game. */
    private static Map<String, Map<String, Set<String>>> known;
    /** Kinds whose server mobs were gathered this game (or are being). */
    private static final Set<EntityType<?>> GATHERED = new HashSet<>();
    private static final Map<EntityType<?>, Optional<Entity>> PROBES = new HashMap<>();
    private static @Nullable ClientLevel probesLevel;

    private MobFields() {}

    /**
     * The fields of the kinds (of several, those they share), the values met among all.
     *
     * @param young  the form asks the age itself: the fields of age left out
     * @param redraw run on the client when more came from the server
     */
    static List<Field> of(List<EntityType<?>> kinds, boolean young, Runnable redraw) {
        if (kinds.isEmpty()) {
            return List.of();
        }
        Map<String, Map<String, Set<String>>> memory = memory();
        boolean learned = false;
        ClientLevel level = Minecraft.getInstance().level;
        for (EntityType<?> kind : kinds) {
            List<CompoundTag> samples = new ArrayList<>();
            Entity probe = probe(kind);
            if (probe != null) {
                saved(probe).ifPresent(samples::add);
            }
            if (level != null) {
                for (Entity entity : level.entitiesForRendering()) {
                    if (entity.getType() == kind && samples.size() < SAMPLES) {
                        saved(entity).ifPresent(samples::add);
                    }
                }
            }
            for (CompoundTag sample : samples) {
                learned |= learn(kind, sample);
            }
            gather(kind, redraw);
        }
        if (learned) {
            save();
        }
        return fields(kinds, young, memory);
    }

    /** Whether every kind can be young (a villager, a wolf, a zombie; not a skeleton), tried on its test mob. */
    static boolean canBeYoung(List<EntityType<?>> kinds) {
        if (kinds.isEmpty()) {
            return false;
        }
        for (EntityType<?> kind : kinds) {
            if (!(probe(kind) instanceof Mob mob)) {
                return false;
            }
            try {
                boolean was = mob.isBaby();
                mob.setBaby(true);
                boolean can = mob.isBaby();
                mob.setBaby(was);
                if (!can) {
                    return false;
                }
            } catch (RuntimeException e) {
                return false;
            }
        }
        return true;
    }

    /** A test mob of the kind on the client, never added to the world; null when it cannot be made here (a mod's). */
    static @Nullable Entity probe(EntityType<?> kind) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return null;
        }
        if (probesLevel != level) {
            PROBES.clear();
            probesLevel = level;
        }
        return PROBES.computeIfAbsent(kind, made -> {
            try {
                return Optional.ofNullable(made.create(level));
            } catch (RuntimeException | LinkageError e) {
                Storybound.LOGGER.debug("Storybound: could not make a {} on the client: {}", BuiltInRegistries.ENTITY_TYPE.getKey(made), e.toString());
                return Optional.empty();
            }
        }).orElse(null);
    }

    /** Whether the kind's test mob could be made: when not, only mobs met tell its fields. */
    static boolean probed(EntityType<?> kind) {
        return probe(kind) != null;
    }

    /** The server's mobs of the kind, once per game, in a world of one's own; the form drawn again when in. */
    private static void gather(EntityType<?> kind, Runnable redraw) {
        var server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null || !GATHERED.add(kind)) {
            return;
        }
        server.execute(() -> {
            List<CompoundTag> found = new ArrayList<>();
            for (var level : server.getAllLevels()) {
                for (Entity entity : level.getAllEntities()) {
                    if (entity.getType() == kind && found.size() < SAMPLES) {
                        saved(entity).ifPresent(found::add);
                    }
                }
            }
            Minecraft.getInstance().execute(() -> {
                boolean learned = false;
                for (CompoundTag sample : found) {
                    learned |= learn(kind, sample);
                }
                if (learned) {
                    save();
                    redraw.run();
                }
            });
        });
    }

    /** Takes in a sample's fields and values; true when anything was new. */
    private static boolean learn(EntityType<?> kind, CompoundTag sample) {
        Map<String, Set<String>> mine = memory().computeIfAbsent(BuiltInRegistries.ENTITY_TYPE.getKey(kind).toString(), id -> new LinkedHashMap<>());
        boolean learned = false;
        for (Map.Entry<String, Tag> field : fieldsOf(sample).entrySet()) {
            Set<String> values = mine.computeIfAbsent(field.getKey(), path -> new java.util.LinkedHashSet<>());
            // A field of very many values is not one to pick from; keep a few more than shown, to know it.
            if (values.size() <= MAX_VALUES) {
                learned |= values.add(field.getValue().toString());
            }
        }
        return learned;
    }

    /** A sample's fields to pick from: a yes/no, a whole number or a short text, also one level inside a field. */
    private static Map<String, Tag> fieldsOf(CompoundTag sample) {
        Map<String, Tag> out = new LinkedHashMap<>();
        for (String key : sample.getAllKeys()) {
            if (NOISE.contains(key)) {
                continue;
            }
            Tag tag = sample.get(key);
            if (tag instanceof CompoundTag inner) {
                for (String sub : inner.getAllKeys()) {
                    if (pickable(inner.get(sub))) {
                        out.put(key + "/" + sub, inner.get(sub));
                    }
                }
            } else if (pickable(tag)) {
                out.put(key, tag);
            }
        }
        return out;
    }

    private static boolean pickable(@Nullable Tag tag) {
        return tag instanceof ByteTag || tag instanceof ShortTag || tag instanceof IntTag
                || tag instanceof StringTag text && text.getAsString().length() <= 48;
    }

    /** The fields every kind has (of several, those they share), with the values met among all. */
    private static List<Field> fields(List<EntityType<?>> kinds, boolean young, Map<String, Map<String, Set<String>>> memory) {
        Map<String, Set<String>> shared = null;
        for (EntityType<?> kind : kinds) {
            Map<String, Set<String>> mine = memory.getOrDefault(BuiltInRegistries.ENTITY_TYPE.getKey(kind).toString(), Map.of());
            if (shared == null) {
                shared = new LinkedHashMap<>();
                for (Map.Entry<String, Set<String>> entry : mine.entrySet()) {
                    shared.put(entry.getKey(), new java.util.LinkedHashSet<>(entry.getValue()));
                }
            } else {
                shared.keySet().retainAll(mine.keySet());
                for (Map.Entry<String, Set<String>> entry : shared.entrySet()) {
                    entry.getValue().addAll(mine.get(entry.getKey()));
                }
            }
        }
        List<Field> fields = new ArrayList<>();
        String namespace = BuiltInRegistries.ENTITY_TYPE.getKey(kinds.getFirst()).getNamespace();
        if (shared == null) {
            return fields;
        }
        for (Map.Entry<String, Set<String>> entry : shared.entrySet()) {
            String path = entry.getKey();
            if (young && AGE.contains(path.contains("/") ? path.substring(0, path.indexOf('/')) : path)) {
                continue;
            }
            Map<String, Tag> values = new LinkedHashMap<>();
            for (String text : entry.getValue()) {
                parse(text).ifPresent(tag -> values.put(text, tag));
            }
            if (values.isEmpty() || values.size() > MAX_VALUES) {
                continue;
            }
            boolean flag = values.values().stream().allMatch(tag -> tag instanceof ByteTag b && (b.getAsByte() == 0 || b.getAsByte() == 1));
            fields.add(new Field(path, label(path, namespace), withRegistry(values), flag));
        }
        fields.sort(Comparator.comparing(Field::label));
        return fields;
    }

    private static Optional<Tag> parse(String snbt) {
        try {
            return Optional.of(TagParser.parseTag("{v:" + snbt + "}").get("v"));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Values that are ids of one of the game's registries (professions, a wolf's coats, a mod's own registry): all of
     * that registry's, the smallest holding them all, of at most {@value #REGISTRY_VALUES}. Else the values as met.
     */
    private static Map<String, Tag> withRegistry(Map<String, Tag> met) {
        ClientLevel level = Minecraft.getInstance().level;
        List<ResourceLocation> ids = new ArrayList<>();
        for (Tag tag : met.values()) {
            if (!(tag instanceof StringTag text) || !text.getAsString().contains(":") || !(ResourceLocation.tryParse(text.getAsString()) instanceof ResourceLocation id)) {
                return met;
            }
            ids.add(id);
        }
        if (ids.isEmpty() || level == null) {
            return met;
        }
        Registry<?> best = level.registryAccess().registries().map(RegistryAccess.RegistryEntry::value)
                .filter(registry -> registry.size() <= REGISTRY_VALUES && ids.stream().allMatch(registry::containsKey))
                .min(Comparator.comparingInt(Registry::size)).orElse(null);
        if (best == null) {
            return met;
        }
        Map<String, Tag> all = new LinkedHashMap<>(met);
        best.keySet().stream().sorted(Comparator.comparing(ResourceLocation::toString)).forEach(id -> {
            Tag tag = StringTag.valueOf(id.toString());
            all.putIfAbsent(tag.toString(), tag);
        });
        return all;
    }

    /** "VillagerData/profession" as "Villager data › Profession"; a mod's prefix ("slimpatch_personality") left out. */
    static String label(String path, String namespace) {
        List<String> words = new ArrayList<>();
        for (String part : path.split("/")) {
            String name = part;
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            if (!namespace.isEmpty() && !namespace.equals("minecraft") && lower.startsWith(namespace) && name.length() > namespace.length()) {
                name = name.substring(namespace.length()).replaceFirst("^[_\\-.:]", "");
            }
            String spaced = name.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replace('_', ' ').trim().toLowerCase(java.util.Locale.ROOT);
            words.add(spaced.isEmpty() ? part : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1));
        }
        return String.join(" › ", words);
    }

    /** A value as the author reads it: yes / no for a flag, a game id by its name, else as written. */
    static String valueLabel(Tag tag, boolean flag) {
        if (flag && tag instanceof NumericTag number) {
            return DevUi.yesNo(number.getAsInt() != 0);
        }
        if (tag instanceof StringTag text && text.getAsString().contains(":") && ResourceLocation.tryParse(text.getAsString()) instanceof ResourceLocation id) {
            return DevUi.humanize(id.getPath());
        }
        return tag instanceof StringTag text ? text.getAsString() : tag.getAsString();
    }

    /** What the form writes into {@code data} for a value: a text, true / false for a yes / no, else a number. */
    static com.google.gson.JsonPrimitive toJson(Tag tag, boolean flag) {
        if (tag instanceof NumericTag number) {
            if (flag) {
                return new com.google.gson.JsonPrimitive(number.getAsInt() != 0);
            }
            double value = number.getAsDouble();
            return value == Math.rint(value) ? new com.google.gson.JsonPrimitive(number.getAsLong()) : new com.google.gson.JsonPrimitive(value);
        }
        return new com.google.gson.JsonPrimitive(tag.getAsString());
    }

    /** Whether a value written in {@code data} is this tag, as the game compares them (see {@code DataFilter}). */
    static boolean holds(JsonElement written, Tag tag) {
        if (!(written instanceof com.google.gson.JsonPrimitive primitive)) {
            return false;
        }
        if (primitive.isBoolean()) {
            return tag instanceof NumericTag number && (number.getAsDouble() != 0) == primitive.getAsBoolean();
        }
        if (primitive.isNumber()) {
            return tag instanceof NumericTag number && number.getAsDouble() == primitive.getAsDouble();
        }
        return tag instanceof StringTag text && text.getAsString().equals(primitive.getAsString());
    }

    /** A value typed for "Other value...", of the type of the values met (a text, a whole number); null when it is not one. */
    static @Nullable Tag typed(String text, Collection<Tag> met) {
        Tag sample = met.isEmpty() ? null : met.iterator().next();
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            if (sample instanceof ByteTag) {
                return ByteTag.valueOf(Byte.parseByte(trimmed));
            }
            if (sample instanceof ShortTag) {
                return ShortTag.valueOf(Short.parseShort(trimmed));
            }
            if (sample instanceof IntTag) {
                return IntTag.valueOf(Integer.parseInt(trimmed));
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return StringTag.valueOf(trimmed);
    }

    private static Optional<CompoundTag> saved(Entity entity) {
        try {
            return Optional.of(entity.saveWithoutId(new CompoundTag()));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static Map<String, Map<String, Set<String>>> memory() {
        if (known == null) {
            known = new LinkedHashMap<>();
            Path file = FMLPaths.GAMEDIR.get().resolve(FILE);
            if (Files.isRegularFile(file)) {
                try {
                    JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                    root.entrySet().forEach(kind -> {
                        Map<String, Set<String>> fields = new LinkedHashMap<>();
                        kind.getValue().getAsJsonObject().entrySet().forEach(field -> {
                            Set<String> values = new java.util.LinkedHashSet<>();
                            field.getValue().getAsJsonArray().forEach(value -> values.add(value.getAsString()));
                            fields.put(field.getKey(), values);
                        });
                        known.put(kind.getKey(), fields);
                    });
                } catch (IOException | RuntimeException e) {
                    Storybound.LOGGER.warn("Storybound: could not read {}: {}", file, e.toString());
                }
            }
        }
        return known;
    }

    private static void save() {
        JsonObject root = new JsonObject();
        memory().forEach((kind, fields) -> {
            JsonObject own = new JsonObject();
            fields.forEach((path, values) -> {
                JsonArray list = new JsonArray();
                values.forEach(list::add);
                own.add(path, list);
            });
            root.add(kind, own);
        });
        Path file = FMLPaths.GAMEDIR.get().resolve(FILE);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(root) + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            Storybound.LOGGER.warn("Storybound: could not write {}: {}", file, e.toString());
        }
    }
}
