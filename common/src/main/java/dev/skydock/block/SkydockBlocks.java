package dev.skydock.block;

import dev.architectury.registry.registries.*;
import dev.architectury.registry.menu.MenuRegistry;
import dev.architectury.registry.CreativeTabRegistry;
import dev.skydock.Skydock;
import dev.skydock.data.DockTier;
import dev.skydock.menu.DockMenu;
import dev.skydock.menu.DeviceMenu;
import dev.skydock.menu.EngineMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.TagKey;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import java.util.*;
import java.util.function.Supplier;

public final class SkydockBlocks {
    private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(Skydock.ID, Registries.BLOCK);
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Skydock.ID, Registries.ITEM);
    private static final DeferredRegister<BlockEntityType<?>> ENTITIES = DeferredRegister.create(Skydock.ID, Registries.BLOCK_ENTITY_TYPE);
    private static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Skydock.ID, Registries.MENU);
    private static final DeferredRegister<net.minecraft.world.entity.EntityType<?>> ENTITY_TYPES = DeferredRegister.create(Skydock.ID, Registries.ENTITY_TYPE);
    private static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Skydock.ID, Registries.CREATIVE_MODE_TAB);
    public static final TagKey<Block> NOT_SHIP = TagKey.create(Registries.BLOCK, Skydock.id("not_ship"));
    public static final TagKey<Block> FORBIDDEN = TagKey.create(Registries.BLOCK, Skydock.id("forbidden"));
    public static final TagKey<Block> ENGINES = TagKey.create(Registries.BLOCK, Skydock.id("engines"));
    public static final TagKey<Block> LIFT_CELLS = TagKey.create(Registries.BLOCK, Skydock.id("lift_cells"));
    public static final TagKey<Block> DECORATIONS = TagKey.create(Registries.BLOCK, Skydock.id("decorations"));
    public static final Map<DockTier, RegistrySupplier<DockControllerBlock>> DOCKS = new EnumMap<>(DockTier.class);
    public static final List<RegistrySupplier<Item>> ALL_ITEMS = new ArrayList<>();
    static { for (DockTier tier : DockTier.values()) DOCKS.put(tier, register(tier.key() + "_dock_controller", () -> new DockControllerBlock(tier))); }
    public static final RegistrySupplier<DeviceBlock> HELM = register("helm", () -> new DeviceBlock(DeviceBlock.Kind.HELM, props().strength(3)));
    public static final RegistrySupplier<LiftCellBlock> LIFT_CELL = register("lift_cell", () -> new LiftCellBlock(BlockBehaviour.Properties.of().strength(1).sound(SoundType.WOOL).noOcclusion()));
    public static final RegistrySupplier<EngineBlock> ENGINE = register("engine", () -> new EngineBlock(EngineTier.BRASS));
    public static final RegistrySupplier<EngineBlock> ENGINE_REINFORCED = register("engine_reinforced", () -> new EngineBlock(EngineTier.COMPOUND));
    public static final RegistrySupplier<EngineBlock> ENGINE_TURBINE = register("engine_turbine", () -> new EngineBlock(EngineTier.TURBINE));
    public static final RegistrySupplier<EngineBlock> ENGINE_AETHER = register("engine_aether", () -> new EngineBlock(EngineTier.AETHER));
    public static final RegistrySupplier<DeviceBlock> BALLAST = register("ballast", () -> new DeviceBlock(DeviceBlock.Kind.BALLAST, props().strength(5)));
    public static final RegistrySupplier<DeviceBlock> CLAMP = register("mooring_clamp", () -> new DeviceBlock(DeviceBlock.Kind.CLAMP, props()));
    public static final RegistrySupplier<DeviceBlock> SEAT = register("seat", () -> new DeviceBlock(DeviceBlock.Kind.SEAT, props().strength(2)));
    public static final RegistrySupplier<DecorationBlock> BRASS_LANTERN = register("brass_lantern", () -> new DecorationBlock(props().strength(2).lightLevel(state -> 12).noOcclusion()));
    public static final RegistrySupplier<DirectionalDecorationBlock> CANVAS_AWNING = register("canvas_awning", () -> new DirectionalDecorationBlock(DirectionalDecorationBlock.Kind.AWNING, BlockBehaviour.Properties.of().strength(1).sound(SoundType.WOOL).noOcclusion()));
    public static final RegistrySupplier<FenceBlock> TIMBER_RAILING = register("timber_railing", () -> new FenceBlock(BlockBehaviour.Properties.of().strength(2).sound(SoundType.WOOD).noOcclusion()));
    public static final RegistrySupplier<DirectionalDecorationBlock> SIGNAL_FLAG = register("signal_flag", () -> new DirectionalDecorationBlock(DirectionalDecorationBlock.Kind.FLAG, BlockBehaviour.Properties.of().strength(1).sound(SoundType.WOOL).noOcclusion()));
    public static final RegistrySupplier<BlockEntityType<DockBlockEntity>> DOCK_ENTITY = ENTITIES.register("dock_controller", () ->
            BlockEntityType.Builder.of(DockBlockEntity::new, DOCKS.values().stream().map(Supplier::get).toArray(Block[]::new)).build(null));
    public static final RegistrySupplier<BlockEntityType<EngineBlockEntity>> ENGINE_ENTITY = ENTITIES.register("engine", () ->
            BlockEntityType.Builder.of(EngineBlockEntity::new, ENGINE.get(), ENGINE_REINFORCED.get(), ENGINE_TURBINE.get(), ENGINE_AETHER.get()).build(null));
    public static final RegistrySupplier<MenuType<DockMenu>> DOCK_MENU = MENUS.register("dock", () -> MenuRegistry.ofExtended(DockMenu::new));
    public static final RegistrySupplier<MenuType<DeviceMenu>> DEVICE_MENU = MENUS.register("device", () -> MenuRegistry.ofExtended(DeviceMenu::new));
    public static final RegistrySupplier<MenuType<EngineMenu>> ENGINE_MENU = MENUS.register("engine", () -> MenuRegistry.of(EngineMenu::new));
    public static final RegistrySupplier<Item> SHIPWRIGHT_HAMMER = ITEMS.register("shipwright_hammer", () -> new dev.skydock.item.ShipwrightHammerItem(new Item.Properties().durability(250).stacksTo(1)));
    public static final RegistrySupplier<Item> WARP_ANCHOR = ITEMS.register("warp_anchor", () -> new dev.skydock.item.WarpAnchorItem(new Item.Properties().durability(64)));
    public static final RegistrySupplier<dev.skydock.cargo.CargoPodBlock> CARGO_POD = BLOCKS.register("cargo_pod", () -> new dev.skydock.cargo.CargoPodBlock(BlockBehaviour.Properties.of().strength(3).sound(SoundType.METAL)));
    public static final RegistrySupplier<Item> CARGO_POD_ITEM = ITEMS.register("cargo_pod", () -> new dev.skydock.cargo.CargoPodItem(CARGO_POD.get(), new Item.Properties().stacksTo(1)));
    public static final RegistrySupplier<BlockEntityType<dev.skydock.cargo.CargoPodBlockEntity>> CARGO_POD_ENTITY = ENTITIES.register("cargo_pod", () ->
            BlockEntityType.Builder.of(dev.skydock.cargo.CargoPodBlockEntity::new, CARGO_POD.get()).build(null));
    public static final RegistrySupplier<net.minecraft.world.entity.EntityType<dev.skydock.cargo.PodEntity>> POD = ENTITY_TYPES.register("pod", () ->
            net.minecraft.world.entity.EntityType.Builder.<dev.skydock.cargo.PodEntity>of(dev.skydock.cargo.PodEntity::new, net.minecraft.world.entity.MobCategory.MISC)
                    .sized(.9f, .9f).clientTrackingRange(16).updateInterval(2).build("pod"));
    public static final RegistrySupplier<CreativeModeTab> TAB = TABS.register("skydock", () -> CreativeTabRegistry.create(
            Component.translatable("itemGroup.skydock"), () -> new ItemStack(HELM.get())));
    public static BlockBehaviour.Properties props() { return BlockBehaviour.Properties.of().strength(4).sound(SoundType.METAL).noOcclusion(); }
    private static <T extends Block> RegistrySupplier<T> register(String id, Supplier<T> factory) {
        RegistrySupplier<T> block = BLOCKS.register(id, factory);
        ALL_ITEMS.add(ITEMS.register(id, () -> new BlockItem(block.get(), new Item.Properties())));
        return block;
    }
    public static void init() {
        BLOCKS.register(); ITEMS.register(); ENTITIES.register(); ENTITY_TYPES.register(); MENUS.register(); TABS.register();
        CreativeTabRegistry.appendStack(TAB, ALL_ITEMS.stream().map(i -> (Supplier<ItemStack>) () -> new ItemStack(i.get())));
        CreativeTabRegistry.appendStack(TAB, () -> new ItemStack(SHIPWRIGHT_HAMMER.get()));
        CreativeTabRegistry.appendStack(TAB, () -> new ItemStack(WARP_ANCHOR.get()));
    }
}
