package dev.dzenthai.storybound.module.scene;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.dzenthai.storybound.core.logic.Action;
import dev.dzenthai.storybound.core.logic.EntitySelector;
import dev.dzenthai.storybound.core.logic.LogicCodecs;
import dev.dzenthai.storybound.core.logic.StructureRef;
import dev.dzenthai.storybound.core.logic.action.ScreenFade;
import dev.dzenthai.storybound.core.text.StoryText;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.phys.Vec3;

/**
 * A scene ({@code scenes/<namespace>/<name>.json}): shots one after another, each a camera flight from one point to
 * another placed against an anchor (the player, an NPC, an entity a selector finds, a structure, a waypoint), and what
 * happens meanwhile at given seconds: a title as a quest's on screen, a line in subtitles with its voice-over, a sound,
 * a fade, actions. The player watches it whole: by default nothing hurts them, mobs near leave them and the villagers
 * alone and no monster spawns there ({@code world: calm}), and it cannot be skipped. {@code on_end} runs when it is
 * over.
 *
 * @param letterbox black bars above and below while it plays
 * @param invulnerable nothing hurts the watcher but the void and {@code /kill}
 * @param world {@code calm}: mobs near do nothing to the watcher and villagers, no monster spawns near; {@code live}: the world goes on
 */
public record SceneDefinition(List<Shot> shots, boolean letterbox, boolean invulnerable, World world, List<Action> onEnd) {
    public static final Codec<SceneDefinition> CODEC = RecordCodecBuilder.<SceneDefinition>create(instance -> instance.group(
            Shot.CODEC.listOf().fieldOf("shots").forGetter(SceneDefinition::shots),
            Codec.BOOL.optionalFieldOf("letterbox", false).forGetter(SceneDefinition::letterbox),
            Codec.BOOL.optionalFieldOf("invulnerable", true).forGetter(SceneDefinition::invulnerable),
            World.CODEC.optionalFieldOf("world", World.CALM).forGetter(SceneDefinition::world),
            LogicCodecs.ACTIONS.optionalFieldOf("on_end", List.of()).forGetter(SceneDefinition::onEnd)
    ).apply(instance, SceneDefinition::new)).validate(scene -> scene.shots().isEmpty()
            ? DataResult.error(() -> "A scene needs at least one shot")
            : DataResult.success(scene));

    public enum World implements StringRepresentable {
        CALM("calm"),
        LIVE("live");

        public static final Codec<World> CODEC = StringRepresentable.fromEnum(World::values);
        private final String name;

