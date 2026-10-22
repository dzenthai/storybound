package dev.dzenthai.storybound.core.logic;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.dzenthai.storybound.Storybound;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Actions waiting to run ({@code wait}, {@code schedule}), in SavedData {@code storybound_scheduled} of the overworld,
 * so a restart only pauses them: they count game time, which stops while the server is down. Kept as the JSON they
 * are written in and read again when they run (after a /reload, the types and ids of that moment). A task waits for
 * its player to be online; the context entity is looked up again by uuid and may be gone by then.
 */
public final class ScheduledActions extends SavedData {
    private static final String NAME = "storybound_scheduled";
    private static final Factory<ScheduledActions> FACTORY = new Factory<>(() -> new ScheduledActions(List.of()), ScheduledActions::load, null);

    /**
     * @param due     overworld game time when it runs
     * @param actions the actions as JSON
     */
    record Task(UUID player, Optional<UUID> entity, String actions, String origin, OnFail onFail, long due) {
        static final Codec<Task> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                UUIDUtil.CODEC.fieldOf("player").forGetter(Task::player),
                UUIDUtil.CODEC.optionalFieldOf("entity").forGetter(Task::entity),
                Codec.STRING.fieldOf("actions").forGetter(Task::actions),
                Codec.STRING.fieldOf("origin").forGetter(Task::origin),
                OnFail.CODEC.fieldOf("on_fail").forGetter(Task::onFail),
                Codec.LONG.fieldOf("due").forGetter(Task::due)
        ).apply(instance, Task::new));
    }

    private final List<Task> tasks;

    private ScheduledActions(List<Task> tasks) {
        this.tasks = new ArrayList<>(tasks);
    }

    public static void register(IEventBus gameBus) {
        gameBus.addListener(ScheduledActions::onServerTick);
    }

    static ScheduledActions get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    /** Runs the actions for the context's player that many ticks of game time from now. */
    public static void schedule(StoryContext context, List<Action> actions, int ticks, String origin, OnFail onFail) {
        if (actions.isEmpty()) {
            return;
        }
        MinecraftServer server = context.server();
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
        Optional<JsonElement> json = LogicCodecs.ACTIONS.encodeStart(ops, actions)
                .resultOrPartial(error -> Storybound.LOGGER.error("Storybound: {}: could not keep the actions for later: {}", origin, error));
        if (json.isEmpty()) {
            return;
        }
        ScheduledActions data = get(server);
        long due = server.overworld().getGameTime() + ticks;
        data.tasks.add(new Task(context.player().getUUID(), Optional.ofNullable(context.entity()).map(Entity::getUUID), json.get().toString(),
                origin, onFail, due));
        data.setDirty();
        StoryTrace.note(context.player(), origin, actions.size() + " actions wait " + ticks + " ticks (until game time " + due + ")");
    }

    /** The tasks waiting for the player, for tests and tools. */
    public static int pending(MinecraftServer server, UUID player) {
        return (int) get(server).tasks.stream().filter(task -> task.player().equals(player)).count();
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        ScheduledActions data = get(server);
        if (data.tasks.isEmpty()) {
            return;
        }
        long now = server.overworld().getGameTime();
        List<Task> due = new ArrayList<>();
        for (Task task : data.tasks) {
            if (task.due() <= now && server.getPlayerList().getPlayer(task.player()) != null) {
                due.add(task);
            }
        }
        if (due.isEmpty()) {
            return;
        }
        // Removed first: an action that schedules again adds a new task.
        data.tasks.removeAll(due);
        data.setDirty();
        for (Task task : due) {
            run(server, task);
        }
    }

    private static void run(MinecraftServer server, Task task) {
        ServerPlayer player = server.getPlayerList().getPlayer(task.player());
        if (player == null) {
            return;
        }
        String origin = task.origin() + " (later)";
        List<Action> actions;
        try {
            actions = LogicCodecs.ACTIONS.parse(RegistryOps.create(JsonOps.INSTANCE, server.registryAccess()), JsonParser.parseString(task.actions()))
                    .getOrThrow();
        } catch (RuntimeException e) {
            // A type or id removed since (a /reload, a mod gone): the task cannot run any more.
            Storybound.LOGGER.error("Storybound: {} for {} can no longer run: {}", origin, player.getGameProfile().getName(), e.getMessage());
            StoryTrace.note(player, origin, "dropped: the actions no longer load: " + e.getMessage());
            return;
        }
        Actions.runAll(actions, new StoryContext(player, task.entity().map(id -> entity(server, id)).orElse(null)), origin, task.onFail());
    }

    private static @Nullable Entity entity(MinecraftServer server, UUID id) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(id);
            if (entity != null) {
                return entity;
            }
        }
        return null;
    }

    private static ScheduledActions load(CompoundTag tag, HolderLookup.Provider registries) {
        List<Task> list = Task.CODEC.listOf().parse(RegistryOps.create(NbtOps.INSTANCE, registries), tag.get("tasks"))
                .resultOrPartial(error -> Storybound.LOGGER.error("Storybound: failed to load scheduled actions: {}", error))
                .orElse(List.of());
        return new ScheduledActions(list);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        Task.CODEC.listOf().encodeStart(RegistryOps.create(NbtOps.INSTANCE, registries), tasks)
                .resultOrPartial(error -> Storybound.LOGGER.error("Storybound: failed to save scheduled actions: {}", error))
                .ifPresent(list -> tag.put("tasks", list));
        return tag;
    }
}
