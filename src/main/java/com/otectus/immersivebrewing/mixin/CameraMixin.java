package com.otectus.immersivebrewing.mixin;

import com.otectus.immersivebrewing.client.workshop.WorkshopCamera;
import com.otectus.immersivebrewing.workshop.CameraFit;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Moves the render camera to the working view's rig (handoff 5.1). Forge 1.20.1 has no camera position event
 * ({@code ViewportEvent} only turns the camera), so this runs at the end of {@code Camera.setup}, after vanilla and
 * camera mods placed the camera: their pose is where the glide starts and ends. Only the game's main camera, and only
 * while the controller frames a station; otherwise nothing changes.
 *
 * <p>Adapted from Diegesis's {@code CameraMixin}, Copyright (c) 2026 Pocky, MIT License; the full notice ships as
 * {@code META-INF/licenses/Diegesis-MIT.txt}. Brewing does not otherwise include or depend on Diegesis (ADR-6).
 */
@Mixin(value = Camera.class, priority = 1500)
public abstract class CameraMixin {
    @Shadow
    private boolean detached;

    @Shadow
    protected abstract void setPosition(Vec3 pos);

    @Shadow
    protected abstract void setRotation(float yRot, float xRot);

    @Shadow
    public abstract Vec3 getPosition();

    @Shadow
    public abstract float getXRot();

    @Shadow
    public abstract float getYRot();

    @Inject(method = "setup", at = @At("TAIL"))
    private void immersiveBrewing$workshopCamera(BlockGetter level, Entity entity, boolean detachedView, boolean mirrored, float partialTick,
                                               CallbackInfo ci) {
        if ((Object) this != Minecraft.getInstance().gameRenderer.getMainCamera() || !WorkshopCamera.active()) return;
        CameraFit.Pose pose = WorkshopCamera.pose(getPosition(), getYRot(), getXRot());
        if (pose == null) return;
        setRotation(pose.yaw(), pose.pitch());
        setPosition(pose.position());
        // Away from the eyes the player's own body is not drawn into the work (it stays drawn for everyone else).
        if (pose.position().distanceToSqr(entity.getEyePosition(partialTick)) > 0.04) detached = false;
    }
}
