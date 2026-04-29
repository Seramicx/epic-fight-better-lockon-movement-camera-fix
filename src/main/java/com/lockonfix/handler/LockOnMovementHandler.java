package com.lockonfix.handler;

import com.lockonfix.FixConfig;
import com.lockonfix.LockOnMovementFix;
import com.lockonfix.client.EpicFightClientHooks;
import com.lockonfix.compat.BLOTransitionSkipHook;
import com.lockonfix.compat.ControllableIntegration;
import com.lockonfix.compat.IntegrationRegistry;
import com.lockonfix.compat.ValkyrienSkiesIntegration;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
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
 * 360° free movement during lock-on, plus camera/body decoupling for sprint
 * and mount.
 *
 * <p>Epic Fight's postClientTick rotates player.yRot toward the target at
 * 30°/tick. To avoid that corrupting our smoothing, we track our own
 * smoothedYRot independent of what Epic Fight writes, and re-apply yRot in
 * both {@code MovementInputUpdateEvent} (for correct movement direction) and
 * {@code PlayerTickEvent.END} (to override postClientTick).
 */
@Mod.EventBusSubscriber(modid = LockOnMovementFix.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class LockOnMovementHandler {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Minecraft MC = Minecraft.getInstance();

    // Safe defaults
    private static final float DEFAULT_TURN_SPEED = 0.45F;
    private static final float DEFAULT_IDLE_TURN_SPEED = 0.7F;
    private static final boolean DEFAULT_AUTO_FACE_TARGET = true;

    // Cached API
    private static EpicFightCameraAPI cachedAPI = null;

    // =====================================================================
    // OUR OWN tracked rotation state (immune to Epic Fight's overrides)
    // =====================================================================
    private static float smoothedYRot = Float.NaN;
    private static boolean wasLockedOn = false;

    // =====================================================================
    // Sprint-rotate (non-lock-on, 3rd person, sprinting): decouple body and
    // camera so pressing A/D/S while sprinting turns the body to that
    // direction at sprint speed without rotating the camera.
    // =====================================================================
    private static boolean sprintRotateActive = false;
    private static float sprintRotateSavedYaw = 0F;

    // =====================================================================
    // Mount-rotate (non-lock-on, 3rd person, riding a Mob): camera/body
    // decoupling, BTP-style. While decoupleActive, mouse deltas are routed
    // to decoupledCameraYaw/XRot via MixinEntityTurnDecouple instead of
    // player.yRot/xRot, and Camera.setup reads from decoupledCameraYaw via
    // MixinCameraDecouple. player.yRot is set persistently to bodyYaw so the
    // mount steers toward WASD direction (and the server agrees).
    // =====================================================================
    private static volatile boolean mountRotateActive = false;
    private static boolean mountRotateWasActiveLastTick = false;
    private static float mountSmoothedYaw = Float.NaN;

    private static volatile boolean decoupleActive = false;
    private static volatile boolean decoupleTransitioning = false;
    private static volatile float decoupledCameraYaw = 0F;
    private static volatile float decoupledCameraXRot = 0F;

    // Tracks api.isLockingOnTarget() across ticks so we can detect the
    // true->false edge and trigger BLOTransitionSkipHook on lock-off.
    private static boolean wasApiLockedOn = false;

    public static boolean isMountRotateActive() { return mountRotateActive; }
    /** Read by MixinLivingEntityRiddenInterp to substitute pPlayer.yRot inside the mount's tickRidden. */
    public static float getMountSmoothedYaw()  { return mountSmoothedYaw; }
    /**
     * Read by MixinLocalPlayerInputOverride to force input at HEAD of serverAiStep,
     * after BLO/other mods may have rewritten it during MovementInputUpdateEvent.
     */
    public static float getMountInputMagnitude() { return mountInputMagnitude; }
    private static volatile float mountInputMagnitude = 0F;

    /**
     * True only while user-driven camera turn code is running (mouse path:
     * {@code MouseHandler.turnPlayer}; controller path: Controllable's
     * {@code CameraHandler.updateCamera}). {@code MixinEntityTurnDecouple}
     * gates on this so only legitimate input routes deltas to the decoupled
     * camera; synthetic Entity.turn calls (Epic Fight / BLO transitions) do
     * not.
     */
    public static boolean isProcessingMouseTurn() { return processingMouseTurn; }
    public static void setProcessingMouseTurn(boolean v) { processingMouseTurn = v; }
    private static volatile boolean processingMouseTurn = false;

    /** True while the camera is decoupled from player.yRot (mount-rotate active path). */
    public static boolean isDecoupleActive()       { return decoupleActive; }
    /** Camera yaw to render when decoupled. */
    public static float   getDecoupledCameraYaw() { return decoupledCameraYaw; }
    /** Camera pitch to render when decoupled. */
    public static float   getDecoupledCameraXRot(){ return decoupledCameraXRot; }

    /** Called from MixinEntityTurnDecouple to push captured mouse deltas into the camera. */
    public static void addCameraDelta(float dy, float dx) {
        decoupledCameraYaw  = Mth.wrapDegrees(decoupledCameraYaw + dy);
        decoupledCameraXRot = Mth.clamp(decoupledCameraXRot + dx, -90F, 90F);
    }

    /**
     * Smoothly exit the decoupled mode. Doesn't snap yRot: sets
     * {@code decoupleTransitioning = true} so {@link #onPlayerTick} can lerp
     * {@code player.yRot/xRot} toward {@code decoupledCameraYaw/XRot} at
     * {@code MOUNT_TURN_SPEED}. Camera redirect and mouse intercept stay
     * active during the transition so the user can free-look while the body
     * rotates back; once the body catches up, decouple finalizes and vanilla
     * mouse to player.yRot resumes.
     */
    private static void deactivateDecouple(LocalPlayer player) {
        if (decoupleActive && !decoupleTransitioning) {
            // Normalize player.yRot to within ±180° of decoupledCameraYaw
            // before the transition lerp starts. Visually unchanged (rotation
            // is mod 360°), but unwrapped player.yRot can drift far outside
            // [-180, 180] over a session, hitting an FP edge at exactly 180°
            // in Mth.wrapDegrees that flips the lerp's direction and
            // produces a ~360° spin.
            float py = player.getYRot();
            float wrapped = Mth.wrapDegrees(py - decoupledCameraYaw);
            float normalized = decoupledCameraYaw + wrapped;
            if (normalized != py) {
                player.setYRot(normalized);
                player.yRotO = normalized;
            }
            decoupleTransitioning = true;
        }
        mountRotateActive = false;
        mountRotateWasActiveLastTick = false;
    }

    // =====================================================================
    // Safe config reads
    // =====================================================================

    private static float getTurnSpeed() {
        try { return (float) FixConfig.TURN_SPEED.get().doubleValue(); }
        catch (Exception e) { return DEFAULT_TURN_SPEED; }
    }

    private static float getIdleTurnSpeed() {
        try { return (float) FixConfig.IDLE_TURN_SPEED.get().doubleValue(); }
        catch (Exception e) { return DEFAULT_IDLE_TURN_SPEED; }
    }

    private static float getMountTurnSpeed() {
        try { return (float) FixConfig.MOUNT_TURN_SPEED.get().doubleValue(); }
        catch (Exception e) { return 0.25F; }
    }

    /** True when the player is the controlling passenger of a mountable Mob (excludes boats/minecarts/passengers). */
    private static boolean isOnMountedMob(LocalPlayer player) {
        Entity v = player.getVehicle();
        return v instanceof Mob mob && mob.getControllingPassenger() == player;
    }

    private static boolean getAutoFaceTarget() {
        try { return FixConfig.AUTO_FACE_TARGET.get(); }
        catch (Exception e) { return DEFAULT_AUTO_FACE_TARGET; }
    }

    // =====================================================================
    // API access
    // =====================================================================

    private static EpicFightCameraAPI getAPI() {
        if (cachedAPI == null) {
            try { cachedAPI = EpicFightCameraAPI.getInstance(); }
            catch (Exception e) { return null; }
        }
        return cachedAPI;
    }

    // =====================================================================
    // Math helpers
    // =====================================================================

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

    // =====================================================================
    // Attack state detection via Epic Fight capabilities
    // =====================================================================

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

    // =====================================================================
    // Input reading: keyboard with controller fallback
    // =====================================================================

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

    // =====================================================================
    // PRIMARY: MovementInputUpdateEvent (after BetterLockOn)
    // =====================================================================

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onMovementInput(MovementInputUpdateEvent event) {
        LocalPlayer player = MC.player;
        if (player == null) return;

        EpicFightCameraAPI api = getAPI();
        boolean isLockedOnNow = api != null && api.isLockingOnTarget();

        // BLO holds its lock-on camera transform for ~3s post-release
        // (blo$unlockDelayTick max 60 + BLOCameraSetting.transitionTick max
        // 30). On a mount that produces a vanilla "camera follows mount"
        // window before mount-rotate takes over; force-skip both timers.
        if (wasApiLockedOn && !isLockedOnNow && isOnMountedMob(player)) {
            BLOTransitionSkipHook.skipPostLockOff();
        }
        wasApiLockedOn = isLockedOnNow;

        if (!isLockedOnNow) {
            if (wasLockedOn) {
                smoothedYRot = Float.NaN;
                wasLockedOn = false;
            }
            // Mount-rotate runs first; if it activates the player isn't on
            // foot so sprint-rotate is naturally a no-op.
            if (!handleMountRotate(player, event.getInput())) {
                handleSprintRotate(player, event.getInput());
            }
            return;
        }

        LivingEntity target = api.getFocusingEntity();
        if (target == null || !target.isAlive()) return;

        // Locked-on path is taking over: tear down decouple instantly. Can't
        // run the smooth transition here, because BLO drives player.yRot
        // every tick during lock-on and the lerp would fight it forever
        // (never converging to |dy| < 1).
        if (decoupleActive) {
            decoupleActive = false;
            decoupleTransitioning = false;
            mountRotateActive = false;
            mountRotateWasActiveLastTick = false;
        }

        // BLO already handles 360° movement and guard auto-face. Defer to it
        // unless we're aiming/casting: BLO doesn't know about Iron's Spells,
        // and we need our world-space movement math during a cast or
        // W/A/S/D would otherwise drag the player toward the target.
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
            // doesn't drift as the body rotates. Project the desired world
            // yaw onto the body-relative axes against current smoothedYRot.
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

        // On a mount: slower per-tick smoothing so the larger model doesn't
        // read as 8-direction snaps. Combined with the per-frame interp in
        // MixinLivingEntityRiddenInterp, this gives a smooth 360° turn.
        boolean onMountedMob = isOnMountedMob(player);
        float turnSpeed = onMountedMob ? getMountTurnSpeed() : getTurnSpeed();
        float idleTurnSpeed = onMountedMob ? getMountTurnSpeed() : getIdleTurnSpeed();

        if (isMoving) {
            float offsetAngle = -(float) Math.toDegrees(Math.atan2(rawStrafe, rawForward));
            float desiredYRot = Mth.wrapDegrees(targetYaw + offsetAngle);

            smoothedYRot = smoothAngle(smoothedYRot, desiredYRot, turnSpeed);
            player.setYRot(smoothedYRot);

            float magnitude = Mth.sqrt(rawForward * rawForward + rawStrafe * rawStrafe);
            float speed = input.shiftKeyDown ? magnitude * 0.3F : magnitude;

            input.forwardImpulse = speed;
            input.leftImpulse = 0;

            // Preserve raw directional booleans for any mod that reads them.
            // DodgeSkillMixin reads raw key states directly, so these
            // booleans don't affect dodge direction.
            float dz = 0.3F;
            input.up    = rawForward >  dz;
            input.down  = rawForward < -dz;
            input.left  = rawStrafe  >  dz;
            input.right = rawStrafe  < -dz;

        } else {
            if (getAutoFaceTarget()) {
                float desiredYRot = Mth.wrapDegrees(targetYaw);
                smoothedYRot = smoothAngle(smoothedYRot, desiredYRot, idleTurnSpeed);
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

    // =====================================================================
    // Mount-rotate (non-lock-on, third-person, riding a Mob)
    //
    // BTP-style camera/body decoupling. While active, mouse/right-stick
    // deltas route to decoupledCameraYaw (MixinEntityTurnDecouple), and
    // Camera.setup reads from there (MixinCameraDecouple). player.yRot is
    // set persistently to mountSmoothedYaw so the mount steers there and the
    // server agrees (no rotation snap-back).
    //
    // Returns true if mount-rotate took effect this tick (caller skips
    // sprint-rotate; they're mutually exclusive).
    // =====================================================================
    private static boolean handleMountRotate(LocalPlayer player, Input input) {
        mountRotateActive = false;
        if (MC.options.getCameraType() != net.minecraft.client.CameraType.THIRD_PERSON_BACK) {
            deactivateDecouple(player);
            mountSmoothedYaw = Float.NaN;
            return false;
        }
        if (!isOnMountedMob(player)) {
            deactivateDecouple(player);
            mountSmoothedYaw = Float.NaN;
            return false;
        }

        // Aiming/blocking/using-item: leave camera-driven yaw alone so the
        // bow/spell/shield aims at the crosshair from horseback. Same gate
        // sprint-rotate uses on foot.
        if (EpicFightClientHooks.isAiming(player)
                || player.isUsingItem()
                || player.isBlocking()) {
            deactivateDecouple(player);
            return false;
        }

        float[] dir = readDirectionalInput(input);
        float rawForward = dir[0];
        float rawStrafe = dir[1];
        float rawMagnitude = Mth.sqrt(rawForward * rawForward + rawStrafe * rawStrafe);
        if (rawMagnitude < 0.01F) {
            // No input: release decouple so the user can free-look while idle.
            deactivateDecouple(player);
            return false;
        }

        // If a deactivation transition was running, cancel it and resume
        // smoothing from the player's current yaw to avoid a jump.
        if (decoupleTransitioning) {
            decoupleTransitioning = false;
            mountSmoothedYaw = player.getYRot();
        }

        // First activation in this burst: snapshot the camera direction and
        // seed the body smoother.
        if (!decoupleActive) {
            decoupledCameraYaw  = player.getYRot();
            decoupledCameraXRot = player.getXRot();
            mountSmoothedYaw    = player.getYRot();
        }

        float offsetAngle = -(float) Math.toDegrees(Math.atan2(rawStrafe, rawForward));
        float bodyYaw     = Mth.wrapDegrees(decoupledCameraYaw + offsetAngle);

        mountSmoothedYaw = smoothAngle(mountSmoothedYaw, bodyYaw, getMountTurnSpeed());

        // Persist body yaw. The mount's tickRidden reads player.yRot and
        // steers there; the server receives the same value via
        // ServerboundMovePlayerPacket.Rot.
        player.setYRot(mountSmoothedYaw);

        // Forward-only impulse in the body's frame.
        float modMagnitude = Mth.sqrt(input.forwardImpulse * input.forwardImpulse
                + input.leftImpulse * input.leftImpulse);
        float magnitude = Math.min(rawMagnitude, modMagnitude);
        input.forwardImpulse = magnitude;
        input.leftImpulse = 0F;
        // Published for MixinLocalPlayerInputOverride. If a lower-priority
        // mod overwrites the impulses, the mixin re-applies these values at
        // HEAD of serverAiStep.
        mountInputMagnitude = magnitude;

        decoupleActive = true;
        mountRotateActive = true;
        mountRotateWasActiveLastTick = true;
        return true;
    }

    // =====================================================================
    // Sprint-rotate (non-lock-on, third-person, sprinting on foot)
    //
    // Body runs in the direction of input while the camera stays where the
    // mouse pointed. We override player.yRot to the body direction during
    // the tick (so vanilla travel + sprint check both pass), then restore
    // yRot at PlayerTickEvent.END so the camera returns to the user's aim.
    // =====================================================================
    private static void handleSprintRotate(LocalPlayer player, Input input) {
        sprintRotateActive = false;
        if (MC.options.getCameraType() != net.minecraft.client.CameraType.THIRD_PERSON_BACK) return;
        if (!player.isSprinting()) return;

        // While aiming/casting/using-item/blocking, body must lock to camera
        // direction so projectiles fly at the crosshair. Skip sprint-rotate
        // so vanilla yRot drives both body facing and WASD direction.
        if (EpicFightClientHooks.isAiming(player)
                || player.isUsingItem()
                || player.isBlocking()) return;

        float[] dir = readDirectionalInput(input);
        float rawForward = dir[0];
        float rawStrafe = dir[1];
        float rawMagnitude = Mth.sqrt(rawForward * rawForward + rawStrafe * rawStrafe);
        if (rawMagnitude < 0.01F) return;

        float cameraYaw = player.getYRot();
        float offsetAngle = -(float) Math.toDegrees(Math.atan2(rawStrafe, rawForward));
        float bodyYaw = Mth.wrapDegrees(cameraYaw + offsetAngle);

        sprintRotateSavedYaw = cameraYaw;
        player.setYRot(bodyYaw);

        // forwardImpulse positive (>= 0.8 sprint threshold) keeps MC's sprint
        // check happy. min(...) preserves any prior mod-applied slowdown.
        float modMagnitude = Mth.sqrt(input.forwardImpulse * input.forwardImpulse
                + input.leftImpulse * input.leftImpulse);
        float magnitude = Math.min(rawMagnitude, modMagnitude);
        input.forwardImpulse = magnitude;
        input.leftImpulse = 0F;

        sprintRotateActive = true;
    }

    // =====================================================================
    // SECONDARY: PlayerTickEvent.END (after Epic Fight's postClientTick)
    // =====================================================================

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!event.side.isClient()) return;

        LocalPlayer player = MC.player;
        if (player == null || event.player != player) return;

        // Restore the user's camera yaw if sprint-rotate diverted yRot to the
        // body direction during this tick. Vanilla tickHeadTurn already lerped
        // yBodyRot toward the body direction; we just put yRot back so the
        // camera returns to the user's mouse aim.
        if (sprintRotateActive) {
            player.setYRot(sprintRotateSavedYaw);
            player.yRotO = sprintRotateSavedYaw;
            // Head follows body (movement direction), not camera. Otherwise
            // running backward looks weird (body away from camera, head
            // facing camera). Vanilla tickHeadTurn already smoothed
            // yBodyRot toward bodyYaw, so just sync head.
            player.yHeadRot = player.yBodyRot;
            player.yHeadRotO = player.yBodyRotO;
            sprintRotateActive = false;
        }

        // Mount-rotate aligns the rider's body to bodyYaw. Vanilla
        // tickHeadTurn sees zero movement delta for a mounted player (seat-
        // locked), so without this override the rider's body would freeze at
        // whatever yBodyRot it had when mount-rotate started.
        //
        // We also re-apply player.yRot here at LOWEST priority so Epic
        // Fight's postClientTick (which rotates yRot toward the locked-on
        // target while ridden) doesn't leave the rider facing a former
        // target after lock-off. *_O fields are left alone so vanilla's
        // start-of-tick capture drives smooth per-frame interpolation.
        if (mountRotateActive) {
            player.setYRot(mountSmoothedYaw);
            player.yBodyRot = mountSmoothedYaw;
            player.yHeadRot = mountSmoothedYaw;
        }

        // Smooth deactivation: lerp player.yRot toward the decoupled camera
        // direction at MOUNT_TURN_SPEED. Camera redirect stays on so the
        // camera holds at the user's last mouse direction; once the body
        // catches up, decouple finalizes and vanilla mouse to player.yRot
        // resumes.
        if (decoupleActive && decoupleTransitioning) {
            float currentYRot = player.getYRot();
            float dy = Mth.wrapDegrees(decoupledCameraYaw - currentYRot);
            float currentXRot = player.getXRot();
            float dx = decoupledCameraXRot - currentXRot;
            if (Math.abs(dy) < 1.0F && Math.abs(dx) < 1.0F) {
                player.setYRot(decoupledCameraYaw);
                player.setXRot(decoupledCameraXRot);
                player.yBodyRot = decoupledCameraYaw;
                player.yHeadRot = decoupledCameraYaw;
                decoupleActive = false;
                decoupleTransitioning = false;
            } else {
                float step = getMountTurnSpeed();
                float newYRot = currentYRot + dy * step;
                float newXRot = currentXRot + dx * step;
                player.setYRot(newYRot);
                player.setXRot(newXRot);
                player.yBodyRot = newYRot;
                player.yHeadRot = newYRot;
            }
        }

        // BLO handles yaw override; our smoothedYRot would be stale.
        if (IntegrationRegistry.isBetterLockOn()) return;

        EpicFightCameraAPI api = getAPI();
        if (api == null || !api.isLockingOnTarget()) return;

        LivingEntity target = api.getFocusingEntity();
        if (target == null || !target.isAlive()) return;

        // When locked on, drive yRot toward the target even mid-cast or
        // mid-draw, so auto-face holds while quick-casting in a different
        // direction. (No-lock-on aim correction is handled by
        // MixinMultiPlayerGameMode and QuickCastAimHandler.)
        //
        // Do not snap yRotO/yBodyRotO/yHeadRotO to smoothedYRot here.
        // LivingEntity.aiStep already captured them at the start of this
        // tick (the previous tick's values), and per-frame
        // lerp(yRotO, yRot, partialTick) needs that to look smooth.
        // Overwriting *_O collapses the lerp and produces a per-tick snap.
        if (!Float.isNaN(smoothedYRot)) {
            player.setYRot(smoothedYRot);
            player.yBodyRot = smoothedYRot;
            player.yHeadRot = smoothedYRot;
        }
    }

    // =====================================================================
    // Shared state
    // =====================================================================

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
