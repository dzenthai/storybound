package dev.dzenthai.storybound.module.scene;

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
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.StringRepresentable;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Scenes in the story: the action {@code play_scene}, the trigger {@code scene_finished}, the condition {@code in_scene}. */
public final class SceneLogic {
    static final DeferredRegister<ActionType<?>> REGISTER_ACTIONS = DeferredRegister.create(LogicRegistries.ACTION_TYPE_KEY, Storybound.MOD_ID);
    static final DeferredRegister<ConditionType<?>> REGISTER_CONDITIONS = DeferredRegister.create(LogicRegistries.CONDITION_TYPE_KEY, Storybound.MOD_ID);
    static final DeferredRegister<TriggerType<?, ?>> REGISTER_TRIGGERS = DeferredRegister.create(LogicRegistries.TRIGGER_TYPE_KEY, Storybound.MOD_ID);

    public static final DeferredHolder<ActionType<?>, ActionType<PlayScene>> PLAY_SCENE =
            REGISTER_ACTIONS.register("play_scene", () -> new ActionType<>(PlayScene.CODEC));
    public static final DeferredHolder<ConditionType<?>, ConditionType<InScene>> IN_SCENE =
            REGISTER_CONDITIONS.register("in_scene", () -> new ConditionType<>(InScene.CODEC));
    public static final DeferredHolder<TriggerType<?, ?>, TriggerType<SceneFinished, Finished>> SCENE_FINISHED =
            REGISTER_TRIGGERS.register("scene_finished", () -> new TriggerType<>(SceneFinished.CODEC));

    private SceneLogic() {}

    /** How a scene ended for the player: watched to the end, or cut short by death or by being moved away. */
    public enum Reason implements StringRepresentable {
        FINISH("finish"),
        DIED("died"),
        MOVED("moved");

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

    public record Finished(ResourceLocation scene, Reason reason) {}

    /**
     * {@code { "type": "play_scene", "scene": "mypack:arrival" }}: the player watches it; while they talk or watch
     * another one, it waits its turn. An unknown scene fails the action.
     */
    public record PlayScene(ResourceLocation scene) implements Action {
        public static final MapCodec<PlayScene> CODEC = ResourceLocation.CODEC.fieldOf("scene").xmap(PlayScene::new, PlayScene::scene);

        @Override
        public ActionType<?> type() {
            return PLAY_SCENE.get();
        }

        @Override
        public void collectReferences(dev.dzenthai.storybound.core.logic.References references) {
            references.scene(scene);
        }

        @Override
        public void run(StoryContext context) throws StoryException {
            if (SceneRegistries.SCENES.serverStore().getOrNull(scene) == null) {
                throw new StoryException(Component.literal("No scene " + scene + " is loaded (folder scenes/)"));
            }
            Scenes.play(context.player(), scene);
        }
    }

    /** {@code { "type": "in_scene", "scene": "mypack:arrival" }}: the player watches it now (any scene without an id). */
    public record InScene(Optional<ResourceLocation> scene) implements Condition {
        public static final MapCodec<InScene> CODEC = ResourceLocation.CODEC.optionalFieldOf("scene").xmap(InScene::new, InScene::scene);

        @Override
        public ConditionType<?> type() {
            return IN_SCENE.get();
        }

        @Override
        public boolean test(StoryContext context) {
            ResourceLocation watching = Scenes.watching(context.player());
            return watching != null && scene.map(watching::equals).orElse(true);
        }

        @Override
        public String describe(StoryContext context) {
            ResourceLocation watching = Scenes.watching(context.player());
            return watching == null ? "the player watches no scene" : "the player watches " + watching;
        }
    }

    /**
     * {@code { "type": "scene_finished", "scene": "mypack:arrival", "reason": "finish" }}: a scene the player watched
     * ended ({@code reason}: {@code finish}, {@code died}, {@code moved}; any without it). Not when the player left the
     * game: the scene starts again when they come back.
     */
    public record SceneFinished(Optional<ResourceLocation> scene, Optional<Reason> reason) implements Trigger<Finished> {
        public static final MapCodec<SceneFinished> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                ResourceLocation.CODEC.optionalFieldOf("scene").forGetter(SceneFinished::scene),
                Reason.CODEC.optionalFieldOf("reason").forGetter(SceneFinished::reason)
        ).apply(instance, SceneFinished::new));

        @Override
        public TriggerType<?, Finished> type() {
            return SCENE_FINISHED.get();
        }

        @Override
        public boolean matches(StoryContext context, Finished finished) {
            return scene.map(finished.scene()::equals).orElse(true) && reason.map(finished.reason()::equals).orElse(true);
        }
    }
}
