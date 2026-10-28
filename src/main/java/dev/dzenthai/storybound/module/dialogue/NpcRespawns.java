package dev.dzenthai.storybound.module.dialogue;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.logic.EntityReturns;
import dev.dzenthai.storybound.core.logic.action.SceneActions;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingExperienceDropEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * NPCs that come back ({@code "respawn": true} in a binding file that matches them): when such an entity dies or
 * disappears (despawned, discarded by a command or a mod, turned into another mob), a copy of it as it was — name,
 * look, equipment, trades, tags, uuid — comes back where it was, {@link #DELAY_TICKS} later, whole and unhurt.
 * Leaving with its chunk, for another dimension or because the server stops is not disappearing. Waiting NPCs are kept
 * in SavedData {@code storybound_npc_respawns} of the overworld, so a restart only pauses them; one whose place is not
 * loaded waits until it is, and one whose binding no longer says {@code respawn} when its time comes stays gone. A
 * respawning NPC drops no items and no experience when it dies, so it cannot be farmed.
 */
public final class NpcRespawns extends SavedData {
    private static final String NAME = "storybound_npc_respawns";
    /** How long after dying or disappearing the NPC comes back: 5 seconds of game time. */
    public static final int DELAY_TICKS = 100;
    private static final int CHECK_INTERVAL = 10;
    /** What the copy does not keep from the moment it was gone: it comes back still, unhurt and on its own. */
    private static final List<String> RESET = List.of("Health", "DeathTime", "HurtTime", "HurtByTimestamp", "Fire", "active_effects", "Motion",
            "FallDistance", "Air", "AbsorptionAmount", "Passengers", "leash");
    private static final Factory<NpcRespawns> FACTORY = new Factory<>(() -> new NpcRespawns(List.of()), NpcRespawns::load, null);

    /**
     * @param entity the entity's NBT with its id, at the place it was gone from
     * @param due    overworld game time when it comes back
     */
    record Pending(ResourceKey<Level> dimension, CompoundTag entity, long due) {
        static final Codec<Pending> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(Pending::dimension),
                CompoundTag.CODEC.fieldOf("entity").forGetter(Pending::entity),
                Codec.LONG.fieldOf("due").forGetter(Pending::due)
        ).apply(instance, Pending::new));
    }

    /**
     * Respawning NPCs as they were when they died: by the time the body leaves the level it has dropped its equipment
     * and may have slid or fallen during the death animation.
     */
    private static final Map<Entity, CompoundTag> AT_DEATH = new WeakHashMap<>();

    private final List<Pending> pending;

    private NpcRespawns(List<Pending> pending) {
        this.pending = new ArrayList<>(pending);
    }

    public static void register(IEventBus gameBus) {
        gameBus.addListener(EventPriority.LOWEST, NpcRespawns::onDeath);
        gameBus.addListener(NpcRespawns::onLeave);
        gameBus.addListener(NpcRespawns::onDrops);
        gameBus.addListener(NpcRespawns::onExperience);
        gameBus.addListener(NpcRespawns::onServerTick);
        // An NPC waiting to come back exists for spawn_entity's "unique": spawning another would leave two of it.
        SceneActions.registerElsewhere((level, type, tags) -> get(level.getServer()).pending.stream().anyMatch(pending ->
                pending.dimension().equals(level.dimension())
                        && pending.entity().getString("id").equals(EntityType.getKey(type).toString())
                        && pending.entity().getList("Tags", Tag.TAG_STRING).stream().map(Tag::getAsString).toList().containsAll(tags)));
        // And it is not gone for good: a waypoint following it keeps the place where it was seen.
        EntityReturns.register((server, uuid) -> get(server).pending.stream()
                .anyMatch(pending -> pending.entity().hasUUID("UUID") && pending.entity().getUUID("UUID").equals(uuid)));
    }

    private static NpcRespawns get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    /** How many NPCs wait to come back, for tests and tools. */
    public static int waiting(MinecraftServer server) {
        return get(server).pending.size();
    }

    /** NPCs carrying any of these scoreboard tags stop waiting: they will not come back. For tests and tools. */
    public static int forget(MinecraftServer server, List<String> tags) {
        NpcRespawns data = get(server);
        int before = data.pending.size();
        data.pending.removeIf(pending -> pending.entity().getList("Tags", Tag.TAG_STRING).stream().map(Tag::getAsString).anyMatch(tags::contains));
        if (data.pending.size() != before) {
            data.setDirty();
        }
        return before - data.pending.size();
    }

    private static void onDeath(LivingDeathEvent event) {
        if (!event.isCanceled() && !event.getEntity().level().isClientSide() && NpcBindings.respawns(event.getEntity())) {
            AT_DEATH.put(event.getEntity(), snapshot(event.getEntity()));
        }
    }

    private static void onLeave(EntityLeaveLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || event.getEntity() instanceof Player) {
            return;
        }
        Entity entity = event.getEntity();
        Entity.RemovalReason reason = entity.getRemovalReason();
        if ((reason != Entity.RemovalReason.KILLED && reason != Entity.RemovalReason.DISCARDED) || !entity.getType().canSerialize()
                || !NpcBindings.respawns(entity)) {
            return;
        }
        CompoundTag atDeath = AT_DEATH.remove(entity);
        CompoundTag tag = atDeath != null ? atDeath : snapshot(entity);
        NpcRespawns data = get(level.getServer());
        data.pending.add(new Pending(level.dimension(), tag, level.getServer().overworld().getGameTime() + DELAY_TICKS));
        data.setDirty();
        Storybound.LOGGER.debug("Storybound: NPC {} ({}) is gone ({}); it comes back in {} ticks", entity.getName().getString(), entity.getUUID(),
                reason, DELAY_TICKS);
    }

    // A story NPC that comes back would otherwise be a farm of whatever it carries.
    private static void onDrops(LivingDropsEvent event) {
        if (!event.getEntity().level().isClientSide() && NpcBindings.respawns(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    private static void onExperience(LivingExperienceDropEvent event) {
        if (!event.getEntity().level().isClientSide() && NpcBindings.respawns(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % CHECK_INTERVAL != 0) {
            return;
        }
        NpcRespawns data = get(server);
        if (data.pending.isEmpty()) {
            return;
        }
        long now = server.overworld().getGameTime();
        boolean changed = false;
        for (Iterator<Pending> it = data.pending.iterator(); it.hasNext(); ) {
            Pending next = it.next();
            if (next.due() > now) {
                continue;
            }
            ServerLevel level = server.getLevel(next.dimension());
            if (level != null && !level.isLoaded(position(next.entity()))) {
                // Nobody is near: it comes back once its place is loaded.
                continue;
            }
            it.remove();
            changed = true;
            if (level == null) {
                Storybound.LOGGER.warn("Storybound: an NPC cannot come back: the dimension {} is gone", next.dimension().location());
            } else {
                respawn(level, next.entity());
            }
        }
        if (changed) {
            data.setDirty();
        }
    }

    private static void respawn(ServerLevel level, CompoundTag tag) {
        UUID id = tag.hasUUID("UUID") ? tag.getUUID("UUID") : null;
        if (id != null && level.getEntity(id) != null) {
            return;
        }
        Entity entity = EntityType.loadEntityRecursive(tag.copy(), level, loaded -> loaded);
        if (entity == null) {
            Storybound.LOGGER.warn("Storybound: an NPC cannot come back: {} no longer loads", tag.getString("id"));
            return;
        }
        if (!NpcBindings.respawns(entity)) {
            // Its binding was changed or removed meanwhile: it stays gone.
            return;
        }
        if (entity instanceof LivingEntity living) {
            living.setHealth(living.getMaxHealth());
        }
        if (!level.tryAddFreshEntityWithPassengers(entity)) {
            Storybound.LOGGER.warn("Storybound: NPC {} cannot come back: its uuid {} is taken", entity.getName().getString(), entity.getUUID());
            return;
        }
        Storybound.LOGGER.debug("Storybound: NPC {} ({}) came back at {}", entity.getName().getString(), entity.getUUID(), entity.blockPosition());
    }

    /** The entity's NBT with its id, without what the copy must not keep ({@link #RESET}). */
    private static CompoundTag snapshot(Entity entity) {
        // saveWithoutId: Entity.save refuses an entity removed for good, which this one is when it leaves.
        CompoundTag tag = entity.saveWithoutId(new CompoundTag());
        tag.putString("id", EntityType.getKey(entity.getType()).toString());
        RESET.forEach(tag::remove);
        return tag;
    }

    private static BlockPos position(CompoundTag tag) {
        ListTag pos = tag.getList("Pos", Tag.TAG_DOUBLE);
        return pos.size() == 3 ? BlockPos.containing(pos.getDouble(0), pos.getDouble(1), pos.getDouble(2)) : BlockPos.ZERO;
    }

    private static NpcRespawns load(CompoundTag tag, HolderLookup.Provider registries) {
        List<Pending> list = Pending.CODEC.listOf().parse(NbtOps.INSTANCE, tag.get("pending"))
                .resultOrPartial(error -> Storybound.LOGGER.error("Storybound: failed to load the NPCs waiting to come back: {}", error))
                .orElse(List.of());
        return new NpcRespawns(list);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        Pending.CODEC.listOf().encodeStart(NbtOps.INSTANCE, pending)
                .resultOrPartial(error -> Storybound.LOGGER.error("Storybound: failed to save the NPCs waiting to come back: {}", error))
                .ifPresent(list -> tag.put("pending", list));
        return tag;
    }
}
