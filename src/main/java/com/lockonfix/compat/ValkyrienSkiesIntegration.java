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

    public static boolean isMountedOnShip(Entity entity) {
        return getShipMountedTo(entity) != null;
    }

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

            double rad = Math.toRadians(worldYaw);
            Vector3d worldLook = new Vector3d(-Math.sin(rad), 0.0, Math.cos(rad));
            Vector3d shipLook = new Vector3d();
            worldToShip.transformDirection(worldLook, shipLook);

            return (float) (Math.atan2(-shipLook.x, shipLook.z) * 180.0 / Math.PI);
        } catch (Throwable t) {
            return worldYaw;
        }
    }

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
