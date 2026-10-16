package dev.dzenthai.storybound.compat.epicfight;

import dev.dzenthai.storybound.client.camera.CameraIntegrations;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.jetbrains.annotations.Nullable;
import yesman.epicfight.api.client.camera.EpicFightCameraAPI;
import yesman.epicfight.client.world.capabilites.entitypatch.player.LocalPlayerPatch;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;

/**
 * Epic Fight's target lock turns the camera and the player towards its target, and battle mode changes how the
 * player is drawn. During a dialogue shot the lock is released and the player put in vanilla mode; battle mode
 * comes back afterwards, the lock does not.
 */
public final class EpicFightCompat implements CameraIntegrations.Integration {
    private boolean battleMode;

    @Override
    public String name() {
        return "Epic Fight";
    }

    @Override
    public void pause() {
        EpicFightCameraAPI camera = EpicFightCameraAPI.getInstance();
        if (camera.isLockingOnTarget()) {
            camera.setLockOn(false);
        }
        LocalPlayerPatch patch = patch();
        if (patch != null && patch.isEpicFightMode()) {
            patch.toVanillaMode(true);
            battleMode = true;
        }
    }

    @Override
    public void resume() {
        if (battleMode) {
            battleMode = false;
            LocalPlayerPatch patch = patch();
            if (patch != null) {
                patch.toEpicFightMode(true);
            }
        }
    }

    private static @Nullable LocalPlayerPatch patch() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null ? EpicFightCapabilities.getLocalPlayerPatch(player) : null;
    }
}
