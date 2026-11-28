package dev.dzenthai.storybound.module.dialogue;

import dev.dzenthai.storybound.core.CoreDefinitions;
import dev.dzenthai.storybound.core.event.DialogueEvent;
import dev.dzenthai.storybound.core.event.StoryValueChangedEvent;
import dev.dzenthai.storybound.core.state.FlagDeclaration;
import dev.dzenthai.storybound.core.state.StoryState;
import dev.dzenthai.storybound.core.state.StoryValue;
import dev.dzenthai.storybound.core.state.StoryValueType;
import dev.dzenthai.storybound.core.story.StoryRenames;
import dev.dzenthai.storybound.network.ReputationPayload;
import dev.dzenthai.storybound.network.StoryboundNetwork;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.jetbrains.annotations.Nullable;

/**
 * A player's standing with an NPC on the HUD: an NPC's relationship scale ({@code <ns>:rep/<npc>}, a number flag of the
 * player's, -50 to 50) shows a star for every 10 above 0, a skull for every 10 below, five at most. It shows for a few
 * seconds when it changes, and all the while the player talks with that NPC. The server says which scale and whose
 * name; the client draws it ({@code ReputationHud}).
 */
public final class NpcReputation {
    public static final int MIN = -50;
    public static final int MAX = 50;
    /** Points a star or a skull. */
    public static final int STEP = 10;

    /** The NPC a player talks with now, for naming its scale when it changes: the scale and the speaker's name. */
    private record Focus(ResourceLocation scale, Component name) {}

    private static final Map<UUID, Focus> FOCUS = new HashMap<>();

    private NpcReputation() {}

    public static void register(IEventBus gameBus) {
        gameBus.addListener(NpcReputation::onValueChanged);
        gameBus.addListener(NpcReputation::onDialogueStarted);
        gameBus.addListener(NpcReputation::onDialogueEnded);
        gameBus.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> FOCUS.remove(event.getEntity().getUUID()));
        gameBus.addListener((ServerStoppedEvent event) -> FOCUS.clear());
    }

    /** Whether the flag is an NPC's relationship scale: {@code rep/<npc>}, declared, a number. */
    public static boolean isScale(ResourceLocation key) {
        if (!key.getPath().startsWith(StoryRenames.RELATIONSHIP_PREFIX)) {
            return false;
        }
        FlagDeclaration declaration = CoreDefinitions.FLAGS.serverStore().getOrNull(key);
        return declaration != null && declaration.type() == StoryValueType.INT;
    }

    /** The scale of the entity's NPC: of the closest of its bindings that has one; empty for an entity no NPC. */
    public static Optional<ResourceLocation> scaleOf(Entity entity) {
        return NpcBindings.find(entity).flatMap(found -> found.chain().stream()
                .filter(layer -> layer.id() != null)
                .map(layer -> StoryRenames.relationshipScale(layer.id()))
                .filter(NpcReputation::isScale)
                .findFirst());
    }

    /**
     * Whose scale it is, in the player's language: the NPC talked to now if it is theirs; else the NPC's own name
     * ({@code display_name}); else its node's name, made readable ("old_miller" is "Old miller").
     */
    public static Component nameOf(ServerPlayer player, ResourceLocation scale) {
        Focus focus = FOCUS.get(player.getUUID());
        if (focus != null && focus.scale().equals(scale)) {
            return focus.name();
        }
        ResourceLocation npc = ResourceLocation.fromNamespaceAndPath(scale.getNamespace(),
                scale.getPath().substring(StoryRenames.RELATIONSHIP_PREFIX.length()));
        NpcBinding binding = DialogueRegistries.NPC_BINDINGS.serverStore().getOrNull(npc);
        if (binding != null && binding.displayName().isPresent()) {
            return binding.displayName().get().resolve(player).toComponent();
        }
        String words = npc.getPath().replace('_', ' ');
        return Component.literal(words.isEmpty() ? words : words.substring(0, 1).toUpperCase(Locale.ROOT) + words.substring(1));
    }

    /** A scale changed: everyone whose value it is (the player; a party's members if an author made it a party's) sees it. */
    private static void onValueChanged(StoryValueChangedEvent event) {
        if (!isScale(event.getKey())) {
            return;
        }
        int before = number(event.getOldValue(), event.getKey());
        int after = number(event.getNewValue(), event.getKey());
        if (before == after) {
            return;
        }
        for (ServerPlayer player : StoryState.onlinePlayersOf(event.getServer(), event.getScope(), event.getOwner())) {
            StoryboundNetwork.sendToPlayer(player, new ReputationPayload(Optional.of(event.getKey()), nameOf(player, event.getKey()),
                    after, after - before, false));
        }
    }

    /** Talking with an NPC that has a scale: it shows all the while. */
    private static void onDialogueStarted(DialogueEvent.Started event) {
        Entity speaker = event.getSpeaker();
        Optional<ResourceLocation> scale = speaker == null ? Optional.empty() : scaleOf(speaker);
        ServerPlayer player = event.getPlayer();
        if (scale.isEmpty()) {
            return;
        }
        Component name = speaker.getDisplayName();
        FOCUS.put(player.getUUID(), new Focus(scale.get(), name));
        int value = number(StoryState.get(player, StoryState.declaredScope(scale.get()), scale.get()), scale.get());
        StoryboundNetwork.sendToPlayer(player, new ReputationPayload(scale, name, value, 0, true));
    }

    private static void onDialogueEnded(DialogueEvent.Ended event) {
        ServerPlayer player = event.getPlayer();
        if (FOCUS.remove(player.getUUID()) != null) {
            StoryboundNetwork.sendToPlayer(player, new ReputationPayload(Optional.empty(), Component.empty(), 0, 0, true));
        }
    }

    /** The scale's number, the default when nothing is written, within its bounds. */
    private static int number(Optional<StoryValue> value, ResourceLocation key) {
        StoryValue shown = value.orElseGet(() -> {
            FlagDeclaration declaration = CoreDefinitions.FLAGS.serverStore().getOrNull(key);
            return declaration != null ? declaration.defaultValue() : new StoryValue.Int(0);
        });
        return shown instanceof StoryValue.Int(int number) ? number : 0;
    }

    /** Stars (above 0) or skulls (below) the value shows, 0 to 5: one for every {@link #STEP}, toward zero. */
    public static int symbols(int value) {
        return Math.min(5, Math.abs(Math.max(MIN, Math.min(MAX, value))) / STEP);
    }

    /** For tests: the scale a player talks about now. */
    public static @Nullable ResourceLocation focus(ServerPlayer player) {
        Focus focus = FOCUS.get(player.getUUID());
        return focus == null ? null : focus.scale();
    }
}
