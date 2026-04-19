package com.lockonfix.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import com.lockonfix.client.EpicFightClientHooks;

import java.util.function.Predicate;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Redirect(
        method = "pick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/projectile/ProjectileUtil;getEntityHitResult(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;D)Lnet/minecraft/world/phys/EntityHitResult;"
        )
    )
    private EntityHitResult redirectGetEntityHitResult(Entity shooter, Vec3 startPos, Vec3 endPos, AABB boundingBox, Predicate<Entity> filter, double interactionRangeSq) {
        Minecraft mc = Minecraft.getInstance();

        if (mc.player != null && shooter == mc.player && mc.options.getCameraType() == CameraType.THIRD_PERSON_BACK && !EpicFightClientHooks.isLockOnTargeting()) {
            Camera camera = mc.gameRenderer.getMainCamera();
            Vec3 cameraPos = camera.getPosition();
            Vec3 lookVec = new Vec3(camera.getLookVector());

            float partialTick = mc.getFrameTime();
            double eyeToCameraDist = cameraPos.distanceTo(shooter.getEyePosition(partialTick));
            double interactionRange = Math.sqrt(interactionRangeSq);
            double totalTraceDist = interactionRange + eyeToCameraDist;

            Vec3 cameraEndPos = cameraPos.add(lookVec.scale(totalTraceDist));

            // Perform block clip from camera to find world target
            BlockHitResult blockHit = shooter.level().clip(new ClipContext(
                cameraPos, cameraEndPos, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, shooter
            ));

            Vec3 worldTargetPos = blockHit.getType() != HitResult.Type.MISS ? blockHit.getLocation() : cameraEndPos;
            double distToWorldTarget = worldTargetPos.distanceTo(cameraPos);

            // Now trace entities between camera and the world target
            AABB searchBox = new AABB(cameraPos, worldTargetPos).inflate(1.0);
            return ProjectileUtil.getEntityHitResult(shooter, cameraPos, worldTargetPos, searchBox, filter, distToWorldTarget * distToWorldTarget);
        }
        
        // Vanilla fallback
        return ProjectileUtil.getEntityHitResult(shooter, startPos, endPos, boundingBox, filter, interactionRangeSq);
    }
}
