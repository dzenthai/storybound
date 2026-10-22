package dev.dzenthai.storybound.compat.cutscene;

import dev.dzenthai.storybound.core.logic.StoryContext;
import dev.dzenthai.storybound.core.logic.trigger.TriggerDispatcher;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.thewinnt.cutscenes.CutsceneManager;
import net.thewinnt.cutscenes.CutsceneType;
import net.thewinnt.cutscenes.event.CutsceneEvents;
import net.thewinnt.cutscenes.util.ServerPlayerExt;
import org.jetbrains.annotations.Nullable;

/**
 * The only class using Cutscene API's own classes (1.6.6 for 1.21.1): loaded when the mod is installed, never
 * otherwise, so Storybound runs without it.
 */
final class CutsceneApiBridge {
    private CutsceneApiBridge() {}

    static void listen() {
        CutsceneEvents.CUTSCENE_OVER_SERVER.addListener((type, id, player, reason) -> {
            if (id != null) {
                Cutscenes.Reason ended = switch (reason) {
                    case FINISH -> Cutscenes.Reason.FINISH;
                    case INTERRUPT -> Cutscenes.Reason.INTERRUPT;
                    case COMMAND -> Cutscenes.Reason.COMMAND;
                    case ERROR -> Cutscenes.Reason.ERROR;
                };
                TriggerDispatcher.fire(Cutscenes.CUTSCENE_FINISHED.get(), StoryContext.of(player), new Cutscenes.Finished(id, ended));
            }
        });
    }

    static boolean exists(ResourceLocation id) {
        return CutsceneManager.REGISTRY.containsKey(id);
    }

    static void play(ServerPlayer player, ResourceLocation id, Vec3 pos, Vec3 rotation, Vec3 pathRotation) {
        CutsceneManager.startCutscene(id, pos, rotation, pathRotation, player);
    }

    static @Nullable ResourceLocation running(ServerPlayer player) {
        CutsceneType type = ((ServerPlayerExt) player).csapi$getRunningCutscene();
        return type != null ? CutsceneManager.REGISTRY.inverse().get(type) : null;
    }
}
