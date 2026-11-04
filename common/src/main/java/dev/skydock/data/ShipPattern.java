package dev.skydock.data;

import dev.skydock.block.SkydockBlocks;
import dev.skydock.block.EngineTier;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** An immutable ship recipe in north-frame dock-local coordinates, built in or loaded from a datapack. {@code name} and {@code description} are translation keys or plain text;
 * a {@code shopOnly} pattern is hidden from players who have not unlocked it in the cargo shop. */
public record ShipPattern(ResourceLocation id, String name, String description, DockTier tier, List<Block> blocks, boolean shopOnly) {
    public ShipPattern(ResourceLocation id, String name, String description, DockTier tier, List<Block> blocks) { this(id, name, description, tier, blocks, false); }

    public ShipPattern {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(tier, "tier");
        Objects.requireNonNull(blocks, "blocks");
        if (name.isBlank()) throw new IllegalArgumentException("Pattern name cannot be blank");
        Set<BlockPos> occupied = new LinkedHashSet<>();
        List<Block> checked = new ArrayList<>(blocks.size());
        for (Block cell : blocks) {
            Objects.requireNonNull(cell, "pattern block");
            BlockPos pos = cell.pos().immutable();
            if (pos.getX() < 0 || pos.getX() >= tier.width || pos.getY() < 0 || pos.getY() >= tier.height
                    || pos.getZ() < 0 || pos.getZ() >= tier.length)
                throw new IllegalArgumentException("Pattern block outside " + tier.key() + " dock: " + pos);
            if (cell.state().isAir()) throw new IllegalArgumentException("Patterns cannot contain air cells: " + pos);
            if (Item.byBlock(cell.state().getBlock()) == Items.AIR)
                throw new IllegalArgumentException("Pattern block has no inventory item: " + cell.state().getBlock());
            if (!occupied.add(pos)) throw new IllegalArgumentException("Duplicate pattern cell: " + pos);
            checked.add(new Block(pos, cell.state(), cell.decoration()));
        }
        if (checked.isEmpty()) throw new IllegalArgumentException("Pattern cannot be empty");
        blocks = List.copyOf(checked);
    }

    public record Block(BlockPos pos, BlockState state, boolean decoration) {
        public Block {
            Objects.requireNonNull(pos, "pos");
            Objects.requireNonNull(state, "state");
            pos = pos.immutable();
        }
    }

    public record Stats(int blocks, int structuralBlocks, double mass, double lift, int cells, int engines,
                        double power, double maxSpeed, int width, int height, int length) {}

    public List<Block> included(boolean decorations) {
        if (decorations) return blocks;
        return blocks.stream().filter(cell -> !cell.decoration()).toList();
    }

    /** Cost and preview are deliberately derived from the same cell list. */
    public Map<Item, Integer> cost(boolean decorations) {
        Map<Item, Integer> cost = new LinkedHashMap<>();
        for (Block cell : included(decorations)) cost.merge(Item.byBlock(cell.state().getBlock()), 1, Integer::sum);
        return Collections.unmodifiableMap(cost);
    }

    public Stats stats(boolean decorations) {
        List<Block> included = included(decorations);
        double mass = 0;
        int structural = 0, cells = 0, engines = 0;
        double power = 0, maxSpeed = 0;
        for (Block cell : included) {
            boolean decorative = cell.decoration() || cell.state().is(SkydockBlocks.DECORATIONS);
            if (!decorative) {
                structural++;
                mass += MassTable.mass(cell.state());
                if (cell.state().is(SkydockBlocks.LIFT_CELLS)) cells++;
            }
            if (cell.state().is(SkydockBlocks.ENGINES)) {
                engines++;
                EngineTier tier = EngineTier.from(cell.state());
                power += tier.thrustMultiplier;
                maxSpeed = Math.max(maxSpeed, tier.maxSpeed);
            }
        }
        AABB bounds = bounds(decorations);
        return new Stats(included.size(), structural, mass, cells * MassTable.liftPerCell(), cells, engines,
                power, maxSpeed, (int) bounds.getXsize(), (int) bounds.getYsize(), (int) bounds.getZsize());
    }

    public AABB bounds(boolean decorations) {
        List<Block> cells = included(decorations);
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Block cell : cells) {
            BlockPos pos = cell.pos();
            minX = Math.min(minX, pos.getX()); minY = Math.min(minY, pos.getY()); minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX()); maxY = Math.max(maxY, pos.getY()); maxZ = Math.max(maxZ, pos.getZ());
        }
        return new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
    }
}
