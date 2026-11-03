package dev.skydock.menu;

import dev.architectury.registry.fuel.FuelRegistry;
import dev.skydock.block.*;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;

public final class EngineMenu extends AbstractContainerMenu {
    public final Container engine;
    private final ContainerData data;

    public EngineMenu(int id, Inventory inventory) { this(id, inventory, new SimpleContainer(5), new SimpleContainerData(3)); }
    public EngineMenu(int id, Inventory inventory, EngineBlockEntity engine) {
        this(id, inventory, engine, new ContainerData() {
            @Override public int get(int index) { return switch (index) { case 0 -> engine.burnTicks() & 0xffff; case 1 -> engine.burnTicks() >>> 16; default -> engine.tier().ordinal(); }; }
            @Override public void set(int index, int value) {}
            @Override public int getCount() { return 3; }
        });
    }
    private EngineMenu(int id, Inventory inventory, Container engine, ContainerData data) {
        super(SkydockBlocks.ENGINE_MENU.get(), id); this.engine = engine; this.data = data;
        checkContainerSize(engine, 5); checkContainerDataCount(data, 3);
        for (int slot = 0; slot < 5; slot++) addSlot(new Slot(engine, slot, 96 + slot * 18, 68) {
            @Override public boolean mayPlace(ItemStack stack) { return FuelRegistry.get(stack) > 0; }
        });
        for (int row = 0; row < 3; row++) for (int column = 0; column < 9; column++)
            addSlot(new Slot(inventory, column + row * 9 + 9, 60 + column * 18, 105 + row * 18));
        for (int column = 0; column < 9; column++) addSlot(new Slot(inventory, column, 60 + column * 18, 163));
        addDataSlots(data); engine.startOpen(inventory.player);
    }
    public int burnTicks() { return ((data.get(1) & 0xffff) << 16) | (data.get(0) & 0xffff); }
    public EngineTier tier() { return EngineTier.values()[Math.clamp(data.get(2), 0, EngineTier.values().length - 1)]; }
    @Override public boolean stillValid(Player player) { return engine.stillValid(player); }
    @Override public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index); if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack source = slot.getItem(), copy = source.copy();
        if (index < 5) { if (!moveItemStackTo(source, 5, slots.size(), true)) return ItemStack.EMPTY; }
        else if (FuelRegistry.get(source) > 0) { if (!moveItemStackTo(source, 0, 5, false)) return ItemStack.EMPTY; }
        else return ItemStack.EMPTY;
        if (source.isEmpty()) slot.set(ItemStack.EMPTY); else slot.setChanged();
        return copy;
    }
    @Override public void removed(Player player) { super.removed(player); engine.stopOpen(player); }
}
