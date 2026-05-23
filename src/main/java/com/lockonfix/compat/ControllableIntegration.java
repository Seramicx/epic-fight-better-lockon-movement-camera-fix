package com.lockonfix.compat;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class ControllableIntegration {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final float DEADZONE = 0.15F;

    private ControllableIntegration() {}

    public static float[] readAnalogDirection(Input input) {
        float forward = input.forwardImpulse;
        float strafe  = input.leftImpulse;

        float magnitude = (float) Math.sqrt(forward * forward + strafe * strafe);
        if (magnitude < DEADZONE) {
            return new float[]{0, 0};
        }

        return new float[]{forward, strafe};
    }

    public static float getAnalogMagnitude(Input input) {
        float forward = input.forwardImpulse;
        float strafe  = input.leftImpulse;
        float mag = (float) Math.sqrt(forward * forward + strafe * strafe);
        if (mag < DEADZONE) return 0;
        return Math.min(mag, 1.0F);
    }

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

    private enum ApiPath { NONE, NEW_021_9, LEGACY }

    private static boolean cameraReflectionInitialized = false;
    private static ApiPath apiPath = ApiPath.NONE;

    private static Method getInputMethod = null;
    private static Field targetYawField = null;

    private static Field cameraHandlerInstanceField = null;
    private static Field cameraHandlerYawDeltaField = null;

    private static void initCameraReflection() {
        if (cameraReflectionInitialized) return;
        cameraReflectionInitialized = true;
        if (!IntegrationRegistry.isControllable()) return;

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

    public static float getCameraYawDelta() {
        initCameraReflection();
        switch (apiPath) {
            case NEW_021_9:
                try {
                    Object input = getInputMethod.invoke(null);
                    if (input == null) return 0F;
                    float targetYaw = targetYawField.getFloat(input);
                    if (targetYaw == 0F) return 0F;
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
