package com.lockonfix.mixin;

import com.lockonfix.compat.IronSpellsIntegration;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TridentItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import yesman.epicfight.api.client.camera.EpicFightCameraAPI;

@Mixin(MultiPlayerGameMode.class)
public abstract class MixinMultiPlayerGameMode {

    @Inject(method = "releaseUsingItem", at = @At("HEAD"))
    private void lockonfix$alignAimBeforeRelease(Player player, CallbackInfo ci) {
        if (player == null || !player.isUsingItem()) return;

        ItemStack use = player.getUseItem();
        if (use.isEmpty()) return;

        Item item = use.getItem();
        boolean isRanged = item instanceof BowItem
                || item instanceof CrossbowItem
                || item instanceof TridentItem
                || IronSpellsIntegration.isIronsItem(item);
        if (!isRanged) return;

        if (Minecraft.getInstance().options.getCameraType() != CameraType.THIRD_PERSON_BACK) return;

        EpicFightCameraAPI api;
        try {
            api = EpicFightCameraAPI.getInstance();
        } catch (Throwable t) {
            return;
        }
        if (api == null) return;

        if (api.isLockingOnTarget()) return;

        float origYRot = player.getYRot();
        float origXRot = player.getXRot();
        float origYHeadRot = player.getYHeadRot();

        try {
            api.alignPlayerLookToCrosshair(false, false, true);
        } catch (Throwable ignored) {
            return;
        } finally {
            player.setYRot(origYRot);
            player.setXRot(origXRot);
            player.setYHeadRot(origYHeadRot);
        }
    }
}
