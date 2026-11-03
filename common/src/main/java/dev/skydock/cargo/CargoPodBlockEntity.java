package dev.skydock.cargo;

import dev.skydock.block.SkydockBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/** Holds a pod's cargo. Placing the item copies its custom data in; the block's loot table copies it back out, so breaking a pod in a berth returns the same item. */
public final class CargoPodBlockEntity extends BlockEntity {
    private PodCargo cargo;
    public CargoPodBlockEntity(BlockPos pos, BlockState state) { this(SkydockBlocks.CARGO_POD_ENTITY.get(), pos, state); }
    CargoPodBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) { super(type, pos, state); }
    public PodCargo cargo() { return cargo; }
    public void setCargo(PodCargo cargo) { this.cargo = cargo; setChanged(); }
    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) { super.saveAdditional(tag, registries); if (cargo != null) tag.merge(cargo.save()); }
    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) { super.loadAdditional(tag, registries); cargo = PodCargo.load(tag).orElse(null); }
    @Override protected void applyImplicitComponents(DataComponentInput input) {
        super.applyImplicitComponents(input);
        CustomData data = input.get(DataComponents.CUSTOM_DATA);
        if (data != null) cargo = PodCargo.load(data.copyTag()).orElse(null);
    }
    @Override protected void collectImplicitComponents(DataComponentMap.Builder builder) {
        super.collectImplicitComponents(builder);
        if (cargo != null) builder.set(DataComponents.CUSTOM_DATA, CustomData.of(cargo.save()));
    }
}
