package dev.dzenthai.storybound.core.logic.trigger;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.dzenthai.storybound.core.event.PartyEvent;
import dev.dzenthai.storybound.core.event.StoryValueChangedEvent;
import dev.dzenthai.storybound.core.logic.Area;
import dev.dzenthai.storybound.core.logic.ItemMatcher;
import dev.dzenthai.storybound.core.logic.References;
import dev.dzenthai.storybound.core.logic.StoryContext;
import dev.dzenthai.storybound.core.logic.StructureRef;
import dev.dzenthai.storybound.core.logic.Trigger;
import dev.dzenthai.storybound.core.logic.TriggerType;
import dev.dzenthai.storybound.core.state.PartyData;
import dev.dzenthai.storybound.core.state.StoryScope;
import dev.dzenthai.storybound.core.state.StoryState;
import dev.dzenthai.storybound.core.state.StoryValue;
import dev.dzenthai.storybound.core.state.StoryValues;
import java.util.Optional;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryCodecs;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.biome.Biome;

/** Triggers backed by core systems and vanilla events. Module triggers live with their modules. */
public final class CoreTriggers {
    private static final MapCodec<Optional<HolderSet<EntityType<?>>>> ENTITY_FILTER =
            RegistryCodecs.homogeneousList(Registries.ENTITY_TYPE).optionalFieldOf("entity");

    private CoreTriggers() {}

    private static boolean entityMatches(Optional<HolderSet<EntityType<?>>> filter, Entity entity) {
        return filter.map(set -> set.contains(entity.getType().builtInRegistryHolder())).orElse(true);
    }

