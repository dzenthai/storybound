package dev.dzenthai.storybound.client;

import java.lang.reflect.Method;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Whether a shader pack of Iris (or Oculus, its port) draws the world. Iris gives each stage of the level's rendering
 * the program of its phase: what is drawn after the weather gets the weather's program, which dims and blurs it. Read
 * through Iris's public API by reflection, so neither mod is needed to build or to run.
 */
public final class ShaderPacks {
    private static final String[] APIS = {"net.irisshaders.iris.api.v0.IrisApi", "net.coderbot.iris.api.v0.IrisApi"};
    private static boolean looked;
    private static @Nullable Object api;
    private static @Nullable Method inUse;
    private static @Nullable Method shadowPass;

    private ShaderPacks() {}

    /** Whether a shader pack is on now; false without Iris or when its API cannot be read. */
    public static boolean inUse() {
        return call(inUse);
    }

    /** Whether Iris is drawing its shadow pass, where nothing of the mod's own belongs. */
    public static boolean renderingShadowPass() {
        return call(shadowPass);
    }

    /**
     * The stage text and lines in the world are drawn at: after the weather, over clouds and rain; with a shader pack,
     * after the entities, in the phase Iris draws name tags in.
     */
    public static RenderLevelStageEvent.Stage worldOverlayStage() {
        return inUse() ? RenderLevelStageEvent.Stage.AFTER_ENTITIES : RenderLevelStageEvent.Stage.AFTER_WEATHER;
    }

    private static boolean call(@Nullable Method method) {
        find();
        if (api == null || method == null) {
            return false;
        }
        try {
            return (boolean) method.invoke(api);
        } catch (ReflectiveOperationException | RuntimeException e) {
            api = null;
            return false;
        }
    }

    private static void find() {
        if (looked) {
            return;
        }
        looked = true;
        for (String name : APIS) {
            try {
                Class<?> type = Class.forName(name);
                api = type.getMethod("getInstance").invoke(null);
                inUse = type.getMethod("isShaderPackInUse");
                shadowPass = type.getMethod("isRenderingShadowPass");
                return;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                api = null;
            }
        }
    }
}
