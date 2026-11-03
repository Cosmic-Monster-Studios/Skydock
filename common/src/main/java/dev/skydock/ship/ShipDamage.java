package dev.skydock.ship;

import dev.skydock.Skydock;
import dev.skydock.block.SkydockBlocks;
import dev.skydock.data.CollisionRules;
import dev.skydock.data.MassTable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/**
 * Collision damage. A ship stopped by an impact above the safe speed loses hull blocks where it struck, and a
 * ship moving above that speed hurts creatures in its path. Terrain is never broken, and crew aboard are never
 * struck by their own ship. Tuning comes from the mass rules' {@code collision} object.
 */
public final class ShipDamage {
    public static final GameRules.Key<GameRules.BooleanValue> RULE =
            GameRules.register("skydockCollisionDamage", GameRules.Category.MISC, GameRules.BooleanValue.create(true));
    public static final ResourceKey<DamageType> STRUCK = ResourceKey.create(Registries.DAMAGE_TYPE, Skydock.id("ship_collision"));
    /** Two ships stopping into each other in one tick must not be damaged twice. */
    private static final Map<Set<UUID>, Long> RECENT_PAIRS = new HashMap<>();
    private ShipDamage() {}

    /** Loads the class during mod setup so the gamerule exists before any world does. */
    public static void init() {}
    static void clear() { RECENT_PAIRS.clear(); }

    /** Hull blocks an impact may break: grows with speed above the safe limit and, more gently, with mass. */
    static int budget(CollisionRules rules, double excessSpeed, double mass) {
        if (excessSpeed <= 0 || rules.maxBlocks() == 0) return 0;
        return Math.min(rules.maxBlocks(), 1 + (int) Math.floor(excessSpeed * rules.blocksPerSpeed() * Math.sqrt(Math.max(1, mass / 10000))));
    }
    /** Harder blocks need a faster impact; unbreakable blocks (negative hardness) never break. */
    static boolean breaks(CollisionRules rules, double excessSpeed, float hardness) {
        return hardness >= 0 && excessSpeed > 0 && excessSpeed >= hardness * rules.speedPerHardness();
    }

    /** {@code pose} is the pose the ship could not move into; {@code velocity} and {@code turn} are what it carried into the hit. */
    static void impact(MinecraftServer server, ServerLevel world, Ship ship, ShipPose pose, Vec3 velocity, double turn, List<Ship> neighbors) {
        if (!world.getGameRules().getBoolean(RULE)) return;
        CollisionRules rules = MassTable.collision();
        AABB hull = ship.hullBounds();
        double radius = Math.hypot(Math.max(Math.abs(hull.minX), Math.abs(hull.maxX)), Math.max(Math.abs(hull.minZ), Math.abs(hull.maxZ)));
        double speed = (velocity.length() + Math.abs(Math.toRadians(turn)) * radius) * 20;
        if (speed <= rules.minSpeed()) return;
        ShipPose.Transform transform = pose.transform();
        List<BlockPos> contacts = new ArrayList<>();
        Set<Ship> struck = new LinkedHashSet<>();
        for (Ship.Surface surface : ship.surfaceCollisionBoxes()) {
            AABB box = transform.toWorld(surface.box()).deflate(1e-5);
            boolean hit = world.getBlockCollisions(null, box).iterator().hasNext();
            for (Ship other : neighbors) if (other.hullWorldBounds().intersects(box) && !ShipCollision.shapes(other, other.pose, box).isEmpty()) {
                hit = true;
                struck.add(other);
            }
            if (hit && !contacts.contains(surface.cell())) contacts.add(surface.cell());
        }
        if (contacts.isEmpty()) return;
        long now = world.getGameTime();
        struck.removeIf(other -> {
            Long last = RECENT_PAIRS.put(Set.of(ship.id, other.id), now);
            return last != null && now - last < 10;
        });
        RECENT_PAIRS.values().removeIf(tick -> now - tick > 200);
        Vec3 center = pose.toWorld(hull.getCenter());
        world.playSound(null, center.x, center.y, center.z, SoundEvents.ZOMBIE_BREAK_WOODEN_DOOR, SoundSource.BLOCKS,
                (float) Math.min(2, .4 + (speed - rules.minSpeed()) * .15), .7f + world.random.nextFloat() * .2f);
        int broken = damageHull(server, world, ship, pose, contacts, speed - rules.minSpeed(), velocity, rules);
        if (rules.damageOtherShips()) for (Ship other : struck) {
            double relative = (velocity.subtract(other.velocity).length() + Math.abs(Math.toRadians(turn)) * radius) * 20;
            damageHull(server, world, other, other.pose, cellsTouching(other, ship, pose), relative - rules.minSpeed(), velocity.reverse(), rules);
        }
        ServerPlayer pilot = ship.pilot == null ? null : server.getPlayerList().getPlayer(ship.pilot);
        if (pilot != null && broken > 0)
            pilot.displayClientMessage(broken == 1 ? Component.translatable("message.skydock.hull_breached_one") : Component.translatable("message.skydock.hull_breached", broken), true);
    }

