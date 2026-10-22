package dev.dzenthai.storybound.module.dialogue;

import dev.dzenthai.storybound.config.StoryboundConfig;
import dev.dzenthai.storybound.core.data.DefinitionType;
import dev.dzenthai.storybound.core.data.ValidationIssue;
import dev.dzenthai.storybound.core.data.ValidationService;
import dev.dzenthai.storybound.core.event.DialogueEvent.EndReason;
import dev.dzenthai.storybound.core.logic.LogicOwners;
import dev.dzenthai.storybound.core.logic.References;
import dev.dzenthai.storybound.module.dialogue.DialogueDefinition.Node;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

public final class DialogueEvents {
    private DialogueEvents() {}

    public static void register(IEventBus gameBus) {
        gameBus.addListener(EventPriority.HIGH, DialogueEvents::onTagsUpdated);
        gameBus.addListener(DialogueEvents::onServerTick);
        gameBus.addListener(DialogueEvents::onLoggedIn);
        gameBus.addListener(DialogueEvents::onLoggedOut);
        gameBus.addListener(DialogueEvents::onDeath);
        gameBus.addListener(DialogueEvents::onDamage);
        gameBus.addListener(DialogueEvents::onBoundInteract);
        // LOWEST: a story event may have started the dialogue from this very click.
        gameBus.addListener(EventPriority.LOWEST, DialogueEvents::onEntityInteract);
        gameBus.addListener(DialogueEvents::onServerStopped);
        LogicOwners.register(DialogueRegistries.DIALOGUES, DialogueEvents::collectReferences);
        LogicOwners.register(DialogueRegistries.NPC_BINDINGS, (binding, references) -> {
            binding.dialogues().forEach(entry -> {
                references.dialogue(entry.dialogue());
                entry.conditions().collectReferences(references);
            });
            binding.busyDialogue().ifPresent(references::dialogue);
        });
        ValidationService.registerValidator(DialogueEvents::validate);
    }

    private static void onTagsUpdated(TagsUpdatedEvent event) {
        if (event.getUpdateCause() == TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) {
            NpcBindings.rebuild();
        }
    }

