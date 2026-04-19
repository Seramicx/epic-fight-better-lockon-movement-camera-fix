package com.lockonfix.client;

import com.lockonfix.LockOnMovementFix;
import com.mojang.logging.LogUtils;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import yesman.epicfight.api.client.camera.EpicFightCameraAPI;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Small Epic Fight client checks used by crosshair, camera, and pick mixins.
 */
@Mod.EventBusSubscriber(modid = LockOnMovementFix.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class EpicFightClientHooks {

    private static final Logger LOGGER = LogUtils.getLogger();

    private EpicFightClientHooks() {}

    public static boolean isLockOnTargeting() {
        try {
            EpicFightCameraAPI api = EpicFightCameraAPI.getInstance();
            return api != null && api.isLockingOnTarget();
        } catch (Throwable t) {
            return false;
        }
    }

    // ----- Iron's Spells cast detection (cached reflection) -----
    private static Method isCastingMethod = null;
    private static Method castDurationRemainingMethod = null;
    private static Method clientGetCastTypeMethod = null;
    private static boolean ironsResolved = false;

    // Latch: once aiming is detected, keep it true briefly to bridge detection
    // gaps during tap-cast animations (button released but spell still winding up).
    private static long lastAimingMs = 0L;
    private static final long AIM_LATCH_MS = 400L;

    // INSTANT cast detection: server processes initiateCast + resetCastingState in
    // one tick, so the client never observes isCasting=true. Latch on the right-click
    // press itself (via PlayerInteractEvent) and treat the next 2s as aiming so
    // auto-face stays active through the cast animation.
    private static long lastCastAttemptMs = 0L;
    private static final long CAST_ATTEMPT_LATCH_MS = 2000L;

    // Reflection cache for resolving the currently selected spell's CastType, used
    // to decide whether to camera-snap on right-click (INSTANT only).
    private static Method getSpellSelectionManagerMethod = null;
    private static Method getSelectionMethod = null;
    private static Field selectionSpellDataField = null;
    private static Method spellDataGetSpellMethod = null;
    private static Method spellGetCastTypeMethod = null;
    private static boolean selectedSpellResolved = false;

    private static void resolveIrons() {
        if (ironsResolved) return;
        ironsResolved = true;

        try {
            Class<?> cmd = Class.forName("io.redspace.ironsspellbooks.player.ClientMagicData");

            try {
                Method m = cmd.getMethod("isCasting");
                if (m.getReturnType() == boolean.class) {
                    isCastingMethod = m;
                }
            } catch (NoSuchMethodException ignored) {}

            // Prefer the canonical getCastDurationRemaining (resets to 0 on cast end).
            // Don't grab "any getCast* method" — getMethods() ordering isn't stable
            // and getCastingSpellLevel/getCastDuration would also match but mean
            // different things.
            try {
                Method m = cmd.getMethod("getCastDurationRemaining");
                Class<?> r = m.getReturnType();
                if (r == int.class || r == long.class || r == float.class || r == double.class) {
                    castDurationRemainingMethod = m;
                }
            } catch (NoSuchMethodException ignored) {}

            // Used to exclude INSTANT casts from "aiming" — the client may briefly
            // observe isCasting=true between the two server packets even though
            // the cast is INSTANT and shouldn't engage the adaptive crosshair.
            try {
                Method m = cmd.getMethod("getCastType");
                clientGetCastTypeMethod = m;
            } catch (NoSuchMethodException ignored) {}

            LOGGER.info("Iron's Spells cast detection: isCasting={}, duration={}, castType={}",
                    isCastingMethod != null,
                    castDurationRemainingMethod != null ? castDurationRemainingMethod.getName() : "none",
                    clientGetCastTypeMethod != null);
        } catch (ClassNotFoundException e) {
            // Iron's Spells not installed — silent
        } catch (Throwable t) {
            LOGGER.warn("Iron's Spells reflection init failed: {}", t.getMessage());
        }
    }

    private static boolean ironsIsCasting() {
        resolveIrons();
        if (isCastingMethod != null) {
            try {
                if ((boolean) isCastingMethod.invoke(null)) return true;
            } catch (Throwable ignored) {}
        }
        if (castDurationRemainingMethod != null) {
            try {
                Object v = castDurationRemainingMethod.invoke(null);
                if (v instanceof Number && ((Number) v).doubleValue() > 0) return true;
            } catch (Throwable ignored) {}
        }
        return false;
    }

    /** True only when the active cast (if any) is reported as INSTANT type. */
    private static boolean ironsCurrentCastIsInstant() {
        resolveIrons();
        if (clientGetCastTypeMethod == null) return false;
        try {
            Object t = clientGetCastTypeMethod.invoke(null);
            return t != null && "INSTANT".equals(t.toString());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Check if the player is in a projectile aiming or spell-casting state.
     * Covers: vanilla bow/crossbow/trident (held), Iron's Spells held casts
     * (continuous/charge), and tap-cast spells (instant/long) during windup.
     * Applies a short latch so tap-casts remain "aiming" through the cast time.
     */
    public static boolean isAiming(LocalPlayer player) {
        if (isAimingRaw(player)) {
            lastAimingMs = System.currentTimeMillis();
            return true;
        }
        return (System.currentTimeMillis() - lastAimingMs) < AIM_LATCH_MS;
    }

    private static boolean isAimingRaw(LocalPlayer player) {
        if (player == null) return false;

        if (player.isUsingItem()) {
            ItemStack stack = player.getUseItem();
            if (stack.getItem() instanceof BowItem
                    || stack.getItem() instanceof CrossbowItem
                    || stack.getItem() instanceof TridentItem) {
                return true;
            }
            // Held Iron's Spells item (spellbook, scroll, staff) — treat as aiming.
            if (stack.getItem().getClass().getName().startsWith("io.redspace.ironsspellbooks")) {
                return true;
            }
        }

        if ((System.currentTimeMillis() - lastCastAttemptMs) < CAST_ATTEMPT_LATCH_MS) {
            return true;
        }

        return ironsIsCasting();
    }

    /**
     * Strict aiming check for the adaptive crosshair. Excludes the INSTANT-cast
     * latch (we snap the player on click instead) and Iron's Spells "held item"
     * heuristic (CastingItem.use returns consume, never use-anim). Only true for
     * actually-drawn ranged weapons or active non-instant Iron's Spells casts.
     *
     * INSTANT casts are explicitly rejected: even if the client briefly observes
     * isCasting=true between the server's start/finish packets, the cast type is
     * INSTANT and the camera-snap handles aim — the crosshair must NOT shift.
     */
    public static boolean isAimingForCrosshair(LocalPlayer player) {
        if (player == null) return false;

        if (player.isUsingItem()) {
            ItemStack stack = player.getUseItem();
            if (stack.getItem() instanceof BowItem
                    || stack.getItem() instanceof CrossbowItem
                    || stack.getItem() instanceof TridentItem) {
                return true;
            }
        }

        return ironsIsCasting() && !ironsCurrentCastIsInstant();
    }

    // ----- Iron's Spells "currently selected spell" reflection -----

    private static void resolveSelectedSpell() {
        if (selectedSpellResolved) return;
        selectedSpellResolved = true;
        try {
            Class<?> cmd = Class.forName("io.redspace.ironsspellbooks.player.ClientMagicData");
            getSpellSelectionManagerMethod = cmd.getMethod("getSpellSelectionManager");

            Class<?> mgrClass = getSpellSelectionManagerMethod.getReturnType();
            getSelectionMethod = mgrClass.getMethod("getSelection");

            Class<?> optClass = getSelectionMethod.getReturnType();
            selectionSpellDataField = optClass.getField("spellData");

            Class<?> spellDataClass = selectionSpellDataField.getType();
            spellDataGetSpellMethod = spellDataClass.getMethod("getSpell");

            Class<?> spellClass = spellDataGetSpellMethod.getReturnType();
            spellGetCastTypeMethod = spellClass.getMethod("getCastType");

            LOGGER.info("Iron's Spells selected-spell reflection resolved.");
        } catch (ClassNotFoundException e) {
            // Iron's Spells not installed — silent.
        } catch (Throwable t) {
            LOGGER.warn("Selected-spell reflection failed: {}", t.getMessage());
        }
    }

    private static boolean isSelectedSpellInstant() {
        resolveSelectedSpell();
        if (getSpellSelectionManagerMethod == null) return false;
        try {
            Object mgr = getSpellSelectionManagerMethod.invoke(null);
            if (mgr == null) return false;
            Object sel = getSelectionMethod.invoke(mgr);
            if (sel == null) return false;
            Object spellData = selectionSpellDataField.get(sel);
            if (spellData == null) return false;
            Object spell = spellDataGetSpellMethod.invoke(spellData);
            if (spell == null) return false;
            Object castType = spellGetCastTypeMethod.invoke(spell);
            return castType != null && "INSTANT".equals(castType.toString());
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ----- Right-click handler: latch + INSTANT camera-snap -----

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || event.getEntity() != mc.player) return;
        if (!event.getItemStack().getItem().getClass().getName()
                .startsWith("io.redspace.ironsspellbooks")) {
            return;
        }

        // (1) Auto-face latch — bridges INSTANT cast windows during lock-on.
        lastCastAttemptMs = System.currentTimeMillis();

        // (2) Camera-snap for INSTANT spells when NOT locked on. Lock-on already
        // rotates toward the target via auto-face, so don't fight it.
        if (!isLockOnTargeting()
                && mc.options.getCameraType() == CameraType.THIRD_PERSON_BACK
                && isSelectedSpellInstant()) {
            snapPlayerToHitResult(mc.player);
        }
    }

    /**
     * Rotate the player to aim from their eye at the camera-center hit point.
     * mc.hitResult only extends to interaction reach (~5 blocks) and is MISS
     * beyond that, so we do our own 256-block raytrace from the camera origin
     * along the camera look vector — matching whatever the crosshair points at,
     * even for distant targets or open sky. We then force-send a rotation
     * packet so the server processes the new yaw/pitch before the
     * ServerboundUseItemPacket that's about to follow.
     */
    private static final double SNAP_TRACE_DISTANCE = 256.0;

    private static void snapPlayerToHitResult(LocalPlayer player) {
        Minecraft mc = Minecraft.getInstance();
        Vec3 camPos = mc.gameRenderer.getMainCamera().getPosition();
        Vec3 camLook = Vec3.directionFromRotation(
                mc.gameRenderer.getMainCamera().getXRot(),
                mc.gameRenderer.getMainCamera().getYRot());
        Vec3 end = camPos.add(camLook.scale(SNAP_TRACE_DISTANCE));

        BlockHitResult blockHit = player.level().clip(new ClipContext(
                camPos, end,
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                player));
        Vec3 blockTarget = blockHit.getType() != HitResult.Type.MISS ? blockHit.getLocation() : end;

        double reach = camPos.distanceTo(blockTarget);
        AABB box = new AABB(camPos, blockTarget).inflate(1.0);
        EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
                player, camPos, blockTarget, box,
                e -> !e.isSpectator() && e.isPickable() && e != player,
                reach * reach);

        Vec3 target = entityHit != null ? entityHit.getLocation() : blockTarget;

        Vec3 eye = player.getEyePosition(1.0F);
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        if (horiz < 1.0E-4 && Math.abs(dy) < 1.0E-4) return;

        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horiz));

        player.setYRot(yaw);
        player.setXRot(pitch);
        player.yBodyRot = yaw;
        player.yHeadRot = yaw;

        if (mc.getConnection() != null) {
            mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(yaw, pitch, player.onGround()));
        }
    }
}
