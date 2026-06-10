package com.lockonfix.handler;

import com.lockonfix.FixConfig;
import com.lockonfix.LockOnMovementFix;
import com.lockonfix.client.EpicFightClientHooks;
import com.lockonfix.compat.ControllableIntegration;
import com.lockonfix.compat.IntegrationRegistry;
import com.lockonfix.compat.ValkyrienSkiesIntegration;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import yesman.epicfight.api.animation.types.EntityState;
import yesman.epicfight.api.client.camera.EpicFightCameraAPI;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;
import yesman.epicfight.client.world.capabilites.entitypatch.player.LocalPlayerPatch;

public class LockOnMovementHandler {

    public static final LockOnMovementHandler INSTANCE = new LockOnMovementHandler();

    private static final boolean DEFAULT_AUTO_FACE_TARGET = true;

    // Cached singleton ref to Epic Fight's camera API (NOT a per-entity capability — the API singleton is stable across the session).
    private static EpicFightCameraAPI cachedAPI = null;

    private static float smoothedYRot = Float.NaN;
    private static boolean wasLockedOn = false;

    private static float getTurnSpeed() {
        try { return (float) FixConfig.TURN_SPEED.get().doubleValue(); }
        catch (Exception e) { return 0.45F; }
    }

    private static float getIdleTurnSpeed() {
        try { return (float) FixConfig.IDLE_TURN_SPEED.get().doubleValue(); }
        catch (Exception e) { return 0.7F; }
    }

    private static boolean getAutoFaceTarget() {
        try { return FixConfig.AUTO_FACE_TARGET.get(); }
        catch (Exception e) { return DEFAULT_AUTO_FACE_TARGET; }
    }

    private static EpicFightCameraAPI getAPI() {
        if (cachedAPI == null) {
            try { cachedAPI = EpicFightCameraAPI.getInstance(); }
            catch (Exception e) { return null; }
        }
        return cachedAPI;
    }

    private static float getYawToTarget(LocalPlayer player, LivingEntity target) {
        double dx = target.getX() - player.getX();
        double dz = target.getZ() - player.getZ();
        float worldYaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;

        if (IntegrationRegistry.isValkyrienSkies()
                && ValkyrienSkiesIntegration.isMountedOnShip(player)) {
            return ValkyrienSkiesIntegration.worldYawToShipYaw(player, worldYaw);
        }
        return worldYaw;
    }

    private static float smoothAngle(float from, float to, float factor) {
        float delta = Mth.wrapDegrees(to - from);
        return from + delta * factor;
    }

