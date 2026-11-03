package dev.skydock.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.skydock.block.SkydockBlocks;
import dev.skydock.cargo.PodEntity;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;

/** An ejected pod: the pod block, and once open, a lift-cell balloon above it on three ropes. Drawn at full brightness so it shows from afar. */
public final class PodEntityRenderer extends EntityRenderer<PodEntity> {
    private static final float BALLOON = 1.5f, LIFT = 1.2f;
    private final BlockRenderDispatcher blocks;
    public PodEntityRenderer(EntityRendererProvider.Context context) { super(context); blocks = context.getBlockRenderDispatcher(); shadowRadius = .4f; }

    @Override public void render(PodEntity pod, float yaw, float partialTick, PoseStack poses, MultiBufferSource buffers, int light) {
        poses.pushPose(); poses.translate(-.5, 0, -.5);
        blocks.renderSingleBlock(SkydockBlocks.CARGO_POD.get().defaultBlockState(), poses, buffers, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
        poses.popPose();
        if (pod.open()) {
            float bottom = 1 + LIFT;
            poses.pushPose(); poses.translate(-BALLOON / 2, bottom, -BALLOON / 2); poses.scale(BALLOON, BALLOON, BALLOON);
            blocks.renderSingleBlock(SkydockBlocks.LIFT_CELL.get().defaultBlockState(), poses, buffers, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
            poses.popPose();
            VertexConsumer lines = buffers.getBuffer(RenderType.lines()); PoseStack.Pose pose = poses.last();
            for (int i = 0; i < 3; i++) {
                double a = i * Math.PI * 2 / 3;
                float cx = (float) Math.cos(a), cz = (float) Math.sin(a);
                rope(lines, pose, cx * .35f, 1, cz * .35f, cx * BALLOON * .4f, bottom, cz * BALLOON * .4f);
            }
        }
        super.render(pod, yaw, partialTick, poses, buffers, light);
    }
    private static void rope(VertexConsumer lines, PoseStack.Pose pose, float x0, float y0, float z0, float x1, float y1, float z1) {
        float dx = x1 - x0, dy = y1 - y0, dz = z1 - z0, length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        dx /= length; dy /= length; dz /= length;
        lines.addVertex(pose, x0, y0, z0).setColor(0x6B, 0x4F, 0x2E, 0xFF).setNormal(pose, dx, dy, dz);
        lines.addVertex(pose, x1, y1, z1).setColor(0x6B, 0x4F, 0x2E, 0xFF).setNormal(pose, dx, dy, dz);
    }
    @SuppressWarnings("deprecation")
    @Override public ResourceLocation getTextureLocation(PodEntity pod) { return TextureAtlas.LOCATION_BLOCKS; }
}
