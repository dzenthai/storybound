package dev.dzenthai.storybound.module.dialogue;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.dzenthai.storybound.core.logic.Condition;
import dev.dzenthai.storybound.core.logic.LogicCodecs;
import dev.dzenthai.storybound.core.logic.StoryContext;
import dev.dzenthai.storybound.core.logic.condition.LogicConditions;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryCodecs;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

/**
 * {@code data/<ns>/storybound/npc_bindings/<path>.json}: which entities talk, which dialogue they start and how.
 *
 * @param dialogues tried by priority (highest first); the first whose conditions pass is started
 * @param interact  right-click starts the dialogue
 * @param proximity coming close starts it
 * @param custom    right-click posts {@code npcInteract} to scripts, which decide what happens
 */
public record NpcBinding(Selector entity, List<Entry> dialogues, boolean interact, Optional<Proximity> proximity, boolean custom) {
    public static final Codec<NpcBinding> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Selector.CODEC.fieldOf("entity").forGetter(NpcBinding::entity),
            Entry.CODEC.listOf().optionalFieldOf("dialogues", List.of()).forGetter(NpcBinding::dialogues),
            Codec.BOOL.optionalFieldOf("interact", true).forGetter(NpcBinding::interact),
            Proximity.CODEC.optionalFieldOf("proximity").forGetter(NpcBinding::proximity),
            Codec.BOOL.optionalFieldOf("custom", false).forGetter(NpcBinding::custom)
    ).apply(instance, NpcBinding::new));

    /** The dialogue to start for this player, if any entry's conditions pass. */
    public Optional<ResourceLocation> pick(StoryContext context) {
        return dialogues.stream()
                .sorted(Comparator.comparingInt(Entry::priority).reversed())
                .filter(entry -> entry.conditions().test(context))
                .map(Entry::dialogue)
                .findFirst();
    }

    /** Every part that is given must match; at least one is required. */
    public record Selector(Optional<HolderSet<EntityType<?>>> type, Optional<String> tag, Optional<String> name, Optional<UUID> uuid) {
        public static final Codec<Selector> CODEC = RecordCodecBuilder.<Selector>create(instance -> instance.group(
                RegistryCodecs.homogeneousList(Registries.ENTITY_TYPE).optionalFieldOf("type").forGetter(Selector::type),
                Codec.STRING.optionalFieldOf("tag").forGetter(Selector::tag),
                Codec.STRING.optionalFieldOf("name").forGetter(Selector::name),
                UUIDUtil.STRING_CODEC.optionalFieldOf("uuid").forGetter(Selector::uuid)
        ).apply(instance, Selector::new)).validate(selector ->
                selector.type().isPresent() || selector.tag().isPresent() || selector.name().isPresent() || selector.uuid().isPresent()
                        ? DataResult.success(selector)
                        : DataResult.error(() -> "An NPC selector needs 'type', 'tag', 'name' or 'uuid'"));

        public boolean matches(Entity entity) {
            return type.map(set -> set.contains(entity.getType().builtInRegistryHolder())).orElse(true)
                    && tag.map(entity.getTags()::contains).orElse(true)
                    && name.map(value -> value.equals(Optional.ofNullable(entity.getCustomName()).map(Component::getString).orElse(null))).orElse(true)
                    && uuid.map(entity.getUUID()::equals).orElse(true);
        }
    }

    public record Entry(ResourceLocation dialogue, Condition conditions, int priority) {
        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ResourceLocation.CODEC.fieldOf("dialogue").forGetter(Entry::dialogue),
                LogicCodecs.CONDITION.optionalFieldOf("conditions", LogicConditions.ALWAYS).forGetter(Entry::conditions),
                Codec.INT.optionalFieldOf("priority", 0).forGetter(Entry::priority)
        ).apply(instance, Entry::new));
    }

    /**
     * Starts the dialogue when the player comes within {@code radius}; again only after leaving and
     * coming back, and not before {@code cooldown} seconds have passed.
     *
     * @param once at most once per player, ever
     */
    public record Proximity(float radius, int cooldown, boolean once, boolean lineOfSight) {
        public static final Proximity DEFAULT = new Proximity(4f, 30, false, true);
        public static final Codec<Proximity> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.floatRange(1f, 32f).optionalFieldOf("radius", DEFAULT.radius()).forGetter(Proximity::radius),
                Codec.intRange(0, 86_400).optionalFieldOf("cooldown", DEFAULT.cooldown()).forGetter(Proximity::cooldown),
                Codec.BOOL.optionalFieldOf("once", DEFAULT.once()).forGetter(Proximity::once),
                Codec.BOOL.optionalFieldOf("line_of_sight", DEFAULT.lineOfSight()).forGetter(Proximity::lineOfSight)
        ).apply(instance, Proximity::new));
    }

    /**
     * The binding {@code /storybound npc bind} stores on one entity; it wins over binding files.
     */
    public record Attached(ResourceLocation dialogue, boolean interact, boolean proximity) {
        public static final Codec<Attached> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ResourceLocation.CODEC.fieldOf("dialogue").forGetter(Attached::dialogue),
                Codec.BOOL.optionalFieldOf("interact", true).forGetter(Attached::interact),
                Codec.BOOL.optionalFieldOf("proximity", false).forGetter(Attached::proximity)
        ).apply(instance, Attached::new));

        NpcBinding toBinding(Entity entity) {
            return new NpcBinding(new Selector(Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(entity.getUUID())),
                    List.of(new Entry(dialogue, LogicConditions.ALWAYS, 0)), interact,
                    proximity ? Optional.of(Proximity.DEFAULT) : Optional.empty(), false);
        }
    }
}
