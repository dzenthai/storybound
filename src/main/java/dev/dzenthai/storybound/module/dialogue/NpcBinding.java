package dev.dzenthai.storybound.module.dialogue;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.dzenthai.storybound.core.logic.Condition;
import dev.dzenthai.storybound.core.logic.EntitySelector;
import dev.dzenthai.storybound.core.logic.LogicCodecs;
import dev.dzenthai.storybound.core.logic.StoryContext;
import dev.dzenthai.storybound.core.logic.condition.LogicConditions;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

/**
 * {@code data/<ns>/storybound/npc_bindings/<path>.json}: which entities talk, which dialogue they start and how.
 *
 * @param dialogues tried by priority (highest first); the first whose conditions pass is started
 * @param interact  right-click starts the dialogue
 * @param proximity coming close starts it
 * @param custom    right-click posts {@code npcInteract} to scripts, which decide what happens
 */
public record NpcBinding(EntitySelector entity, List<Entry> dialogues, boolean interact, Optional<Proximity> proximity, boolean custom) {
    public static final Codec<NpcBinding> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            EntitySelector.CODEC.fieldOf("entity").forGetter(NpcBinding::entity),
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
            return new NpcBinding(EntitySelector.of(entity.getUUID()),
                    List.of(new Entry(dialogue, LogicConditions.ALWAYS, 0)), interact,
                    proximity ? Optional.of(Proximity.DEFAULT) : Optional.empty(), false);
        }
    }
}
