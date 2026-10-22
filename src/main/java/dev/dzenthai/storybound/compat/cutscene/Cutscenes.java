package dev.dzenthai.storybound.compat.cutscene;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.StoryException;
import dev.dzenthai.storybound.core.logic.Action;
import dev.dzenthai.storybound.core.logic.ActionType;
import dev.dzenthai.storybound.core.logic.Condition;
import dev.dzenthai.storybound.core.logic.ConditionType;
import dev.dzenthai.storybound.core.logic.LogicRegistries;
import dev.dzenthai.storybound.core.logic.StoryContext;
import dev.dzenthai.storybound.core.logic.Trigger;
import dev.dzenthai.storybound.core.logic.TriggerType;
import dev.dzenthai.storybound.core.logic.action.SceneActions;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.jetbrains.annotations.Nullable;

/**
 * Cutscene API (mod id {@code cutscene_api}) in the story: the action {@code play_cutscene}, the trigger
 * {@code cutscene_finished} and the condition {@code in_cutscene}. The types exist without the mod, so a story using
 * them loads anywhere; without it the action fails (and says so in the log and the trace), the trigger never fires and
 * the condition is false. Only {@link CutsceneApiBridge} touches the mod's classes, and only when it is installed.
 */
public final class Cutscenes {
    public static final String MOD_ID = "cutscene_api";

    static final DeferredRegister<ActionType<?>> REGISTER_ACTIONS = DeferredRegister.create(LogicRegistries.ACTION_TYPE_KEY, Storybound.MOD_ID);
    static final DeferredRegister<ConditionType<?>> REGISTER_CONDITIONS = DeferredRegister.create(LogicRegistries.CONDITION_TYPE_KEY, Storybound.MOD_ID);
    static final DeferredRegister<TriggerType<?, ?>> REGISTER_TRIGGERS = DeferredRegister.create(LogicRegistries.TRIGGER_TYPE_KEY, Storybound.MOD_ID);

    public static final DeferredHolder<ActionType<?>, ActionType<PlayCutscene>> PLAY_CUTSCENE =
            REGISTER_ACTIONS.register("play_cutscene", () -> new ActionType<>(PlayCutscene.CODEC));
    public static final DeferredHolder<ConditionType<?>, ConditionType<InCutscene>> IN_CUTSCENE =
            REGISTER_CONDITIONS.register("in_cutscene", () -> new ConditionType<>(InCutscene.CODEC));
    public static final DeferredHolder<TriggerType<?, ?>, TriggerType<CutsceneFinished, Finished>> CUTSCENE_FINISHED =
            REGISTER_TRIGGERS.register("cutscene_finished", () -> new TriggerType<>(CutsceneFinished.CODEC));

    private static boolean installed;

    private Cutscenes() {}

    public static void register(IEventBus modBus) {
        REGISTER_ACTIONS.register(modBus);
        REGISTER_CONDITIONS.register(modBus);
        REGISTER_TRIGGERS.register(modBus);
        installed = ModList.get().isLoaded(MOD_ID);
        if (installed) {
            CutsceneApiBridge.listen();
        }
    }

    public static boolean installed() {
        return installed;
    }

    /** The cutscene the player watches, or null (always without the mod). */
    public static @Nullable ResourceLocation watching(ServerPlayer player) {
        return installed ? CutsceneApiBridge.running(player) : null;
    }

    /** How a cutscene ended, as Cutscene API says it. */
    public enum Reason implements StringRepresentable {
        FINISH("finish"),
        INTERRUPT("interrupt"),
        COMMAND("command"),
        ERROR("error");

        public static final Codec<Reason> CODEC = StringRepresentable.fromEnum(Reason::values);

        private final String name;

        Reason(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    public record Finished(ResourceLocation cutscene, Reason reason) {}

    /**
     * {@code { "type": "play_cutscene", "cutscene": "mypack:kairen_arrives" }}: plays it for the player from a place
     * (the player's by default; {@code pos} / {@code offset} / {@code at} as for {@code spawn_entity}), the camera
     * turned by {@code rotation} and the path by {@code path_rotation} ([yaw, pitch, roll]).
     */
    public record PlayCutscene(ResourceLocation cutscene, SceneActions.Place place, Vec3 rotation, Vec3 pathRotation) implements Action {
        public static final MapCodec<PlayCutscene> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                ResourceLocation.CODEC.fieldOf("cutscene").forGetter(PlayCutscene::cutscene),
                SceneActions.Place.MAP_CODEC.forGetter(PlayCutscene::place),
                Vec3.CODEC.optionalFieldOf("rotation", Vec3.ZERO).forGetter(PlayCutscene::rotation),
                Vec3.CODEC.optionalFieldOf("path_rotation", Vec3.ZERO).forGetter(PlayCutscene::pathRotation)
        ).apply(instance, PlayCutscene::new));

        @Override
        public ActionType<?> type() {
            return PLAY_CUTSCENE.get();
        }

        @Override
        public void run(StoryContext context) throws StoryException {
            if (!installed) {
                throw new StoryException(Component.literal("Cutscene API is not installed: cannot play " + cutscene));
            }
            if (!CutsceneApiBridge.exists(cutscene)) {
                throw new StoryException(Component.literal("No cutscene " + cutscene + " is loaded (Cutscene API data packs, folder cutscenes/)"));
            }
            SceneActions.Place.Resolved at = place.resolve(context);
            CutsceneApiBridge.play(context.player(), cutscene, at.pos(), rotation, pathRotation);
        }
    }

    /** {@code { "type": "in_cutscene", "cutscene": "mypack:kairen_arrives" }}: the player watches it (any one without an id). */
    public record InCutscene(Optional<ResourceLocation> cutscene) implements Condition {
        public static final MapCodec<InCutscene> CODEC = ResourceLocation.CODEC.optionalFieldOf("cutscene").xmap(InCutscene::new, InCutscene::cutscene);

        @Override
        public ConditionType<?> type() {
            return IN_CUTSCENE.get();
        }

        @Override
        public boolean test(StoryContext context) {
            ResourceLocation watching = watching(context.player());
            return watching != null && cutscene.map(watching::equals).orElse(true);
        }

        @Override
        public String describe(StoryContext context) {
            if (!installed) {
                return "Cutscene API is not installed";
            }
            ResourceLocation watching = watching(context.player());
            return watching == null ? "the player watches no cutscene" : "the player watches " + watching;
        }
    }

    /**
     * {@code { "type": "cutscene_finished", "cutscene": "mypack:kairen_arrives", "reason": "finish" }}: a cutscene the
     * player watched ended ({@code reason}: {@code finish}, {@code interrupt}, {@code command}, {@code error}; any without it).
     */
    public record CutsceneFinished(Optional<ResourceLocation> cutscene, Optional<Reason> reason) implements Trigger<Finished> {
        public static final MapCodec<CutsceneFinished> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                ResourceLocation.CODEC.optionalFieldOf("cutscene").forGetter(CutsceneFinished::cutscene),
                Reason.CODEC.optionalFieldOf("reason").forGetter(CutsceneFinished::reason)
        ).apply(instance, CutsceneFinished::new));

        @Override
        public TriggerType<?, Finished> type() {
            return CUTSCENE_FINISHED.get();
        }

        @Override
        public boolean matches(StoryContext context, Finished finished) {
            return cutscene.map(finished.cutscene()::equals).orElse(true) && reason.map(finished.reason()::equals).orElse(true);
        }
    }
}