        World(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    /** How a flight speeds up and slows down. */
    public enum Ease implements StringRepresentable {
        LINEAR("linear"),
        IN("in"),
        OUT("out"),
        IN_OUT("in_out");

        public static final Codec<Ease> CODEC = StringRepresentable.fromEnum(Ease::values);
        private final String name;

        Ease(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }

        /** The share of the way gone at {@code t} of the time (both 0 to 1): smooth, no jump at either end. */
        public double apply(double t) {
            double x = Math.max(0, Math.min(1, t));
            return switch (this) {
                case LINEAR -> x;
                case IN -> x * x;
                case OUT -> 1 - (1 - x) * (1 - x);
                case IN_OUT -> x * x * (3 - 2 * x);
            };
        }
    }

    /** How a shot follows the one before it: a cut, a fade through black, or a glide from where the camera was. */
    public enum Transition implements StringRepresentable {
        CUT("cut"),
        FADE("fade"),
        EASE("ease");

        public static final Codec<Transition> CODEC = StringRepresentable.fromEnum(Transition::values);
        private final String name;

        Transition(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    /**
     * What points are placed against. {@code "player"}; {@code { "npc": "mypack:elder" }}; {@code { "entity": { "tag":
     * "x" } }} (the nearest such within 64 blocks); {@code { "structure": "#minecraft:village" }} (the one the player is
     * in, else the nearest one waypoints have found); {@code { "waypoint": "mypack:camp" }} (where the player sees it).
     */
    public sealed interface Anchor {
        Codec<Anchor> CODEC = Codec.either(Codec.STRING.comapFlatMap(text -> text.equals("player")
                        ? DataResult.success(new PlayerAnchor()) : DataResult.error(() -> "An anchor is \"player\" or an object, got " + text), anchor -> "player"),
                ObjectAnchor.CODEC).xmap(either -> either.map(player -> (Anchor) player, object -> object.anchor()),
                anchor -> anchor instanceof PlayerAnchor player ? Either.left(player) : Either.right(ObjectAnchor.of(anchor)));
    }

    public record PlayerAnchor() implements Anchor {}

    public record NpcAnchor(ResourceLocation npc) implements Anchor {}

    public record EntityAnchor(EntitySelector entity) implements Anchor {}

    public record StructureAnchor(StructureRef structure) implements Anchor {}

    public record WaypointAnchor(ResourceLocation waypoint) implements Anchor {}

    /** The object form of an anchor: exactly one of its fields. */
    private record ObjectAnchor(Optional<ResourceLocation> npc, Optional<EntitySelector> entity, Optional<StructureRef> structure,
                                Optional<ResourceLocation> waypoint) {
        static final Codec<ObjectAnchor> CODEC = RecordCodecBuilder.<ObjectAnchor>create(instance -> instance.group(
                ResourceLocation.CODEC.optionalFieldOf("npc").forGetter(ObjectAnchor::npc),
                EntitySelector.CODEC.optionalFieldOf("entity").forGetter(ObjectAnchor::entity),
                StructureRef.CODEC.optionalFieldOf("structure").forGetter(ObjectAnchor::structure),
                ResourceLocation.CODEC.optionalFieldOf("waypoint").forGetter(ObjectAnchor::waypoint)
        ).apply(instance, ObjectAnchor::new)).validate(object -> (object.npc.isPresent() ? 1 : 0) + (object.entity.isPresent() ? 1 : 0)
                + (object.structure.isPresent() ? 1 : 0) + (object.waypoint.isPresent() ? 1 : 0) == 1
                ? DataResult.success(object)
                : DataResult.error(() -> "An anchor names exactly one of npc, entity, structure or waypoint"));

        Anchor anchor() {
            if (npc.isPresent()) {
                return new NpcAnchor(npc.get());
            }
            if (entity.isPresent()) {
                return new EntityAnchor(entity.get());
            }
            return structure.<Anchor>map(StructureAnchor::new).orElseGet(() -> new WaypointAnchor(waypoint.orElseThrow()));
        }

        static ObjectAnchor of(Anchor anchor) {
            return switch (anchor) {
                case NpcAnchor npc -> new ObjectAnchor(Optional.of(npc.npc()), Optional.empty(), Optional.empty(), Optional.empty());
                case EntityAnchor entity -> new ObjectAnchor(Optional.empty(), Optional.of(entity.entity()), Optional.empty(), Optional.empty());
                case StructureAnchor structure -> new ObjectAnchor(Optional.empty(), Optional.empty(), Optional.of(structure.structure()), Optional.empty());
                case WaypointAnchor waypoint -> new ObjectAnchor(Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(waypoint.waypoint()));
                case PlayerAnchor player -> throw new IllegalArgumentException("the player is written as a string");
            };
        }
    }

    /**
     * Where the camera looks: {@code "anchor"} (the shot's anchor, followed when it is an entity), {@code "path"} (the
     * way it flies), or another anchor.
     */
    public sealed interface Look {
        Codec<Look> CODEC = Codec.either(Codec.STRING.comapFlatMap(text -> switch (text) {
                    case "anchor" -> DataResult.success((Look) new AtAnchor());
                    case "path" -> DataResult.success((Look) new AlongPath());
                    case "player" -> DataResult.success((Look) new AtOther(new PlayerAnchor()));
                    default -> DataResult.error(() -> "look is \"anchor\", \"path\", \"player\" or an anchor, got " + text);
                }, look -> look instanceof AlongPath ? "path" : look instanceof AtOther other && other.anchor() instanceof PlayerAnchor ? "player" : "anchor"),
                ObjectAnchor.CODEC).xmap(either -> either.map(look -> look, object -> new AtOther(object.anchor())),
                look -> look instanceof AtOther other && !(other.anchor() instanceof PlayerAnchor) ? Either.right(ObjectAnchor.of(other.anchor())) : Either.left(look));
    }

    public record AtAnchor() implements Look {}

    public record AlongPath() implements Look {}

    public record AtOther(Anchor anchor) implements Look {}

    /**
     * The flight: {@code from} and {@code to} are offsets from the anchor (right, up, forward, as the anchor faces; a
     * structure faces south), flown in {@code duration} seconds or at {@code speed} blocks a second, one of them.
     */
    public record Camera(Vec3 from, Vec3 to, Look look, Optional<Float> duration, Optional<Float> speed, Ease ease) {
        /** The longest shot: a scene is watched, not waited out. */
        public static final float MAX_SECONDS = 120;
        public static final MapCodec<Camera> MAP_CODEC = RecordCodecBuilder.<Camera>mapCodec(instance -> instance.group(
                Vec3.CODEC.fieldOf("from").forGetter(Camera::from),
                Vec3.CODEC.optionalFieldOf("to").forGetter(camera -> Optional.of(camera.to())),
                Look.CODEC.optionalFieldOf("look", new AtAnchor()).forGetter(Camera::look),
                Codec.floatRange(0.05f, MAX_SECONDS).optionalFieldOf("duration").forGetter(Camera::duration),
                Codec.floatRange(0.05f, 200f).optionalFieldOf("speed").forGetter(Camera::speed),
                Ease.CODEC.optionalFieldOf("ease", Ease.IN_OUT).forGetter(Camera::ease)
        ).apply(instance, (from, to, look, duration, speed, ease) -> new Camera(from, to.orElse(from), look, duration, speed, ease)))
                .validate(camera -> camera.duration().isPresent() == camera.speed().isPresent()
                        ? DataResult.error(() -> "A shot's camera has either 'duration' or 'speed'")
                        : DataResult.success(camera));
        public static final Codec<Camera> CODEC = MAP_CODEC.codec();

        /** How long the flight takes: as written, or by the distance at the speed; never longer than {@link #MAX_SECONDS}. */
        public float seconds() {
            float length = duration.orElseGet(() -> (float) (from.distanceTo(to) / speed.orElseThrow()));
            return Math.max(0.05f, Math.min(MAX_SECONDS, length));
        }
    }

    /** One thing in a shot, at {@code at} seconds from its start (past the shot's end: at its end). */
    public record Cue(float at, Optional<Title> title, Optional<Line> line, Optional<Sound> sound, Optional<ScreenFade> fade, List<Action> actions) {
        public static final Codec<Cue> CODEC = RecordCodecBuilder.<Cue>create(instance -> instance.group(
                Codec.floatRange(0, Camera.MAX_SECONDS).optionalFieldOf("at", 0f).forGetter(Cue::at),
                Title.CODEC.optionalFieldOf("title").forGetter(Cue::title),
                Line.CODEC.optionalFieldOf("line").forGetter(Cue::line),
                Sound.CODEC.optionalFieldOf("sound").forGetter(Cue::sound),
                ScreenFade.CODEC.optionalFieldOf("fade").forGetter(Cue::fade),
                LogicCodecs.ACTIONS.optionalFieldOf("actions", List.of()).forGetter(Cue::actions)
        ).apply(instance, Cue::new)).validate(cue -> (cue.title.isPresent() ? 1 : 0) + (cue.line.isPresent() ? 1 : 0) + (cue.sound.isPresent() ? 1 : 0)
                + (cue.fade.isPresent() ? 1 : 0) + (cue.actions.isEmpty() ? 0 : 1) == 1
                ? DataResult.success(cue)
                : DataResult.error(() -> "A cue in 'during' is exactly one of title, line, sound, fade or actions"));
    }

    /** Big in the middle of the screen, as a quest's title when it starts. */
    public record Title(StoryText text, Optional<StoryText> subtitle) {
        public static final Codec<Title> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                StoryText.CODEC.fieldOf("text").forGetter(Title::text),
                StoryText.CODEC.optionalFieldOf("subtitle").forGetter(Title::subtitle)
        ).apply(instance, Title::new));
    }

    /**
     * A line in subtitles: who says it ({@code speaker}, a name), the words, its voice-over ({@code sound}); shown for
     * {@code duration} seconds, else for as long as the words take to read.
     */
    public record Line(Optional<StoryText> speaker, StoryText text, Optional<ResourceLocation> sound, Optional<Float> duration) {
        public static final Codec<Line> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                StoryText.CODEC.optionalFieldOf("speaker").forGetter(Line::speaker),
                StoryText.CODEC.fieldOf("text").forGetter(Line::text),
                ResourceLocation.CODEC.optionalFieldOf("sound").forGetter(Line::sound),
                Codec.floatRange(0.5f, Camera.MAX_SECONDS).optionalFieldOf("duration").forGetter(Line::duration)
        ).apply(instance, Line::new));
    }

