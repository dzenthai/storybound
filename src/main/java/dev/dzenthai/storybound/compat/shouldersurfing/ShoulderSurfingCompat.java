package dev.dzenthai.storybound.compat.shouldersurfing;

import com.github.exopandora.shouldersurfing.api.client.IShoulderSurfing;
import com.github.exopandora.shouldersurfing.api.client.Perspective;
import com.github.exopandora.shouldersurfing.api.client.ShoulderSurfing;
import dev.dzenthai.storybound.client.camera.CameraIntegrations;

/**
 * Shoulder Surfing Reloaded moves the camera, fades the player out when the camera is close and draws its own
 * crosshair. During a dialogue shot it goes back to first person through its API and returns afterwards.
 */
public final class ShoulderSurfingCompat implements CameraIntegrations.Integration {
    private boolean paused;

    @Override
    public String name() {
        return "Shoulder Surfing Reloaded";
    }

    @Override
    public void pause() {
        IShoulderSurfing shoulderSurfing = ShoulderSurfing.getInstance();
        if (shoulderSurfing.isShoulderSurfing()) {
            shoulderSurfing.changePerspective(Perspective.FIRST_PERSON);
            paused = true;
        }
    }

    @Override
    public void resume() {
        if (paused) {
            paused = false;
            ShoulderSurfing.getInstance().changePerspective(Perspective.SHOULDER_SURFING);
        }
    }
}
