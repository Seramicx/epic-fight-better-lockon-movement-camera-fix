package com.lockonfix.handler;

import com.lockonfix.FixConfig;
import com.lockonfix.LockOnMovementFix;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
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
 * 1) Camera offset (ViewportEvent.ComputeCameraAngles) — lateral + vertical + zoom shift only (no aim yaw/pitch hack)
 * 2) Adaptive crosshair + optional body aim are handled elsewhere (SSR-style)
 * 3) Player visibility (RenderLivingEvent.Pre) — hide when camera is too close
 *
 * All hooks fire AFTER Epic Fight's camera mixin has completed, avoiding the
 * conflict that Shoulder Surfing Reloaded has with Epic Fight's lock-on camera.
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

    // Shoulder swap state (runtime toggle, not persisted to config)
    private static boolean shoulderSwapped = false;

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

    // =====================================================================
    // Reflection: find Camera internals by type/signature (mapping-agnostic)
    // =====================================================================

    private static void initReflection() {
        if (reflectionAttempted) return;
        reflectionAttempted = true;

        // Strategy 1: Find Camera fields by type.
        for (Field f : Camera.class.getDeclaredFields()) {
            // Camera has exactly one Vec3 field (its position).
            if (f.getType() == Vec3.class) {
                f.setAccessible(true);
                cameraPositionField = f;
                LOGGER.info("CameraOffsetHandler: Found Camera position field '{}'", f.getName());
            }
            // Camera has a MutableBlockPos field for its block position.
            else if (f.getType() == BlockPos.MutableBlockPos.class) {
                f.setAccessible(true);
                cameraBlockPositionField = f;
                LOGGER.info("CameraOffsetHandler: Found Camera blockPosition field '{}'", f.getName());
            }
        }

        // Strategy 2: Find Camera.checkInFluid() by signature.
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

        // Strategy 3: Find Camera.move(double, double, double) by signature.
        for (Method m : Camera.class.getDeclaredMethods()) {
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
            LOGGER.error("CameraOffsetHandler: Could not find Camera position field or move method — offset disabled");
        }
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

        // --- Shoulder swap keybind ---
        if (LockOnMovementFix.SWAP_SHOULDER != null) {
            while (LockOnMovementFix.SWAP_SHOULDER.consumeClick()) {
                shoulderSwapped = !shoulderSwapped;
            }
        }


    }

    // =====================================================================
    // Part 2: Camera offset — applied every frame after camera setup
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

        float targetX = (float) getOffsetX();
        if (shoulderSwapped) targetX = -targetX;
        float targetY = (float) getOffsetY();
        float targetZ = (float) getOffsetZ();

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
    // Part 3: Player visibility — hide when camera is very close
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

            BlockHitResult hit = player.level().clip(new ClipContext(
                startBox, endBox,
                ClipContext.Block.VISUAL,
                ClipContext.Fluid.NONE,
                player
            ));

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
