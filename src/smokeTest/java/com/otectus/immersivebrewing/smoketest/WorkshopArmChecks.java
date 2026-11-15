package com.otectus.immersivebrewing.smoketest;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.otectus.immersivebrewing.client.render.WorkshopArmRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Measures actual renderer vertices with the client's baked vanilla layers, not duplicated model dimensions. */
final class WorkshopArmChecks {
    static void verify(Minecraft mc, Map<String, Object> checks) {
        checks.put("arm_runtime_skin_model", mc.player.getModelName());
        try {
            Method draw = WorkshopArmRenderer.class.getDeclaredMethod("draw", PoseStack.class, MultiBufferSource.class,
                    ResourceLocation.class, boolean.class, boolean.class, boolean.class, float.class, int.class);
            Method poseArm = WorkshopArmRenderer.class.getDeclaredMethod("poseArm", PoseStack.class, Vec3.class, Vec3.class,
                    boolean.class, boolean.class);
            draw.setAccessible(true);
            poseArm.setAccessible(true);
            for (boolean slim : new boolean[] {false, true}) for (boolean right : new boolean[] {false, true}) {
                String name = (slim ? "slim" : "classic") + (right ? "_right" : "_left");
                for (boolean sleeve : new boolean[] {false, true}) {
                    Capture vertices = new Capture();
                    draw.invoke(null, new PoseStack(), (MultiBufferSource) type -> vertices, mc.player.getSkinTextureLocation(),
                            slim, right, sleeve, .45F, 0xF000F0);
                    int expected = sleeve ? 48 : 24;
                    String key = "arm_" + name + (sleeve ? "_sleeve" : "_bare");
                    checks.put(key + "_vanilla_dimensions", vertices.points.size() == expected
                            && close(vertices.extent(0), ((slim ? 3 : 4) + (sleeve ? .5 : 0)) / 16.0)
                            && close(vertices.extent(1), (12 + (sleeve ? .5 : 0)) / 16.0)
                            && close(vertices.extent(2), (4 + (sleeve ? .5 : 0)) / 16.0));
                    checks.put(key + "_translucent", vertices.alphas.size() == expected
                            && faded(vertices.alphas.subList(0, 24), .45)
                            && (!sleeve || faded(vertices.alphas.subList(24, 48), .2475)));
                }
                // Widely separated tool/transfer grips and directions must not alter size, or move the wrist off its grip.
                boolean rigid = true, anchored = true;
                for (Vec3 grip : List.of(new Vec3(8, 18, 8), new Vec3(1, 3, -2), new Vec3(15, 11, 14))) {
                    for (Vec3 toward : List.of(new Vec3(-.5, .22, -1), new Vec3(.5, .22, -1), Vec3.ZERO, new Vec3(0, -1, 0))) {
                        PoseStack pose = new PoseStack();
                        poseArm.invoke(null, pose, grip, toward, slim, right);
                        Vector3f scale = pose.last().pose().getScale(new Vector3f());
                        rigid &= close(scale.x, 1) && close(scale.y, 1) && close(scale.z, 1)
                                && close(pose.last().pose().determinant(), 1);
                        Vector3f wrist = pose.last().pose().transformPosition(new Vector3f(
                                (right ? -1 : 1) * (slim ? .5F : 1F) / 16F, 10F / 16F, 0));
                        anchored &= close(wrist.x, grip.x / 16) && close(wrist.y, grip.y / 16) && close(wrist.z, grip.z / 16);
                    }
                }
                checks.put("arm_" + name + "_rigid_every_grip", rigid);
                checks.put("arm_" + name + "_wrist_on_grip", anchored);
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot inspect workshop arm geometry", e);
        }
    }

    private static boolean close(double a, double b) { return Math.abs(a - b) < 1.0e-5; }

    private static boolean faded(List<Integer> alpha, double opacity) {
        return alpha.stream().allMatch(a -> a >= 0 && a / 255.0 <= opacity + .005)
                && alpha.stream().anyMatch(a -> a == 0)
                && alpha.stream().anyMatch(a -> Math.abs(a / 255.0 - opacity) < .005);
    }

    private static final class Capture implements VertexConsumer {
        final List<double[]> points = new ArrayList<>();
        final List<Integer> alphas = new ArrayList<>();
        double extent(int axis) {
            return points.stream().mapToDouble(p -> p[axis]).max().orElseThrow()
                    - points.stream().mapToDouble(p -> p[axis]).min().orElseThrow();
        }
        @Override public VertexConsumer vertex(double x, double y, double z) { points.add(new double[] {x, y, z}); return this; }
        @Override public VertexConsumer color(int r, int g, int b, int a) { alphas.add(a); return this; }
        @Override public VertexConsumer uv(float u, float v) { return this; }
        @Override public VertexConsumer overlayCoords(int u, int v) { return this; }
        @Override public VertexConsumer uv2(int u, int v) { return this; }
        @Override public VertexConsumer normal(float x, float y, float z) { return this; }
        @Override public void endVertex() {}
        @Override public void defaultColor(int r, int g, int b, int a) {}
        @Override public void unsetDefaultColor() {}
    }

    private WorkshopArmChecks() {}
}