    private static boolean isMovementLocked(LocalPlayer player) {
        try {
            LocalPlayerPatch patch = EpicFightCapabilities.getLocalPlayerPatch(player);
            if (patch == null) return false;
            EntityState state = patch.getEntityState();
            if (state == null) return false;
            return state.movementLocked();
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isHoldingGuard(LocalPlayer player) {
        try {
            LocalPlayerPatch patch = EpicFightCapabilities.getLocalPlayerPatch(player);
            return patch != null && patch.isHoldingAny();
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean shouldAutoFaceTarget(LocalPlayer player) {
        return isHoldingGuard(player)
                || player.isBlocking()
                || EpicFightClientHooks.isAiming(player);
    }

    private static float[] readDirectionalInput(Input input) {
        Minecraft mc = Minecraft.getInstance();
        float rawForward = 0;
        if (mc.options.keyUp.isDown()) rawForward += 1.0F;
        if (mc.options.keyDown.isDown()) rawForward -= 1.0F;

        float rawStrafe = 0;
        if (mc.options.keyLeft.isDown()) rawStrafe += 1.0F;
        if (mc.options.keyRight.isDown()) rawStrafe -= 1.0F;

        if (rawForward == 0 && rawStrafe == 0) {
            float[] analog = ControllableIntegration.readAnalogDirection(input);
            rawForward = analog[0];
            rawStrafe  = analog[1];
        }

        return new float[]{rawForward, rawStrafe};
    }

    // LOWEST so this mod's movement-direction override applies after other input handlers
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onMovementInput(MovementInputUpdateEvent event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;

        EpicFightCameraAPI api = getAPI();
        boolean isLockedOnNow = api != null && api.isLockingOnTarget();

        if (!isLockedOnNow) {
            if (wasLockedOn) {
                smoothedYRot = Float.NaN;
                wasLockedOn = false;
            }
            return;
        }

        LivingEntity target = api.getFocusingEntity();
        if (target == null || !target.isAlive()) return;

        boolean isFirstPerson = mc.options.getCameraType() == CameraType.FIRST_PERSON;
        if (isFirstPerson) {
            wasLockedOn = true;
            Input fpInput = event.getInput();

            if (IntegrationRegistry.isBetterLockOn()) {
                float[] dir = readDirectionalInput(fpInput);
                float rawForward = dir[0];
                float rawStrafe = dir[1];
                float rawMagnitude = Mth.sqrt(rawForward * rawForward + rawStrafe * rawStrafe);
                boolean isMoving = rawMagnitude > 0.01F;
                boolean sprintHeld = mc.options.keySprint.isDown() && !mc.options.keyUse.isDown();

                if (sprintHeld && isMoving) {
                    float targetYaw = getYawToTarget(player, target);
                    float offsetAngle = -(float)Math.toDegrees(Math.atan2(rawStrafe, rawForward));
                    float movementYaw = Mth.wrapDegrees(targetYaw + offsetAngle);
                    player.setYRot(movementYaw);
                    player.yRotO = movementYaw;
                    float modMagnitude = Mth.sqrt(fpInput.forwardImpulse * fpInput.forwardImpulse
                            + fpInput.leftImpulse * fpInput.leftImpulse);
                    fpInput.forwardImpulse = Math.min(rawMagnitude, modMagnitude);
                    fpInput.leftImpulse = 0F;
                } else {
                    if (isMoving) {
                        float modMagnitude = Mth.sqrt(fpInput.forwardImpulse * fpInput.forwardImpulse
                                + fpInput.leftImpulse * fpInput.leftImpulse);
                        float magnitude = Math.min(rawMagnitude, modMagnitude);
                        float scale = magnitude / rawMagnitude;
                        fpInput.forwardImpulse = rawForward * scale;
                        fpInput.leftImpulse = rawStrafe * scale;
                    } else {
                        fpInput.forwardImpulse = 0F;
                        fpInput.leftImpulse = 0F;
                    }
                }

                float dz = 0.3F;
                fpInput.up    = rawForward >  dz;
                fpInput.down  = rawForward < -dz;
                fpInput.left  = rawStrafe  >  dz;
                fpInput.right = rawStrafe  < -dz;
            }
            return;
        }

        boolean needAutoFace = shouldAutoFaceTarget(player) && getAutoFaceTarget();
        if (IntegrationRegistry.isBetterLockOn() && !needAutoFace) {
            return;
        }

        wasLockedOn = true;
        Input input = event.getInput();

        if (Float.isNaN(smoothedYRot)) {
            smoothedYRot = player.getYRot();
        }

        if (shouldAutoFaceTarget(player) && getAutoFaceTarget()) {
            float targetYaw = getYawToTarget(player, target);
            smoothedYRot = smoothAngle(smoothedYRot, targetYaw, getIdleTurnSpeed());
            player.setYRot(smoothedYRot);
            player.yBodyRot = smoothedYRot;
            player.yHeadRot = smoothedYRot;

            float[] dir = readDirectionalInput(input);
            float rawForward = dir[0];
            float rawStrafe = dir[1];
            float rawMagnitude = Mth.sqrt(rawForward * rawForward + rawStrafe * rawStrafe);

            float modMagnitude = Mth.sqrt(input.forwardImpulse * input.forwardImpulse
                    + input.leftImpulse * input.leftImpulse);
            float magnitude = Math.min(rawMagnitude, modMagnitude);

            if (magnitude > 0.01F) {
                float offsetAngle = -(float) Math.toDegrees(Math.atan2(rawStrafe, rawForward));
                float worldMoveYaw = Mth.wrapDegrees(targetYaw + offsetAngle);
                float thetaRad = (float) Math.toRadians(Mth.wrapDegrees(worldMoveYaw - smoothedYRot));
                input.forwardImpulse = magnitude * Mth.cos(thetaRad);
                input.leftImpulse = -magnitude * Mth.sin(thetaRad);
            } else {
                input.forwardImpulse = 0F;
                input.leftImpulse = 0F;
            }

            float dz = 0.3F;
            input.up    = rawForward >  dz;
            input.down  = rawForward < -dz;
            input.left  = rawStrafe  >  dz;
            input.right = rawStrafe  < -dz;
            return;
        }

        if (EpicFightClientHooks.isAiming(player)) {
            return;
        }

        if (isMovementLocked(player)) {
            input.forwardImpulse = 0;
            input.leftImpulse = 0;
            input.up = false;
            input.down = false;
            input.left = false;
            input.right = false;
            return;
        }

        float[] dir = readDirectionalInput(input);
        float rawForward = dir[0];
        float rawStrafe = dir[1];

        float targetYaw = getYawToTarget(player, target);

        boolean isMoving = Math.abs(rawForward) > 0.01F || Math.abs(rawStrafe) > 0.01F;

        if (isMoving) {
            float offsetAngle = -(float) Math.toDegrees(Math.atan2(rawStrafe, rawForward));
            float desiredYRot = Mth.wrapDegrees(targetYaw + offsetAngle);

            smoothedYRot = smoothAngle(smoothedYRot, desiredYRot, getTurnSpeed());
            player.setYRot(smoothedYRot);

            float magnitude = Mth.sqrt(rawForward * rawForward + rawStrafe * rawStrafe);
            float speed = input.shiftKeyDown ? magnitude * 0.3F : magnitude;

            input.forwardImpulse = speed;
            input.leftImpulse = 0;

            float dz = 0.3F;
            input.up    = rawForward >  dz;
            input.down  = rawForward < -dz;
            input.left  = rawStrafe  >  dz;
            input.right = rawStrafe  < -dz;

        } else {
            if (getAutoFaceTarget()) {
                float desiredYRot = Mth.wrapDegrees(targetYaw);
                smoothedYRot = smoothAngle(smoothedYRot, desiredYRot, getIdleTurnSpeed());
            }

            player.setYRot(smoothedYRot);

            input.forwardImpulse = 0;
            input.leftImpulse = 0;
            input.up = false;
            input.down = false;
            input.left = false;
            input.right = false;
        }

        player.yBodyRot = player.getYRot();
        player.yHeadRot = player.getYRot();
    }

    @SubscribeEvent
    public void onClientTickEnd(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;
        if (mc.options.getCameraType() != CameraType.FIRST_PERSON) return;
        if (!IntegrationRegistry.isBetterLockOn()) return;
        EpicFightCameraAPI api = getAPI();
        if (api == null || !api.isLockingOnTarget()) return;
        try {
            float cameraYRot = api.getCameraYRot();
            player.setYRot(cameraYRot);
            player.yRotO = cameraYRot;
        } catch (Throwable ignored) {}
    }

    // LOWEST so player-tick rotation override runs after EpicFight's tick logic
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!event.side.isClient()) return;

        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || event.player != player) return;

        if (IntegrationRegistry.isBetterLockOn()) return;

        EpicFightCameraAPI api = getAPI();
        if (api == null || !api.isLockingOnTarget()) return;

        LivingEntity target = api.getFocusingEntity();
        if (target == null || !target.isAlive()) return;

        if (!Float.isNaN(smoothedYRot)) {
            player.setYRot(smoothedYRot);
            player.yBodyRot = smoothedYRot;
            player.yHeadRot = smoothedYRot;
        }
    }

    public static float getSmoothedYRot() {
        return smoothedYRot;
    }

    public static void setSmoothedYRot(float yRot) {
        smoothedYRot = yRot;
    }
}
