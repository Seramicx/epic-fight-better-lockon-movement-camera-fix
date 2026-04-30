package com.lockonfix.handler;

import com.lockonfix.FixConfig;
import com.lockonfix.LockOnMovementFix;
import com.lockonfix.compat.IntegrationRegistry;
import com.lockonfix.compat.ValkyrienSkiesIntegration;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Over-the-shoulder camera system:
 *   1. Camera offset (ViewportEvent.ComputeCameraAngles): lateral + vertical + zoom shift.
 *   2. Player visibility (RenderLivingEvent.Pre): hide when camera is too close.
 *
 * All hooks fire after Epic Fight's camera mixin so its lock-on camera is preserved.
 */
@Mod.EventBusSubscriber(modid = LockOnMovementFix.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class CameraOffsetHandler {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Minecraft MC = Minecraft.getInstance();

    // Smoothed offset state (lerps toward target for shoulder swap transitions)
    private static float currentOffsetX = 0;
    private static float currentOffsetY = 0;
    private static float currentOffsetZ = 0;
    private static double currentCollisionDist = -1;
    private static boolean initialized = false;

    // Shoulder-mode cycle: 0 = right, 1 = left, 2 = overhead.
    // Runtime-only (not persisted); cycles on each press of SWAP_SHOULDER.
    private static final int MODE_RIGHT = 0;
    private static final int MODE_LEFT = 1;
    private static final int MODE_OVERHEAD = 2;
    private static final int MODE_COUNT = 3;
    private static int shoulderMode = MODE_RIGHT;
    private static boolean defaultShoulderApplied = false;

    // Whether the offset was actually applied this frame (used by crosshair + visibility)
    private static boolean offsetActiveThisFrame = false;

    // Reflection: Camera.position field (found by type, mapping-agnostic)
    private static Field cameraPositionField = null;
    // Reflection: Camera.blockPosition field (MutableBlockPos)
    private static Field cameraBlockPositionField = null;
    // Reflection: Camera.checkInFluid() method (to fix underwater flickering)
    private static Method cameraCheckInFluidMethod = null;
    // Reflection: Camera.move(double, double, double) method (fallback)
    private static Method cameraMoveMethod = null;
    private static boolean reflectionAttempted = false;

    // =====================================================================
    // Safe config reads
    // =====================================================================

    private static double getOffsetX() {
        try { return FixConfig.CAMERA_OFFSET_X.get(); }
        catch (Exception e) { return -0.75; }
    }

    private static double getOffsetY() {
        try { return FixConfig.CAMERA_OFFSET_Y.get(); }
        catch (Exception e) { return 0.15; }
    }

    private static double getOffsetZ() {
        try { return FixConfig.CAMERA_OFFSET_Z.get(); }
        catch (Exception e) { return 0.0; }
    }

    private static double getSmoothing() {
        try { return FixConfig.CAMERA_OFFSET_SMOOTHING.get(); }
        catch (Exception e) { return 0.5; }
    }

    private static boolean getHidePlayerWhenClose() {
        try { return FixConfig.HIDE_PLAYER_WHEN_CLOSE.get(); }
        catch (Exception e) { return true; }
    }

    private static double getHidePlayerDistance() {
        try { return FixConfig.HIDE_PLAYER_DISTANCE.get(); }
        catch (Exception e) { return 0.8; }
    }

    private static double getOverheadOffsetY() {
        try { return FixConfig.CAMERA_OVERHEAD_OFFSET_Y.get(); }
        catch (Exception e) { return 1.2; }
    }

    private static double getLookDownCenterAngle() {
        try { return FixConfig.CAMERA_LOOK_DOWN_CENTER_ANGLE.get(); }
        catch (Exception e) { return 1.0; }
    }

    // =====================================================================
    // Reflection: find Camera internals by type/signature (mapping-agnostic)
    // =====================================================================

    private static void initReflection() {
        if (reflectionAttempted) return;
        reflectionAttempted = true;

        // Strategy 1: resolve Camera.position by name (mojang/parchment, then SRG).
        // Iterating fields by type alone collides with mod mixins that add Vec3 fields
        // to Camera (e.g. block_factorys_bosses' bosses_rise_java$pos for cinematic mode):
        // getDeclaredFields() ordering is undefined, so a type-iteration loop can pick
        // the mixin synthetic instead of vanilla position and the offset gets written
        // to a field nobody reads.
        cameraPositionField = findFieldByName(Camera.class, Vec3.class, "position", "f_90553_");
        if (cameraPositionField != null) {
            LOGGER.info("CameraOffsetHandler: Found Camera position field '{}' by name", cameraPositionField.getName());
        }

        cameraBlockPositionField = findFieldByName(Camera.class, BlockPos.MutableBlockPos.class, "blockPosition", "f_90555_");
        if (cameraBlockPositionField != null) {
            LOGGER.info("CameraOffsetHandler: Found Camera blockPosition field '{}' by name", cameraBlockPositionField.getName());
        }

        // Strategy 2: type-iteration fallback. Skip any field whose name contains '$' —
        // mixin-injected synthetics universally include '$' (e.g. bosses_rise_java$pos);
        // vanilla MC fields never do.
        for (Field f : Camera.class.getDeclaredFields()) {
            if (f.getName().indexOf('$') >= 0) continue;
            if (cameraPositionField == null && f.getType() == Vec3.class) {
                f.setAccessible(true);
                cameraPositionField = f;
                LOGGER.info("CameraOffsetHandler: Found Camera position field '{}' by type fallback", f.getName());
            } else if (cameraBlockPositionField == null && f.getType() == BlockPos.MutableBlockPos.class) {
                f.setAccessible(true);
                cameraBlockPositionField = f;
                LOGGER.info("CameraOffsetHandler: Found Camera blockPosition field '{}' by type fallback", f.getName());
            }
        }

        // Strategy 3: Find Camera.checkInFluid() by signature.
        for (Method m : Camera.class.getDeclaredMethods()) {
            if (m.getReturnType() == void.class && m.getParameterCount() == 0) {
                String name = m.getName();
                if (name.equals("checkInFluid") || name.equals("m_90590_")) {
                    m.setAccessible(true);
                    cameraCheckInFluidMethod = m;
                    LOGGER.info("CameraOffsetHandler: Found Camera checkInFluid method '{}'", name);
                }
            }
        }

        // Strategy 4: Find Camera.move(double, double, double) by signature.
        // Skip mixin-synthetic methods (names containing '$').
        for (Method m : Camera.class.getDeclaredMethods()) {
            if (m.getName().indexOf('$') >= 0) continue;
            Class<?>[] params = m.getParameterTypes();
            if (params.length == 3
                    && params[0] == double.class
                    && params[1] == double.class
                    && params[2] == double.class) {
                m.setAccessible(true);
                cameraMoveMethod = m;
                LOGGER.info("CameraOffsetHandler: Found Camera move method '{}'", m.getName());
                break;
            }
        }

        if (cameraPositionField == null && cameraMoveMethod == null) {
            LOGGER.error("CameraOffsetHandler: could not find Camera position field or move method, offset disabled");
        }
    }

    /** Look up a declared field by name from a list of candidates and verify its type. */
    private static Field findFieldByName(Class<?> clazz, Class<?> expectedType, String... names) {
        for (String name : names) {
            try {
                Field f = clazz.getDeclaredField(name);
                if (f.getType() != expectedType) continue;
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {}
        }
        return null;
    }

    /**
     * Set the camera's world position. Uses field access (preferred) or
     * falls back to Camera.move() if field wasn't found.
     */
    private static void setCameraPosition(Camera camera, Vec3 pos) {
        initReflection();
        try {
            if (cameraPositionField != null) {
                cameraPositionField.set(camera, pos);

                // Update blockPosition field too to ensure fluid checks are accurate.
                if (cameraBlockPositionField != null) {
                    BlockPos.MutableBlockPos mbp = (BlockPos.MutableBlockPos) cameraBlockPositionField.get(camera);
                    if (mbp != null) {
                        mbp.set(pos.x, pos.y, pos.z);
                    }
                }
            } else if (cameraMoveMethod != null) {
                Vec3 current = camera.getPosition();
                Vec3 delta = pos.subtract(current);
                Vec3 fwd = new Vec3(camera.getLookVector());
                Vec3 up = new Vec3(camera.getUpVector());
                Vec3 left = new Vec3(camera.getLeftVector());
                double dFwd = delta.dot(fwd);
                double dUp = delta.dot(up);
                double dLeft = delta.dot(left);
                cameraMoveMethod.invoke(camera, dFwd, dUp, dLeft);
            }

            // Fix underwater flickering: force camera to update its fluid state
            if (cameraCheckInFluidMethod != null) {
                cameraCheckInFluidMethod.invoke(camera);
            }
        } catch (Exception e) {
            LOGGER.warn("CameraOffsetHandler: Failed to set camera position: {}", e.getMessage());
        }
    }

    // =====================================================================
    // Part 1: Shoulder swap keybind + crosshair correction (client tick)
    // =====================================================================

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (MC.player == null || MC.screen != null) return;

        // Defer the configured starting preset to the first in-world tick:
        // config isn't ready at class-load time.
        if (!defaultShoulderApplied) {
            try {
                shoulderMode = switch (FixConfig.DEFAULT_SHOULDER_PRESET.get()) {
                    case LEFT -> MODE_LEFT;
                    case OVERHEAD -> MODE_OVERHEAD;
                    default -> MODE_RIGHT;
                };
            } catch (Exception ignored) { }
            defaultShoulderApplied = true;
        }

        // Shoulder cycle: right -> left -> overhead -> right
        if (LockOnMovementFix.SWAP_SHOULDER != null) {
            while (LockOnMovementFix.SWAP_SHOULDER.consumeClick()) {
                shoulderMode = (shoulderMode + 1) % MODE_COUNT;
                showShoulderModeToast();
            }
        }
    }

    private static void showShoulderModeToast() {
        if (MC.player == null) return;
        String label = switch (shoulderMode) {
            case MODE_LEFT -> "left";
            case MODE_OVERHEAD -> "overhead";
            default -> "right";
        };
        MC.player.displayClientMessage(
            Component.literal("Shoulder: ")
                .append(Component.literal(label).withStyle(ChatFormatting.AQUA)),
            true
        );
    }

    // =====================================================================
    // Part 2: Camera offset, applied every frame after camera setup
    // =====================================================================

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        offsetActiveThisFrame = false;

        if (MC.options.getCameraType() != CameraType.THIRD_PERSON_BACK) return;

        LocalPlayer player = MC.player;
        if (player == null) return;

        Camera camera = event.getCamera();
        float partialTick = (float) event.getPartialTick();

        Vec3 pivotPos = camera.getPosition();

        float targetX;
        float targetY;
        float targetZ = (float) getOffsetZ();
        switch (shoulderMode) {
            case MODE_LEFT -> {
                targetX = -(float) getOffsetX();
                targetY = (float) getOffsetY();
            }
            case MODE_OVERHEAD -> {
                // Player model sits below screen center; crosshair stays at
                // center. Camera raised along +Y puts the player lower in the
                // frame and the crosshair visually above their head.
                targetX = 0f;
                targetY = (float) getOverheadOffsetY();
            }
            default -> { // MODE_RIGHT
                targetX = (float) getOffsetX();
                targetY = (float) getOffsetY();
            }
        }

        // Pillar-up assist: when pitched within the configured threshold of
        // straight down, collapse lateral/vertical offsets to 0 so the camera
        // sits directly above the player and the crosshair points onto the
        // block under their feet. The existing smoothing lerp handles the
        // transition.
        double lookDownThreshold = getLookDownCenterAngle();
        if (lookDownThreshold > 0) {
            float pitch = event.getPitch();
            float angleFromDown = Math.abs(90F - pitch);
            if (angleFromDown < lookDownThreshold) {
                targetX = 0F;
                targetY = 0F;
            }
        }

        if (!initialized) {
            currentOffsetX = targetX;
            currentOffsetY = targetY;
            currentOffsetZ = targetZ;
            initialized = true;
        }

        float smoothing = (float) getSmoothing();
        currentOffsetX += (targetX - currentOffsetX) * smoothing;
        currentOffsetY += (targetY - currentOffsetY) * smoothing;
        currentOffsetZ += (targetZ - currentOffsetZ) * smoothing;

        if (Math.abs(currentOffsetX) < 0.001f && Math.abs(currentOffsetY) < 0.001f && Math.abs(currentOffsetZ) < 0.001f && currentCollisionDist < 0) return;

        float yaw = event.getYaw();
        float pitch = event.getPitch();

        Vec3[] b1 = basisYawPitch(yaw, pitch);
        Vec3 look1 = b1[0];
        Vec3 up1 = b1[1];
        Vec3 left1 = b1[2];

        Vec3 desired = pivotPos
                .add(left1.scale(currentOffsetX))
                .add(up1.scale(currentOffsetY))
                .subtract(look1.scale(currentOffsetZ));

        Vec3 finalPos = clipOffsetToWall(pivotPos, desired, player, partialTick);

        setCameraPosition(camera, finalPos);
        offsetActiveThisFrame = true;
    }

    /**
     * Re-apply the shoulder offset after VS2 has reset the camera to the
     * ship-mounted eye. Invoked from {@code LevelRenderer.prepareCullFrustum}
     * HEAD, which runs after VS2's WrapOperation has called
     * {@code setupWithShipMounted} (which wipes any earlier offset). We
     * re-derive from the camera's current basis vectors so left/right/up are
     * correct even when the ship is rolled. State (offsets, collision dist,
     * shoulder mode) is shared with {@link #onCameraAngles} for continuity
     * across dismount.
     */
    public static void applyOffsetForVsMountedShip(Camera camera, float partialTick) {
        offsetActiveThisFrame = false;

        if (MC.options.getCameraType() != CameraType.THIRD_PERSON_BACK) return;

        LocalPlayer player = MC.player;
        if (player == null) return;

        float targetX;
        float targetY;
        float targetZ = (float) getOffsetZ();
        switch (shoulderMode) {
            case MODE_LEFT -> {
                targetX = -(float) getOffsetX();
                targetY = (float) getOffsetY();
            }
            case MODE_OVERHEAD -> {
                targetX = 0f;
                targetY = (float) getOverheadOffsetY();
            }
            default -> { // MODE_RIGHT
                targetX = (float) getOffsetX();
                targetY = (float) getOffsetY();
            }
        }

        // Pillar-up assist: same check as onCameraAngles, but reads pitch from
        // the camera (post-VS2 ship-aware xRot) since there is no
        // ViewportEvent here.
        double lookDownThreshold = getLookDownCenterAngle();
        if (lookDownThreshold > 0) {
            float pitch = camera.getXRot();
            float angleFromDown = Math.abs(90F - pitch);
            if (angleFromDown < lookDownThreshold) {
                targetX = 0F;
                targetY = 0F;
            }
        }

        if (!initialized) {
            currentOffsetX = targetX;
            currentOffsetY = targetY;
            currentOffsetZ = targetZ;
            initialized = true;
        }

        float smoothing = (float) getSmoothing();
        currentOffsetX += (targetX - currentOffsetX) * smoothing;
        currentOffsetY += (targetY - currentOffsetY) * smoothing;
        currentOffsetZ += (targetZ - currentOffsetZ) * smoothing;

        if (Math.abs(currentOffsetX) < 0.001f && Math.abs(currentOffsetY) < 0.001f && Math.abs(currentOffsetZ) < 0.001f && currentCollisionDist < 0) return;

        Vec3 pivot = camera.getPosition();
        Vec3 look = new Vec3(camera.getLookVector());
        Vec3 up = new Vec3(camera.getUpVector());
        Vec3 left = new Vec3(camera.getLeftVector());

        // Sign flip on currentOffsetX vs onCameraAngles: vanilla Camera.left
        // points to camera's-left (== east at yaw 0), opposite of the
        // cross-product basis (look × up) that onCameraAngles uses.
        Vec3 desired = pivot
                .add(left.scale(-currentOffsetX))
                .add(up.scale(currentOffsetY))
                .subtract(look.scale(currentOffsetZ));

        Vec3 finalPos = clipOffsetToWall(pivot, desired, player, partialTick);

        setCameraPosition(camera, finalPos);
        offsetActiveThisFrame = true;
    }

    private static Vec3[] basisYawPitch(float yawDeg, float pitchDeg) {
        float f = pitchDeg * ((float) Math.PI / 180F);
        float f1 = -yawDeg * ((float) Math.PI / 180F);
        float f2 = (float) Math.cos(f1);
        float f3 = (float) Math.sin(f1);
        float f4 = (float) Math.cos(f);
        float f5 = (float) Math.sin(f);
        Vec3 look = new Vec3(f3 * f4, -f5, f2 * f4);
        Vec3 up = new Vec3(f3 * f5, f4, f2 * f5);
        Vec3 left = look.cross(up);
        return new Vec3[]{look, up, left};
    }

    // =====================================================================
    // Part 3: Player visibility, hide when camera is very close
    // =====================================================================

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRenderLiving(RenderLivingEvent.Pre<?, ?> event) {
        if (MC.player == null) return;
        if (event.getEntity() != MC.player) return;
        if (MC.options.getCameraType() != CameraType.THIRD_PERSON_BACK) return;
        if (!getHidePlayerWhenClose()) return;

        Camera camera = MC.gameRenderer.getMainCamera();
        Vec3 cameraPos = camera.getPosition();
        Vec3 eyePos = MC.player.getEyePosition(event.getPartialTick());

        double dist = cameraPos.distanceTo(eyePos);

        // Also check distance to feet to be safe
        double distFeet = cameraPos.distanceTo(MC.player.position());

        if (dist < getHidePlayerDistance() || distFeet < getHidePlayerDistance()) {
            event.setCanceled(true);
        }
    }

    // =====================================================================
    // Wall collision raytrace
    // =====================================================================

    private static Vec3 clipOffsetToWall(Vec3 basePos, Vec3 desiredPos, LocalPlayer player, float partialTick) {
        if (player.level() == null) return desiredPos;

        Vec3 eyePos = player.getEyePosition(partialTick);
        Vec3 dirFromEye = desiredPos.subtract(eyePos);
        double fullDist = dirFromEye.length();
        if (fullDist < 0.01) return desiredPos;

        dirFromEye = dirFromEye.normalize();
        double targetDist = fullDist;

        // Near plane dimensions (roughly)
        float h = 0.1f;

        // Perform 8 raytraces from corners of the near plane to ensure no clipping
        for(int i = 0; i < 8; ++i) {
            float f = (float)((i & 1) * 2 - 1) * h;
            float f1 = (float)((i >> 1 & 1) * 2 - 1) * h;
            float f2 = (float)((i >> 2 & 1) * 2 - 1) * h;

            Vec3 startBox = eyePos.add(f, f1, f2);
            Vec3 endBox = desiredPos.add(f, f1, f2);

            ClipContext clipCtx = new ClipContext(
                startBox, endBox,
                ClipContext.Block.VISUAL,
                ClipContext.Fluid.NONE,
                player
            );
            BlockHitResult hit = IntegrationRegistry.isValkyrienSkies()
                ? ValkyrienSkiesIntegration.clipIncludeShips(player.level(), clipCtx)
                : player.level().clip(clipCtx);

            if (hit.getType() != HitResult.Type.MISS) {
                double hitDist = hit.getLocation().distanceTo(startBox);
                if (hitDist < targetDist) {
                    targetDist = hitDist;
                }
            }
        }

        // Initialize collision distance if needed
        if (currentCollisionDist < 0) {
            currentCollisionDist = targetDist;
        }

        // Smoothly interpolate the collision distance to prevent snapping
        // We use a "fast-in, slow-out" approach:
        // If zooming IN (colliding), we want it to be responsive (but not instant).
        // If zooming OUT (clearing), we want it to be smooth.
        double diff = targetDist - currentCollisionDist;
        float smoothing = (float) getSmoothing();
        float step = (float) (diff < 0 ? 0.4f : smoothing * 0.2f); // 0.4 for fast-in, 0.2*smoothing for slow-out

        currentCollisionDist += diff * step;

        // Clamp to avoid overshooting
        if (diff > 0 && currentCollisionDist > targetDist) currentCollisionDist = targetDist;
        if (diff < 0 && currentCollisionDist < targetDist) currentCollisionDist = targetDist;

        if (currentCollisionDist < fullDist - 0.01) {
            return eyePos.add(dirFromEye.scale(currentCollisionDist));
        }

        return desiredPos;
    }
}
