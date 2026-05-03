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

        // 1ST PERSON LOCK-ON
        //
        // The fundamental difference vs. 3rd person:
        //   - 3rd person: BLO's setupCamera writes to its own Camera object,
        //     not to player.yRot. So BLO's postClientTick (line 615) can
        //     freely rotate player.yRot to match movement direction; the
        //     camera stays facing the target via cameraYRot independently.
        //   - 1st person: BLO's setupCamera writes player.yRot directly
        //     (player.yRot IS the camera). BLO's postClientTick writeback
        //     to player.yRot is gated to 3rd person, so player.yRot just
        //     stays as whatever cameraYRot lerp said it was -- always
        //     pointing at the target.
        //
        // Travel() uses player.yRot for movement direction. With BLO's
        // sprint-mangle (S/A/D -> forward), player.yRot = target_dir, and
        // forwardImpulse > 0, you sprint TOWARD the target regardless of
        // which key you pressed.
        //
        // Fix: do BLO's 3rd-person compensation ourselves at MovementInput
        // time. Set player.yRot to the desired movement direction
        // (target_yaw + WASD_offset) so travel() walks/sprints in the
        // intended direction. BLO's setupCamera per-frame then overrides
        // player.yRot back to cameraYRot for the camera render, so the
        // user still sees the target. Two different yRot values used at
        // two different times: tick-time for movement, frame-time for
        // camera.
        boolean isFirstPerson = MC.options.getCameraType() == CameraType.FIRST_PERSON;
        if (isFirstPerson) {
            wasLockedOn = true;
            Input fpInput = event.getInput();

            if (IntegrationRegistry.isBetterLockOn()) {
                // 1ST PERSON LOCK-ON
                //
                // Sprint case: vanilla MC requires forwardImpulse > 0 for
                // sprint to engage. BLO's LockOnControl mangles S/A/D into
                // forward (forwardImpulse > 0) when sprinting+locked, but
                // leaves player.yRot pointing at the target — so without
                // yRot rotation, sprint+S would run you toward the target.
                // We rotate player.yRot to the WASD-relative movement
                // direction so travel() sprints in the right direction.
                //
                // The catch: setting yRot to a wildly different value
                // causes vanilla Entity to unwrap yRotO by +/-360 to keep
                // |yRot - yRotO| < 180. Then BLO's setupCamera per-frame
                // override of yRot to cameraYRot leaves a 360-degree gap
                // between yRotO and yRot, and vanilla Camera.setup does
                // Mth.lerp(yRotO, yRot, partialTick) -- interpolating across
                // that gap, causing visible camera jitter every render frame.
                //
                // We work around this by resetting BOTH yRot and yRotO
                // back to cameraYRot in PlayerTickEvent.END (after travel,
                // before render). That way, render-side lerp(yRotO, yRot)
                // stays close to cameraYRot and the camera renders cleanly.
                float[] dir = readDirectionalInput(fpInput);
                float rawForward = dir[0];
                float rawStrafe = dir[1];
                float rawMagnitude = Mth.sqrt(rawForward * rawForward + rawStrafe * rawStrafe);
                boolean isMoving = rawMagnitude > 0.01F;
                boolean sprintHeld = MC.options.keySprint.isDown() && !MC.options.keyUse.isDown();

                if (sprintHeld && isMoving) {
                    // Sprint path: rotate yRot to movement direction so
                    // travel + sprint moves correctly. Set both yRot and
                    // yRotO to the same value so the in-tick vanilla unwrap
                    // doesn't fire (yRotO would otherwise get unwrapped by
                    // 360 to track our wild yRot value).
                    float targetYaw = getYawToTarget(player, target);
                    float offsetAngle = -(float)Math.toDegrees(Math.atan2(rawStrafe, rawForward));
                    float movementYaw = Mth.wrapDegrees(targetYaw + offsetAngle);
                    player.setYRot(movementYaw);
                    player.yRotO = movementYaw;
                    // Leave fpInput.forwardImpulse / leftImpulse as BLO set
                    // them (positive forward).
                } else {
                    // Walk / idle path: just restore raw input. Don't touch
                    // yRot -- it stays at whatever turnPlayer + setupCamera
                    // last left it (~cameraYRot ~ target direction). Vanilla
                    // travel interprets WASD relative to that: W=toward,
                    // S=away, A=strafe-left, D=strafe-right.
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

        // 3RD PERSON LOCK-ON
        // Defer to BLO unless we're aiming / blocking / casting (BLO doesn't
        // know about Iron's Spells, and we need world-space movement math
        // during a cast or W/A/S/D would drag the player toward the target).
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

    /**
     * 1st person lock-on yRot reset. Runs at ClientTickEvent.END which
     * fires after the entire Minecraft.tick() body, including turnPlayer
     * (which would otherwise leave player.yRot mouse-rotated and far
     * from cameraYRot at the next tick start).
     *
     * <p>Travel has already used whatever wild movement-direction yRot we
     * set in MovementInputUpdateEvent. Now reset BOTH yRot and yRotO to
     * cameraYRot. The MixinEntityViewRot bypass keeps the camera using
     * cameraYRot lerp regardless, but resetting yRot keeps gameplay
     * systems (animations, head bob, networking) consistent with where
     * the camera is pointing.
     */
    @SubscribeEvent
    public static void onClientTickEnd(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        LocalPlayer player = MC.player;
        if (player == null) return;
        if (MC.options.getCameraType() != CameraType.FIRST_PERSON) return;
        if (!IntegrationRegistry.isBetterLockOn()) return;
        EpicFightCameraAPI api = getAPI();
        if (api == null || !api.isLockingOnTarget()) return;
        try {
            float cameraYRot = api.getCameraYRot();
            player.setYRot(cameraYRot);
            player.yRotO = cameraYRot;
        } catch (Throwable ignored) {}
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
