package dev.dzenthai.storybound.module.dialogue;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.dzenthai.storybound.core.logic.Condition;
import dev.dzenthai.storybound.core.logic.Conditions;
import dev.dzenthai.storybound.core.logic.EntitySelector;
import dev.dzenthai.storybound.core.logic.LogicCodecs;
import dev.dzenthai.storybound.core.logic.StoryContext;
import dev.dzenthai.storybound.core.logic.StoryTrace;
import dev.dzenthai.storybound.core.logic.condition.LogicConditions;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

/**
 * {@code data/<ns>/storybound/npc_bindings/<path>.json}: which entities talk, which dialogue they start and how.
 *
 * @param dialogues tried by priority (highest first); the first whose conditions pass is started
 * @param interact  right-click starts the dialogue
 * @param proximity coming close starts it
 * @param custom    right-click posts {@code npcInteract} to scripts, which decide what happens
 * @param exclusive while the NPC talks with one player, others cannot start its dialogues (parallel talks are the default)
 * @param busyDialogue what others get meanwhile, e.g. a bubble "Wait, I am busy"; without it, nothing
 */
public record NpcBinding(EntitySelector entity, List<Entry> dialogues, boolean interact, Optional<Proximity> proximity,
                         boolean custom, boolean overrideInteract, boolean exclusive, Optional<ResourceLocation> busyDialogue) {
    public static final Codec<NpcBinding> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            EntitySelector.CODEC.fieldOf("entity").forGetter(NpcBinding::entity),
            Entry.CODEC.listOf().optionalFieldOf("dialogues", List.of()).forGetter(NpcBinding::dialogues),
            Codec.BOOL.optionalFieldOf("interact", true).forGetter(NpcBinding::interact),
            Proximity.CODEC.optionalFieldOf("proximity").forGetter(NpcBinding::proximity),
            Codec.BOOL.optionalFieldOf("custom", false).forGetter(NpcBinding::custom),
            Codec.BOOL.optionalFieldOf("override_interact", false).forGetter(NpcBinding::overrideInteract),
            Codec.BOOL.optionalFieldOf("exclusive", false).forGetter(NpcBinding::exclusive),
            ResourceLocation.CODEC.optionalFieldOf("busy_dialogue").forGetter(NpcBinding::busyDialogue)
    ).apply(instance, NpcBinding::new));

    public NpcBinding(EntitySelector entity, List<Entry> dialogues, boolean interact, Optional<Proximity> proximity, boolean custom) {
        this(entity, dialogues, interact, proximity, custom, false, false, Optional.empty());
    }

    /** The dialogue to start for this player, if any entry's conditions pass. */
    public Optional<ResourceLocation> pick(StoryContext context) {
        return pick(context, null);
    }

    /**
     * The dialogue to start for this player, if any entry's conditions pass.
     *
     * @param source names the binding in the trace when the player talks (see {@link Conditions#check}); null for
     *               checks that are only asked, such as whether the talk key works on the NPC
     */
    public Optional<ResourceLocation> pick(StoryContext context, @Nullable String source) {
        return dialogues.stream()
                .sorted(Comparator.comparingInt(Entry::priority).reversed())
                // A dialogue that is gone (deleted, a typo) is passed over, so the next entry still talks.
                .filter(entry -> {
                    boolean known = Dialogues.definition(entry.dialogue()) != null;
                    if (!known && source != null) {
                        StoryTrace.note(context.player(), source + " entry " + entry.dialogue(), "skipped: no such dialogue");
                    }
                    return known;
                })
                .filter(entry -> source == null ? entry.conditions().test(context)
                        : Conditions.check(entry.conditions(), context, source + " entry " + entry.dialogue()))
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
