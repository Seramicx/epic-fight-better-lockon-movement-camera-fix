package com.lockonfix.handler;

import com.lockonfix.FixConfig;
import com.lockonfix.LockOnMovementFix;
import com.lockonfix.client.EpicFightClientHooks;
import com.lockonfix.compat.ControllableIntegration;
import com.lockonfix.compat.IntegrationRegistry;
import com.lockonfix.compat.ValkyrienSkiesIntegration;
import com.mojang.logging.LogUtils;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import yesman.epicfight.api.animation.types.EntityState;
import yesman.epicfight.api.client.camera.EpicFightCameraAPI;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;
import yesman.epicfight.client.world.capabilites.entitypatch.player.LocalPlayerPatch;

/**
 * 360 degree free movement during lock-on, plus body smoothing that survives
 * Epic Fight's per-tick {@code postClientTick} yRot rewrites. We track our own
 * {@code smoothedYRot} independent of what EF writes and re-apply it in both
 * {@code MovementInputUpdateEvent} and {@code PlayerTickEvent.END}.
 */
@Mod.EventBusSubscriber(modid = LockOnMovementFix.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class LockOnMovementHandler {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Minecraft MC = Minecraft.getInstance();

    private static final float DEFAULT_TURN_SPEED = 0.45F;
    private static final float DEFAULT_IDLE_TURN_SPEED = 0.7F;
    private static final boolean DEFAULT_AUTO_FACE_TARGET = true;

    private static EpicFightCameraAPI cachedAPI = null;

    private static float smoothedYRot = Float.NaN;
    private static boolean wasLockedOn = false;

    private static float getTurnSpeed() {
        try { return (float) FixConfig.TURN_SPEED.get().doubleValue(); }
        catch (Exception e) { return DEFAULT_TURN_SPEED; }
    }

    private static float getIdleTurnSpeed() {
        try { return (float) FixConfig.IDLE_TURN_SPEED.get().doubleValue(); }
        catch (Exception e) { return DEFAULT_IDLE_TURN_SPEED; }
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

        // On a VS2 ship, player.yRot is in ship-local space. Convert the
        // world-space target yaw to ship-local so setYRot() points at the
        // target in world space after VS2's render-time transform.
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
        // isAiming() covers ranged item draw, Iron's items right-click, active
        // cast, cast latch, and any cast keymap held, so this stays true
        // through the whole quick-cast / continuous spell window.
        return isHoldingGuard(player)
                || player.isBlocking()
                || EpicFightClientHooks.isAiming(player);
    }

    private static float[] readDirectionalInput(Input input) {
        float rawForward = 0;
        if (MC.options.keyUp.isDown()) rawForward += 1.0F;
        if (MC.options.keyDown.isDown()) rawForward -= 1.0F;

        float rawStrafe = 0;
        if (MC.options.keyLeft.isDown()) rawStrafe += 1.0F;
        if (MC.options.keyRight.isDown()) rawStrafe -= 1.0F;

        // Controllable updates input.forwardImpulse/leftImpulse but not the
        // keyboard isDown() states, so the analog fallback fires correctly
        // when on controller.
        if (rawForward == 0 && rawStrafe == 0) {
            float[] analog = ControllableIntegration.readAnalogDirection(input);
            rawForward = analog[0];
            rawStrafe  = analog[1];
        }

        return new float[]{rawForward, rawStrafe};
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onMovementInput(MovementInputUpdateEvent event) {
        LocalPlayer player = MC.player;
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

        // BLO compensates its sprint-convert (S/A/D -> forward) by rotating
        // player.yRot to a side direction, but that compensation is gated to
        // 3rd person only (EpicFightCameraAPIMixin line 604). In 1st person,
        // sprinting + locked on while pressing S/A/D drives the player toward
        // the target. So in 1st person we always need to run our 360 code.
        // In 3rd person, defer to BLO unless we're aiming / blocking / casting.
        boolean needAutoFace = shouldAutoFaceTarget(player) && getAutoFaceTarget();
        boolean isFirstPerson = MC.options.getCameraType() == CameraType.FIRST_PERSON;
        if (IntegrationRegistry.isBetterLockOn() && !needAutoFace && !isFirstPerson) {
            return;
        }

        wasLockedOn = true;
        Input input = event.getInput();

        if (Float.isNaN(smoothedYRot)) {
            smoothedYRot = player.getYRot();
        }

        if (shouldAutoFaceTarget(player) && getAutoFaceTarget()) {
            // Body smoothly rotates to face the target while movement stays
            // world-space (W=toward, A=left, S=away, D=right) so a held key
            // doesn't drift as the body rotates.
            float targetYaw = getYawToTarget(player, target);
            smoothedYRot = smoothAngle(smoothedYRot, targetYaw, getIdleTurnSpeed());
            player.setYRot(smoothedYRot);
            player.yBodyRot = smoothedYRot;
            player.yHeadRot = smoothedYRot;

            float[] dir = readDirectionalInput(input);
            float rawForward = dir[0];
            float rawStrafe = dir[1];
            float rawMagnitude = Mth.sqrt(rawForward * rawForward + rawStrafe * rawStrafe);

            // Preserve magnitude shrinks applied by earlier handlers (Iron's
            // Spells multiplies impulses by ~0.2 during a cast). Using
            // rawMagnitude alone would skip that slowdown.
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

        // Aiming (bow/spell): skip body rotation entirely. Epic Fight's
        // postClientTick already aligns yRot to the camera hit-point so the
        // projectile spawns toward the crosshair; touching yBodyRot would
        // visibly spin the character.
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

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!event.side.isClient()) return;

        LocalPlayer player = MC.player;
        if (player == null || event.player != player) return;

        // BLO handles yaw override; our smoothedYRot would be stale.
        if (IntegrationRegistry.isBetterLockOn()) return;

        EpicFightCameraAPI api = getAPI();
        if (api == null || !api.isLockingOnTarget()) return;

        LivingEntity target = api.getFocusingEntity();
        if (target == null || !target.isAlive()) return;

        // When locked on, drive yRot toward the target even mid-cast or
        // mid-draw, so auto-face holds while quick-casting in a different
        // direction.
        //
        // Do not snap yRotO/yBodyRotO/yHeadRotO to smoothedYRot here.
        // LivingEntity.aiStep already captured them at the start of this
        // tick (the previous tick's values), and per-frame
        // lerp(yRotO, yRot, partialTick) needs that to look smooth.
        if (!Float.isNaN(smoothedYRot)) {
            player.setYRot(smoothedYRot);
            player.yBodyRot = smoothedYRot;
            player.yHeadRot = smoothedYRot;
        }
    }

    /** Current tracked body yaw, or NaN when not locked on. */
    public static float getSmoothedYRot() {
        return smoothedYRot;
    }

    /**
     * Force-set the tracked body yaw. Used by {@code QuickCastAimHandler} on
     * cast-key edge press while locked on, so the very first cast tick fires
     * at the target instead of smoothing in from a stale value.
     */
    public static void setSmoothedYRot(float yRot) {
        smoothedYRot = yRot;
    }
}
