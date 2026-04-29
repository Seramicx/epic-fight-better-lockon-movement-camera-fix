package com.lockonfix.compat;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.joml.Matrix4dc;
import org.joml.Vector3d;
import org.slf4j.Logger;

import java.lang.reflect.Method;

/**
 * Valkyrien Skies 2 compat. Reflection-only so VS2 is a soft dependency.
 *
 * <p>When a player is mounted on a VS2 ship, VS2 stores {@code player.yRot/xRot}
 * in <b>ship-local</b> space and mixin-hooks {@code calculateViewVector} to
 * transform back to world space on demand. Our code writes world-space yaws
 * directly to {@code player.setYRot()}, which breaks when mounted because
 * VS2 re-applies the ship transform on render. This integration converts our
 * world-space yaw to ship-local before it's written.
 *
 * <p>Three reflection entry points:
 * <ul>
 *   <li>{@code VSGameUtilsKt.getShipMountedTo(Entity)}</li>
 *   <li>{@code Ship.getTransform().getWorldToShip()}: JOML {@link Matrix4dc}</li>
 *   <li>{@code RaycastUtilsKt.clipIncludeShips(Level, ClipContext)}</li>
 * </ul>
 * Any reflection failure short-circuits to the vanilla path.
 */
public final class ValkyrienSkiesIntegration {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static Method getShipMountedToMethod = null;
    private static Method shipGetTransformMethod = null;
    private static Method transformGetWorldToShipMethod = null;
    private static Method clipIncludeShipsMethod = null;
    private static boolean resolved = false;
    private static boolean resolvedOk = false;

    private ValkyrienSkiesIntegration() {}

    private static void resolve() {
        if (resolved) return;
        resolved = true;
        if (!IntegrationRegistry.isValkyrienSkies()) return;

        try {
            Class<?> gameUtilsKt = Class.forName("org.valkyrienskies.mod.common.VSGameUtilsKt");
            getShipMountedToMethod = gameUtilsKt.getMethod("getShipMountedTo", Entity.class);

            Class<?> raycastUtilsKt = Class.forName("org.valkyrienskies.mod.common.world.RaycastUtilsKt");
            clipIncludeShipsMethod = raycastUtilsKt.getMethod("clipIncludeShips", Level.class, ClipContext.class);

            // ship.getTransform() and transform.getWorldToShip() resolve
            // lazily on first call: the ship/transform classes live in the VS
            // core jar and their types vary by version.
            resolvedOk = true;
        } catch (Throwable t) {
            LOGGER.warn("ValkyrienSkies integration reflection failed: {}", t.getMessage());
        }
    }

    private static Object getShipMountedTo(Entity entity) {
        if (entity == null) return null;
        if (!IntegrationRegistry.isValkyrienSkies()) return null;
        resolve();
        if (!resolvedOk) return null;
        try {
            return getShipMountedToMethod.invoke(null, entity);
        } catch (Throwable t) {
            return null;
        }
    }

    /** True when the entity is currently mounted on a VS2 ship. */
    public static boolean isMountedOnShip(Entity entity) {
        return getShipMountedTo(entity) != null;
    }

    /**
     * Convert a world-space yaw to ship-local yaw for the ship the entity is
     * mounted on. When VS2 isn't loaded, the entity isn't mounted, or the
     * transform can't be resolved, returns {@code worldYaw} unchanged.
     *
     * <p>Derivation mirrors {@code MixinLocalPlayer.adjustLookOnMount}:
     * build a horizontal unit look vector from {@code worldYaw}, transform it
     * by {@code worldToShip}, then extract ship-local yaw via atan2.</p>
     */
    public static float worldYawToShipYaw(Entity entity, float worldYaw) {
        Object ship = getShipMountedTo(entity);
        if (ship == null) return worldYaw;

        try {
            if (shipGetTransformMethod == null) {
                shipGetTransformMethod = ship.getClass().getMethod("getTransform");
            }
            Object transform = shipGetTransformMethod.invoke(ship);
            if (transform == null) return worldYaw;

            if (transformGetWorldToShipMethod == null) {
                transformGetWorldToShipMethod = transform.getClass().getMethod("getWorldToShip");
            }
            Object w2sObj = transformGetWorldToShipMethod.invoke(transform);
            if (!(w2sObj instanceof Matrix4dc worldToShip)) return worldYaw;

            // Minecraft convention: yaw 0 looks toward +Z, yaw 90 toward -X.
            // Unit look vector: (-sin(yaw), 0, cos(yaw)).
            double rad = Math.toRadians(worldYaw);
            Vector3d worldLook = new Vector3d(-Math.sin(rad), 0.0, Math.cos(rad));
            Vector3d shipLook = new Vector3d();
            worldToShip.transformDirection(worldLook, shipLook);

            // atan2(-x, z) inverts the sin, then convert to degrees.
            return (float) (Math.atan2(-shipLook.x, shipLook.z) * 180.0 / Math.PI);
        } catch (Throwable t) {
            return worldYaw;
        }
    }

    /**
     * VS2's ship-aware raycast. Clips against ship blocks as well as the world.
     * Falls back to {@code level.clip(ctx)} when VS2 is absent or reflection
     * fails, which preserves vanilla behavior.
     */
    public static BlockHitResult clipIncludeShips(Level level, ClipContext ctx) {
        if (level == null) return null;
        if (!IntegrationRegistry.isValkyrienSkies()) return level.clip(ctx);
        resolve();
        if (!resolvedOk || clipIncludeShipsMethod == null) return level.clip(ctx);
        try {
            Object result = clipIncludeShipsMethod.invoke(null, level, ctx);
            if (result instanceof BlockHitResult hit) return hit;
            return level.clip(ctx);
        } catch (Throwable t) {
            return level.clip(ctx);
        }
    }
}
