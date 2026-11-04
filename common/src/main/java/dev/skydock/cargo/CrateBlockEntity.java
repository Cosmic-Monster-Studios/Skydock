package dev.skydock.cargo;

import dev.skydock.Skydock;
import dev.skydock.block.SkydockBlocks;
import dev.skydock.cargo.CrateShow.Phase;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import java.util.Locale;
import java.util.UUID;

/**
 * A placed crate. Opening it records the game time and the opener and syncs the time to clients, which animate the show from it;
 * the server plays the rank's sounds as phases change and, at the burst, removes the block, rolls {@code skydock:crate/<rank>},
 * flings the stacks into the air and awards the opener's pattern prize ({@link Shop#awardCratePrize}), online or not.
 */
public final class CrateBlockEntity extends BlockEntity {
    private long openedAt = -1;
    private UUID opener;
    private Phase cued;
    public CrateBlockEntity(BlockPos pos, BlockState state) { super(SkydockBlocks.CRATE_ENTITY.get(), pos, state); }

    public CrateRank rank() { return ((CrateBlock) getBlockState().getBlock()).rank; }
    public boolean opened() { return openedAt >= 0; }
    /** Ticks since the show started, with the partial tick, for the renderer. */
    public float age(float partialTick) { return level == null ? 0 : level.getGameTime() - openedAt + partialTick; }

    void open(ServerPlayer player) {
        if (opened() || level == null) return;
        openedAt = level.getGameTime(); opener = player.getUUID(); setChanged();
        level.setBlock(worldPosition, getBlockState().setValue(CrateBlock.OPEN, true), 3);
        level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, CrateBlockEntity crate) {
        if (!crate.opened() || !(level instanceof ServerLevel server)) return;
        CrateRank rank = crate.rank(); int age = (int) (level.getGameTime() - crate.openedAt);
        Phase phase = age >= CrateShow.duration(rank) ? Phase.BURST : CrateShow.phase(rank, age);
        if (phase != crate.cued) { crate.cued = phase; cue(server, pos, rank, phase); }
        if (phase == Phase.BURST) crate.burst(server, pos, rank);
    }

    private static void cue(ServerLevel level, BlockPos pos, CrateRank rank, Phase phase) {
        switch (rank) {
            case TIN -> { if (phase == Phase.LID) play(level, pos, SoundEvents.CHEST_OPEN, 1, 1.2f); }
            case COPPER -> { if (phase == Phase.LID) play(level, pos, SoundEvents.CHEST_OPEN, 1, 1.1f); else if (phase == Phase.BURST) play(level, pos, SoundEvents.AMETHYST_BLOCK_CHIME, 1.5f, 1); }
            case BRASS -> {
                if (phase == Phase.LID) play(level, pos, SoundEvents.CHEST_OPEN, 1, .9f);
                else if (phase == Phase.RISE) for (float pitch : new float[] {1, 1.26f, 1.5f}) play(level, pos, SoundEvents.NOTE_BLOCK_BELL.value(), 1.5f, pitch);
            }
            case GOLD -> { if (phase == Phase.RISE) play(level, pos, SoundEvents.CHEST_OPEN, 1, .8f); else if (phase == Phase.BURST) play(level, pos, SoundEvents.PLAYER_LEVELUP, 2, 1); }
            case AETHER -> { if (phase == Phase.RISE) play(level, pos, SoundEvents.BEACON_ACTIVATE, 2, 1); else if (phase == Phase.BURST) play(level, pos, SoundEvents.GENERIC_EXPLODE.value(), 4, 1.2f); }
        }
    }
    private static void play(ServerLevel level, BlockPos pos, SoundEvent sound, float volume, float pitch) { level.playSound(null, pos, sound, SoundSource.BLOCKS, volume, pitch); }

