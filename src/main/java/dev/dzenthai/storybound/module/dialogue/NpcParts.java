package dev.dzenthai.storybound.module.dialogue;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.state.StoryMigrations;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import org.jetbrains.annotations.Nullable;

/**
 * "One, picked by the mod" (November 7, 2026, stage 4 of "Who an NPC is"): an NPC binding for every entity of a kind with
 * {@code "one": true} (an Elder: a grown villager in a village) is one entity only, picked when the mod first works out a
 * fitting one near a player (as names are put on, within 32 blocks); until then the part is empty. Never one a binding by
 * hand matches (Brann, a tagged elder of another story), nor one holding another part. One per world, kept in SavedData
 * {@value #NAME} of the overworld: binding → its holder's uuid, where it was last worked out, ended or not. The part is
 * the uuid: the binding's tag is put on the holder too (for vanilla selectors and {@code /data}), but a copy of the tag
 * on another entity does not make a second Elder. The filters pick him only once: he stays the Elder when he loses his
 * job or walks out of the village. Gone: with {@code respawn} (on unless the binding writes it off) he comes back as
 * himself ({@link NpcRespawns}); without, the part ends when he dies or is discarded, and is never given again.
 * Unloaded, in another dimension, a restart or a reload: the part stays his. A binding renamed in the editor: its part
 * moves with it (the migrations); deleted, or no longer "one": its part is dropped.
 */
public final class NpcParts extends SavedData {
    private static final String NAME = "storybound_npc_parts";
    private static final Factory<NpcParts> FACTORY = new Factory<>(NpcParts::new, NpcParts::load, null);
    /** Bumped on every load of the bindings: the parts are checked against them on their next use. */
    private static int bindingsGeneration;

