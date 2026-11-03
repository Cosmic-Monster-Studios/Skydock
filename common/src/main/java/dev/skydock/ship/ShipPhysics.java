package dev.skydock.ship;

import dev.skydock.block.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.chunk.LevelChunk;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class ShipPhysics {
    public static void step(MinecraftServer server, ServerLevel world, Ship ship) {
        if (ship.moored) { ship.velocity = Vec3.ZERO; ship.yawVelocity = 0; ship.cruise = false; ship.appliedThrottle = ship.appliedClimb = 0; return; }
        Vec3 forward = ShipPose.rotate(new Vec3(0, 0, -1), ship.pose.yaw());
        double inertia = FlightDynamics.inertia(ship.mass);
        EngineTier poweredTier = null;
        double poweredThrust = 0;
        if (ship.cruise || Math.abs(ship.throttle) > .001 || Math.abs(ship.appliedThrottle) > .001 || Math.abs(ship.steering) > .01) {
            ServerLevel yard = server.getLevel(ShipManager.SHIPYARD);
            double load = FlightDynamics.engineLoad(ship.appliedThrottle, ship.steering);
            if (ship.cruise) load = Math.max(load, .05);
            for (BlockPos p : ship.enginePositions()) if (yard.getBlockEntity(ship.yard.offset(p)) instanceof EngineBlockEntity engine && engine.burn(load)) {
                EngineTier tier = engine.tier();
                poweredThrust += tier.thrustMultiplier;
                if (poweredTier == null || tier.ordinal() > poweredTier.ordinal()) poweredTier = tier;
            }
        }
        boolean powered = poweredTier != null;
        if (ship.cruise && !powered) {
            ship.cruise = false;
            // The pilot may be a teammate rather than the owner; tell whoever is flying.
            var pilot = server.getPlayerList().getPlayer(ship.pilot != null ? ship.pilot : ship.owner);
            if (pilot != null) ShipManager.tell(pilot, "message.skydock.cruise_no_fuel");
        }
        double speedLimit = powered ? poweredTier.maxSpeed : FlightDynamics.MAX_SPEED;
        if (ship.cruise) ship.cruiseSpeed = Math.clamp(ship.cruiseSpeed + ship.throttle * .006, -speedLimit, speedLimit);
        double requested = ship.cruise ? FlightDynamics.cruiseThrottle(ship.velocity.dot(forward), ship.cruiseSpeed) * inertia : ship.throttle;
        ship.appliedThrottle = FlightDynamics.approach(ship.appliedThrottle, Math.clamp(requested, -1, 1), .1);
        ship.appliedClimb = FlightDynamics.approach(ship.appliedClimb, ship.climb, .12);
        double thrust = powered ? ship.appliedThrottle * FlightDynamics.ACCELERATION * poweredThrust / inertia : 0;
        Vec3 lateral = new Vec3(ship.velocity.x, 0, ship.velocity.z).subtract(forward.scale(ship.velocity.dot(forward)));
        Vec3 velocity = ship.velocity.multiply(.97, .9, .97).subtract(lateral.scale(.12)).add(forward.scale(thrust));
        double buoyancy = ship.mass <= 0 ? 0 : (ship.lift - ship.mass) / ship.mass;
        double vertical = buoyancy < 0 ? Math.max(-.18, buoyancy * .12) : ship.appliedClimb * Math.min(.16, .035 + buoyancy * .025);
        velocity = new Vec3(velocity.x, velocity.y * .75 + vertical * .25, velocity.z);
        if (velocity.horizontalDistance() > speedLimit) {
            double scale = speedLimit / velocity.horizontalDistance(); velocity = new Vec3(velocity.x * scale, velocity.y, velocity.z * scale);
        }
        double hullLength = Math.max(ship.hullBounds().getXsize(), ship.hullBounds().getZsize());
        double desiredTurn = powered ? ship.steering * FlightDynamics.turnRate(ship.mass, hullLength) : 0;
        ship.yawVelocity = FlightDynamics.approach(ship.yawVelocity, desiredTurn, desiredTurn == 0 ? .28 : .18);
        double turn = ship.yawVelocity;
        // A hovering hull cannot move into terrain, so skip the full-envelope collision scan.
        if (velocity.lengthSqr() < 1e-8 && Math.abs(turn) < 1e-6) { ship.velocity = Vec3.ZERO; ship.yawVelocity = 0; ship.blocked = false; return; }
        AABB hull = ship.hullBounds();
        double radius = Math.hypot(Math.max(Math.abs(hull.minX), Math.abs(hull.maxX)), Math.max(Math.abs(hull.minZ), Math.abs(hull.maxZ)));
        double arc = Math.abs(Math.toRadians(turn)) * radius;
        // One box covers the whole tick: both end poses, widened by the arc any hull point sweeps while turning.
        ShipPose end = new ShipPose(ship.pose.x() + velocity.x, ship.pose.y() + velocity.y, ship.pose.z() + velocity.z, ship.pose.yaw() + turn);
        AABB swept = ship.hullWorldBounds().minmax(end.transform().toWorld(hull)).inflate(arc + .05);
        Terrain terrain = terrain(world, swept);
        if (terrain == Terrain.UNLOADED) {
            // Hold at the edge of loaded terrain instead of flying into chunks that could load around the hull.
            ship.velocity = Vec3.ZERO; ship.yawVelocity = 0; ship.blocked = false;
            ShipManager.forceTickets(server, ship);
            return;
        }
        List<Ship> neighbors = ShipManager.ships(world).stream().filter(other -> other != ship && other.hullWorldBounds().intersects(swept)).toList();
        boolean inside = swept.minY >= world.getMinBuildHeight() && swept.maxY < world.getMaxBuildHeight() && world.getWorldBorder().isWithinBounds(swept);
        boolean clear = terrain == Terrain.EMPTY && neighbors.isEmpty() && inside;
        int steps = clear ? 1 : Math.max(1, (int) Math.ceil((velocity.length() + arc) / .2));
        ship.blocked = false;
        for (int i = 0; i < steps; i++) {
            ShipPose next = new ShipPose(ship.pose.x() + velocity.x / steps, ship.pose.y() + velocity.y / steps,
                    ship.pose.z() + velocity.z / steps, ship.pose.yaw() + turn / steps);
            if (!clear && collides(world, ship, next, terrain == Terrain.SOLID, neighbors, inside)) {
                Vec3 impact = velocity;
                ship.blocked = true; velocity = Vec3.ZERO; ship.yawVelocity = 0; ship.cruise = false; ship.appliedThrottle = 0;
                ShipDamage.impact(server, world, ship, next, impact, turn, neighbors);
                break;
            }
            ship.pose = next;
        }
        ship.velocity = velocity;
    }
    private enum Terrain { EMPTY, SOLID, UNLOADED }
    /** Classifies the chunk sections under a box without touching individual blocks; open sky is the common case. */
    private static Terrain terrain(ServerLevel world, AABB box) {
        int minSection = Math.max(world.getMinSection(), SectionPos.blockToSectionCoord(Mth.floor(box.minY)));
        int maxSection = Math.min(world.getMaxSection() - 1, SectionPos.blockToSectionCoord(Mth.floor(box.maxY)));
        boolean solid = false;
        for (int x = SectionPos.blockToSectionCoord(Mth.floor(box.minX)); x <= SectionPos.blockToSectionCoord(Mth.floor(box.maxX)); x++)
            for (int z = SectionPos.blockToSectionCoord(Mth.floor(box.minZ)); z <= SectionPos.blockToSectionCoord(Mth.floor(box.maxZ)); z++) {
                LevelChunk chunk = world.getChunkSource().getChunkNow(x, z);
                if (chunk == null) return Terrain.UNLOADED;
                for (int y = minSection; y <= maxSection && !solid; y++)
                    if (!chunk.getSection(chunk.getSectionIndexFromSectionY(y)).hasOnlyAir()) solid = true;
            }
        return solid ? Terrain.SOLID : Terrain.EMPTY;
    }
    public static double installedMaxSpeed(Ship ship) {
        double result = FlightDynamics.MAX_SPEED;
        for (BlockPos p : ship.enginePositions()) result = Math.max(result, EngineTier.from(ship.state(p)).maxSpeed);
        return result;
    }
    private static boolean collides(ServerLevel world, Ship ship, ShipPose pose, boolean terrain, List<Ship> neighbors, boolean inside) {
        ShipPose.Transform transform = pose.transform();
        AABB bounds = transform.toWorld(ship.hullBounds());
        if (!inside && (bounds.minY < world.getMinBuildHeight() || bounds.maxY >= world.getMaxBuildHeight() || !world.getWorldBorder().isWithinBounds(bounds))) return true;
        // Terrain is sampled around each surface cell rather than across the whole hull box.
        if (terrain) for (Ship.Surface surface : ship.surfaceCollisionBoxes())
            if (world.getBlockCollisions(null, transform.toWorld(surface.box()).deflate(1e-5)).iterator().hasNext()) return true;
        for (Ship other : neighbors) if (other.hullWorldBounds().intersects(bounds)) {
            AABB overlap = other.hullWorldBounds().intersect(bounds);
            for (VoxelShape box : ShipCollision.shapes(other, other.pose, overlap))
                if (!ShipCollision.shapes(ship, pose, box.bounds().deflate(1e-5)).isEmpty()) return true;
        }
        return false;
    }
}
