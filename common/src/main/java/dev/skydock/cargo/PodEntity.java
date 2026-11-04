package dev.skydock.cargo;

import dev.skydock.block.SkydockBlocks;
import dev.skydock.ship.Ship;
import dev.skydock.ship.ShipPose;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * A cargo pod thrown clear of a damaged hull. It tumbles, opens a balloon and drifts down with the wind, then sits
 * where it lands for good: it never despawns and cannot be hurt. Any player may pick it up; falling out of the world loses it.
 */
public final class PodEntity extends Entity {
    private static final EntityDataAccessor<Boolean> OPEN = SynchedEntityData.defineId(PodEntity.class, EntityDataSerializers.BOOLEAN);
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
    private PodCargo cargo;
    private int age;
    private boolean landed;

    public PodEntity(EntityType<? extends PodEntity> type, Level level) { super(type, level); }

    /** Throws the pod at {@code cell} out of the hull: up, and away from what the ship struck. The caller refreshes the ship. */
    public static void eject(ServerLevel world, ServerLevel yard, Ship ship, ShipPose pose, BlockPos cell, Vec3 impactDirection) {
        BlockPos at = ship.yard.offset(cell);
        PodCargo stored = yard.getBlockEntity(at) instanceof CargoPodBlockEntity pod ? pod.cargo() : null;
        yard.setBlock(at, Blocks.AIR.defaultBlockState(), FLAGS);
        PodLoading.forget(ship, cell);
        ship.revision++;
        Vec3 center = pose.toWorld(Vec3.atCenterOf(cell).subtract(ship.center()));
        world.playSound(null, center.x, center.y, center.z, SoundEvents.CHICKEN_EGG, SoundSource.BLOCKS, 2, .5f);
        if (stored == null) return;
        PodEntity entity = new PodEntity(SkydockBlocks.POD.get(), world);
        PodTag tag = stored.tag();
        entity.cargo = new PodCargo(new PodTag(tag.contract(), tag.index(), true), stored.cargoKey(), stored.destination());
        entity.setPos(center.x, center.y - entity.getBbHeight() / 2, center.z);
        entity.setDeltaMovement(ship.velocity.add(0, .4, 0).add(away(impactDirection)));
        world.addFreshEntity(entity);
    }
    /** A 0.3 block per tick kick along the horizontal opposite the impact, or none for a straight vertical blow. */
    static Vec3 away(Vec3 impact) {
        Vec3 flat = new Vec3(-impact.x, 0, -impact.z);
        return flat.lengthSqr() < 1e-8 ? Vec3.ZERO : flat.normalize().scale(.3);
    }

    /** A pod comes to rest only on ground beneath it once the balloon is open; brushing a wall is not landing. */
    public static boolean landsOn(boolean onGround, double vy, int age) { return onGround && vy <= 0 && age >= PodDrift.TUMBLE; }

    public PodCargo cargo() { return cargo; }
    public boolean open() { return entityData.get(OPEN); }

    @Override public void tick() {
        super.tick();
        if (!(level() instanceof ServerLevel world)) return;
        if (getY() < world.getMinBuildHeight()) { lose(world); return; }
        if (landed) {
            setDeltaMovement(Vec3.ZERO);
            if (tickCount % 10 == 0 && world.noCollision(this, getBoundingBox().move(0, -.05, 0))) landed = false;
            else return;
        }
        if (age == PodDrift.TUMBLE) world.playSound(null, getX(), getY(), getZ(), SoundEvents.WOOL_PLACE, SoundSource.NEUTRAL, 1, 1);
        if (age >= PodDrift.TUMBLE && !open()) entityData.set(OPEN, true);
        setDeltaMovement(PodDrift.step(getDeltaMovement(), age, CargoSavedData.get(world.getServer()).wind));
        move(MoverType.SELF, getDeltaMovement());
        if (landsOn(onGround(), getDeltaMovement().y, age)) { landed = true; setDeltaMovement(Vec3.ZERO); }
        if (age % 5 == 0) world.sendParticles(ParticleTypes.END_ROD, getX(), getY() + .5, getZ(), 1, .1, .1, .1, .01);
        age++;
    }
    private void lose(ServerLevel world) {
        if (cargo != null) {
            CargoSavedData data = CargoSavedData.get(world.getServer());
            var holder = data.findContract(cargo.tag().contract());
            if (holder != null) {
                CargoService.applyResult(data, holder.getKey(), holder.getValue(), Delivery.lose(holder.getValue(), cargo.tag().index()));
                net.minecraft.server.level.ServerPlayer online = world.getServer().getPlayerList().getPlayer(holder.getKey());
                if (online != null) CargoService.sync(online);
            }
        }
        discard();
    }
    @Override protected void onBelowWorld() { if (level() instanceof ServerLevel world) lose(world); else super.onBelowWorld(); }

    @Override public InteractionResult interact(Player player, InteractionHand hand) {
        if (level().isClientSide) return InteractionResult.SUCCESS;
        if (isRemoved()) return InteractionResult.PASS;
        if (cargo != null) {
            ItemStack item = CargoPodItem.create(cargo.tag(), cargo.cargoKey(), cargo.destination());
            if (!player.getInventory().add(item)) player.drop(item, false);
        }
        discard();
        return InteractionResult.CONSUME;
    }
    @Override public boolean isPickable() { return true; }
    @Override public boolean hurt(DamageSource source, float amount) { return false; }
    @Override public void checkDespawn() {}
    @Override public boolean shouldBeSaved() { return !isRemoved(); }
    @Override public boolean shouldRenderAtSqrDistance(double distance) { return distance < 256 * 256; }

    @Override protected void defineSynchedData(SynchedEntityData.Builder builder) { builder.define(OPEN, false); }
    @Override protected void addAdditionalSaveData(CompoundTag tag) {
        if (cargo != null) tag.put("Cargo", cargo.save());
        tag.putInt("Age", age); tag.putBoolean("Landed", landed);
    }
    @Override protected void readAdditionalSaveData(CompoundTag tag) {
        cargo = PodCargo.load(tag.getCompound("Cargo")).orElse(null);
        age = tag.getInt("Age"); landed = tag.getBoolean("Landed");
        entityData.set(OPEN, age >= PodDrift.TUMBLE);
    }
}
