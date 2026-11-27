package dev.dzenthai.storybound.module.world;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.dzenthai.storybound.core.logic.Condition;
import dev.dzenthai.storybound.core.logic.LogicCodecs;
import dev.dzenthai.storybound.core.logic.StructureRef;
import dev.dzenthai.storybound.core.logic.WorldPlaces;
import dev.dzenthai.storybound.core.text.StoryText;
import dev.dzenthai.storybound.module.document.DocumentDefinition;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryCodecs;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

/**
 * {@code data/<ns>/storybound/world_rules/<path>.json}. Server-only world-level rules edited in Dev Mode's
 * World Setup tab: item locks, mob spawn locks, place locks (dimension/biome/structure), and general loot modifiers.
 */
public sealed interface WorldRuleDefinition permits WorldRuleDefinition.ItemLock, WorldRuleDefinition.MobLock,
        WorldRuleDefinition.PlaceLock, WorldRuleDefinition.LootModifierRule {

    Codec<WorldRuleDefinition> CODEC = Codec.lazyInitialized(() -> Kind.CODEC.dispatch("type", WorldRuleDefinition::kind, Kind::codec));

    Codec<List<ResourceLocation>> ID_LIST = Codec.either(ResourceLocation.CODEC, ResourceLocation.CODEC.listOf())
            .xmap(either -> either.map(List::of, Function.identity()),
                    list -> list.size() == 1 ? Either.left(list.getFirst()) : Either.right(list));

    Codec<List<StructureRef>> STRUCTURES = Codec.either(StructureRef.CODEC, StructureRef.CODEC.listOf())
            .xmap(either -> either.map(List::of, Function.identity()),
                    list -> list.size() == 1 ? Either.left(list.getFirst()) : Either.right(list));

    Codec<List<EntryRef>> ENTRY_REFS = Codec.either(EntryRef.CODEC, EntryRef.CODEC.listOf())
            .xmap(either -> either.map(List::of, Function.identity()),
                    list -> list.size() == 1 ? Either.left(list.getFirst()) : Either.right(list));

    Kind kind();

    Optional<String> title();

    boolean enabled();

    enum Kind implements StringRepresentable {
        ITEM("item", () -> ItemLock.CODEC),
        MOB("mob", () -> MobLock.CODEC),
        PLACE("place", () -> PlaceLock.CODEC),
        LOOT("loot", () -> LootModifierRule.CODEC);

        public static final Codec<Kind> CODEC = StringRepresentable.fromEnum(Kind::values);

        private final String name;
        private final Supplier<MapCodec<? extends WorldRuleDefinition>> codec;

        Kind(String name, Supplier<MapCodec<? extends WorldRuleDefinition>> codec) {
            this.name = name;
            this.codec = codec;
        }

        public MapCodec<? extends WorldRuleDefinition> codec() {
            return codec.get();
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    /**
     * A registry entry id ({@code minecraft:diamond_sword}) or tag ({@code #minecraft:swords}). Unlike
     * {@link RegistryCodecs#homogeneousList}, a list of {@code EntryRef} allows mixing tags and individual ids
     * in one rule and gracefully tolerates removed mod ids until the validator reports them.
     */
    record EntryRef(ResourceLocation id, boolean tag) {
        public static final Codec<EntryRef> CODEC = Codec.STRING.comapFlatMap(EntryRef::parse, EntryRef::toString);

        public static DataResult<EntryRef> parse(String raw) {
            boolean isTag = raw.startsWith("#");
            return ResourceLocation.read(isTag ? raw.substring(1) : raw).map(id -> new EntryRef(id, isTag));
        }

        public static EntryRef ofId(ResourceLocation id) {
            return new EntryRef(id, false);
        }

        public static EntryRef ofTag(ResourceLocation id) {
            return new EntryRef(id, true);
        }

        /** Resolves every matching item currently registered (empty when unknown). */
        public void resolveItems(Set<Item> out) {
            if (tag) {
                BuiltInRegistries.ITEM.getTag(TagKey.create(Registries.ITEM, id))
                        .ifPresent(holders -> holders.forEach(holder -> out.add(holder.value())));
            } else {
                BuiltInRegistries.ITEM.getOptional(id).ifPresent(out::add);
            }
        }

        /** Resolves every matching entity type currently registered (empty when unknown). */
        public void resolveEntities(Set<EntityType<?>> out) {
            if (tag) {
                BuiltInRegistries.ENTITY_TYPE.getTag(TagKey.create(Registries.ENTITY_TYPE, id))
                        .ifPresent(holders -> holders.forEach(holder -> out.add(holder.value())));
            } else {
                BuiltInRegistries.ENTITY_TYPE.getOptional(id).ifPresent(out::add);
            }
        }

        public boolean matchesItem(ItemStack stack) {
            if (stack.isEmpty()) {
                return false;
            }
            if (tag) {
                return stack.is(TagKey.create(Registries.ITEM, id));
            }
            return BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(id);
        }

        public boolean matchesEntity(EntityType<?> entityType) {
            if (tag) {
                return entityType.is(TagKey.create(Registries.ENTITY_TYPE, id));
            }
            return BuiltInRegistries.ENTITY_TYPE.getKey(entityType).equals(id);
        }

        public <T> boolean existsIn(Registry<T> registry, ResourceKey<Registry<T>> key) {
            return tag ? registry.getTag(TagKey.create(key, id)).isPresent() : registry.containsKey(id);
        }

        @Override
        public String toString() {
            return (tag ? "#" : "") + id;
        }
    }

    static Set<Item> resolveItems(List<EntryRef> refs) {
        Set<Item> items = new LinkedHashSet<>();
        for (EntryRef ref : refs) {
            ref.resolveItems(items);
        }
        return items;
    }

    static Set<EntityType<?>> resolveEntities(List<EntryRef> refs) {
        Set<EntityType<?>> entities = new LinkedHashSet<>();
        for (EntryRef ref : refs) {
            ref.resolveEntities(entities);
        }
        return entities;
    }

    /**
     * Locks one or more items (or item tags) until {@code unlock} conditions pass.
     */
    record ItemLock(Optional<String> title, boolean enabled, List<EntryRef> items, Optional<Condition> unlock,
                    boolean blockUse, boolean blockAttack, boolean blockEquip, boolean blockCraft,
                    boolean blockPickup, boolean hideInJei, Optional<StoryText> message) implements WorldRuleDefinition {

        public static final MapCodec<ItemLock> CODEC = RecordCodecBuilder.<ItemLock>mapCodec(instance -> instance.group(
                Codec.STRING.optionalFieldOf("title").forGetter(ItemLock::title),
                Codec.BOOL.optionalFieldOf("enabled", true).forGetter(ItemLock::enabled),
                ENTRY_REFS.fieldOf("items").forGetter(ItemLock::items),
                LogicCodecs.CONDITION.optionalFieldOf("unlock").forGetter(ItemLock::unlock),
                Codec.BOOL.optionalFieldOf("block_use", true).forGetter(ItemLock::blockUse),
                Codec.BOOL.optionalFieldOf("block_attack", true).forGetter(ItemLock::blockAttack),
                Codec.BOOL.optionalFieldOf("block_equip", true).forGetter(ItemLock::blockEquip),
                Codec.BOOL.optionalFieldOf("block_craft", false).forGetter(ItemLock::blockCraft),
                Codec.BOOL.optionalFieldOf("block_pickup", false).forGetter(ItemLock::blockPickup),
                Codec.BOOL.optionalFieldOf("hide_in_jei", false).forGetter(ItemLock::hideInJei),
                StoryText.CODEC.optionalFieldOf("message").forGetter(ItemLock::message)
        ).apply(instance, ItemLock::new)).validate(lock -> lock.items().isEmpty()
                ? DataResult.error(() -> "Item lock needs at least one item or #tag in 'items'")
                : DataResult.success(lock));

        @Override
        public Kind kind() {
            return Kind.ITEM;
        }
    }

    /**
     * Blocks natural/chunk/structure/patrol/reinforcement spawns (and optionally spawners) of matching mobs unless a
     * nearby player satisfies {@code unlock}. When {@code despawnExisting} is true, already-spawned matching mobs are
     * also removed (skipping named, tamed, leashed, summoned, and Storybound NPC/boss entities). Optionally spawns
     * {@code replaceWith} instead.
     */
    record MobLock(Optional<String> title, boolean enabled, List<EntryRef> entities,
                   Optional<ResourceKey<Level>> dimension, Optional<HolderSet<Biome>> biome,
                   Optional<List<StructureRef>> structure, Optional<Condition> unlock,
                   boolean despawnExisting, boolean blockSpawners,
                   Optional<Holder<EntityType<?>>> replaceWith) implements WorldRuleDefinition {

        public static final MapCodec<MobLock> CODEC = RecordCodecBuilder.<MobLock>mapCodec(instance -> instance.group(
                Codec.STRING.optionalFieldOf("title").forGetter(MobLock::title),
                Codec.BOOL.optionalFieldOf("enabled", true).forGetter(MobLock::enabled),
                ENTRY_REFS.fieldOf("entities").forGetter(MobLock::entities),
                Level.RESOURCE_KEY_CODEC.optionalFieldOf("dimension").forGetter(MobLock::dimension),
                RegistryCodecs.homogeneousList(Registries.BIOME).optionalFieldOf("biome").forGetter(MobLock::biome),
                STRUCTURES.optionalFieldOf("structure").forGetter(MobLock::structure),
                LogicCodecs.CONDITION.optionalFieldOf("unlock").forGetter(MobLock::unlock),
                Codec.BOOL.optionalFieldOf("despawn_existing", true).forGetter(MobLock::despawnExisting),
                Codec.BOOL.optionalFieldOf("block_spawners", false).forGetter(MobLock::blockSpawners),
                BuiltInRegistries.ENTITY_TYPE.holderByNameCodec().optionalFieldOf("replace_with").forGetter(MobLock::replaceWith)
        ).apply(instance, MobLock::new)).validate(lock -> lock.entities().isEmpty()
                ? DataResult.error(() -> "Mob lock needs at least one entity or #tag in 'entities'")
                : DataResult.success(lock));

        @Override
        public Kind kind() {
            return Kind.MOB;
        }

        public WorldPlaces.Place place() {
            return new WorldPlaces.Place(dimension, biome, structure);
        }
    }

    /**
     * Locks entry into a dimension, biome, or structure until {@code unlock} passes.
     */
    record PlaceLock(Optional<String> title, boolean enabled, Optional<ResourceKey<Level>> dimension,
                     Optional<HolderSet<Biome>> biome, Optional<List<StructureRef>> structure,
                     Optional<Condition> unlock, PlaceMode mode, List<HazardEffect> effects,
                     float damage, Optional<StoryText> message) implements WorldRuleDefinition {

        public static final MapCodec<PlaceLock> CODEC = RecordCodecBuilder.<PlaceLock>mapCodec(instance -> instance.group(
                Codec.STRING.optionalFieldOf("title").forGetter(PlaceLock::title),
                Codec.BOOL.optionalFieldOf("enabled", true).forGetter(PlaceLock::enabled),
                Level.RESOURCE_KEY_CODEC.optionalFieldOf("dimension").forGetter(PlaceLock::dimension),
                RegistryCodecs.homogeneousList(Registries.BIOME).optionalFieldOf("biome").forGetter(PlaceLock::biome),
                STRUCTURES.optionalFieldOf("structure").forGetter(PlaceLock::structure),
                LogicCodecs.CONDITION.optionalFieldOf("unlock").forGetter(PlaceLock::unlock),
                PlaceMode.CODEC.optionalFieldOf("mode", PlaceMode.BARRIER).forGetter(PlaceLock::mode),
                HazardEffect.CODEC.listOf().optionalFieldOf("effects", List.of()).forGetter(PlaceLock::effects),
                Codec.floatRange(0.0f, 20.0f).optionalFieldOf("damage", 0.0f).forGetter(PlaceLock::damage),
                StoryText.CODEC.optionalFieldOf("message").forGetter(PlaceLock::message)
        ).apply(instance, PlaceLock::new)).validate(lock -> lock.place().isEmpty()
                ? DataResult.error(() -> "Place lock needs at least one of 'dimension', 'biome', or 'structure'")
                : DataResult.success(lock));

        @Override
        public Kind kind() {
            return Kind.PLACE;
        }

        public WorldPlaces.Place place() {
            return new WorldPlaces.Place(dimension, biome, structure);
        }

        /** Whether this lock restricts the entire dimension (no finer biome or structure filter). */
        public boolean isDimensionWide() {
            return dimension.isPresent() && biome.isEmpty() && structure.isEmpty();
        }
    }

    enum PlaceMode implements StringRepresentable {
        BARRIER("barrier"),
        HAZARD("hazard");

        public static final Codec<PlaceMode> CODEC = StringRepresentable.fromEnum(PlaceMode::values);

        private final String name;

        PlaceMode(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    record HazardEffect(Holder<MobEffect> effect, int amplifier, int durationSeconds) {
        public static final Codec<HazardEffect> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                BuiltInRegistries.MOB_EFFECT.holderByNameCodec().fieldOf("effect").forGetter(HazardEffect::effect),
                Codec.intRange(0, 10).optionalFieldOf("amplifier", 0).forGetter(HazardEffect::amplifier),
                Codec.intRange(1, 60).optionalFieldOf("duration", 4).forGetter(HazardEffect::durationSeconds)
        ).apply(instance, HazardEffect::new));

        public MobEffectInstance createInstance() {
            return new MobEffectInstance(effect, durationSeconds * 20, amplifier, true, true);
        }
    }

    /**
     * General Global Loot Modifier rule for any item: add, replace, or remove across chests, loot tables, or mob drops.
     */
    record LootModifierRule(Optional<String> title, boolean enabled, LootOperation operation,
                            List<ResourceLocation> tables, List<EntryRef> entities,
                            Optional<ResourceKey<Level>> dimension, Optional<HolderSet<Biome>> biome,
                            Optional<List<StructureRef>> structure, Optional<Condition> conditions,
                            float chance, DocumentDefinition.LootRule.Once once,
                            List<EntryRef> target, Optional<Holder<Item>> item,
                            int minCount, int maxCount, DataComponentPatch components) implements WorldRuleDefinition {

        public static final MapCodec<LootModifierRule> CODEC = RecordCodecBuilder.<LootModifierRule>mapCodec(instance -> instance.group(
                Codec.STRING.optionalFieldOf("title").forGetter(LootModifierRule::title),
                Codec.BOOL.optionalFieldOf("enabled", true).forGetter(LootModifierRule::enabled),
                LootOperation.CODEC.optionalFieldOf("operation", LootOperation.ADD).forGetter(LootModifierRule::operation),
                ID_LIST.optionalFieldOf("tables", List.of()).forGetter(LootModifierRule::tables),
                ENTRY_REFS.optionalFieldOf("entities", List.of()).forGetter(LootModifierRule::entities),
                Level.RESOURCE_KEY_CODEC.optionalFieldOf("dimension").forGetter(LootModifierRule::dimension),
                RegistryCodecs.homogeneousList(Registries.BIOME).optionalFieldOf("biome").forGetter(LootModifierRule::biome),
                STRUCTURES.optionalFieldOf("structure").forGetter(LootModifierRule::structure),
                LogicCodecs.CONDITION.optionalFieldOf("conditions").forGetter(LootModifierRule::conditions),
                Codec.floatRange(0.0f, 1.0f).optionalFieldOf("chance", 1.0f).forGetter(LootModifierRule::chance),
                DocumentDefinition.LootRule.Once.CODEC.optionalFieldOf("once", DocumentDefinition.LootRule.Once.NONE).forGetter(LootModifierRule::once),
                ENTRY_REFS.optionalFieldOf("target", List.of()).forGetter(LootModifierRule::target),
                ItemStack.ITEM_NON_AIR_CODEC.optionalFieldOf("item").forGetter(LootModifierRule::item),
                Codec.intRange(1, 64).optionalFieldOf("min_count", 1).forGetter(LootModifierRule::minCount),
                Codec.intRange(1, 64).optionalFieldOf("max_count", 1).forGetter(LootModifierRule::maxCount),
                DataComponentPatch.CODEC.optionalFieldOf("components", DataComponentPatch.EMPTY).forGetter(LootModifierRule::components)
        ).apply(instance, LootModifierRule::new)).validate(LootModifierRule::validate);

        private static DataResult<LootModifierRule> validate(LootModifierRule rule) {
            if (rule.operation() == LootOperation.ADD && rule.item().isEmpty()) {
                return DataResult.error(() -> "Loot modifier with operation 'add' needs an 'item'");
            }
            if (rule.operation() == LootOperation.REPLACE) {
                if (rule.target().isEmpty()) {
                    return DataResult.error(() -> "Loot modifier with operation 'replace' needs 'target' items");
                }
                if (rule.item().isEmpty()) {
                    return DataResult.error(() -> "Loot modifier with operation 'replace' needs a replacement 'item'");
                }
            }
            if (rule.operation() == LootOperation.REMOVE && rule.target().isEmpty()) {
                return DataResult.error(() -> "Loot modifier with operation 'remove' needs 'target' items");
            }
            if (rule.maxCount() < rule.minCount()) {
                return DataResult.error(() -> "Loot modifier 'max_count' (" + rule.maxCount() + ") must be >= 'min_count' (" + rule.minCount() + ")");
            }
            return DataResult.success(rule);
        }

        @Override
        public Kind kind() {
            return Kind.LOOT;
        }

        public WorldPlaces.Place place() {
            return new WorldPlaces.Place(dimension, biome, structure);
        }

        public boolean matchesTarget(ItemStack stack) {
            for (EntryRef ref : target) {
                if (ref.matchesItem(stack)) {
                    return true;
                }
            }
            return false;
        }

        public boolean matchesEntity(EntityType<?> entityType) {
            for (EntryRef ref : entities) {
                if (ref.matchesEntity(entityType)) {
                    return true;
                }
            }
            return false;
        }

        public int rollCount(RandomSource random) {
            if (maxCount <= minCount) {
                return minCount;
            }
            return minCount + random.nextInt(maxCount - minCount + 1);
        }

        public ItemStack createStack(int count) {
            Holder<Item> holder = item.orElseThrow();
            int clamped = Math.max(1, Math.min(count, holder.value().getDefaultMaxStackSize()));
            return new ItemStack(holder, clamped, components);
        }
    }

    enum LootOperation implements StringRepresentable {
        ADD("add"),
        REPLACE("replace"),
        REMOVE("remove");

        public static final Codec<LootOperation> CODEC = StringRepresentable.fromEnum(LootOperation::values);

        private final String name;

        LootOperation(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }
}

