package com.lockonfix.compat;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Bosses'Rise (block_factorys_bosses) integration. Every public method is a
 * no-op when Bosses'Rise is not installed (checked via
 * {@link IntegrationRegistry}). Reflection is resolved lazily on first call
 * and cached.
 */
public final class BossesRiseIntegration {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String ROLL_CAP_CLASS =
            "net.unusual.block_factorys_bosses.capability.entity.RollCap";

    private static Method fromPlayerMethod = null;
    private static Method isRollingMethod = null;
    private static boolean resolved = false;

    private BossesRiseIntegration() {}

    private static void resolve() {
        if (resolved) return;
        resolved = true;
        if (!IntegrationRegistry.isBossesRise()) return;

        try {
            Class<?> rollCapClass = Class.forName(ROLL_CAP_CLASS);
            fromPlayerMethod = rollCapClass.getMethod("fromPlayer", Player.class);
            isRollingMethod = rollCapClass.getMethod("isRolling");
            LOGGER.info("BossesRise integration: resolved RollCap.fromPlayer + RollCap.isRolling");
        } catch (Throwable t) {
            LOGGER.warn("BossesRise integration failed to resolve: {}", t.toString());
            fromPlayerMethod = null;
            isRollingMethod = null;
        }
    }

    /**
     * @return true if BR is loaded, the player has a {@code RollCap}, and that
     *         cap reports {@code isRolling()}. Any reflection failure or absent
     *         cap returns false.
     */
    public static boolean isRolling(Player player) {
        resolve();
        if (player == null || fromPlayerMethod == null || isRollingMethod == null) return false;
        try {
            Object opt = fromPlayerMethod.invoke(null, player);
            if (!(opt instanceof Optional<?> optional)) return false;
            Object rollCap = optional.orElse(null);
            if (rollCap == null) return false;
            Object result = isRollingMethod.invoke(rollCap);
            return result instanceof Boolean b && b;
        } catch (Throwable t) {
            return false;
        }
    }
}
