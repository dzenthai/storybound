package dev.dzenthai.storybound.module.dialogue;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.dzenthai.storybound.core.text.StoryText;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.event.entity.living.LivingConversionEvent;
import org.jetbrains.annotations.Nullable;

/**
 * NPCs' names over their heads (November 6, 2026; names from a list November 7, 2026). What an entity is called comes
 * from the first binding of its chain that names it:
 * <ul>
 * <li>{@code display_name} of a binding by hand (a tag, a name, a uuid): the one NPC's name, always shown over its
 * head (unless {@code show_name} is off), each player reading it in their own language (sent as a translation key);
 * it is put back over a name tag, as the NPC is that one person. On a binding for every entity of a kind it names
 * nobody: it would name them all alike (the validator says so).</li>
 * <li>{@code names} of a binding for every entity of a kind: one of them given to each entity that has no name of its
 * own (a name tag, another mod's), the one least used among the entities within {@value #AROUND} blocks named from
 * that list (so a village has no two Agnas while the list lasts), then at random. It keeps it for life, while its
 * binding exists and still lists the name, also once it no longer matches that binding (a smith who lost his job).
 * Shown over the head only when looked at, so a village is not a field of name plates.</li>
 * </ul>
 * The mod remembers on the entity which name is its own doing ({@link Given}), and takes off only that one; a
 * player's name tag over a name from a list is the entity's own from then on, the list's forgotten. A mob turning into
 * another (a villager into a zombie villager) keeps its custom name in vanilla: the name the mod gave is taken off the
 * new one, so there is no zombie Miron. Before, an entity named by a binding carried the tag {@value #NAMED_TAG}
 * (still read in old worlds), and a plain text name could not be told from a name tag's.
 */
public final class NpcNames {
    private static final int CHECK_TICKS = 40;
    /** How far from a player NPCs are named: as far as a name over a head is seen. */
    private static final double REACH = 32;
    /** Around an entity given a name from a list, the others named from it are counted this far: about a village. */
    static final double AROUND = 64;
    /** Marked an entity whose name a binding gave, until November 7, 2026; read once and replaced by {@link Given}. */
    static final String NAMED_TAG = "storybound_named";

    private NpcNames() {}

