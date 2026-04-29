package com.lockonfix.compat;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.lang.reflect.Field;

/**
 * Short-circuits BetterLockOn's post-lock-off transition delays.
 *
 * <p>BLO holds its lock-on camera behavior after lock-off via two timers:
 * {@code BLOCameraSetting.transitionTick} (max 30, camera-offset interp) and
 * {@code EpicFightCameraAPI.blo$unlockDelayTick} (max 60, applies the lock-on
 * camera transform). On a mount that produces a 2-3 second window where the
 * camera tracks the mount before mount-rotate takes over.
 * {@link #skipPostLockOff()} forces both timers to their max so BLO releases
 * on the next tick.
 */
public final class BLOTransitionSkipHook {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static Field transitionTickField = null;
    private static Field unlockDelayTickField = null;
    private static Field maxUnlockDelayTickField = null;
    private static Object epicFightCameraApiSingleton = null;
    private static boolean resolved = false;
    private static boolean resolvedOk = false;

    private BLOTransitionSkipHook() {}

    private static void resolve() {
        if (resolved) return;
        resolved = true;

        if (!IntegrationRegistry.isBetterLockOn()) {
            return;
        }

        try {
            Class<?> blOCameraSetting = Class.forName(
                "net.shelmarow.betterlockon.client.control.BLOCameraSetting");
            transitionTickField = blOCameraSetting.getDeclaredField("transitionTick");
            transitionTickField.setAccessible(true);

            Class<?> epicFightCameraApi = Class.forName(
                "yesman.epicfight.api.client.camera.EpicFightCameraAPI");
            epicFightCameraApiSingleton = epicFightCameraApi
                .getMethod("getInstance").invoke(null);

            unlockDelayTickField = epicFightCameraApi.getDeclaredField("blo$unlockDelayTick");
            unlockDelayTickField.setAccessible(true);

            maxUnlockDelayTickField = epicFightCameraApi.getDeclaredField("blo$maxUnlockDelayTick");
            maxUnlockDelayTickField.setAccessible(true);

            resolvedOk = true;
        } catch (Throwable t) {
            LOGGER.warn("BLO transition skip: failed to resolve fields ({}), post-lock-off transition will not be skipped",
                    t.getMessage());
        }
    }

    /**
     * Called when {@code api.isLockingOnTarget()} flips from true to false
     * while the player is on a mount. Forces BLO's transition timers to
     * "finished" so BLO releases lock-on camera behavior on the next tick.
     */
    public static void skipPostLockOff() {
        resolve();
        if (!resolvedOk) return;
        try {
            transitionTickField.setInt(null, 30);
            int max = maxUnlockDelayTickField.getInt(epicFightCameraApiSingleton);
            unlockDelayTickField.setInt(epicFightCameraApiSingleton, max);
        } catch (Throwable t) {
            LOGGER.warn("BLO transition skip: reflection set failed: {}", t.getMessage());
        }
    }
}
