package dev.dzenthai.storybound.core.logic.trigger;

import dev.dzenthai.storybound.core.event.PartyEvent;
import dev.dzenthai.storybound.core.event.StoryValueChangedEvent;
import dev.dzenthai.storybound.core.logic.Area;
import dev.dzenthai.storybound.core.logic.EntitySelector;
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
import dev.dzenthai.storybound.core.text.StoryText;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryCodecs;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/** Triggers backed by core systems and vanilla events. Module triggers live with their modules. */
public final class CoreTriggers {
    private static final MapCodec<Optional<HolderSet<EntityType<?>>>> ENTITY_FILTER =
            RegistryCodecs.homogeneousList(Registries.ENTITY_TYPE).optionalFieldOf("entity");

    private CoreTriggers() {}

    private static boolean entityMatches(Optional<HolderSet<EntityType<?>>> filter, Entity entity) {
        return filter.map(set -> set.contains(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.wrapAsHolder(entity.getType()))).orElse(true);
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
     * The payload of {@code entity_killed}: fired for the player who killed it ({@code killingBlow}), then for every
     * party member of theirs near the victim, whom {@code shared} triggers count, then for every other player near it
     * ({@code bystander}), whom only {@code by_anyone} triggers count; a death no player caused has only bystanders.
     */
    public record Kill(LivingEntity victim, boolean killingBlow, boolean bystander) {}

    /**
     * The player dealt the killing blow; with {@code shared}, also a party member of the killer within
     * {@code detection.killCreditRadius}; with {@code by_anyone}, every player that near, whoever or whatever killed it
     * (an iron golem, another mob, a fall, lava). {@code entity} is an id, a #tag or a list; omitted means any.
     */
    public record EntityKilled(Optional<HolderSet<EntityType<?>>> entity, boolean shared, boolean byAnyone) implements Trigger<Kill> {
        public static final MapCodec<EntityKilled> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                ENTITY_FILTER.forGetter(EntityKilled::entity),
                Codec.BOOL.optionalFieldOf("shared", false).forGetter(EntityKilled::shared),
                Codec.BOOL.optionalFieldOf("by_anyone", false).forGetter(EntityKilled::byAnyone)
        ).apply(instance, EntityKilled::new));

        @Override
        public TriggerType<?, Kill> type() {
            return BuiltinTriggers.ENTITY_KILLED.get();
        }

