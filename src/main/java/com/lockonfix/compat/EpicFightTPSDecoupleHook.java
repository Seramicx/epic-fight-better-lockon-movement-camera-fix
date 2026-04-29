package com.lockonfix.compat;

import com.lockonfix.handler.LockOnMovementHandler;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import yesman.epicfight.api.client.event.EpicFightClientHooks;
import yesman.epicfight.api.client.event.types.ActivateTPSCamera;
import yesman.epicfight.api.event.subscriptions.DefaultEventSubscription;

/**
 * Cancels Epic Fight's TPS camera mode while mount-rotate decouple is active.
 *
 * <p>Epic Fight's TPS mode owns the camera direction: it intercepts mouse
 * input via {@code MixinMouseHandler} and writes camera rotation directly in
 * {@code setupCamera}. By cancelling {@code ActivateTPSCamera} while decouple
 * is active, {@code isTPSMode()} returns false and Epic Fight defers to the
 * vanilla camera path, letting our decouple mixins handle the camera.
 * Cancellation is gated to decouple-only so Epic Fight's TPS mode functions
 * normally outside mount-rotate.
 */
public final class EpicFightTPSDecoupleHook {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean registered = false;

    private EpicFightTPSDecoupleHook() {}

    public static void register() {
        if (registered) return;
        registered = true;

        try {
            DefaultEventSubscription<ActivateTPSCamera> sub = event -> {
                if (LockOnMovementHandler.isDecoupleActive()) {
                    event.cancel();
                }
            };
            EpicFightClientHooks.Camera.ACTIVATE_TPS_CAMERA.registerEvent(sub);
        } catch (Throwable t) {
            LOGGER.warn("Failed to register Epic Fight TPS-cancel hook: {}", t.getMessage());
        }
    }
}
