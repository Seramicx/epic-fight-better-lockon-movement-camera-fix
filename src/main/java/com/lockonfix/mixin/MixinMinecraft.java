package com.lockonfix.mixin;

import com.lockonfix.compat.IronSpellsIntegration;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SpawnEggItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import yesman.epicfight.api.client.camera.EpicFightCameraAPI;

// priority 1500 so save/restore of player rotation wraps other mods' tick logic
@Mixin(value = Minecraft.class, priority = 1500)
public abstract class MixinMinecraft {

    @Unique private static float lockonfix$origYRot;
    @Unique private static float lockonfix$origXRot;
    @Unique private static float lockonfix$origYHeadRot;
    @Unique private static float lockonfix$origYBodyRot;
    @Unique private static boolean lockonfix$shouldRestore;

    @Unique private static boolean lockonfix$keybinds$wasLocked;
    @Unique private static boolean lockonfix$keybinds$wasTPB;

    @Inject(method = "handleKeybinds()V", at = @At("HEAD"))
    private void lockonfix$captureLockOnState(CallbackInfo ci) {
        try {
            EpicFightCameraAPI api = EpicFightCameraAPI.getInstance();
            lockonfix$keybinds$wasLocked = api != null && api.isLockingOnTarget();
        } catch (Throwable t) {
            lockonfix$keybinds$wasLocked = false;
        }
        Minecraft mc = (Minecraft) (Object) this;
        lockonfix$keybinds$wasTPB = mc.options.getCameraType() == CameraType.THIRD_PERSON_BACK;
    }

    @Inject(method = "handleKeybinds()V", at = @At("TAIL"))
    private void lockonfix$restoreLockOnIn1stPerson(CallbackInfo ci) {
        if (!lockonfix$keybinds$wasLocked) return;
        if (!lockonfix$keybinds$wasTPB) return;

        Minecraft mc = (Minecraft) (Object) this;
        if (mc.options.getCameraType() != CameraType.FIRST_PERSON) return;

        try {
            EpicFightCameraAPI api = EpicFightCameraAPI.getInstance();
            if (api != null && !api.isLockingOnTarget()) {
                api.setLockOn(true);
            }
        } catch (Throwable ignored) {}
    }

    @Inject(method = "startUseItem", at = @At("HEAD"))
    private void lockonfix$alignAimBeforeUseItem(CallbackInfo ci) {
        lockonfix$shouldRestore = false;

        Minecraft mc = (Minecraft) (Object) this;
        if (mc.options.getCameraType() != CameraType.THIRD_PERSON_BACK) return;

        LocalPlayer player = mc.player;
        if (player == null) return;

        boolean matched = isShoulderFixItem(player.getItemInHand(InteractionHand.MAIN_HAND).getItem())
                || isShoulderFixItem(player.getItemInHand(InteractionHand.OFF_HAND).getItem());
        if (!matched) return;

        EpicFightCameraAPI api;
        try {
            api = EpicFightCameraAPI.getInstance();
        } catch (Throwable t) {
            return;
        }
        if (api == null) return;

        if (api.isLockingOnTarget()) return;

        lockonfix$origYRot = player.getYRot();
        lockonfix$origXRot = player.getXRot();
        lockonfix$origYHeadRot = player.getYHeadRot();
        lockonfix$origYBodyRot = player.yBodyRot;
        lockonfix$shouldRestore = true;

        try {
            api.alignPlayerLookToCrosshair(false, false, true);
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "startUseItem", at = @At("RETURN"))
    private void lockonfix$restoreAimAfterUseItem(CallbackInfo ci) {
        if (!lockonfix$shouldRestore) return;
        lockonfix$shouldRestore = false;

        Minecraft mc = (Minecraft) (Object) this;
        LocalPlayer player = mc.player;
        if (player == null) return;

        player.setYRot(lockonfix$origYRot);
        player.setXRot(lockonfix$origXRot);
        player.setYHeadRot(lockonfix$origYHeadRot);
        player.yBodyRot = lockonfix$origYBodyRot;
    }

    @Unique
    private static boolean isShoulderFixItem(Item item) {
        if (item == null) return false;
        return item instanceof BucketItem
                || item instanceof SpawnEggItem
                || item instanceof FishingRodItem
                || IronSpellsIntegration.isIronsItem(item);
    }
}
