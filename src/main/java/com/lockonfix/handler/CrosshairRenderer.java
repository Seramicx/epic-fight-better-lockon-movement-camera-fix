package com.lockonfix.handler;

import com.lockonfix.FixConfig;
import com.lockonfix.LockOnMovementFix;
import com.lockonfix.client.EpicFightClientHooks;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Adaptive third-person crosshair.
 *
 * The camera is offset (over-the-shoulder), but the crosshair should show where
 * the PLAYER'S aim will land — not the camera's. When aiming a projectile, we
 * trace from the player's eye along the player's view vector, then project that
 * world point through the view + projection matrices captured at AFTER_LEVEL.
 *
 * Only active while aiming (bow / crossbow / trident / Iron's Spells cast).
 * Otherwise the crosshair draws at screen center. Lock-on disables it entirely.
 */
@Mod.EventBusSubscriber(modid = LockOnMovementFix.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class CrosshairRenderer {

    private static final ResourceLocation GUI_ICONS = new ResourceLocation("minecraft", "textures/gui/icons.png");
    private static final Minecraft MC = Minecraft.getInstance();
    private static final double TRACE_DISTANCE = 256.0;

    private static final Matrix4f LAST_PROJECTION = new Matrix4f();
    private static boolean matricesValid = false;

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        // NOTE: event.getPoseStack() at AFTER_LEVEL is the PROJECTION stack (GameRenderer
        // passes `posestack` which was built from the projection matrix), NOT the view
        // matrix. Camera rotation lives on a different PoseStack (pPoseStack) that never
        // reaches this event. So we only capture projection here and build the view
        // matrix ourselves from Camera.getXRot()/getYRot() when we need it.
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            LAST_PROJECTION.set(event.getProjectionMatrix());
            matricesValid = true;
        }
    }

    @SubscribeEvent
    public static void onRenderCrosshair(RenderGuiOverlayEvent.Pre event) {
        if (event.getOverlay() != VanillaGuiOverlay.CROSSHAIR.type()) return;
        if (MC.options.getCameraType() != CameraType.THIRD_PERSON_BACK) return;
        if (MC.options.hideGui) return;
        if (MC.player == null || MC.level == null) return;
        if (!adaptiveEnabled()) return;
        if (EpicFightClientHooks.isLockOnTargeting()) return;

        int width = event.getWindow().getGuiScaledWidth();
        int height = event.getWindow().getGuiScaledHeight();

        float offsetX = 0f;
        float offsetY = 0f;

        if (matricesValid && EpicFightClientHooks.isAimingForCrosshair(MC.player)) {
            Vec3 aim = tracePlayerAim(MC.player, event.getPartialTick());
            if (aim != null) {
                Camera camera = MC.gameRenderer.getMainCamera();
                Vec3 rel = aim.subtract(camera.getPosition());
                float[] screen = project(rel, camera);
                if (screen != null) {
                    float screenW = MC.getWindow().getScreenWidth();
                    float screenH = MC.getWindow().getScreenHeight();
                    float guiScale = (float) MC.getWindow().getGuiScale();
                    if (guiScale > 0f) {
                        offsetX = (screen[0] - screenW * 0.5f) / guiScale;
                        offsetY = (screen[1] - screenH * 0.5f) / guiScale;
                    }
                }
            }
        }

        drawAdaptiveCrosshair(event.getGuiGraphics(), width, height, offsetX, offsetY);
        event.setCanceled(true);
    }

    private static Vec3 tracePlayerAim(LocalPlayer player, float partialTick) {
        Vec3 eye = player.getEyePosition(partialTick);
        Vec3 look = player.getViewVector(partialTick);
        Vec3 end = eye.add(look.scale(TRACE_DISTANCE));

        BlockHitResult blockHit = player.level().clip(new ClipContext(
                eye, end,
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                player
        ));

        Vec3 blockTarget = blockHit.getType() != HitResult.Type.MISS ? blockHit.getLocation() : end;
        double reach = eye.distanceTo(blockTarget);

        AABB searchBox = new AABB(eye, blockTarget).inflate(1.0);
        EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
                player, eye, blockTarget, searchBox,
                e -> !e.isSpectator() && e.isPickable() && e != player,
                reach * reach
        );

        return entityHit != null ? entityHit.getLocation() : blockTarget;
    }

    private static float[] project(Vec3 cameraRelative, Camera camera) {
        int screenW = MC.getWindow().getScreenWidth();
        int screenH = MC.getWindow().getScreenHeight();
        if (screenW == 0 || screenH == 0) return null;

        // Reconstruct the view matrix (camera rotation)
        // Minecraft's view matrix at RenderLevelStageEvent:
        // 1. Rotate X by pitch
        // 2. Rotate Y by yaw + 180
        float pitch = (float) Math.toRadians(camera.getXRot());
        float yaw = (float) Math.toRadians(camera.getYRot() + 180f);

        Matrix4f view = new Matrix4f().rotationX(pitch).rotateY(yaw);
        Vector4f v = new Vector4f((float) cameraRelative.x, (float) cameraRelative.y, (float) cameraRelative.z, 1f);

        v.mul(view);
        v.mul(LAST_PROJECTION);

        if (v.w() == 0f) return null;

        float invW = (1f / v.w()) * 0.5f;
        // NDC coordinates are [-1, 1]. Map to [0, 1] then to screen pixels.
        // Also check if the point is behind the camera (z > 0 in view space before projection,
        // but easier to check w after projection for standard perspective).
        if (v.w() < 0.1f) return null;

        float x = (v.x() * invW + 0.5f) * screenW;
        float y = (v.y() * invW + 0.5f) * screenH;

        if (Float.isNaN(x) || Float.isNaN(y) || Float.isInfinite(x) || Float.isInfinite(y)) return null;
        return new float[]{x, y};
    }

    private static void drawAdaptiveCrosshair(GuiGraphics guiGraphics, int width, int height, float offsetX, float offsetY) {
        int x = (width - 15) / 2;
        int y = (height - 15) / 2;

        PoseStack pose = guiGraphics.pose();
        pose.pushPose();
        // NDC y is up, GUI y is down — flip
        pose.translate(offsetX, -offsetY, 0f);

        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(
                GlStateManager.SourceFactor.ONE_MINUS_DST_COLOR,
                GlStateManager.DestFactor.ONE_MINUS_SRC_COLOR,
                GlStateManager.SourceFactor.ONE,
                GlStateManager.DestFactor.ZERO
        );
        guiGraphics.blit(GUI_ICONS, x, y, 0, 0, 15, 15);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();

        pose.popPose();
    }

    private static boolean adaptiveEnabled() {
        try {
            return FixConfig.ADAPTIVE_CROSSHAIR.get();
        } catch (Exception e) {
            return true;
        }
    }
}
