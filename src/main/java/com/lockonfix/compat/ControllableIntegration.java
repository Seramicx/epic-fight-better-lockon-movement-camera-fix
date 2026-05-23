package com.lockonfix.compat;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
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
     * Whether the current directional input is analog (controller stick)
     * rather than digital (keyboard or none).
     *
     * <p>Keyboard takes precedence: if any of W/A/S/D is physically down
     * we treat input as digital, regardless of impulse values. This guards
     * against handlers that rewrite {@link Input#forwardImpulse}/
     * {@code leftImpulse} to fractional values during keyboard play (e.g.
     * {@code LockOnMovementHandler}'s 1st-person sprint sets
     * {@code forwardImpulse = sqrt(2)} ≈ 1.414, which would otherwise
     * trip the fractional check and send camera-relative dodges down
     * the analog branch).
     *
     * <p>Only after we've ruled out keyboard do we fall back to the
     * fractional-impulse heuristic for actual stick input.
     */
    public static boolean isAnalogInput(Input input) {
        if (!IntegrationRegistry.isControllable()) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.options != null) {
            if (mc.options.keyUp.isDown() || mc.options.keyDown.isDown()
                    || mc.options.keyLeft.isDown() || mc.options.keyRight.isDown()) {
                return false;
            }
        }
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
    // CameraHandler.instance.yawDelta. Reading it lets AutoLockOnHandler's
    // flick detection treat controller turns the same as mouse turns.
    // (Controllable calls mc.player.turn(...) directly, bypassing
    // MouseHandler, so accumulatedDX never reflects right-stick input.)
    //
    // On Controllable 1.21.1 (multiloader branch), CameraHandler.updateRotationDelta
    // computes yawDelta = yawSpeed * thumbstickX (raw per-tick stick value).
    // CameraHandler.updateCamera then applies it as:
    //   elapsedDeltaYaw = yawDelta * elapsedTicks
    //   mc.player.turn(elapsedDeltaYaw, ...)   // turn() multiplies arg by 0.15
    // So the camera actually moves yawDelta * elapsedTicks * 0.15 degrees
    // this frame - that's what we return so it matches MouseHandler's
    // degrees-per-frame semantics used by flick detection.

    private static boolean cameraReflectionInitialized = false;
    private static boolean cameraReflectionResolved = false;

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
            cameraReflectionResolved = true;
            LOGGER.info("Controllable: CameraHandler.yawDelta resolved");
        } catch (Throwable t) {
            LOGGER.warn("Controllable: CameraHandler reflection failed ({}), controller-flick targeting disabled",
                    t.getMessage());
            cameraReflectionResolved = false;
        }
    }

    /**
     * Returns Controllable's right-stick yaw delta for this frame in degrees.
     * Positive = right, negative = left. Returns 0 if Controllable is absent
     * or reflection failed.
     */
    public static float getCameraYawDelta() {
        initCameraReflection();
        if (!cameraReflectionResolved) return 0F;
        try {
            Object instance = cameraHandlerInstanceField.get(null);
            if (instance == null) return 0F;
            float yawDelta = cameraHandlerYawDeltaField.getFloat(instance);
            if (yawDelta == 0F) return 0F;
            float elapsedTicks = Minecraft.getInstance().getTimer().getRealtimeDeltaTicks();
            return yawDelta * elapsedTicks * 0.15F;
        } catch (Throwable t) {
            return 0F;
        }
    }
}
