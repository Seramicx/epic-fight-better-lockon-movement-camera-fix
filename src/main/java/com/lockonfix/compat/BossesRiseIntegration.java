package com.lockonfix.compat;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;

import java.lang.reflect.Method;

/**
 * Bosses'Rise (block_factorys_bosses) integration. Every public method is a
 * no-op when Bosses'Rise is not installed (checked via
 * {@link IntegrationRegistry}). Reflection is resolved lazily on first call
 * and cached.
 *
 * <p>BR 2.x for NeoForge 1.21.1 replaced the Forge capability
 * {@code RollCap} (returned {@code Optional<RollCap>}) with the
 * data-attachment {@code RollAttachment} (returned directly).
 */
public final class BossesRiseIntegration {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String ROLL_ATTACHMENT_CLASS =
            "net.unusual.block_factorys_bosses.attachment.entity.RollAttachment";

    private static Method fromPlayerMethod = null;
    private static Method isRollingMethod = null;
    private static boolean resolved = false;

    private BossesRiseIntegration() {}

    private static void resolve() {
        if (resolved) return;
        resolved = true;
        if (!IntegrationRegistry.isBossesRise()) return;

        try {
            Class<?> rollAttachmentClass = Class.forName(ROLL_ATTACHMENT_CLASS);
            fromPlayerMethod = rollAttachmentClass.getMethod("fromPlayer", Player.class);
            isRollingMethod = rollAttachmentClass.getMethod("isRolling");
            LOGGER.info("BossesRise integration: resolved RollAttachment.fromPlayer + RollAttachment.isRolling");
        } catch (Throwable t) {
            LOGGER.warn("BossesRise integration failed to resolve: {}", t.toString());
            fromPlayerMethod = null;
            isRollingMethod = null;
        }
    }

    /**
     * @return true if BR is loaded and that player's {@code RollAttachment}
     *         reports {@code isRolling()}. Any reflection failure returns false.
     */
    public static boolean isRolling(Player player) {
        resolve();
        if (player == null || fromPlayerMethod == null || isRollingMethod == null) return false;
        try {
            Object attachment = fromPlayerMethod.invoke(null, player);
            if (attachment == null) return false;
            Object result = isRollingMethod.invoke(attachment);
            return result instanceof Boolean b && b;
        } catch (Throwable t) {
            return false;
        }
    }
}