    private static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            NpcBindings.loggedIn(player);
        }
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        Dialogues.tick(event.getServer());
        NpcBindings.tick(event.getServer());
    }

    private static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            Dialogues.interrupt(player, EndReason.LOGGED_OUT);
            NpcBindings.forget(player);
        }
    }

    private static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            Dialogues.interrupt(player, EndReason.DIED);
        }
    }

    private static void onDamage(LivingDamageEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player && event.getNewDamage() > 0 && StoryboundConfig.COMMON.dialogueInterruptOnDamage.get()) {
            Dialogues.interrupt(player, EndReason.DAMAGED);
        }
    }

    // Talking is on its own key, so right-click keeps trading and mods' own screens; a server may also allow right-click.
    private static void onBoundInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getHand() == InteractionHand.MAIN_HAND && event.getEntity() instanceof ServerPlayer player) {
            if (NpcBindings.rightClick(player, event.getTarget())) {
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.SUCCESS);
            }
        }
    }

    // Right-clicking the NPC you talk to must not also open its trades.
    private static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getEntity() instanceof ServerPlayer player && Dialogues.isTalkingTo(player, event.getTarget())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        Dialogues.reset();
        NpcBindings.reset();
    }

    /** Walks the conditions and actions of every node, option and {@code on_abort}. */
    public static void collectReferences(DialogueDefinition dialogue, References references) {
        dialogue.onAbort().forEach(action -> action.collectReferences(references));
        for (Node node : dialogue.nodes().values()) {
            switch (node) {
                case DialogueDefinition.Choice choice -> choice.options().forEach(option -> {
                    option.conditions().collectReferences(references);
                    option.actions().forEach(action -> action.collectReferences(references));
                });
                case DialogueDefinition.Branch branch -> branch.conditions().collectReferences(references);
                case DialogueDefinition.Act act -> act.actions().forEach(action -> action.collectReferences(references));
                case DialogueDefinition.End end -> end.actions().forEach(action -> action.collectReferences(references));
                default -> {}
            }
        }
    }

    /**
     * Each dialogue's graph (start node, targets, speakers, jumps and calls into other dialogues,
     * unreachable nodes, loops that never show anything) and references to dialogues everywhere.
     */
    private static void validate(Consumer<ValidationIssue> sink) {
        DefinitionType<DialogueDefinition> type = DialogueRegistries.DIALOGUES;
        type.serverStore().all().forEach((id, dialogue) -> {
            Consumer<String> error = message -> sink.accept(ValidationIssue.error(type.id(), id, message));
            Map<String, Node> nodes = dialogue.nodes();
            if (!nodes.containsKey(dialogue.start())) {
                error.accept("Start node '" + dialogue.start() + "' does not exist");
            }
            long bound = dialogue.participants().values().stream().filter(p -> p.kind() == DialogueDefinition.Participant.Kind.BOUND).count();
            if (bound > 1) {
                error.accept("Only one participant can be 'bound' (the entity the dialogue was started with)");
            }
            if (dialogue.participants().containsKey(DialogueDefinition.PLAYER)) {
                error.accept("'player' is reserved for the player and cannot be a participant id");
            }
            nodes.forEach((nodeId, node) -> {
                node.targets(target -> {
                    if (!DialogueDefinition.isSentinel(target) && !nodes.containsKey(target)) {
                        error.accept("Node '" + nodeId + "' leads to unknown node '" + target + "'");
                    }
                });
                switch (node) {
                    case DialogueDefinition.Line line -> {
                        if (!line.speaker().equals(DialogueDefinition.PLAYER) && !dialogue.participants().containsKey(line.speaker())) {
                            error.accept("Node '" + nodeId + "' has unknown speaker '" + line.speaker() + "'");
                        }
                    }
                    case DialogueDefinition.Choice choice -> {
                        if (choice.options().isEmpty()) {
                            error.accept("Choice '" + nodeId + "' has no options");
                        }
                        Set<String> optionIds = new HashSet<>();
                        for (int i = 0; i < choice.options().size(); i++) {
                            if (!optionIds.add(choice.optionId(i))) {
                                error.accept("Choice '" + nodeId + "' has duplicate option id '" + choice.optionId(i) + "'");
                            }
                        }
                    }
                    case DialogueDefinition.Jump jump -> jump.dialogue().ifPresent(other -> checkEntry(other, jump.node().orElse(null), nodeId, error));
                    case DialogueDefinition.Call call -> checkEntry(call.dialogue(), call.node().orElse(null), nodeId, error);
                    default -> {}
                }
            });
            for (String unreachable : unreachable(dialogue)) {
                sink.accept(ValidationIssue.warning(type.id(), id, "Node '" + unreachable + "' is never reached"));
            }
            List<String> loop = silentLoop(nodes);
            if (!loop.isEmpty()) {
                sink.accept(ValidationIssue.warning(type.id(), id, "Nodes " + loop + " can loop without showing anything; the server stops such a dialogue"));
            }
        });
        DialogueRegistries.NPC_BINDINGS.serverStore().all().forEach((id, binding) -> {
            if (binding.dialogues().isEmpty() && !binding.custom()) {
                sink.accept(ValidationIssue.warning(DialogueRegistries.NPC_BINDINGS.id(), id, "Binds no dialogue and is not custom; it does nothing"));
            }
        });
        LogicOwners.walkAll((owner, ownerId) -> new References() {
            @Override
            public void dialogue(ResourceLocation dialogue) {
                if (!type.serverStore().contains(dialogue)) {
                    sink.accept(ValidationIssue.error(owner.id(), ownerId, "Unknown dialogue " + dialogue));
                }
            }
        });
    }

    private static void checkEntry(ResourceLocation other, String node, String from, Consumer<String> error) {
        DialogueDefinition target = DialogueRegistries.DIALOGUES.serverStore().getOrNull(other);
        if (target == null) {
            error.accept("Node '" + from + "' goes to unknown dialogue " + other);
        } else if (node != null && !target.nodes().containsKey(node)) {
            error.accept("Node '" + from + "' goes to unknown node '" + node + "' of dialogue " + other);
        }
    }

    private static Set<String> unreachable(DialogueDefinition dialogue) {
        Set<String> reached = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        if (dialogue.nodes().containsKey(dialogue.start())) {
            queue.add(dialogue.start());
        }
        while (!queue.isEmpty()) {
            String nodeId = queue.poll();
            Node node = dialogue.nodes().get(nodeId);
            if (node == null || !reached.add(nodeId)) {
                continue;
            }
            node.targets(queue::add);
        }
        Set<String> result = new LinkedHashSet<>(dialogue.nodes().keySet());
        result.removeAll(reached);
        return result;
    }

    /**
     * A cycle made only of nodes that show nothing (conditions, actions, random, local jumps) could spin
     * forever; returns one such cycle, or an empty list. Calls are not followed: the called dialogue shows something.
     */
    private static List<String> silentLoop(Map<String, Node> nodes) {
        Map<String, Integer> state = new HashMap<>();
        for (String nodeId : nodes.keySet()) {
            List<String> path = new ArrayList<>();
            if (findLoop(nodeId, nodes, state, path)) {
                return path;
            }
        }
        return List.of();
    }

    // state: 1 = on the current path, 2 = finished without a loop
    private static boolean findLoop(String nodeId, Map<String, Node> nodes, Map<String, Integer> state, List<String> path) {
        Node node = nodes.get(nodeId);
        if (node == null || node.visible() || node instanceof DialogueDefinition.Call || state.getOrDefault(nodeId, 0) == 2) {
            return false;
        }
        if (state.getOrDefault(nodeId, 0) == 1) {
            path.subList(0, path.indexOf(nodeId)).clear();
            return true;
        }
        state.put(nodeId, 1);
        path.add(nodeId);
        List<String> targets = new ArrayList<>();
        node.targets(targets::add);
        for (String target : targets) {
            if (findLoop(target, nodes, state, path)) {
                return true;
            }
        }
        path.removeLast();
        state.put(nodeId, 2);
        return false;
    }
}