    /** A sound for the watcher, from where they are. */
    public record Sound(ResourceLocation sound, SoundSource source, float volume, float pitch) {
        public static final Codec<Sound> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ResourceLocation.CODEC.fieldOf("sound").forGetter(Sound::sound),
                dev.dzenthai.storybound.core.logic.action.WorldActions.SOUND_SOURCE_CODEC.optionalFieldOf("source", SoundSource.MASTER).forGetter(Sound::source),
                Codec.floatRange(0, 10).optionalFieldOf("volume", 1f).forGetter(Sound::volume),
                Codec.floatRange(0.5f, 2f).optionalFieldOf("pitch", 1f).forGetter(Sound::pitch)
        ).apply(instance, Sound::new));
    }

    /** One shot: an anchor, the camera's flight against it, what happens meanwhile, how it follows the one before. */
    public record Shot(Anchor anchor, Camera camera, List<Cue> during, Transition transition) {
        public static final Codec<Shot> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Anchor.CODEC.optionalFieldOf("anchor", new PlayerAnchor()).forGetter(Shot::anchor),
                Camera.CODEC.fieldOf("camera").forGetter(Shot::camera),
                Cue.CODEC.listOf().optionalFieldOf("during", List.of()).forGetter(Shot::during),
                Transition.CODEC.optionalFieldOf("transition", Transition.CUT).forGetter(Shot::transition)
        ).apply(instance, Shot::new));
    }
}