    /**
     * What the mod gave an entity, kept with it (also through a respawn).
     *
     * @param node  the binding whose {@code names} gave its name from a list
     * @param name  that name
     * @param shown the custom name the mod put on it, to tell it from one put on by anyone else
     */
    public record Given(Optional<ResourceLocation> node, Optional<StoryText> name, Optional<Component> shown) {
        public static final Given NONE = new Given(Optional.empty(), Optional.empty(), Optional.empty());
        public static final Codec<Given> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ResourceLocation.CODEC.optionalFieldOf("node").forGetter(Given::node),
                StoryText.CODEC.optionalFieldOf("name").forGetter(Given::name),
                ComponentSerialization.CODEC.optionalFieldOf("shown").forGetter(Given::shown)
        ).apply(instance, Given::new));

        Given withListed(@Nullable ResourceLocation node, @Nullable StoryText name) {
            return new Given(Optional.ofNullable(node), Optional.ofNullable(name), shown);
        }

        Given withShown(@Nullable Component shown) {
            return new Given(node, name, Optional.ofNullable(shown));
        }
    }

    static void tick(MinecraftServer server) {
        if (server.getTickCount() % CHECK_TICKS != 0 || !Dialogues.isEnabled()) {
            return;
        }
        Set<Entity> seen = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            List<Entity> around = new java.util.ArrayList<>(player.level().getEntities(player, player.getBoundingBox().inflate(REACH),
                    candidate -> candidate instanceof LivingEntity && !(candidate instanceof Player)));
            // Nearest first: "one" picks the fitting entity nearest the player (November 9, 2026; before, the first the
            // level listed, maybe one behind a wall rather than the villager in front of them).
            around.sort(java.util.Comparator.comparingDouble(entity -> entity.distanceToSqr(player)));
            for (Entity entity : around) {
                if (seen.add(entity)) {
                    // A part picked first, so its holder is named as that NPC at once.
                    NpcParts.consider(entity);
                    apply(entity);
                }
            }
        }
    }

    /**
     * The name of the NPC the entity is, for dialogues, also while it is not shown over its head: its binding's
     * {@code display_name}, else the name a list gave it.
     */
    public static Optional<StoryText> nameOf(Entity entity) {
        Optional<StoryText> display = NpcBindings.find(entity).flatMap(found -> found.binding().displayName());
        return display.isPresent() ? display : listed(entity, given(entity));
    }

    /** Whether the binding knows its entities by their name: then their name is theirs, not the binding's to set. */
    static boolean byName(NpcBinding binding) {
        return binding.entities().stream().anyMatch(selector -> selector.name().isPresent());
    }

    /** Gives the entity its name, or takes back one the mod gave; public for tests. */
    public static void apply(Entity entity) {
        NpcBindings.Found found = NpcBindings.find(entity).orElse(null);
        if (found != null && found.chain().stream().anyMatch(layer -> byName(layer.binding()))) {
            // Known by the name it carries (a name tag's "Murka"): renaming it would lose it, and then name it back.
            return;
        }
        Given given = given(entity);
        Component current = entity.getCustomName();
        if (given.shown().isPresent() && !given.shown().get().equals(current)) {
            // Renamed by someone else (a name tag) or cleared: its own name now, and the list's forgotten.
            given = Given.NONE;
        }
        boolean ownName = current != null && given.shown().isEmpty();
        Component wanted = null;
        boolean always = false;
        Optional<StoryText> display = found != null ? found.binding().displayName() : Optional.empty();
        if (display.isPresent()) {
            // One NPC's name: put back over a name tag too, as before.
            wanted = found.showName() ? display.get().asComponent() : null;
            always = true;
        } else {
            Optional<StoryText> listed = listed(entity, given);
            if (listed.isEmpty() && !ownName && found != null && !found.binding().names().isEmpty()) {
                ResourceLocation node = found.source("names").map(NpcBindings.Layer::id).orElse(null);
                StoryText name = choose(entity, node, found.binding().names());
                given = given.withListed(node, name);
                listed = Optional.of(name);
            } else if (listed.isEmpty()) {
                given = given.withListed(null, null);
            }
            if (listed.isPresent() && !ownName && (found == null || found.showName())) {
                wanted = listed.get().asComponent();
            }
        }
        if (wanted != null) {
            if (!Objects.equals(current, wanted) || entity.isCustomNameVisible() != always) {
                entity.setCustomName(wanted);
                entity.setCustomNameVisible(always);
            }
            given = given.withShown(wanted);
        } else if (given.shown().isPresent()) {
            entity.setCustomName(null);
            entity.setCustomNameVisible(false);
            given = given.withShown(null);
        }
        if (given.equals(Given.NONE)) {
            entity.removeData(DialogueRegistries.NPC_NAME);
        } else {
            entity.setData(DialogueRegistries.NPC_NAME, given);
        }
    }

    /**
     * A villager turned into a zombie villager (or any mob into another) keeps its custom name in vanilla: a name the mod
     * gave is taken off the new one, which is no longer that NPC (a name tag's stays).
     */
    static void converted(LivingConversionEvent.Post event) {
        Optional<Component> shown = given(event.getEntity()).shown();
        LivingEntity outcome = event.getOutcome();
        if (shown.isPresent() && shown.get().equals(outcome.getCustomName())) {
            outcome.setCustomName(null);
            outcome.setCustomNameVisible(false);
        }
        outcome.removeData(DialogueRegistries.NPC_NAME);
    }

    /** What the mod gave the entity; a world from before November 7, 2026 marked it with a tag instead. */
    private static Given given(Entity entity) {
        if (entity.hasData(DialogueRegistries.NPC_NAME)) {
            return entity.getData(DialogueRegistries.NPC_NAME);
        }
        if (entity.getTags().contains(NAMED_TAG) && !entity.level().isClientSide()) {
            entity.removeTag(NAMED_TAG);
            // The tag marked a binding's name; a key was surely the binding's (a name tag writes plain text).
            Component current = entity.getCustomName();
            if (current != null && current.getContents() instanceof TranslatableContents) {
                return Given.NONE.withShown(current);
            }
        }
        return Given.NONE;
    }

    /** The name a list gave the entity, while its binding exists and still lists it. */
    private static Optional<StoryText> listed(Entity entity, Given given) {
        if (given.node().isEmpty() || given.name().isEmpty()) {
            return Optional.empty();
        }
        NpcBinding binding = DialogueRegistries.NPC_BINDINGS.serverStore().all().get(given.node().get());
        return binding != null && binding.names().contains(given.name().get()) ? given.name() : Optional.empty();
    }

    /** Of the names, one least used by the entities around named from the same binding; of those, one at random. */
    private static StoryText choose(Entity entity, @Nullable ResourceLocation node, List<StoryText> names) {
        Map<StoryText, Integer> used = new HashMap<>();
        names.forEach(name -> used.put(name, 0));
        for (Entity other : entity.level().getEntities(entity, entity.getBoundingBox().inflate(AROUND),
                candidate -> candidate.hasData(DialogueRegistries.NPC_NAME))) {
            Given theirs = other.getData(DialogueRegistries.NPC_NAME);
            if (theirs.node().isPresent() && theirs.node().get().equals(node) && theirs.name().isPresent()) {
                used.computeIfPresent(theirs.name().get(), (name, count) -> count + 1);
            }
        }
        int least = used.values().stream().mapToInt(Integer::intValue).min().orElse(0);
        List<StoryText> fewest = names.stream().distinct().filter(name -> used.get(name) == least).toList();
        return fewest.get(entity.getRandom().nextInt(fewest.size()));
    }
}
