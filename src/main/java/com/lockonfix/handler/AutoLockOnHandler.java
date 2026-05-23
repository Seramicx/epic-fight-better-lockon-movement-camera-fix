package com.lockonfix.handler;

import com.lockonfix.FixConfig;
import com.lockonfix.LockOnMovementFix;
import com.lockonfix.compat.ControllableIntegration;
import com.lockonfix.compat.FTBTeamsIntegration;
import com.lockonfix.compat.IntegrationRegistry;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import yesman.epicfight.api.animation.types.EntityState;
import yesman.epicfight.api.client.camera.EpicFightCameraAPI;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;
import yesman.epicfight.client.world.capabilites.entitypatch.player.LocalPlayerPatch;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

@Mod.EventBusSubscriber(modid = LockOnMovementFix.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class AutoLockOnHandler {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Minecraft MC = Minecraft.getInstance();

    private static boolean autoLockOnEnabled = false;

    private static boolean wasLockedOn = false;
    private static LivingEntity lastKnownTarget = null;

    private static LivingEntity previousTarget = null;

    private static int settlingDelay = 0;

    private static double flickAccum = 0.0;
    private static int flickCooldown = 0;

    private static volatile double capturedMouseDx = 0.0;

    public static void recordMouseDx(double dx) {
        capturedMouseDx = dx;
    }

    private static Field focusingEntityField = null;
    private static Method sendTargetingMethod = null;
    private static boolean reflectionInitialized = false;

    private static Field mouseAccumDXField = null;
    private static boolean mouseReflectionInitialized = false;

    private static EpicFightCameraAPI cachedAPI = null;

    private static EpicFightCameraAPI getAPI() {
        if (cachedAPI == null) {
            try { cachedAPI = EpicFightCameraAPI.getInstance(); }
            catch (Exception e) { return null; }
        }
        return cachedAPI;
    }

    private static boolean getFilterPlayers() {
        try { return FixConfig.FILTER_PLAYERS_FROM_AUTO_LOCKON.get(); }
        catch (Exception e) { return true; }
    }

    private static boolean getFilterFtbAllies() {
        try { return FixConfig.FILTER_FTB_ALLIES_FROM_AUTO_LOCKON.get(); }
        catch (Exception e) { return true; }
    }

    private static double getFlickSensitivity() {
        try { return FixConfig.FLICK_SENSITIVITY.get(); }
        catch (Exception e) { return 15.0; }
    }

    private static int getLockOnRange() {
        try { return FixConfig.LOCK_ON_RANGE.get(); }
        catch (Exception e) { return 64; }
    }

    public static boolean isAutoLockOnEnabled() {
        return autoLockOnEnabled;
    }

    private static boolean isInActionState(LocalPlayer player) {
        try {
            LocalPlayerPatch patch = EpicFightCapabilities.getLocalPlayerPatch(player);
            if (patch == null) return false;
            EntityState state = patch.getEntityState();
            if (state == null) return false;
            return state.movementLocked() || state.turningLocked();
        } catch (Exception e) {
            return false;
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (MC.player == null || MC.level == null) return;
        if (MC.screen != null) return;

        if (event.phase == TickEvent.Phase.START) {
            handleFlickTickStart();
            return;
        }

        handleToggleKeybind();

        if (settlingDelay > 0) settlingDelay--;

        EpicFightCameraAPI api = getAPI();
        boolean isLockedOn = api != null && api.isLockingOnTarget();
        LivingEntity currentTarget = api != null ? api.getFocusingEntity() : null;

        if (autoLockOnEnabled && api != null) {
            if (settlingDelay <= 0) {
                boolean targetDead = false;

                if (isLockedOn && currentTarget != null
                        && (!currentTarget.isAlive() || currentTarget.isRemoved())) {
                    targetDead = true;
                }

                if (wasLockedOn && !isLockedOn
                        && lastKnownTarget != null
                        && (!lastKnownTarget.isAlive() || lastKnownTarget.isRemoved())) {
                    targetDead = true;
                }

                if (targetDead) {
                    if (!IntegrationRegistry.isBetterLockOn()) {
                        handleTargetLost(api);
                    }
                }
            }
        }

        boolean isFirstPerson = MC.options.getCameraType() == CameraType.FIRST_PERSON;
        boolean bloGapInFirstPerson = isFirstPerson && IntegrationRegistry.isBetterLockOn();
        boolean flickActive = autoLockOnEnabled || bloGapInFirstPerson;
        if (!flickActive || !isLockedOn || currentTarget == null || !currentTarget.isAlive()) {
            resetFlickState();
        }

        wasLockedOn = isLockedOn;
        if (currentTarget != null) {
            lastKnownTarget = currentTarget;
        }
    }

    private static void handleToggleKeybind() {
        if (LockOnMovementFix.TOGGLE_AUTO_LOCKON == null) return;

        while (LockOnMovementFix.TOGGLE_AUTO_LOCKON.consumeClick()) {
            autoLockOnEnabled = !autoLockOnEnabled;

            if (MC.player != null) {
                Component status = autoLockOnEnabled
                    ? Component.literal("ON").withStyle(ChatFormatting.GREEN)
                    : Component.literal("OFF").withStyle(ChatFormatting.RED);
                MC.player.displayClientMessage(
                    Component.literal("Auto Lock-On: ").append(status), true
                );
            }
        }
    }

    private static void handleTargetLost(EpicFightCameraAPI api) {
        previousTarget = lastKnownTarget;

        LivingEntity best = findBestTarget(MC.player, lastKnownTarget, 0, null);
        if (best != null) {
            setFocusingEntityReflect(api, best);
            if (!api.isLockingOnTarget()) {
                api.setLockOn(true);
            }
            sendTargetingReflect(api, best);
            settlingDelay = 3;
        } else {
            if (api.isLockingOnTarget()) {
                api.setLockOn(false);
            }
            resetFlickState();
        }
    }

    private static void initMouseReflection() {
        if (mouseReflectionInitialized) return;
        mouseReflectionInitialized = true;

        Class<?> clazz = MouseHandler.class;
        String[] names = {"accumulatedDX", "cursorDeltaX", "f_91516_"};
        for (String name : names) {
            try {
                Field f = clazz.getDeclaredField(name);
                if (f.getType() != double.class && f.getType() != Double.TYPE) continue;
                f.setAccessible(true);
                mouseAccumDXField = f;
                LOGGER.info("Auto lock-on: MouseHandler horizontal delta field resolved as '{}'", name);
                return;
            } catch (NoSuchFieldException ignored) {}
        }
        LOGGER.error("Auto lock-on: could not resolve MouseHandler horizontal cursor delta field; flick switching disabled");
    }

    private static double readMouseAccumDx() {
        if (capturedMouseDx != 0.0) return capturedMouseDx;

        initMouseReflection();
        if (mouseAccumDXField == null) return 0;
        try {
            return mouseAccumDXField.getDouble(MC.mouseHandler);
        } catch (Exception e) {
            return 0;
        }
    }

    private static double mouseDxToYawDegrees(double dx) {
        if (dx == 0) return 0;
        if (Math.abs(dx) > 1500.0) return 0;
        double sens = MC.options.sensitivity().get() * 0.6 + 0.2;
        double f1 = sens * sens * sens * 8.0;
        return dx * f1;
    }

    private static void handleFlickTickStart() {
        EpicFightCameraAPI api = getAPI();
        if (api == null) return;

        LocalPlayer player = MC.player;
        boolean isLockedOn = api.isLockingOnTarget();
        LivingEntity currentTarget = api.getFocusingEntity();
        if (!isLockedOn || currentTarget == null || !currentTarget.isAlive()) return;

        boolean isFirstPerson = MC.options.getCameraType() == CameraType.FIRST_PERSON;
        boolean bloGapInFirstPerson = isFirstPerson && IntegrationRegistry.isBetterLockOn();
        if (!autoLockOnEnabled && !bloGapInFirstPerson) return;
        if (settlingDelay > 0) return;
        if (isInActionState(player)) return;

        flickAccum *= 0.98;

        if (flickCooldown > 0) {
            flickCooldown--;
            return;
        }

        double dx = readMouseAccumDx();
        double yawDegrees = mouseDxToYawDegrees(dx);
        double stickDegrees = ControllableIntegration.getCameraYawDelta();
        double tickDegrees = yawDegrees + stickDegrees;

        double damper = isFirstPerson ? 0.5 : 0.15;
        flickAccum += tickDegrees * damper;

        double threshold = getFlickSensitivity();
        if (Math.abs(flickAccum) <= threshold) return;

        int flickDir = isFirstPerson
            ? (flickAccum > 0 ? -1 : 1)
            : (flickAccum > 0 ?  1 : -1);

        previousTarget = currentTarget;
        float camYawPre = Float.NaN;
        try { camYawPre = api.getCameraYRot(); } catch (Throwable ignored) {}
        api.setNextLockOnTarget(flickDir);

        if (isFirstPerson) {
            LivingEntity newTarget = api.getFocusingEntity();
            if (newTarget != null && newTarget.isAlive() && newTarget != currentTarget) {
                Vec3 from = player.getEyePosition();
                Vec3 to = newTarget.getEyePosition();
                double tdx = to.x - from.x;
                double tdy = to.y - from.y;
                double tdz = to.z - from.z;
                double horiz = Math.sqrt(tdx * tdx + tdz * tdz);
                float newYaw = (float)(Mth.atan2(tdz, tdx) * (180.0 / Math.PI)) - 90.0F;
                float newPitch = (float)(-Mth.atan2(tdy, horiz) * (180.0 / Math.PI));

                final float blend = 0.85F;
                float oldYaw = camYawPre;
                float oldPitch = newPitch;
                try { oldPitch = api.getCameraXRot(); } catch (Throwable ignored) {}
                float blendedYaw = Mth.wrapDegrees(oldYaw + Mth.wrapDegrees(newYaw - oldYaw) * blend);
                float blendedPitch = oldPitch + (newPitch - oldPitch) * blend;

                try {
                    api.setCameraRotations(blendedPitch, blendedYaw, true);
                } catch (Throwable ignored) {}
            }
        }

        settlingDelay = 3;
        flickAccum = 0;
        flickCooldown = 4;  // BLO native uses 4 ticks
    }

    private static void resetFlickState() {
        flickAccum = 0;
        flickCooldown = 0;
    }

    private static LivingEntity findBestTarget(
        LocalPlayer player, LivingEntity exclude, int flickDir, LivingEntity reference
    ) {
        double maxRange = getLockOnRange();
        Vec3 cameraForward = new Vec3(MC.gameRenderer.getMainCamera().getLookVector());
        Vec3 playerEyePos = player.getEyePosition();

        LivingEntity bestCandidate = null;
        double bestScore = 0;

        for (Entity entity : MC.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living)) continue;
            if (living == player) continue;
            if (living == exclude) continue;
            if (living instanceof ArmorStand) continue;
            if (!living.isAlive() || living.isRemoved() || living.isSpectator()) continue;
            if (!living.isPickable()) continue;
            if (living.isInvisibleTo(player)) continue;
            if (living instanceof Player playerTarget) {
                if (getFilterPlayers()) continue;
                if (getFilterFtbAllies()) {
                    if (player.isAlliedTo(playerTarget)) continue;
                    if (IntegrationRegistry.isFtbTeams()
                            && FTBTeamsIntegration.isAllyOrSameTeam(playerTarget)) continue;
                }
            }

            double dist = player.distanceTo(living);
            if (dist > maxRange) continue;

            if (!player.hasLineOfSight(living)) continue;

            double score = scoreCandidate(
                player, living, cameraForward, playerEyePos, maxRange, flickDir, reference
            );

            if (score > bestScore) {
                bestScore = score;
                bestCandidate = living;
            }
        }

        return bestCandidate;
    }

    private static double scoreCandidate(
        LocalPlayer player, LivingEntity candidate, Vec3 cameraForward,
        Vec3 playerEyePos, double maxRange, int flickDir, LivingEntity reference
    ) {
        Vec3 toCandidate = candidate.getEyePosition().subtract(playerEyePos).normalize();

        double dot = Mth.clamp(cameraForward.dot(toCandidate), -1.0, 1.0);
        double angle = Math.toDegrees(Math.acos(dot));
        double coneScore = Math.max(0.0, 1.0 - (angle / 90.0));

        double dist = player.distanceTo(candidate);
        double distScore = Math.max(0.0, 1.0 - (dist / maxRange));

        double contScore = 0.0;
        if (previousTarget != null && previousTarget.isAlive() && !previousTarget.isRemoved()) {
            Vec3 prevDir = previousTarget.getEyePosition().subtract(playerEyePos).normalize();
            double prevDot = Mth.clamp(prevDir.dot(toCandidate), -1.0, 1.0);
            double angleBetween = Math.toDegrees(Math.acos(prevDot));
            contScore = Math.max(0.0, 1.0 - (angleBetween / 90.0));
        }

        if (flickDir != 0 && reference != null) {
            float candYaw = getYawToEntity(player, candidate);
            float refYaw = getYawToEntity(player, reference);
            float relAngle = Mth.wrapDegrees(candYaw - refYaw);

            if (relAngle * flickDir <= 0) {
                return 0.0;
            }

            coneScore = Math.max(0.0, 1.0 - (Math.abs(relAngle) / 180.0));
        }

        return coneScore * 0.5 + distScore * 0.3 + contScore * 0.2;
    }

    private static float getYawToEntity(LocalPlayer player, Entity target) {
        double dx = target.getX() - player.getX();
        double dz = target.getZ() - player.getZ();
        return (float)(Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
    }

    private static void initReflection() {
        if (reflectionInitialized) return;
        reflectionInitialized = true;

        try {
            focusingEntityField = EpicFightCameraAPI.class.getDeclaredField("focusingEntity");
            focusingEntityField.setAccessible(true);
        } catch (Exception e) {
            LOGGER.error("Auto lock-on: cannot access focusingEntity field; auto target switching disabled: {}", e.getMessage());
        }

        try {
            sendTargetingMethod = EpicFightCameraAPI.class.getDeclaredMethod("sendTargeting", LivingEntity.class);
            sendTargetingMethod.setAccessible(true);
        } catch (Exception e) {
            LOGGER.error("Auto lock-on: cannot access sendTargeting method; server sync disabled: {}", e.getMessage());
        }
    }

    private static void setFocusingEntityReflect(EpicFightCameraAPI api, LivingEntity target) {
        initReflection();
        if (focusingEntityField == null) return;
        try {
            focusingEntityField.set(api, target);
        } catch (Exception e) {
            LOGGER.warn("Failed to set focusing entity: {}", e.getMessage());
        }
    }

    private static void sendTargetingReflect(EpicFightCameraAPI api, LivingEntity target) {
        initReflection();
        if (sendTargetingMethod == null) return;
        try {
            sendTargetingMethod.invoke(api, target);
        } catch (Exception e) {
            LOGGER.warn("Failed to send targeting packet: {}", e.getMessage());
        }
    }
}
