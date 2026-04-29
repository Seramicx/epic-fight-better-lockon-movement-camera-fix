package com.lockonfix.handler;

import com.lockonfix.LockOnMovementFix;
import com.lockonfix.client.EpicFightClientHooks;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Renders the vanilla crosshair sprite at screen center in third person.
 * MC 1.20.1's {@code Gui.renderCrosshair} is gated on
 * {@code CameraType.FIRST_PERSON}, so the HUD has no crosshair in third
 * person. This renderer draws the same 15x15 sprite with the same inverted-
 * color blend mode as vanilla; no adaptive shifting, no raycast. Epic
 * Fight's {@code postClientTick} keeps player.yRot aligned to the camera,
 * so projectiles land where this static crosshair points.
 */
@Mod.EventBusSubscriber(modid = LockOnMovementFix.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class CenterCrosshairRenderer {

    private static final ResourceLocation GUI_ICONS = new ResourceLocation("textures/gui/icons.png");

    private CenterCrosshairRenderer() {}

    @SubscribeEvent
    public static void onCrosshairOverlay(RenderGuiOverlayEvent.Post event) {
        if (event.getOverlay() != VanillaGuiOverlay.CROSSHAIR.type()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui) return;
        if (mc.player == null) return;
        // Vanilla already drew it in first person; we only supplement TPS.
        if (mc.options.getCameraType() == CameraType.FIRST_PERSON) return;
        // While locked on, Epic Fight's lock-on indicator is the aim cue.
        // Hide our static crosshair so the user reads the lock-on glyph.
        if (EpicFightClientHooks.isLockOnTargeting()) return;

        GuiGraphics g = event.getGuiGraphics();
        int w = g.guiWidth();
        int h = g.guiHeight();

        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(
            GlStateManager.SourceFactor.ONE_MINUS_DST_COLOR, GlStateManager.DestFactor.ONE_MINUS_SRC_COLOR,
            GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO
        );

        g.blit(GUI_ICONS, (w - 15) / 2, (h - 15) / 2, 0, 0, 15, 15);

        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }
}