        @Override
        public boolean matches(StoryContext context, Kill kill) {
            boolean credited = kill.killingBlow() || byAnyone || shared && !kill.bystander();
            return credited && entityMatches(entity, kill.victim());
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

    /**
     * What the player looks straight at, for {@code observed}: an entity, or else a block with where it is.
     *
     * @param entity the entity under the crosshair; null for a block
     */
    public record Observation(@Nullable Entity entity, @Nullable BlockPos pos, @Nullable BlockState state, ResourceLocation dimension) {}

    /**
     * The player looked straight at the target for {@code time} seconds from at most {@code distance} blocks (see
     * {@link ObservationTracker}; a bar under the crosshair fills meanwhile): an entity ({@code entity}, the selector
     * of NPC bindings), a kind of block ({@code block}: an id, a #tag or a list), or one block ({@code pos}, with
     * {@code dimension}); {@code block} and {@code pos} together: that block, if it is still such a one. Or an item
     * ({@code item}: an id, a #tag or a list), wherever it shows: lying on the ground, in an item frame, held or worn
     * by the one looked at, or, an item that is a block, placed as that block ({@link #itemSeen}); never in the
     * player's own hand.
     *
     * @param text what the bar says ("Examine the tracks"); without one, "Observing: <the target>"
     */
    public record Observed(Optional<EntitySelector> entity, Optional<HolderSet<Block>> block, Optional<BlockPos> pos, Optional<ResourceLocation> dimension,
                           float time, double distance, Optional<StoryText> text, Optional<HolderSet<Item>> item) implements Trigger<Observation> {
        public static final float DEFAULT_TIME = 3;
        public static final double DEFAULT_DISTANCE = 16;
        public static final MapCodec<Observed> CODEC = RecordCodecBuilder.<Observed>mapCodec(instance -> instance.group(
                EntitySelector.CODEC.optionalFieldOf("entity").forGetter(Observed::entity),
                RegistryCodecs.homogeneousList(Registries.BLOCK).optionalFieldOf("block").forGetter(Observed::block),
                BlockPos.CODEC.optionalFieldOf("pos").forGetter(Observed::pos),
                ResourceLocation.CODEC.optionalFieldOf("dimension").forGetter(Observed::dimension),
                Codec.floatRange(0.25f, 600f).optionalFieldOf("time", DEFAULT_TIME).forGetter(Observed::time),
                Codec.doubleRange(1, 64).optionalFieldOf("distance", DEFAULT_DISTANCE).forGetter(Observed::distance),
                StoryText.CODEC.optionalFieldOf("text").forGetter(Observed::text),
                RegistryCodecs.homogeneousList(Registries.ITEM).optionalFieldOf("item").forGetter(Observed::item)
        ).apply(instance, Observed::new)).validate(observed -> {
            int kinds = (observed.entity().isPresent() ? 1 : 0) + (observed.block().isPresent() || observed.pos().isPresent() ? 1 : 0)
                    + (observed.item().isPresent() ? 1 : 0);
            return kinds > 1 ? DataResult.error(() -> "observed takes 'entity', 'block' and/or 'pos', or 'item': one kind of target")
                    : DataResult.success(observed);
        });

        /** Without an item, as before items could be observed. */
        public Observed(Optional<EntitySelector> entity, Optional<HolderSet<Block>> block, Optional<BlockPos> pos, Optional<ResourceLocation> dimension,
                        float time, double distance, Optional<StoryText> text) {
            this(entity, block, pos, dimension, time, distance, text, Optional.empty());
        }

        /** Without a target (just picked in the editor) it observes nothing until one is given. */
        public boolean hasTarget() {
            return entity.isPresent() || block.isPresent() || pos.isPresent() || item.isPresent();
        }

        /**
         * The item of {@code item} the target shows, or empty: the stack lying there (an item on the ground), the one in an
         * item frame, one held or worn by the creature looked at (an NPC, an armor stand, another player), or the block's
         * own item (a chest placed is a chest). The player is never their own target, so their hand never counts.
         */
        public ItemStack itemSeen(Observation target) {
            if (item.isEmpty()) {
                return ItemStack.EMPTY;
            }
            List<ItemStack> shown = new ArrayList<>();
            if (target.entity() instanceof ItemEntity lying) {
                shown.add(lying.getItem());
            } else if (target.entity() instanceof ItemFrame frame) {
                shown.add(frame.getItem());
            } else if (target.entity() instanceof LivingEntity holder) {
                for (EquipmentSlot slot : EquipmentSlot.values()) {
                    shown.add(holder.getItemBySlot(slot));
                }
            } else if (target.entity() == null && target.state() != null) {
                shown.add(new ItemStack(target.state().getBlock().asItem()));
            }
            for (ItemStack stack : shown) {
                if (!stack.isEmpty() && item.get().contains(stack.getItemHolder())) {
                    return stack;
                }
            }
            return ItemStack.EMPTY;
        }

        /** An entity the selector picks: for the Codex and Bosses, whose sections open on studying their creature. */
        public static Observed of(EntitySelector entity, float time) {
            return new Observed(Optional.of(entity), Optional.empty(), Optional.empty(), Optional.empty(), time, DEFAULT_DISTANCE, Optional.empty(),
                    Optional.empty());
        }

        @Override
        public TriggerType<?, Observation> type() {
            return BuiltinTriggers.OBSERVED.get();
        }

        @Override
        public void collectReferences(References references) {
            entity.ifPresent(selector -> references.place(selector.place()));
        }

        @Override
        public boolean matches(StoryContext context, Observation target) {
            if (item.isPresent()) {
                return !itemSeen(target).isEmpty();
            }
            if (target.entity() != null) {
                return entity.map(selector -> selector.matches(target.entity())).orElse(false);
            }
            if (!hasTarget() || entity.isPresent() || target.state() == null || target.pos() == null) {
                return false;
            }
            return block.map(set -> set.contains(target.state().getBlockHolder())).orElse(true)
                    && pos.map(target.pos()::equals).orElse(true)
                    && dimension.map(target.dimension()::equals).orElse(true);
        }
    }

    /**
     * The time of day reaches a period in the player's level: {@code { "type": "time", "period": "night" }}. Checked
     * with the areas ({@link DayTimeTracker}); never in a level whose time stands still (the Nether, the End). Being in
     * that period is the state it announces, so a stage that begins at night counts at once.
     */
    public record TimeReached(dev.dzenthai.storybound.core.logic.condition.WorldConditions.Time.Period period)
            implements Trigger<dev.dzenthai.storybound.core.logic.condition.WorldConditions.Time.Period> {
        public static final MapCodec<TimeReached> CODEC = dev.dzenthai.storybound.core.logic.condition.WorldConditions.Time.Period.CODEC
                .fieldOf("period").xmap(TimeReached::new, TimeReached::period);

        @Override
        public TriggerType<?, dev.dzenthai.storybound.core.logic.condition.WorldConditions.Time.Period> type() {
            return BuiltinTriggers.TIME.get();
        }

        @Override
        public boolean matches(StoryContext context, dev.dzenthai.storybound.core.logic.condition.WorldConditions.Time.Period event) {
            return event == period;
        }

        @Override
        public StateKind stateKind() {
            return StateKind.CURRENT;
        }

        @Override
        public boolean holdsNow(StoryContext context) {
            return DayTimeTracker.periodOf(context.player()) == period;
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
