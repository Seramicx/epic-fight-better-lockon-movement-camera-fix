package com.lockonfix.compat;

import com.mojang.logging.LogUtils;
import net.minecraft.client.player.Input;
import org.slf4j.Logger;

import java.lang.reflect.Field;

/**
 * Controllable integration. When Controllable is present, analog stick values
 * flow through vanilla {@link Input#forwardImpulse}/{@code leftImpulse} as
 * fractional floats. When absent, the impulse values are ±1/0 from keyboard
 * and every public method degrades gracefully.
 */
public final class ControllableIntegration {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final float DEADZONE = 0.15F;

    private ControllableIntegration() {}

    /**
     * Read the analog direction from the Input, applying a deadzone.
     * Returns {forward, strafe} with values in [-1, 1].
     * When below the deadzone, returns {0, 0}.
     */
    public static float[] readAnalogDirection(Input input) {
        float forward = input.forwardImpulse;
        float strafe  = input.leftImpulse;

        float magnitude = (float) Math.sqrt(forward * forward + strafe * strafe);
        if (magnitude < DEADZONE) {
            return new float[]{0, 0};
        }

        return new float[]{forward, strafe};
    }

    /**
     * Analog stick magnitude, clamped to [0, 1]. Useful for scaling
     * movement speed or other magnitude-dependent behaviors.
     */
    public static float getAnalogMagnitude(Input input) {
        float forward = input.forwardImpulse;
        float strafe  = input.leftImpulse;
        float mag = (float) Math.sqrt(forward * forward + strafe * strafe);
        if (mag < DEADZONE) return 0;
        return Math.min(mag, 1.0F);
    }

    /**
     * Whether the current directional input is analog (fractional) rather
     * than digital (exactly ±1 or 0). This heuristic checks if either
     * impulse value is non-zero and not exactly ±1.
     */
    public static boolean isAnalogInput(Input input) {
        if (!IntegrationRegistry.isControllable()) return false;
        return isFractional(input.forwardImpulse) || isFractional(input.leftImpulse);
    }

    private static boolean isFractional(float v) {
        if (v == 0) return false;
        float abs = Math.abs(v);
        return abs > 0.01F && Math.abs(abs - 1.0F) > 0.01F;
    }

    // =====================================================================
    // Controller right-stick yaw delta (degrees per tick, sign = direction)
    // =====================================================================
    //
    // Controllable's CameraHandler stores the right-stick-driven yaw turn for
    // this frame in a private float `yawDelta` on its singleton. Reading that
    // lets AutoLockOnHandler's flick detection treat controller turns the
    // same as mouse turns. (Controllable's CameraHandler.updateCamera calls
    // mc.player.turn directly, bypassing MouseHandler, so accumulatedDX never
    // reflects right-stick input.)

    private static boolean cameraReflectionInitialized = false;
    private static Field cameraHandlerInstanceField = null;
    private static Field cameraHandlerYawDeltaField = null;

    private static void initCameraReflection() {
        if (cameraReflectionInitialized) return;
        cameraReflectionInitialized = true;
        if (!IntegrationRegistry.isControllable()) return;
        try {
            Class<?> cameraHandlerClass = Class.forName(
                "com.mrcrayfish.controllable.client.CameraHandler");
            cameraHandlerInstanceField = cameraHandlerClass.getDeclaredField("instance");
            cameraHandlerInstanceField.setAccessible(true);
            cameraHandlerYawDeltaField = cameraHandlerClass.getDeclaredField("yawDelta");
            cameraHandlerYawDeltaField.setAccessible(true);
            LOGGER.info("Controllable: CameraHandler yawDelta field resolved");
        } catch (Throwable t) {
            LOGGER.warn("Controllable: could not resolve CameraHandler reflection ({}), controller-flick targeting disabled",
                    t.getMessage());
            cameraHandlerInstanceField = null;
            cameraHandlerYawDeltaField = null;
        }
    }

    /**
     * Returns Controllable's right-stick yaw delta for this frame in degrees.
     * Positive = right, negative = left. Returns 0 if Controllable is absent
     * or reflection failed.
     */
    public static float getCameraYawDelta() {
        initCameraReflection();
        if (cameraHandlerInstanceField == null || cameraHandlerYawDeltaField == null) return 0F;
        try {
            Object instance = cameraHandlerInstanceField.get(null);
            if (instance == null) return 0F;
            return cameraHandlerYawDeltaField.getFloat(instance);
        } catch (Throwable t) {
            return 0F;
        }
    }
}
