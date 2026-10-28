package dev.dzenthai.storybound.core.logic.action;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.dzenthai.storybound.core.StoryException;
import dev.dzenthai.storybound.core.logic.Action;
import dev.dzenthai.storybound.core.logic.ActionType;
import dev.dzenthai.storybound.core.logic.StoryContext;
import dev.dzenthai.storybound.core.logic.StoryTrace;
import dev.dzenthai.storybound.core.text.StoryText;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;

/**
 * Actions that stage a scene without {@code run_command}, so the validator and the editor know what they do: an
 * entity appears (tagged, so a waypoint or an NPC binding can find it), someone is moved, an effect, a title on
 * screen, a sound at a place.
 */
public final class SceneActions {
    private SceneActions() {}

    /** Who an action is about: the player, or the context entity (the NPC talked to, the mob killed). */
    public enum Target implements StringRepresentable {
        PLAYER("player"),
        ENTITY("entity");

        public static final Codec<Target> CODEC = StringRepresentable.fromEnum(Target::values);

        private final String name;

        Target(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }

        Entity resolve(StoryContext context) throws StoryException {
            if (this == PLAYER) {
                return context.player();
            }
            if (context.entity() == null) {
                throw new StoryException(Component.literal("No entity here (an NPC, a killed mob or a dialogue partner) for \"entity\""));
            }
            return context.entity();
        }
    }

