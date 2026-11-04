package dev.skydock.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.skydock.cargo.CrateBlock;
import dev.skydock.cargo.CrateBlockEntity;
import dev.skydock.cargo.CrateRank;
import dev.skydock.cargo.CrateShow;
import dev.skydock.cargo.CrateShow.Phase;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Draws an opened crate through its show: the body, a separate lid that pops, tilts back or flies off by rank, the whole crate
 * lifting and spinning for Gold and Aether, and the Aether beam. An unopened crate is drawn by its block model instead.
 */
public final class CrateRenderer implements BlockEntityRenderer<CrateBlockEntity> {
    private static final float LID = 3 / 16f, BODY = 1 - LID;
    private final BlockRenderDispatcher blocks;
    public CrateRenderer(BlockEntityRendererProvider.Context context) { blocks = context.getBlockRenderDispatcher(); }

    @Override public void render(CrateBlockEntity crate, float partialTick, PoseStack poses, MultiBufferSource buffers, int light, int overlay) {
        if (!crate.opened() || crate.getLevel() == null) return;
        CrateRank rank = crate.rank(); float age = Math.min(crate.age(partialTick), CrateShow.duration(rank) - 1e-3f);
        Phase phase = CrateShow.phase(rank, (int) Math.floor(age)); float t = CrateShow.ease(CrateShow.progress(rank, age));
        BlockState state = crate.getBlockState().setValue(CrateBlock.OPEN, false);
        int glow = rank == CrateRank.GOLD && phase == Phase.SPIN || rank == CrateRank.AETHER ? LightTexture.FULL_BRIGHT : light;
        float lift = CrateShow.lift(rank, age);
        float spin = rank == CrateRank.GOLD && phase == Phase.SPIN ? 360 * t : rank == CrateRank.AETHER && phase == Phase.BEAM ? 180 * t : 0;
        poses.pushPose();
        poses.translate(.5, lift, .5); poses.mulPose(Axis.YP.rotationDegrees(spin)); poses.translate(-.5, 0, -.5);
        poses.pushPose(); poses.scale(1, BODY, 1); blocks.renderSingleBlock(state, poses, buffers, glow, overlay); poses.popPose();
        lid(rank, phase, t, state, poses, buffers, glow, overlay);
        poses.popPose();
        if (rank == CrateRank.AETHER && phase == Phase.BEAM) {
            poses.pushPose(); poses.translate(0, lift + 1, 0);
            BeaconRenderer.renderBeaconBeam(poses, buffers, BeaconRenderer.BEAM_LOCATION, partialTick, 1, crate.getLevel().getGameTime(), 0, Math.round(64 * t) + 1, 0xFFC890FF, .15f + .1f * t, .25f + .1f * t);
            poses.popPose();
        }
    }

    /** Tin and Copper tip the lid back on its rear hinge, Brass throws it spinning into the air, Gold keeps it shut and Aether floats it open over the beam. */
    private void lid(CrateRank rank, Phase phase, float t, BlockState state, PoseStack poses, MultiBufferSource buffers, int light, int overlay) {
        float up = 0, tilt = 0, turn = 0;
        switch (rank) {
            case TIN -> { up = .2f; tilt = 35; }
            case COPPER -> { up = .25f; tilt = 70; }
            case BRASS -> { if (phase != Phase.LID) return; up = 3 * t; turn = 540 * t; }
            case AETHER -> { if (phase == Phase.BEAM) { up = .6f * t; turn = 90 * t; } }
            default -> {}
        }
        if (rank.compareTo(CrateRank.COPPER) <= 0) { up *= t; tilt *= t; }
        poses.pushPose();
        poses.translate(0, BODY + up, 0);
        poses.translate(.5, 0, .5); poses.mulPose(Axis.YP.rotationDegrees(turn)); poses.translate(-.5, 0, -.5);
        poses.translate(0, 0, 1); poses.mulPose(Axis.XP.rotationDegrees(tilt)); poses.translate(0, 0, -1);
        poses.scale(1, LID, 1);
        blocks.renderSingleBlock(state, poses, buffers, light, overlay);
        poses.popPose();
    }

    /**
     * NeoForge culls a block entity renderer by {@code IBlockEntityRendererExtension.getRenderBoundingBox(T)}, a one-block box by default.
     * {@code common} cannot see that interface, so this declares its erased signature (no {@code @Override}, parameter erased to
     * {@link BlockEntity}) and overrides it at runtime with {@link CrateBlockEntity#renderBox}.
     */
    public net.minecraft.world.phys.AABB getRenderBoundingBox(BlockEntity be) {
        return be instanceof CrateBlockEntity crate ? CrateBlockEntity.renderBox(crate) : new net.minecraft.world.phys.AABB(be.getBlockPos());
    }
    @Override public boolean shouldRenderOffScreen(CrateBlockEntity crate) { return crate.rank() == CrateRank.AETHER; }
}