    /** Cells of {@code target} (at its current pose) that the striking ship's collision boxes overlap at {@code pose}. */
    private static List<BlockPos> cellsTouching(Ship target, Ship striker, ShipPose pose) {
        ShipPose.Transform transform = target.transform();
        List<BlockPos> cells = new ArrayList<>();
        for (Ship.Surface surface : target.surfaceCollisionBoxes()) {
            AABB box = transform.toWorld(surface.box()).deflate(1e-5);
            if (!cells.contains(surface.cell()) && !ShipCollision.shapes(striker, pose, box).isEmpty()) cells.add(surface.cell());
        }
        return cells;
    }

    private static int damageHull(MinecraftServer server, ServerLevel world, Ship ship, ShipPose pose, List<BlockPos> contacts,
                                  double excessSpeed, Vec3 direction, CollisionRules rules) {
        int budget = budget(rules, excessSpeed, ship.mass);
        ServerLevel yard = server.getLevel(ShipManager.SHIPYARD);
        if (budget == 0 || yard == null || contacts.isEmpty()) return 0;
        // The cells furthest along the line of travel took the blow first.
        Vec3 local = ShipPose.rotate(direction, -pose.yaw());
        List<BlockPos> order = new ArrayList<>(contacts);
        order.sort(Comparator.<BlockPos>comparingDouble(cell -> -(cell.getX() * local.x + cell.getY() * local.y + cell.getZ() * local.z))
                .thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
        int broken = 0;
        for (BlockPos cell : order) {
            if (broken >= budget) break;
            BlockState state = ship.state(cell);
            // The helm stays, so a damaged ship can always be flown home.
            if (state.isAir() || state.is(SkydockBlocks.HELM.get())) continue;
            BlockPos at = ship.yard.offset(cell);
            if (!breaks(rules, excessSpeed, state.getDestroySpeed(yard, at))) continue;
            shatter(world, yard, ship, pose, cell, state);
            broken++;
        }
        if (broken > 0) ShipManager.refresh(server, ship);
        return broken;
    }

    /**
     * Breaks one hull block the vanilla way in the shipyard, so loot, container contents and popped attachments
     * behave as usual, then carries the dropped items out to where the block was in the world.
     */
    private static void shatter(ServerLevel world, ServerLevel yard, Ship ship, ShipPose pose, BlockPos cell, BlockState state) {
        BlockPos at = ship.yard.offset(cell);
        Vec3 center = pose.toWorld(Vec3.atCenterOf(cell).subtract(ship.center()));
        yard.destroyBlock(at, true);
        for (ItemEntity item : yard.getEntitiesOfClass(ItemEntity.class, new AABB(at).inflate(1.5))) {
            ItemEntity moved = new ItemEntity(world, center.x, center.y, center.z, item.getItem().copy());
            moved.setDefaultPickUpDelay();
            world.addFreshEntity(moved);
            item.discard();
        }
        world.levelEvent(LevelEvent.PARTICLES_DESTROY_BLOCK, BlockPos.containing(center), Block.getId(state));
    }

    /** Creatures caught by a moving hull are hurt in proportion to its speed and thrown clear. */
    static void ram(ServerLevel world, Ship ship) {
        CollisionRules rules = MassTable.collision();
        double excess = ship.velocity.length() * 20 - rules.minSpeed();
        if (excess <= 0 || rules.entityDamagePerSpeed() <= 0 || !world.getGameRules().getBoolean(RULE)) return;
        Holder<DamageType> type = world.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolder(STRUCK).orElse(null);
        if (type == null) return;
        ServerPlayer pilot = ship.pilot == null ? null : world.getServer().getPlayerList().getPlayer(ship.pilot);
        for (LivingEntity entity : world.getEntitiesOfClass(LivingEntity.class, ship.hullWorldBounds(), e -> e.isAlive() && !e.isSpectator())) {
            // Crew standing, seated or at the helm move with the hull and are never struck by it.
            if (((ShipAttachmentAccess) entity).skydock$attachment().is(ship.id) || entity.getUUID().equals(ship.pilot) || ship.seated.containsKey(entity.getUUID())) continue;
            if (ShipCollision.shapes(ship, ship.pose, entity.getBoundingBox().inflate(.05)).isEmpty()) continue;
            if (entity.hurt(new DamageSource(type, pilot), (float) (excess * rules.entityDamagePerSpeed()))) {
                Vec3 push = ship.velocity.scale(1.5).add(0, .35, 0);
                entity.push(push.x, push.y, push.z);
                entity.hurtMarked = true;
            }
        }
    }
}
