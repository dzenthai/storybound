package dev.dzenthai.storybound.module.dialogue;

import dev.dzenthai.storybound.core.text.StoryText;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * An NPC binding's name over its NPC (November 6, 2026; before, a tagged villager showed nothing, the name came only
 * with a spawn's own NBT): with {@code show_name}, every entity the binding matches near a player carries its
 * {@code display_name} as its name, shown over its head, each player reading it in their own language (the name is
 * sent as a translation key). An entity named so is marked; when its binding no longer shows a name (turned off, the
 * binding gone or matching it no more), a name given as a key is taken off again; a plain text one stays, as a name
 * tag's does (they cannot be told apart).
 */
public final class NpcNames {
    private static final int CHECK_TICKS = 40;
    /** How far from a player NPCs are named: as far as a name over a head is seen. */
    private static final double REACH = 32;
    /** Marks an entity whose name a binding gave. */
    static final String NAMED_TAG = "storybound_named";

    private NpcNames() {}

    static void tick(MinecraftServer server) {
        if (server.getTickCount() % CHECK_TICKS != 0 || !Dialogues.isEnabled()) {
            return;
        }
        Set<Entity> seen = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            for (Entity entity : player.level().getEntities(player, player.getBoundingBox().inflate(REACH),
                    candidate -> candidate instanceof LivingEntity && !(candidate instanceof Player))) {
                if (seen.add(entity)) {
                    apply(entity);
                }
            }
        }
    }

    /** The name of the NPC the entity is: its binding's {@code display_name}, shown over its head or not. */
    public static Optional<StoryText> nameOf(Entity entity) {
        return NpcBindings.find(entity).flatMap(found -> found.binding().displayName());
    }

    /** Whether the binding knows its entities by their name: then their name is theirs, not the binding's to set. */
    static boolean byName(NpcBinding binding) {
        return binding.entities().stream().anyMatch(selector -> selector.name().isPresent());
    }

    /** Gives the entity its binding's name, or takes back one a binding gave; public for tests. */
    public static void apply(Entity entity) {
        NpcBinding binding = NpcBindings.find(entity).map(NpcBindings.Found::binding).orElse(null);
        if (binding != null && byName(binding)) {
            // Known by the name it carries (a name tag's "Murka"): renaming it would lose it, and then name it back.
            return;
        }
        Component name = binding != null && binding.showName() ? binding.displayName().map(text -> text.asComponent()).orElse(null) : null;
        if (name != null) {
            if (!Objects.equals(entity.getCustomName(), name) || !entity.isCustomNameVisible()) {
                entity.setCustomName(name);
                entity.setCustomNameVisible(true);
            }
            entity.addTag(NAMED_TAG);
        } else if (entity.getTags().contains(NAMED_TAG)) {
            entity.removeTag(NAMED_TAG);
            // A key is the binding's (a name tag writes plain text, which stays); a plain name the binding gave stays too.
            if (entity.getCustomName() != null && entity.getCustomName().getContents() instanceof TranslatableContents) {
                entity.setCustomName(null);
                entity.setCustomNameVisible(false);
            }
        }
    }
}