    private void burst(ServerLevel level, BlockPos pos, CrateRank rank) {
        Vec3 from = Vec3.atCenterOf(pos).add(0, CrateShow.lift(rank, CrateShow.duration(rank)), 0);
        UUID who = opener;
        ServerPlayer player = who == null ? null : level.getServer().getPlayerList().getPlayer(who);
        // The crate is spent before anything can fail, so an error never rolls the loot again on the next tick.
        openedAt = -1; level.removeBlock(pos, false);
        LootParams params = new LootParams.Builder(level).withParameter(LootContextParams.ORIGIN, from).withOptionalParameter(LootContextParams.THIS_ENTITY, player).create(LootContextParamSets.CHEST);
        ResourceKey<net.minecraft.world.level.storage.loot.LootTable> table = ResourceKey.create(Registries.LOOT_TABLE, Skydock.id("crate/" + rank.name().toLowerCase(Locale.ROOT)));
        RandomSource random = level.random;
        for (ItemStack stack : level.getServer().reloadableRegistries().getLootTable(table).getRandomItems(params)) {
            ItemEntity item = new ItemEntity(level, from.x, from.y, from.z, stack, Mth.nextDouble(random, -.15, .15), Mth.nextDouble(random, .3, .5), Mth.nextDouble(random, -.15, .15));
            item.setDefaultPickUpDelay(); level.addFreshEntity(item);
        }
        level.sendParticles(rank == CrateRank.AETHER ? ParticleTypes.FLASH : ParticleTypes.POOF, from.x, from.y, from.z, rank == CrateRank.AETHER ? 1 : 8, .3, .3, .3, .05);
        if (who != null) {
            try { Shop.awardCratePrize(level.getServer(), who, rank, random); }
            catch (RuntimeException ex) { Skydock.LOGGER.error("Crate pattern prize failed for {}", who, ex); }
        }
    }

    public static void clientTick(Level level, BlockPos pos, BlockState state, CrateBlockEntity crate) {
        if (!crate.opened()) return;
        CrateRank rank = crate.rank(); int age = (int) (level.getGameTime() - crate.openedAt); Phase phase = CrateShow.phase(rank, age);
        float progress = CrateShow.progress(rank, age); RandomSource random = level.random;
        double x = pos.getX() + .5, y = pos.getY() + CrateShow.lift(rank, age), z = pos.getZ() + .5;
        switch (rank) {
            case COPPER -> { if (phase == Phase.LID && age % 4 == 0) for (int i = 0; i < 12; i++) {
                double a = i * Math.PI / 6; level.addParticle(ParticleTypes.WAX_ON, x + Math.cos(a) * .8, y + 1, z + Math.sin(a) * .8, 0, .05, 0);
            } }
            case BRASS -> { if (phase == Phase.RISE) for (int i = 0; i < 3; i++) {
                double a = age * .6 + i * Math.PI * 2 / 3; level.addParticle(ParticleTypes.ELECTRIC_SPARK, x + Math.cos(a) * .5, y + 1 + progress * 2, z + Math.sin(a) * .5, 0, .02, 0);
            } }
            case GOLD -> { if (phase == Phase.SPIN) for (int i = 0; i < 4; i++)
                level.addParticle(ParticleTypes.GLOW, x + random.nextGaussian() * .6, y + 1.5 + random.nextDouble(), z + random.nextGaussian() * .6, 0, -.1, 0); }
            case AETHER -> {
                if (phase == Phase.RISE) for (int i = 0; i < 6; i++) {
                    double a = age * .3 + i * Math.PI / 3, r = 2.5 - progress * 1.5;
                    level.addParticle(ParticleTypes.PORTAL, x, y + .5, z, Math.cos(a) * r, random.nextDouble() - .5, Math.sin(a) * r);
                } else if (phase == Phase.BEAM) for (int i = 0; i < 3; i++)
                    level.addParticle(ParticleTypes.END_ROD, x + random.nextGaussian() * .1, y + 1 + random.nextDouble() * 8, z + random.nextGaussian() * .1, 0, .2, 0);
            }
            default -> {}
        }
    }

    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (opened()) tag.putLong("OpenedAt", openedAt);
        if (opener != null) tag.putUUID("Opener", opener);
    }
    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        openedAt = tag.contains("OpenedAt") ? tag.getLong("OpenedAt") : -1;
        opener = tag.hasUUID("Opener") ? tag.getUUID("Opener") : null;
    }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) { CompoundTag tag = new CompoundTag(); if (opened()) tag.putLong("OpenedAt", openedAt); return tag; }
    @Override public Packet<ClientGamePacketListener> getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
}