    /**
     * Who holds a part.
     *
     * @param holder    the entity's uuid
     * @param dimension where it was last worked out
     * @param pos       and at which block
     * @param ended     it died or was discarded for good: the part is never given again
     */
    public record Part(UUID holder, ResourceKey<Level> dimension, BlockPos pos, boolean ended) {
        static final Codec<Part> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                UUIDUtil.STRING_CODEC.fieldOf("holder").forGetter(Part::holder),
                Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(Part::dimension),
                BlockPos.CODEC.fieldOf("pos").forGetter(Part::pos),
                Codec.BOOL.optionalFieldOf("ended", false).forGetter(Part::ended)
        ).apply(instance, Part::new));
    }

    private final Map<ResourceLocation, Part> parts = new LinkedHashMap<>();
    /**
     * Entities not loaded when a part left them (freed, its binding renamed or gone) → the tags to take off them when
     * they are next worked out (November 9, 2026: before, the tag stayed, and {@code @e[tag=...]} found two).
     */
    private final Map<UUID, List<String>> untag = new LinkedHashMap<>();
    private int generation = -1;

    private NpcParts() {}

    static void register(IEventBus gameBus) {
        gameBus.addListener(NpcParts::onLeave);
    }

    /** Called after every load of the bindings. */
    static void bindingsChanged() {
        bindingsGeneration++;
    }

    /** The parts, checked against the bindings loaded now. */
    private static NpcParts get(MinecraftServer server) {
        NpcParts data = server.overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
        if (data.generation != bindingsGeneration) {
            data.generation = bindingsGeneration;
            data.follow(server);
        }
        return data;
    }

    /**
     * Moves the parts of renamed bindings to their new ids; drops those of bindings gone or no longer "one". The tag of
     * the old id comes off the holder (now, or when it is next worked out), the new one goes on when it is worked out.
     */
    private void follow(MinecraftServer server) {
        Map<ResourceLocation, Part> next = new LinkedHashMap<>();
        parts.forEach((id, part) -> {
            ResourceLocation current = isOne(id) ? id : StoryMigrations.current().id(StoryMigrations.Kind.NPC_BINDINGS, id);
            if (!current.equals(id) || !isOne(current) || next.containsKey(current)) {
                takeTag(server, part.holder(), tagOf(id));
            }
            if (isOne(current) && !next.containsKey(current)) {
                next.put(current, part);
            } else {
                Storybound.LOGGER.debug("Storybound: the part of NPC {} is dropped: no such \"one\" NPC any more", id);
            }
        });
        if (!next.equals(parts)) {
            parts.clear();
            parts.putAll(next);
            setDirty();
        }
    }

    private static boolean isOne(ResourceLocation id) {
        NpcBinding binding = DialogueRegistries.NPC_BINDINGS.serverStore().all().get(id);
        return binding != null && binding.isOne();
    }

    /** The binding whose part the entity holds (not ended), if any. */
    public static Optional<ResourceLocation> partOf(Entity entity) {
        MinecraftServer server = entity.getServer();
        if (server == null) {
            return Optional.empty();
        }
        UUID uuid = entity.getUUID();
        return get(server).parts.entrySet().stream()
                .filter(entry -> !entry.getValue().ended() && entry.getValue().holder().equals(uuid))
                .map(Map.Entry::getKey).findFirst();
    }

    /** Who holds the binding's part; empty while nobody does (not picked yet, or ended). */
    public static Optional<Part> part(MinecraftServer server, ResourceLocation binding) {
        return Optional.ofNullable(get(server).parts.get(binding));
    }

    /** Every part, for {@code /storybound debug npc parts}. */
    public static Map<ResourceLocation, Part> all(MinecraftServer server) {
        return Map.copyOf(get(server).parts);
    }

    /** The tag a binding's entities carry: its name, a folder's "/" as "_" (as the editor writes it). */
    public static String tagOf(ResourceLocation binding) {
        return binding.getPath().replace('/', '_');
    }

    /**
     * An entity near a player, worked out: its part's place kept up to date, or, when it fits an empty part, picked for
     * it. Called where names are put on; public for tests.
     */
    public static void consider(Entity entity) {
        if (!(entity instanceof LivingEntity) || entity instanceof Player || !entity.isAlive() || !(entity.level() instanceof ServerLevel level)) {
            return;
        }
        NpcParts data = get(level.getServer());
        List<String> stale = data.untag.remove(entity.getUUID());
        if (stale != null) {
            // A part left it while it was not loaded: the tag goes now.
            stale.forEach(entity::removeTag);
            data.setDirty();
            NpcBindings.forget(entity);
        }
        Optional<ResourceLocation> held = partOf(entity);
        if (held.isPresent()) {
            Part part = data.parts.get(held.get());
            if (entity.addTag(tagOf(held.get()))) {
                // Its binding was renamed: the new name's tag (the old one came off above or at the rename).
                NpcBindings.forget(entity);
            }
            if (!part.dimension().equals(level.dimension()) || !part.pos().equals(entity.blockPosition())) {
                data.parts.put(held.get(), new Part(part.holder(), level.dimension(), entity.blockPosition(), false));
                data.setDirty();
            }
            return;
        }
        for (Map.Entry<ResourceLocation, NpcBinding> entry : NpcBindings.files()) {
            NpcBinding binding = entry.getValue();
            if (!binding.isOne() || data.parts.containsKey(entry.getKey()) || !binding.matches(entity)) {
                continue;
            }
            if (byHand(entity)) {
                // Someone's own NPC (Brann): never another part.
                return;
            }
            data.parts.put(entry.getKey(), new Part(entity.getUUID(), level.dimension(), entity.blockPosition(), false));
            data.setDirty();
            entity.addTag(tagOf(entry.getKey()));
            NpcBindings.forget(entity);
            Storybound.LOGGER.debug("Storybound: {} ({}) is now NPC {}", entity.getName().getString(), entity.getUUID(), entry.getKey());
            return;
        }
    }

    /** Takes a part's tag off the entity: now, if it is loaded, else when it is next worked out. */
    private void takeTag(MinecraftServer server, UUID holder, String tag) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(holder);
            if (entity != null) {
                entity.removeTag(tag);
                NpcBindings.forget(entity);
                return;
            }
        }
        untag.computeIfAbsent(holder, uuid -> new java.util.ArrayList<>()).add(tag);
        setDirty();
    }

    /**
     * The holder of a part did not come back after all (its binding no longer has it come back, its kind no longer
     * loads, its dimension is gone; November 9, 2026: before, the part stayed his for good, a waypoint led to his grave
     * and nobody else was ever picked): the part ends, as when it dies without coming back.
     */
    static void notBack(MinecraftServer server, UUID holder) {
        NpcParts data = get(server);
        data.parts.replaceAll((id, part) -> {
            if (part.ended() || !part.holder().equals(holder)) {
                return part;
            }
            data.setDirty();
            Storybound.LOGGER.debug("Storybound: NPC {} did not come back: its part has ended", id);
            return new Part(part.holder(), part.dimension(), part.pos(), true);
        });
    }

    /** Whether a binding by hand (or one stored on the entity) is for the entity. */
    private static boolean byHand(Entity entity) {
        return entity.hasData(DialogueRegistries.NPC_BINDING)
                || NpcBindings.files().stream().anyMatch(entry -> entry.getValue().byHand() && entry.getValue().matches(entity));
    }

    /**
     * Frees a part, to try a picking again: its tag is taken off the holder, now while it is loaded, else when it is next
     * worked out. For {@code /storybound debug npc part <npc> reset}, the form's button and tests.
     *
     * @return whether there was a part
     */
    public static boolean reset(MinecraftServer server, ResourceLocation binding) {
        NpcParts data = get(server);
        Part part = data.parts.remove(binding);
        if (part == null) {
            return false;
        }
        data.setDirty();
        data.takeTag(server, part.holder(), tagOf(binding));
        return true;
    }

    /** The holder dying or discarded (a command, turning into another mob) without coming back: the part ends. */
    private static void onLeave(EntityLeaveLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        Entity entity = event.getEntity();
        Entity.RemovalReason reason = entity.getRemovalReason();
        if (reason != Entity.RemovalReason.KILLED && reason != Entity.RemovalReason.DISCARDED) {
            return;
        }
        Optional<ResourceLocation> held = partOf(entity);
        if (held.isPresent() && !NpcBindings.respawns(entity)) {
            NpcParts data = get(level.getServer());
            Part part = data.parts.get(held.get());
            data.parts.put(held.get(), new Part(part.holder(), level.dimension(), entity.blockPosition(), true));
            data.setDirty();
            Storybound.LOGGER.debug("Storybound: NPC {} is gone for good ({}): its part has ended", held.get(), reason);
        }
    }

    private static NpcParts load(CompoundTag tag, HolderLookup.Provider registries) {
        NpcParts data = new NpcParts();
        Codec.unboundedMap(ResourceLocation.CODEC, Part.CODEC).parse(NbtOps.INSTANCE, tag.get("parts"))
                .resultOrPartial(error -> Storybound.LOGGER.error("Storybound: failed to load the NPCs' parts: {}", error))
                .ifPresent(data.parts::putAll);
        if (tag.contains("untag")) {
            UNTAG.parse(NbtOps.INSTANCE, tag.get("untag"))
                    .resultOrPartial(error -> Storybound.LOGGER.error("Storybound: failed to load the tags to take off NPCs: {}", error))
                    .ifPresent(map -> map.forEach((uuid, tags) -> data.untag.put(uuid, new java.util.ArrayList<>(tags))));
        }
        return data;
    }

    /** Entity uuid → tags to take off it. */
    private static final Codec<Map<UUID, List<String>>> UNTAG = Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.STRING.listOf());

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        Codec.unboundedMap(ResourceLocation.CODEC, Part.CODEC).encodeStart(NbtOps.INSTANCE, parts)
                .resultOrPartial(error -> Storybound.LOGGER.error("Storybound: failed to save the NPCs' parts: {}", error))
                .ifPresent(map -> tag.put("parts", map));
        if (!untag.isEmpty()) {
            UNTAG.encodeStart(NbtOps.INSTANCE, untag)
                    .resultOrPartial(error -> Storybound.LOGGER.error("Storybound: failed to save the tags to take off NPCs: {}", error))
                    .ifPresent(map -> tag.put("untag", map));
        }
        return tag;
    }

    /** Forgets every part; for tests. */
    public static void clearForTests(MinecraftServer server) {
        NpcParts data = get(server);
        data.parts.clear();
        data.untag.clear();
        data.setDirty();
    }

    /** Where the holder is or was last worked out, for a waypoint: null while nobody holds the part or it has ended. */
    public static @Nullable Part holding(MinecraftServer server, ResourceLocation binding) {
        Part part = get(server).parts.get(binding);
        return part == null || part.ended() ? null : part;
    }
}