    /**
     * Where: {@code pos} (with {@code dimension}, else the player's), or {@code offset} from {@code at}, the player
     * by default or the context entity, in its dimension.
     */
    public record Place(Optional<Vec3> pos, Optional<ResourceKey<Level>> dimension, Target at, Vec3 offset) {
        static final Place HERE = new Place(Optional.empty(), Optional.empty(), Target.PLAYER, Vec3.ZERO);
        public static final MapCodec<Place> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Vec3.CODEC.optionalFieldOf("pos").forGetter(Place::pos),
                Level.RESOURCE_KEY_CODEC.optionalFieldOf("dimension").forGetter(Place::dimension),
                Target.CODEC.optionalFieldOf("at", Target.PLAYER).forGetter(Place::at),
                Vec3.CODEC.optionalFieldOf("offset", Vec3.ZERO).forGetter(Place::offset)
        ).apply(instance, Place::new));

        public record Resolved(ServerLevel level, Vec3 pos) {}

        public Resolved resolve(StoryContext context) throws StoryException {
            if (pos.isPresent()) {
                ServerLevel level = dimension.map(key -> context.server().getLevel(key)).orElse(context.level());
                if (level == null) {
                    throw new StoryException(Component.literal("Unknown dimension " + dimension.get().location()));
                }
                return new Resolved(level, pos.get().add(offset));
            }
            Entity anchor = at.resolve(context);
            return new Resolved((ServerLevel) anchor.level(), anchor.position().add(offset));
        }
    }

    /**
     * {@code { "type": "spawn_entity", "entity": "minecraft:zombie", "pos": [10, 64, 20], "tag": "mypack_kairen" }}:
     * {@code count} entities (1 by default) at the place, with the tags (a waypoint at the nearest tagged entity or an
     * NPC binding can find them), a custom {@code name}, NBT as {@code /summon} takes it, and kept from despawning
     * unless {@code persistent} is false.
     */
    public record SpawnEntity(EntityType<?> entity, int count, Place place, List<String> tags, Optional<StoryText> name, Optional<CompoundTag> nbt,
                              boolean persistent, boolean unique) implements Action {
        private static final Codec<List<String>> TAGS = Codec.either(Codec.STRING, Codec.STRING.listOf())
                .xmap(either -> either.map(List::of, list -> list), list -> list.size() == 1 ? Either.left(list.getFirst())
                        : Either.right(list));
        public static final MapCodec<SpawnEntity> CODEC = RecordCodecBuilder.<SpawnEntity>mapCodec(instance -> instance.group(
                BuiltInRegistries.ENTITY_TYPE.byNameCodec().fieldOf("entity").forGetter(SpawnEntity::entity),
                Codec.intRange(1, 64).optionalFieldOf("count", 1).forGetter(SpawnEntity::count),
                Place.MAP_CODEC.forGetter(SpawnEntity::place),
                TAGS.optionalFieldOf("tag", List.of()).forGetter(SpawnEntity::tags),
                StoryText.CODEC.optionalFieldOf("name").forGetter(SpawnEntity::name),
                CompoundTag.CODEC.optionalFieldOf("nbt").forGetter(SpawnEntity::nbt),
                Codec.BOOL.optionalFieldOf("persistent", true).forGetter(SpawnEntity::persistent),
                Codec.BOOL.optionalFieldOf("unique", false).forGetter(SpawnEntity::unique)
        ).apply(instance, SpawnEntity::new)).validate(spawn -> spawn.unique() && spawn.tags().isEmpty()
                ? DataResult.error(() -> "'unique' needs a 'tag': it is how the entity already there is recognised")
                : DataResult.success(spawn));

        @Override
        public ActionType<?> type() {
            return BuiltinActions.SPAWN_ENTITY.get();
        }

        @Override
        public void run(StoryContext context) throws StoryException {
            if (!entity.canSummon()) {
                throw new StoryException(Component.literal(BuiltInRegistries.ENTITY_TYPE.getKey(entity) + " cannot be spawned"));
            }
            Place.Resolved at = place.resolve(context);
            if (unique && exists(at.level())) {
                // Not a failure: the actions after it (a sound, a line) still run.
                StoryTrace.note(context.player(), "spawn_entity", "skipped: a " + BuiltInRegistries.ENTITY_TYPE.getKey(entity) + " tagged " + tags + " is already there");
                return;
            }
            for (int i = 0; i < count; i++) {
                CompoundTag tag = nbt.map(CompoundTag::copy).orElseGet(CompoundTag::new);
                tag.putString("id", BuiltInRegistries.ENTITY_TYPE.getKey(entity).toString());
                Entity spawned = EntityType.loadEntityRecursive(tag, at.level(), created -> {
                    created.moveTo(at.pos().x, at.pos().y, at.pos().z, created.getYRot(), created.getXRot());
                    return created;
                });
                if (spawned == null) {
                    throw new StoryException(Component.literal("Could not spawn " + BuiltInRegistries.ENTITY_TYPE.getKey(entity)));
                }
                if (spawned instanceof Mob mob) {
                    if (nbt.isEmpty()) {
                        // As /summon without NBT: equipment and variants the way the mob spawns naturally.
                        EventHooks.finalizeMobSpawn(mob, at.level(), at.level().getCurrentDifficultyAt(BlockPos.containing(at.pos())), MobSpawnType.EVENT, null);
                    }
                    if (persistent) {
                        mob.setPersistenceRequired();
                    }
                }
                tags.forEach(spawned::addTag);
                name.ifPresent(text -> spawned.setCustomName(text.resolve(context.player()).toComponent()));
                if (!at.level().tryAddFreshEntityWithPassengers(spawned)) {
                    throw new StoryException(Component.literal("Could not spawn " + BuiltInRegistries.ENTITY_TYPE.getKey(entity) + ": the same uuid exists"));
                }
            }
        }

        /**
         * Whether an entity of this type with all these tags is in the level: loaded, or somewhere a module keeps it
         * meanwhile (an NPC waiting to respawn). One in an unloaded chunk is not seen.
         */
        private boolean exists(ServerLevel level) {
            if (!level.getEntities(entity, candidate -> candidate.isAlive() && candidate.getTags().containsAll(tags)).isEmpty()) {
                return true;
            }
            return ELSEWHERE.stream().anyMatch(check -> check.holds(level, entity, tags));
        }
    }

    /** Where an entity can be while it is not in the level, for {@code spawn_entity}'s {@code unique}. */
    @FunctionalInterface
    public interface Elsewhere {
        boolean holds(ServerLevel level, EntityType<?> type, List<String> tags);
    }

    private static final List<Elsewhere> ELSEWHERE = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Lets a module say an entity exists out of the level (core cannot depend on modules). */
    public static void registerElsewhere(Elsewhere check) {
        ELSEWHERE.add(check);
    }

    /** {@code { "type": "teleport", "pos": [10, 64, 20], "dimension": "minecraft:the_nether", "yaw": 90 }}: the player, or the context entity. */
    public record Teleport(Target target, Place place, Optional<Float> yaw, Optional<Float> pitch) implements Action {
        public static final MapCodec<Teleport> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Target.CODEC.optionalFieldOf("target", Target.PLAYER).forGetter(Teleport::target),
                Place.MAP_CODEC.forGetter(Teleport::place),
                Codec.FLOAT.optionalFieldOf("yaw").forGetter(Teleport::yaw),
                Codec.FLOAT.optionalFieldOf("pitch").forGetter(Teleport::pitch)
        ).apply(instance, Teleport::new));

        @Override
        public ActionType<?> type() {
            return BuiltinActions.TELEPORT.get();
        }

        @Override
        public void run(StoryContext context) throws StoryException {
            Entity moved = target.resolve(context);
            Place.Resolved to = place.resolve(context);
            if (!moved.teleportTo(to.level(), to.pos().x, to.pos().y, to.pos().z, Set.of(), yaw.orElse(moved.getYRot()), pitch.orElse(moved.getXRot()))) {
                throw new StoryException(Component.literal("Could not teleport there"));
            }
        }
    }

    /**
     * {@code { "type": "effect", "effect": "minecraft:blindness", "duration": 5 }}: gives the effect for {@code duration}
     * seconds (30 by default, -1 forever) at {@code amplifier} (0 is level I); {@code "clear": true} takes it away.
     */
    public record Effect(Holder<MobEffect> effect, Target target, int duration, int amplifier, boolean particles, boolean clear) implements Action {
        public static final MapCodec<Effect> CODEC = RecordCodecBuilder.<Effect>mapCodec(instance -> instance.group(
                BuiltInRegistries.MOB_EFFECT.holderByNameCodec().fieldOf("effect").forGetter(Effect::effect),
                Target.CODEC.optionalFieldOf("target", Target.PLAYER).forGetter(Effect::target),
                Codec.intRange(-1, 1_000_000).optionalFieldOf("duration", 30).forGetter(Effect::duration),
                Codec.intRange(0, 255).optionalFieldOf("amplifier", 0).forGetter(Effect::amplifier),
                Codec.BOOL.optionalFieldOf("particles", true).forGetter(Effect::particles),
                Codec.BOOL.optionalFieldOf("clear", false).forGetter(Effect::clear)
        ).apply(instance, Effect::new)).validate(effect -> effect.duration() == 0
                ? DataResult.error(() -> "An effect needs a duration other than 0 (-1 is forever)")
                : DataResult.success(effect));

        @Override
        public ActionType<?> type() {
            return BuiltinActions.EFFECT.get();
        }

        @Override
        public void run(StoryContext context) throws StoryException {
            if (!(target.resolve(context) instanceof LivingEntity living)) {
                throw new StoryException(Component.literal("Effects need a living entity"));
            }
            if (clear) {
                living.removeEffect(effect);
                return;
            }
            int ticks = duration < 0 ? MobEffectInstance.INFINITE_DURATION : duration * 20;
            living.addEffect(new MobEffectInstance(effect, ticks, amplifier, false, particles, true));
        }
    }

    /**
     * {@code { "type": "title", "title": "mypack.title.chapter_two", "subtitle": "mypack.title.the_ash" }}: a title, a
     * subtitle and the action bar line for the player; times in ticks.
     */
    public record Title(Optional<StoryText> title, Optional<StoryText> subtitle, Optional<StoryText> actionbar, int fadeIn, int stay, int fadeOut)
            implements Action {
        public static final MapCodec<Title> CODEC = RecordCodecBuilder.<Title>mapCodec(instance -> instance.group(
                StoryText.CODEC.optionalFieldOf("title").forGetter(Title::title),
                StoryText.CODEC.optionalFieldOf("subtitle").forGetter(Title::subtitle),
                StoryText.CODEC.optionalFieldOf("actionbar").forGetter(Title::actionbar),
                Codec.intRange(0, 600).optionalFieldOf("fade_in", 10).forGetter(Title::fadeIn),
                Codec.intRange(0, 2400).optionalFieldOf("stay", 70).forGetter(Title::stay),
                Codec.intRange(0, 600).optionalFieldOf("fade_out", 20).forGetter(Title::fadeOut)
        ).apply(instance, Title::new)).validate(title -> title.title().isEmpty() && title.subtitle().isEmpty() && title.actionbar().isEmpty()
                ? DataResult.error(() -> "A title needs 'title', 'subtitle' or 'actionbar'")
                : DataResult.success(title));

        @Override
        public ActionType<?> type() {
            return BuiltinActions.TITLE.get();
        }

        @Override
        public void run(StoryContext context) {
            ServerPlayer player = context.player();
            player.connection.send(new ClientboundSetTitlesAnimationPacket(fadeIn, stay, fadeOut));
            // A subtitle shows only with a title; an empty title shows the subtitle alone.
            if (title.isPresent() || subtitle.isPresent()) {
                subtitle.ifPresent(text -> player.connection.send(new ClientboundSetSubtitleTextPacket(text.resolve(player).toComponent())));
                player.connection.send(new ClientboundSetTitleTextPacket(title.map(text -> text.resolve(player).toComponent()).orElse(Component.empty())));
            }
            actionbar.ifPresent(text -> player.connection.send(new ClientboundSetActionBarTextPacket(text.resolve(player).toComponent())));
        }
    }

    /**
     * {@code { "type": "sound_at", "sound": "minecraft:entity.wither.spawn", "pos": [10, 64, 20] }}: a sound at a place,
     * heard by everyone near it (unlike {@code play_sound}, which only the player hears).
     */
    public record SoundAt(ResourceLocation sound, Place place, SoundSource source, float volume, float pitch) implements Action {
        public static final MapCodec<SoundAt> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                ResourceLocation.CODEC.fieldOf("sound").forGetter(SoundAt::sound),
                Place.MAP_CODEC.forGetter(SoundAt::place),
                WorldActions.SOUND_SOURCE_CODEC.optionalFieldOf("source", SoundSource.MASTER).forGetter(SoundAt::source),
                Codec.floatRange(0, 10).optionalFieldOf("volume", 1f).forGetter(SoundAt::volume),
                Codec.floatRange(0.5f, 2f).optionalFieldOf("pitch", 1f).forGetter(SoundAt::pitch)
        ).apply(instance, SoundAt::new));

        @Override
        public ActionType<?> type() {
            return BuiltinActions.SOUND_AT.get();
        }

        @Override
        public void run(StoryContext context) throws StoryException {
            Place.Resolved at = place.resolve(context);
            at.level().playSound(null, at.pos().x, at.pos().y, at.pos().z, SoundEvent.createVariableRangeEvent(sound), source, volume, pitch);
        }
    }
}