    /** {@code { "type": "flag_changed", "key": "mypack:met_elder", "value": true }}; without {@code value} any change matches. */
    public record FlagChanged(ResourceLocation key, Optional<StoryValue> value) implements Trigger<StoryValueChangedEvent> {
        public static final MapCodec<FlagChanged> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                ResourceLocation.CODEC.fieldOf("key").forGetter(FlagChanged::key),
                StoryValues.LOOSE_CODEC.optionalFieldOf("value").forGetter(FlagChanged::value)
        ).apply(instance, FlagChanged::new));

        @Override
        public TriggerType<?, StoryValueChangedEvent> type() {
            return BuiltinTriggers.FLAG_CHANGED.get();
        }

        @Override
        public boolean matches(StoryContext context, StoryValueChangedEvent event) {
            return event.getKey().equals(key) && value.map(expected -> event.getNewValue().map(expected::equals).orElse(false)).orElse(true);
        }

        /** With a {@code value}, the flag having it; without one ("any change") only the moment counts. */
        @Override
        public StateKind stateKind() {
            return value.isPresent() ? StateKind.CURRENT : StateKind.NONE;
        }

        /** The change fires from any scope, so the value counts from any of them. */
        @Override
        public boolean holdsNow(StoryContext context) {
            for (StoryScope scope : StoryScope.values()) {
                if (StoryState.getStored(context.player(), scope, key).equals(value)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void collectReferences(References references) {
            references.flag(key);
        }
    }

    /**
     * The payload of {@code entity_killed}: fired for the killer ({@code killingBlow}) and then for every
     * party member of theirs near the victim, whom only {@code shared} triggers count.
     */
    public record Kill(LivingEntity victim, boolean killingBlow) {}

    /**
     * The player dealt the killing blow; with {@code shared}, also a party member of the killer within 48 blocks.
     * {@code entity} is an id, a #tag or a list; omitted means any.
     */
    public record EntityKilled(Optional<HolderSet<EntityType<?>>> entity, boolean shared) implements Trigger<Kill> {
        public static final MapCodec<EntityKilled> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                ENTITY_FILTER.forGetter(EntityKilled::entity),
                Codec.BOOL.optionalFieldOf("shared", false).forGetter(EntityKilled::shared)
        ).apply(instance, EntityKilled::new));

        @Override
        public TriggerType<?, Kill> type() {
            return BuiltinTriggers.ENTITY_KILLED.get();
        }

        @Override
        public boolean matches(StoryContext context, Kill kill) {
            return (kill.killingBlow() || shared) && entityMatches(entity, kill.victim());
        }
    }

    /**
     * The player saw the entity: within range, in the field of view and in line of sight for long enough
     * (see {@link SeenTracker}). Fires once per entity until it leaves the range.
     */
    public record EntitySeen(Optional<HolderSet<EntityType<?>>> entity) implements Trigger<Entity> {
        public static final MapCodec<EntitySeen> CODEC = ENTITY_FILTER.xmap(EntitySeen::new, EntitySeen::entity);

        @Override
        public TriggerType<?, Entity> type() {
            return BuiltinTriggers.ENTITY_SEEN.get();
        }

        @Override
        public boolean matches(StoryContext context, Entity seen) {
            return entityMatches(entity, seen);
        }
    }

    /** A place the player walks into: checked for every player every 10 ticks, fired on crossing in. */
    public interface Entered extends Trigger<Unit> {
        /** Also fires when the player logs in already inside. */
        boolean fireOnLogin();

        /** Being inside: what the crossing announces. */
        @Override
        default StateKind stateKind() {
            return StateKind.CURRENT;
        }

        @Override
        default boolean holdsNow(StoryContext context) {
            return matches(context, Unit.INSTANCE);
        }
    }

    /**
     * Fires when a player crosses into the area; checked every 10 ticks. A player who logs in
     * already inside fires it only with {@code fire_on_login}; a data reload never fires it.
     */
    public record AreaEntered(Area area, boolean fireOnLogin) implements Entered {
        public static final MapCodec<AreaEntered> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Area.CODEC.fieldOf("area").forGetter(AreaEntered::area),
                Codec.BOOL.optionalFieldOf("fire_on_login", false).forGetter(AreaEntered::fireOnLogin)
        ).apply(instance, AreaEntered::new));

        @Override
        public TriggerType<?, Unit> type() {
            return BuiltinTriggers.AREA_ENTERED.get();
        }

        @Override
        public boolean matches(StoryContext context, Unit event) {
            return area.contains(context.player());
        }
    }

    /**
     * Fires when a player comes within a structure: {@code { "type": "structure_entered", "structure": "#minecraft:village" }},
     * an id or a #tag, inside the box around all its pieces. Checked with the areas, under the same login and reload rules.
     */
    public record StructureEntered(StructureRef structure, boolean fireOnLogin) implements Entered {
        public static final MapCodec<StructureEntered> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                StructureRef.CODEC.fieldOf("structure").forGetter(StructureEntered::structure),
                Codec.BOOL.optionalFieldOf("fire_on_login", false).forGetter(StructureEntered::fireOnLogin)
        ).apply(instance, StructureEntered::new));

        @Override
        public TriggerType<?, Unit> type() {
            return BuiltinTriggers.STRUCTURE_ENTERED.get();
        }

        @Override
        public boolean matches(StoryContext context, Unit event) {
            return structure.contains(context.player().serverLevel(), context.player().blockPosition());
        }
    }

    public record BiomeEntered(HolderSet<Biome> biome, boolean fireOnLogin) implements Entered {
        public static final MapCodec<BiomeEntered> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                RegistryCodecs.homogeneousList(Registries.BIOME).fieldOf("biome").forGetter(BiomeEntered::biome),
                Codec.BOOL.optionalFieldOf("fire_on_login", false).forGetter(BiomeEntered::fireOnLogin)
        ).apply(instance, BiomeEntered::new));

        @Override
        public TriggerType<?, Unit> type() {
            return BuiltinTriggers.BIOME_ENTERED.get();
        }

        @Override
        public boolean matches(StoryContext context, Unit event) {
            return biome.contains(context.player().level().getBiome(context.player().blockPosition()));
        }
    }

    public record PoiEntered(HolderSet<PoiType> poi, int radius, boolean fireOnLogin) implements Entered {
        public static final MapCodec<PoiEntered> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                RegistryCodecs.homogeneousList(Registries.POINT_OF_INTEREST_TYPE).fieldOf("poi").forGetter(PoiEntered::poi),
                Codec.intRange(1, 128).optionalFieldOf("radius", 12).forGetter(PoiEntered::radius),
                Codec.BOOL.optionalFieldOf("fire_on_login", false).forGetter(PoiEntered::fireOnLogin)
        ).apply(instance, PoiEntered::new));

        @Override
        public TriggerType<?, Unit> type() {
            return BuiltinTriggers.POI_ENTERED.get();
        }

        @Override
        public boolean matches(StoryContext context, Unit event) {
            return context.player().serverLevel().getPoiManager()
                    .getInRange(poi::contains, context.player().blockPosition(), radius, PoiManager.Occupancy.ANY)
                    .findAny()
                    .isPresent();
        }
    }

    /** Picked up from the ground or crafted. */
    public record ItemObtained(ItemMatcher matcher) implements Trigger<ItemStack> {
        public static final MapCodec<ItemObtained> CODEC = ItemMatcher.MAP_CODEC.xmap(ItemObtained::new, ItemObtained::matcher);

        @Override
        public TriggerType<?, ItemStack> type() {
            return BuiltinTriggers.ITEM_OBTAINED.get();
        }

        @Override
        public boolean matches(StoryContext context, ItemStack stack) {
            return matcher.test(stack);
        }
    }

    /** Right-click on an entity with the main hand. */
    public record EntityInteracted(Optional<HolderSet<EntityType<?>>> entity) implements Trigger<Entity> {
        public static final MapCodec<EntityInteracted> CODEC = ENTITY_FILTER.xmap(EntityInteracted::new, EntityInteracted::entity);

        @Override
        public TriggerType<?, Entity> type() {
            return BuiltinTriggers.ENTITY_INTERACTED.get();
        }

        @Override
        public boolean matches(StoryContext context, Entity target) {
            return entityMatches(entity, target);
        }
    }

    public record PartyJoined() implements Trigger<PartyEvent.Joined> {
        public static final MapCodec<PartyJoined> CODEC = MapCodec.unit(new PartyJoined());

        @Override
        public TriggerType<?, PartyEvent.Joined> type() {
            return BuiltinTriggers.PARTY_JOINED.get();
        }

        @Override
        public boolean matches(StoryContext context, PartyEvent.Joined event) {
            return true;
        }

        @Override
        public StateKind stateKind() {
            return StateKind.CURRENT;
        }

        @Override
        public boolean holdsNow(StoryContext context) {
            return PartyData.get(context.player().server).partyOf(context.player().getUUID()).isPresent();
        }
    }

    /** Left, kicked or disbanded; fires for the player who is no longer in the party. */
    public record PartyLeft() implements Trigger<PartyEvent.Left> {
        public static final MapCodec<PartyLeft> CODEC = MapCodec.unit(new PartyLeft());

        @Override
        public TriggerType<?, PartyEvent.Left> type() {
            return BuiltinTriggers.PARTY_LEFT.get();
        }

        @Override
        public boolean matches(StoryContext context, PartyEvent.Left event) {
            return true;
        }
    }

    /**
     * {@code { "type": "logged_in" }}: the player joined the server (in singleplayer, opened the world); with
     * {@code first_time}, only on their first visit to this world. The payload says whether it is the first.
     */
    public record LoggedIn(boolean firstTime) implements Trigger<Boolean> {
        public static final MapCodec<LoggedIn> CODEC = Codec.BOOL.optionalFieldOf("first_time", false).xmap(LoggedIn::new, LoggedIn::firstTime);

        @Override
        public TriggerType<?, Boolean> type() {
            return BuiltinTriggers.LOGGED_IN.get();
        }

        @Override
        public boolean matches(StoryContext context, Boolean first) {
            return !firstTime || first;
        }
    }

    /** {@code { "type": "custom", "id": "mypack:bell_rung" }}: fired by a command, a script or another mod. */
    public record Custom(ResourceLocation id) implements Trigger<ResourceLocation> {
        public static final MapCodec<Custom> CODEC = ResourceLocation.CODEC.fieldOf("id").xmap(Custom::new, Custom::id);

        @Override
        public TriggerType<?, ResourceLocation> type() {
            return BuiltinTriggers.CUSTOM.get();
        }

        @Override
        public boolean matches(StoryContext context, ResourceLocation fired) {
            return id.equals(fired);
        }
    }
}
