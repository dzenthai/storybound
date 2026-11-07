package dev.dzenthai.storybound.module.dialogue;

import com.mojang.datafixers.util.Either;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.dzenthai.storybound.core.logic.Condition;
import dev.dzenthai.storybound.core.logic.Conditions;
import dev.dzenthai.storybound.core.logic.EntitySelector;
import dev.dzenthai.storybound.core.logic.LogicCodecs;
import dev.dzenthai.storybound.core.logic.StoryContext;
import dev.dzenthai.storybound.core.logic.StoryTrace;
import dev.dzenthai.storybound.core.logic.condition.LogicConditions;
import dev.dzenthai.storybound.core.state.StoryMigrations;
import dev.dzenthai.storybound.core.text.StoryText;
import java.util.Comparator;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

/**
 * {@code data/<ns>/storybound/npc_bindings/<path>.json}: which entities talk, which dialogue they start and how.
 *
 * @param entities  the binding is for any of them: one selector, or a list ({@code "entity"} takes both; a list since
 *                  October 29, 2026, so one NPC file covers, say, the elder and his double in another village)
 * @param dialogues what a click or the talk key gets, tried by priority (highest first): the first entry whose conditions
 *                  pass; a dialogue started, or phrases said in a bubble with no conversation (since November 6, 2026)
 * @param interact  right-click starts the dialogue
 * @param proximity coming close starts it
 * @param custom    right-click posts {@code npcInteract} to scripts, which decide what happens
 * @param exclusive while the NPC talks with one player, others cannot start its dialogues (parallel talks are the default)
 * @param busyDialogue what others get meanwhile, e.g. a bubble "Wait, I am busy"; without it, nothing
 * @param respawn   the NPC comes back where it died or disappeared (see {@link NpcRespawns})
 * @param invulnerable nothing hurts the NPC but the void and {@code /kill} (see {@link NpcReactions})
 * @param whenAttacked what the NPC does when a player hits it; without it, what its kind does in vanilla
 * @param chatter      the NPC says its phrases now and then to players near it (see {@link NpcChatter})
 * @param showName     the entities it matches carry {@code displayName} over their heads (see {@link NpcNames}); on
 *                     unless turned off, so a name written is a name shown
 * @param displayName  the NPC's one name: over its head, and in dialogues, where a participant names nothing else; on a
 *                     binding by hand (a tag, a name, a uuid) only: one for every entity of a kind would name them all
 *                     alike (November 7, 2026; before, it did)
 * @param names        on a binding for every entity of a kind (no tag), names to give them, one each, the one least used
 *                     around (see {@link NpcNames}; November 7, 2026)
 * @param one          on a binding for every entity of a kind (no tag): one entity only, picked by the mod (an Elder; see
 *                     {@link NpcParts}; November 7, 2026)
 * @param share        on a binding for every entity of a kind, all such (no tag, not {@code one}): only this per cent of
 *                     them, each entity rolled once for good by its uuid and the binding (see {@link #inShare}; November 8,
 *                     2026); absent or 100: all
 *                     <p>
 * Several bindings on one entity are layers (November 7, 2026; see {@link NpcBindings#find}): each setting comes from
 * the closest binding that writes it, so the on/off ones are kept as written or not ({@link Optional#empty()}: "from
 * the binding below", the default when none writes it: {@code interact} and {@code show_name} on, the rest off).
 */
