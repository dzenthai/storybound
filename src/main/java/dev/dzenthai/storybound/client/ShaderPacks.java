package dev.dzenthai.storybound.client;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Whether a shader pack of Iris (or Oculus, its port) draws the world. Iris gives each stage of the level's rendering
 * the program of its phase: what is drawn after the weather gets the weather's program, which dims and blurs it. Read
 * through Iris's public API via bound MethodHandles, so neither mod is needed to build or to run and per-frame calls
 * avoid reflective overhead.
 */
public final class ShaderPacks {
    private static final String[] APIS = {"net.irisshaders.iris.api.v0.IrisApi", "net.coderbot.iris.api.v0.IrisApi"};
    private static boolean looked;
    private static @Nullable MethodHandle inUse;
    private static @Nullable MethodHandle shadowPass;

    private ShaderPacks() {}

    /** Whether a shader pack is on now; false without Iris or when its API cannot be read. */
    public static boolean inUse() {
        find();
        return call(inUse);
    }

    /** Whether Iris is drawing its shadow pass, where nothing of the mod's own belongs. */
    public static boolean renderingShadowPass() {
        find();
        return call(shadowPass);
    }

    /**
     * The stage text and lines in the world are drawn at: after the weather, over clouds and rain; with a shader pack,
     * after the entities, in the phase Iris draws name tags in.
     */
    public static RenderLevelStageEvent.Stage worldOverlayStage() {
        return inUse() ? RenderLevelStageEvent.Stage.AFTER_ENTITIES : RenderLevelStageEvent.Stage.AFTER_WEATHER;
    }

    /** Fast stage check that skips querying Iris on the ~10 level stages that are never world overlay stages. */
    public static boolean isWorldOverlayStage(RenderLevelStageEvent.Stage stage) {
        if (stage != RenderLevelStageEvent.Stage.AFTER_ENTITIES && stage != RenderLevelStageEvent.Stage.AFTER_WEATHER) {
            return false;
        }
        return stage == worldOverlayStage();
    }

    private static boolean call(@Nullable MethodHandle handle) {
        find();
        if (handle == null) {
            return false;
        }
        try {
            return (boolean) handle.invokeExact();
        } catch (Throwable e) {
            inUse = null;
            shadowPass = null;
            return false;
        }
    }

    private static void find() {
        if (looked) {
            return;
        }
        looked = true;
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        MethodType boolGetter = MethodType.methodType(boolean.class);
        for (String name : APIS) {
            try {
                Class<?> type = Class.forName(name);
                Object api = type.getMethod("getInstance").invoke(null);
                if (api == null) {
                    continue;
                }
                inUse = lookup.findVirtual(type, "isShaderPackInUse", boolGetter).bindTo(api);
                shadowPass = lookup.findVirtual(type, "isRenderingShadowPass", boolGetter).bindTo(api);
                return;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                inUse = null;
                shadowPass = null;
            }
        }
    }
}
