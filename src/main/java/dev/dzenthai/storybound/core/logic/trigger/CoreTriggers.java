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
import dev.dzenthai.storybound.core.logic.Trigger;
import dev.dzenthai.storybound.core.logic.TriggerType;
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
import net.minecraft.world.item.ItemStack;

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

        @Override
        public void collectReferences(References references) {
            references.flag(key);
        }
    }

    /** The player dealt the killing blow. {@code entity} is an id, a #tag or a list; omitted means any. */
    public record EntityKilled(Optional<HolderSet<EntityType<?>>> entity) implements Trigger<LivingEntity> {
        public static final MapCodec<EntityKilled> CODEC = ENTITY_FILTER.xmap(EntityKilled::new, EntityKilled::entity);

        @Override
        public TriggerType<?, LivingEntity> type() {
            return BuiltinTriggers.ENTITY_KILLED.get();
        }

        @Override
        public boolean matches(StoryContext context, LivingEntity victim) {
            return entityMatches(entity, victim);
        }
    }

    /**
     * Fires when a player crosses into the area; checked every 10 ticks. A player who logs in
     * already inside fires it only with {@code fire_on_login}; a data reload never fires it.
     */
    public record AreaEntered(Area area, boolean fireOnLogin) implements Trigger<Unit> {
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