public record NpcBinding(List<EntitySelector> entities, List<Entry> dialogues, Optional<Boolean> interact, Optional<Proximity> proximity,
                         Optional<Boolean> custom, Optional<Boolean> overrideInteract, Optional<Boolean> exclusive, Optional<ResourceLocation> busyDialogue,
                         Optional<Boolean> respawn, Optional<Boolean> invulnerable, Optional<WhenAttacked> whenAttacked, Optional<Chatter> chatter,
                         Optional<Boolean> showName, Optional<StoryText> displayName, List<StoryText> names, boolean one,
                         Optional<Integer> share) {
    /** One selector, or a non-empty list of them; one is written back as an object, as files had it before lists. */
    private static final Codec<List<EntitySelector>> ENTITIES = Codec.either(EntitySelector.CODEC, ExtraCodecs.nonEmptyList(EntitySelector.CODEC.listOf()))
            .xmap(either -> either.map(List::of, Function.identity()),
                    entities -> entities.size() == 1 ? Either.left(entities.getFirst()) : Either.right(entities));

    /** How many of those it matches it is: {@code one}, and {@code share} (1 to 100 per cent). */
    private static final MapCodec<Pair<Boolean, Optional<Integer>>> CROWD = Codec.mapPair(
            Codec.BOOL.optionalFieldOf("one", false), Codec.intRange(1, 100).optionalFieldOf("share"));

    public static final Codec<NpcBinding> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ENTITIES.fieldOf("entity").forGetter(NpcBinding::entities),
            Entry.CODEC.listOf().optionalFieldOf("dialogues", List.of()).forGetter(NpcBinding::dialogues),
            Codec.BOOL.optionalFieldOf("interact").forGetter(NpcBinding::interact),
            Proximity.CODEC.optionalFieldOf("proximity").forGetter(NpcBinding::proximity),
            Codec.BOOL.optionalFieldOf("custom").forGetter(NpcBinding::custom),
            Codec.BOOL.optionalFieldOf("override_interact").forGetter(NpcBinding::overrideInteract),
            Codec.BOOL.optionalFieldOf("exclusive").forGetter(NpcBinding::exclusive),
            ResourceLocation.CODEC.optionalFieldOf("busy_dialogue").forGetter(NpcBinding::busyDialogue),
            Codec.BOOL.optionalFieldOf("respawn").forGetter(NpcBinding::respawn),
            Codec.BOOL.optionalFieldOf("invulnerable").forGetter(NpcBinding::invulnerable),
            WhenAttacked.CODEC.optionalFieldOf("when_attacked").forGetter(NpcBinding::whenAttacked),
            Chatter.CODEC.optionalFieldOf("chatter").forGetter(NpcBinding::chatter),
            Codec.BOOL.optionalFieldOf("show_name").forGetter(NpcBinding::showName),
            StoryText.CODEC.optionalFieldOf("display_name").forGetter(NpcBinding::displayName),
            StoryText.CODEC.listOf().optionalFieldOf("names", List.of()).forGetter(NpcBinding::names),
            // Two fields as one: a record codec takes at most 16.
            CROWD.forGetter(binding -> Pair.of(binding.one(), binding.share()))
    ).apply(instance, (entities, dialogues, interact, proximity, custom, overrideInteract, exclusive, busyDialogue, respawn, invulnerable,
                       whenAttacked, chatter, showName, displayName, names, crowd) -> new NpcBinding(entities, dialogues, interact, proximity, custom,
            overrideInteract, exclusive, busyDialogue, respawn, invulnerable, whenAttacked, chatter, showName, displayName, names, crowd.getFirst(),
            crowd.getSecond())));

    /**
     * What the NPC does when a player hits it (November 5, 2026; before, what its kind does: an iron golem killed the
     * player who hit it by accident). Only players' hits count; mobs' leave it to its kind.
     *
     * @param reaction  {@code ignore}: no revenge; {@code flee}: runs from the player for a few seconds; {@code fight}:
     *                  goes for the player (for kinds that can attack)
     * @param patience  for {@code fight} only: hits forgiven before it fights back, as followers forgive a stray blow
     * @param warning   for {@code fight} only: one of them said on each forgiven hit ("Careful!", "Once more and..."); a
     *                  phrases node or a text of its own, as {@code phrases}
     * @param phrases   one of them said on every hit it reacts to: for {@code fight}, those past its patience
     * @param dialogue  instead of phrases, a dialogue started with the player on such a hit ("What are you doing?!" as a
     *                  scene), after the one they are in (November 6, 2026; before, a line of the phrases could be one)
     * @param calmAfter seconds without a hit after which the NPC forgives everything: its reaction ends, the count of
     *                  hits and its phrases start over
     */
    public record WhenAttacked(Reaction reaction, int patience, Optional<PhrasesRef> warning, Optional<PhrasesRef> phrases,
                               Optional<ResourceLocation> dialogue, float calmAfter) {
        public static final Codec<WhenAttacked> CODEC = RecordCodecBuilder.<WhenAttacked>create(instance -> instance.group(
                Reaction.CODEC.optionalFieldOf("reaction", Reaction.IGNORE).forGetter(WhenAttacked::reaction),
                Codec.intRange(0, 20).optionalFieldOf("patience", 0).forGetter(WhenAttacked::patience),
                PhrasesRef.CODEC.optionalFieldOf("warning").forGetter(WhenAttacked::warning),
                PhrasesRef.CODEC.optionalFieldOf("phrases").forGetter(WhenAttacked::phrases),
                ResourceLocation.CODEC.optionalFieldOf("dialogue").forGetter(WhenAttacked::dialogue),
                Codec.floatRange(5f, 600f).optionalFieldOf("calm_after", 30f).forGetter(WhenAttacked::calmAfter)
        ).apply(instance, WhenAttacked::new)).validate(rules -> rules.phrases().isPresent() && rules.dialogue().isPresent()
                ? DataResult.error(() -> "when_attacked: \"phrases\" or \"dialogue\", not both: on a hit the NPC says a phrase or starts a scene")
                : DataResult.success(rules));

        /** The hits it forgives: only an NPC that fights back has patience; the others never take revenge anyway. */
        public int forgiven() {
            return reaction == Reaction.FIGHT ? patience : 0;
        }

        public enum Reaction implements StringRepresentable {
            IGNORE("ignore"),
            FLEE("flee"),
            FIGHT("fight");

            public static final Codec<Reaction> CODEC = StringRepresentable.fromEnum(Reaction::values);

            private final String name;

            Reaction(String name) {
                this.name = name;
            }

            @Override
            public String getSerializedName() {
                return name;
            }
        }
    }

    /** Whether the binding is for the entity: any of its selectors matches. */
    public boolean matches(Entity entity) {
        return entities.stream().anyMatch(selector -> selector.matches(entity));
    }

    /**
     * How closely the binding names the entity, for when several match it; null when no selector matches. The best
     * matching selector counts.
     */
    public @Nullable Rank rank(Entity entity) {
        Rank best = null;
        for (EntitySelector selector : entities) {
            if (selector.matches(entity)) {
                Rank rank = new Rank(selector.uuid().isPresent() ? Rank.UUID : selector.tag().isPresent() || selector.name().isPresent() ? Rank.TAG : Rank.KIND,
                        selector.filters());
                best = best == null || rank.compareTo(best) < 0 ? rank : best;
            }
        }
        return best;
    }

    /**
     * How closely a binding names an entity (November 6, 2026; before, the first by id won, so "every villager" named
     * {@code all_villagers} took the elder from {@code elder}; filters November 7, 2026): by its uuid, over by a tag or a
     * name it carries, over by its kind (and filters) alone; of the last, more filters first ("weaponsmiths" over
     * "villagers"). Ordered closest first.
     *
     * @param by      {@link #OWN}, {@link #UUID}, {@link #TAG} or {@link #KIND}
     * @param filters the matching selector's filters ({@link EntitySelector#filters})
     */
    public record Rank(int by, int filters) implements Comparable<Rank> {
        /** The binding stored on the entity by the removed {@code /storybound npc bind}: before any file. */
        public static final int OWN = 5;
        public static final int UUID = 4;
        /** The part of a "one" binding the entity holds ({@link NpcParts}): one entity, as by hand. */
        public static final int PART = 3;
        public static final int TAG = 2;
        public static final int KIND = 1;

        @Override
        public int compareTo(Rank other) {
            return by != other.by ? Integer.compare(other.by, by) : Integer.compare(other.filters, filters);
        }

        /** "by tag", "by kind, 2 filters": for the trace and {@code /storybound debug npc}. */
        public String describe() {
            String way = switch (by) {
                case OWN -> "its own binding";
                case UUID -> "by uuid";
                case PART -> "the one picked";
                case TAG -> "by tag or name";
                default -> "by kind";
            };
            return filters == 0 ? way : way + ", " + filters + (filters == 1 ? " filter" : " filters");
        }
    }

    /**
     * What keeps the entity from matching, for the trace: each selector's reason ({@link EntitySelector#mismatch}), the
     * ones of other entity types left out unless all are; empty when it matches.
     */
    public String mismatch(Entity entity) {
        if (matches(entity)) {
            return "";
        }
        List<String> reasons = entities.stream().map(selector -> selector.mismatch(entity)).toList();
        List<String> sameType = reasons.stream().filter(reason -> !reason.startsWith("type is ")).toList();
        return String.join(" | or ", sameType.isEmpty() ? reasons : sameType);
    }

    /** The per cent of the entities it matches that it is, when that is less than all: all such of a kind, not by hand or one. */
    public Optional<Integer> crowdShare() {
        return share.filter(percent -> percent < 100 && !one && !byHand());
    }

    /**
     * Whether the entity is within a binding's share (November 8, 2026): a roll of its own, from its uuid and the
     * binding's id, the same for good (a restart, a respawn), so a guard stays a guard; raising the share keeps those
     * in it and adds others, lowering it takes some out. Another binding rolls anew; a renamed one too.
     */
    public static boolean inShare(UUID entity, ResourceLocation binding, int percent) {
        long h = entity.getMostSignificantBits() * 0x9E3779B97F4A7C15L ^ entity.getLeastSignificantBits()
                ^ binding.toString().hashCode() * 0xC2B2AE3D27D4EB4FL;
        // SplitMix64's finish: every bit of the input moves the roll.
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return Math.floorMod(h, 100) < percent;
    }

    /** Whether it is one entity picked by the mod ({@link NpcParts}): "one" on a binding for every entity of a kind; by hand, the tag decides. */
    public boolean isOne() {
        return one && !byHand();
    }

    /** Whether it is for particular entities, by hand (a tag, a name or a uuid in every selector), not every one of a kind. */
    public boolean byHand() {
        return entities.stream().allMatch(selector -> selector.tag().isPresent() || selector.name().isPresent() || selector.uuid().isPresent());
    }

    /** The scoreboard tags its selectors look for. */
    public Set<String> tags() {
        return entities.stream().flatMap(selector -> selector.tag().stream()).collect(Collectors.toSet());
    }

    /** What a click gets from this player, if any entry's conditions pass: a dialogue or phrases. */
    public Optional<Entry> pick(StoryContext context) {
        return pick(context, null);
    }

    /**
     * What a click gets from this player, if any entry's conditions pass: a dialogue or phrases.
     *
     * @param source names the binding in the trace when the player talks (see {@link Conditions#check}); null for
     *               checks that are only asked, such as whether the talk key works on the NPC
     */
    public Optional<Entry> pick(StoryContext context, @Nullable String source) {
        return dialogues.stream()
                .sorted(Comparator.comparingInt(Entry::priority).reversed())
                // A dialogue or phrases node that is gone (deleted, a typo) is passed over, so the next entry still talks.
                .filter(entry -> {
                    boolean known = entry.known();
                    if (!known && source != null) {
                        StoryTrace.note(context.player(), source + " entry " + entry.describe(), "skipped: no such " + (entry.dialogue().isPresent() ? "dialogue" : "phrases"));
                    }
                    return known;
                })
                .filter(entry -> source == null ? entry.conditions().test(context)
                        : Conditions.check(entry.conditions(), context, source + " entry " + entry.describe()))
                .findFirst();
    }

    /**
     * One thing a click may get: a dialogue started, or phrases said in a bubble (a node's next line, or a text of its
     * own) with no conversation ("Wolves again…", "Thank you!"; November 6, 2026). Exactly one of the two.
     */
    public record Entry(Optional<ResourceLocation> dialogue, Optional<PhrasesRef> phrases, Condition conditions, int priority) {
        public static final Codec<Entry> CODEC = RecordCodecBuilder.<Entry>create(instance -> instance.group(
                ResourceLocation.CODEC.optionalFieldOf("dialogue").forGetter(Entry::dialogue),
                PhrasesRef.CODEC.optionalFieldOf("phrases").forGetter(Entry::phrases),
                LogicCodecs.CONDITION.optionalFieldOf("conditions", LogicConditions.ALWAYS).forGetter(Entry::conditions),
                Codec.INT.optionalFieldOf("priority", 0).forGetter(Entry::priority)
        ).apply(instance, Entry::new)).validate(entry -> entry.dialogue().isPresent() == entry.phrases().isPresent()
                ? DataResult.error(() -> "A dialogues entry is a \"dialogue\" or \"phrases\", one of the two")
                : DataResult.success(entry));

        public static Entry of(ResourceLocation dialogue) {
            return new Entry(Optional.of(dialogue), Optional.empty(), LogicConditions.ALWAYS, 0);
        }

        /** Whether what it names is loaded (an own text always is). */
        boolean known() {
            return dialogue.map(id -> Dialogues.definition(id) != null)
                    .orElseGet(() -> phrases.flatMap(PhrasesRef::node).map(id -> DialogueRegistries.PHRASES.serverStore().contains(id)).orElse(true));
        }

        /** "mypack:elder" or "phrases mypack:wolves", for the trace and commands. */
        public String describe() {
            return dialogue.map(ResourceLocation::toString).orElseGet(() -> phrases.orElseThrow().describe());
        }
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
     * The NPC's chatter (November 5, 2026): now and then, while a player is within {@code radius} and the NPC is free (not
     * in a dialogue, not angry), it says one of its phrases. The {@code conditions} are tested for the nearest of
     * those players.
     *
     * @param phrases     what it says when no variant's conditions pass (or it has none)
     * @param variants    what it says as the story goes on (November 6, 2026): tried by priority, highest first, for the
     *                    nearest player; the first whose conditions pass is said instead of {@code phrases}
     * @param minInterval seconds between two phrases at least; the wait is random up to {@code maxInterval}
     */
    public record Chatter(Optional<PhrasesRef> phrases, List<Variant> variants, float minInterval, float maxInterval, float radius, Condition conditions) {
        public static final Codec<Chatter> CODEC = RecordCodecBuilder.<Chatter>create(instance -> instance.group(
                PhrasesRef.CODEC.optionalFieldOf("phrases").forGetter(Chatter::phrases),
                Variant.CODEC.listOf().optionalFieldOf("variants", List.of()).forGetter(Chatter::variants),
                Codec.floatRange(2f, 3600f).optionalFieldOf("min_interval", 20f).forGetter(Chatter::minInterval),
                Codec.floatRange(2f, 3600f).optionalFieldOf("max_interval", 40f).forGetter(Chatter::maxInterval),
                Codec.floatRange(1f, 32f).optionalFieldOf("radius", 8f).forGetter(Chatter::radius),
                LogicCodecs.CONDITION.optionalFieldOf("conditions", LogicConditions.ALWAYS).forGetter(Chatter::conditions)
        ).apply(instance, Chatter::new)).validate(chatter -> chatter.phrases().isEmpty() && chatter.variants().isEmpty()
                ? DataResult.error(() -> "chatter needs \"phrases\" or \"variants\": something to say")
                : DataResult.success(chatter));

        /** What is said: a variant's index and phrases (-1: the chatter's own), or empty when nothing applies. */
        public record Said(int variant, PhrasesRef phrases) {}

        /** The phrases to say to this player: the first variant by priority whose conditions pass, else its own. */
        public Optional<Said> pick(StoryContext context) {
            List<Integer> order = new java.util.ArrayList<>(java.util.stream.IntStream.range(0, variants.size()).boxed().toList());
            order.sort(Comparator.comparingInt((Integer index) -> variants.get(index).priority()).reversed());
            for (int index : order) {
                if (variants.get(index).conditions().test(context)) {
                    return Optional.of(new Said(index, variants.get(index).phrases()));
                }
            }
            return phrases.map(own -> new Said(-1, own));
        }

        /** Every phrases it may say, for checks and links. */
        public List<PhrasesRef> allPhrases() {
            List<PhrasesRef> all = new java.util.ArrayList<>();
            phrases.ifPresent(all::add);
            variants.forEach(variant -> all.add(variant.phrases()));
            return all;
        }

        /** Phrases for part of the story: "after the wolves are dealt with". */
        public record Variant(PhrasesRef phrases, Condition conditions, int priority) {
            public static final Codec<Variant> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    PhrasesRef.CODEC.fieldOf("phrases").forGetter(Variant::phrases),
                    LogicCodecs.CONDITION.optionalFieldOf("conditions", LogicConditions.ALWAYS).forGetter(Variant::conditions),
                    Codec.INT.optionalFieldOf("priority", 0).forGetter(Variant::priority)
            ).apply(instance, Variant::new));
        }
    }

    /**
     * The binding the removed {@code /storybound npc bind} stores on one entity; the first layer, over binding files,
     * giving what it has: its dialogue, whether a click starts it and, when on, the default proximity (November 7, 2026;
     * before, it was the whole NPC).
     */
    public record Attached(ResourceLocation dialogue, boolean interact, boolean proximity) {
        public static final Codec<Attached> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ResourceLocation.CODEC.fieldOf("dialogue").forGetter(Attached::dialogue),
                Codec.BOOL.optionalFieldOf("interact", true).forGetter(Attached::interact),
                Codec.BOOL.optionalFieldOf("proximity", false).forGetter(Attached::proximity)
        ).apply(instance, Attached::new));

        NpcBinding toBinding(Entity entity) {
            return new NpcBinding(List.of(EntitySelector.of(entity.getUUID())),
                    // Attached to the entity, so never rewritten: a renamed dialogue is looked up by its new id.
                    List.of(Entry.of(StoryMigrations.current().id(StoryMigrations.Kind.DIALOGUES, dialogue))), Optional.of(interact),
                    proximity ? Optional.of(Proximity.DEFAULT) : Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), List.of(), false, Optional.empty());
        }
    }
}
