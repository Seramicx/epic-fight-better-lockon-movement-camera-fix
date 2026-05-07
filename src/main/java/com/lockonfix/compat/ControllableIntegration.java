package com.lockonfix.compat;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

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
    // Controller right-stick yaw delta (degrees per frame, sign = direction)
    // =====================================================================
    //
    // Controllable stores the right-stick-driven yaw turn for this frame in
    // a private float on its singleton input handler. Reading it lets
    // AutoLockOnHandler's flick detection treat controller turns the same
    // as mouse turns. (Controllable calls mc.player.turn(...) directly,
    // bypassing MouseHandler, so accumulatedDX never reflects right-stick
    // input.)
    //
    // API path differs by Controllable version:
    //   * 0.21.9+ (MC 1.20.1):  Controllable.getInput().targetYaw     (raw stick value)
    //   * older:                CameraHandler.instance.yawDelta       (already degrees/frame)
    //
    // For 0.21.9+, the raw targetYaw is what's fed into player.turn as
    // (targetYaw / 0.15) * elapsedTicks; player.turn then multiplies its
    // arg by 0.15 to get degrees, so degrees-per-frame = targetYaw *
    // elapsedTicks. We compute that here so the function's "degrees per
    // frame" semantics match across Controllable versions.

    private enum ApiPath { NONE, NEW_021_9, LEGACY }

    private static boolean cameraReflectionInitialized = false;
    private static ApiPath apiPath = ApiPath.NONE;

    // 0.21.9+
    private static Method getInputMethod = null;
    private static Field targetYawField = null;

    // legacy
    private static Field cameraHandlerInstanceField = null;
    private static Field cameraHandlerYawDeltaField = null;

    private static void initCameraReflection() {
        if (cameraReflectionInitialized) return;
        cameraReflectionInitialized = true;
        if (!IntegrationRegistry.isControllable()) return;

        // Try Controllable 0.21.9+ first.
        try {
            Class<?> controllableClass = Class.forName("com.mrcrayfish.controllable.Controllable");
            getInputMethod = controllableClass.getMethod("getInput");
            Class<?> controllerInputClass = Class.forName(
                "com.mrcrayfish.controllable.client.ControllerInput");
            targetYawField = controllerInputClass.getDeclaredField("targetYaw");
            targetYawField.setAccessible(true);
            apiPath = ApiPath.NEW_021_9;
            LOGGER.info("Controllable: ControllerInput.targetYaw resolved (0.21.9+ API)");
            return;
        } catch (Throwable t) {
            LOGGER.debug("Controllable: 0.21.9+ API not present ({}), trying legacy CameraHandler",
                    t.getMessage());
            getInputMethod = null;
            targetYawField = null;
        }

        // Fall back to older Controllable versions (CameraHandler.instance.yawDelta).
        try {
            Class<?> cameraHandlerClass = Class.forName(
                "com.mrcrayfish.controllable.client.CameraHandler");
            cameraHandlerInstanceField = cameraHandlerClass.getDeclaredField("instance");
            cameraHandlerInstanceField.setAccessible(true);
            cameraHandlerYawDeltaField = cameraHandlerClass.getDeclaredField("yawDelta");
            cameraHandlerYawDeltaField.setAccessible(true);
            apiPath = ApiPath.LEGACY;
            LOGGER.info("Controllable: CameraHandler.yawDelta resolved (legacy API)");
        } catch (Throwable t) {
            LOGGER.warn("Controllable: neither 0.21.9+ nor legacy reflection resolved ({}), controller-flick targeting disabled",
                    t.getMessage());
            apiPath = ApiPath.NONE;
        }
    }

    /**
     * Returns Controllable's right-stick yaw delta for this frame in degrees.
     * Positive = right, negative = left. Returns 0 if Controllable is absent
     * or reflection failed.
     */
    public static float getCameraYawDelta() {
        initCameraReflection();
        switch (apiPath) {
            case NEW_021_9:
                try {
                    Object input = getInputMethod.invoke(null);
                    if (input == null) return 0F;
                    float targetYaw = targetYawField.getFloat(input);
                    if (targetYaw == 0F) return 0F;
                    // targetYaw is the raw stick value; degrees-per-frame is
                    // targetYaw * elapsedTicks (see comment block above).
                    float elapsedTicks = Minecraft.getInstance().getDeltaFrameTime();
                    return targetYaw * elapsedTicks;
                } catch (Throwable t) {
                    return 0F;
                }
            case LEGACY:
                try {
                    Object instance = cameraHandlerInstanceField.get(null);
                    if (instance == null) return 0F;
                    return cameraHandlerYawDeltaField.getFloat(instance);
                } catch (Throwable t) {
                    return 0F;
                }
            case NONE:
            default:
                return 0F;
        }
    }
}
