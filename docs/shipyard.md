# Shipyard guide

Skydock's NeoForge 1.21.1 build lets you create an airship from a dock controller or assemble one of the built-in patterns. Fabric support is planned for a future release and is not available yet.

## Start a custom ship

1. Place a dock controller facing the way the bow should point; the controller faces the direction you look, and its outline extends behind it, toward you. The outline must be clear first: no solid blocks or fluids, and no overlap with another berth or a launched ship. Plants are cleared for you. The preview outline turns red where a controller cannot go. Build within the outline. Keep the hull separate from terrain. Scaffolding may support construction because it is excluded from the ship.
2. Connect every included block face-to-face to one helm. A ship needs exactly one helm.
3. Add lift cells and engines. A lift cell contributes lift and structural capacity; a connected cluster can grow in any direction without breaking its visual form.
4. Open the controller's **Manual** tab, choose **Inspect ship**, correct any reported issue, then choose **Launch ship**.

The four dock tiers are Scout, Brig, Cruiser, and Dreadnought. Their envelopes and block caps are shown in the main [README](../README.md#build-your-first-airship).

## Engines and fuel

Every engine has five fuel slots and accepts furnace fuel. The progression is:

| Tier | Engine | What improves |
| --- | --- | --- |
| 1 | Brass Engine | Baseline thrust, fuel use, and speed |
| 2 | Compound Engine | More thrust and efficiency |
| 3 | Turbine Engine | Higher thrust, efficiency, and top speed |
| 4 | Aether Drive | Highest thrust, efficiency, and top speed |

Engines burn fuel in proportion to their load, the larger of the throttle and 35 % of the steering input. Full throttle burns at the full rate; 10 % throttle makes the same fuel last ten times longer.

Use an engine to open its five-slot fuel and instruments screen. Sneaking does not open a separate engine screen.

## Patterns and resources

Each dock offers three patterns appropriate to its tier: a cutter, twin-hull ship, and hauler. Across the four dock tiers, the shipyard offers twelve built-in patterns. Datapacks can add patterns or replace built-ins; the dock interface shows three at a time, and the ◀ ▶ buttons page through the rest.

Choose a pattern in the dock interface. Put its resources in one or more vanilla chests adjacent to the controller; touching double chests also work. The dock removes the full cost only when it can supply every requirement. The same full material cost applies in Creative mode.

The decoration toggle controls the four cosmetic blocks: brass lantern, canvas awning, timber railing, and signal flag. When included, they add neither weight nor structural lift demand and they provide no lift. They still use a position in the dock's filled-block cap.

The pattern screen has a 3D preview: drag to orbit it, use the mouse wheel to zoom, and press **R** to reset the view. Its materials list shows both the available and required counts; scroll it to inspect the complete cost. Changing the decoration toggle refreshes the preview and material list.

Before starting, clear the full pattern footprint. Assembly places the ship in stages with particles marking its progress. Cancelling an assembly returns materials owned by its build journal. Once it completes, use the **Launch ship** footer to launch the finished craft. If a datapack reload removes the pattern of an assembly in progress, the assembly is cancelled and its materials are returned.

Patterns build correctly in a dock facing any direction: they are stored facing north and turned with the dock.

### Save your own design

An operator can save a hull built in a dock as a pattern with `/skydock blueprint export <controller x y z> <name>`. The hull must pass **Inspect ship**. The name uses 1 to 32 lowercase letters, digits and underscores. The pattern is written to the world's `skydock_saved` datapack; the first time, run `/datapack enable "file/skydock_saved"`, then `/reload`. It then appears with the other patterns of the dock's tier and builds in any dock of that tier. Container contents and engine fuel are not saved. The file format is described in the [README](../README.md#ship-patterns).

## Devices and controls

Use the helm, mooring clamp, and crew seat normally for their quick action. Sneak and use any of them to open its device screen. Sneak and use a lift cell to view its connected cluster and total lift.

At the helm, **W/S** applies forward or reverse thrust, **A/D** turns, and **Space/Shift** climbs or descends. Press **C** for cruise and **R** to release the helm while remaining in first-person. During cruise, **W/S** changes speed and **A/D** steers; press **C** anywhere aboard to stop cruising. **H** toggles altitude hold (Space/Shift then move the target height) and **J** toggles heading lock (the heading snaps to 15° and A/D move it); both persist after you release the helm and switch off on a collision. The HUD shows your compass heading and the distance, bearing, height difference and yaw error to your berth. A crew seat can be used to sit and **R** gets up.

The mooring clamp can secure a ship only within five blocks of its reserved berth. Securing it stops movement and cruise.

## Repair

Use a **Shipwright's Hammer** (three iron ingots and two sticks, set diagonally: ingots at top middle, top right and middle right; sticks at middle and bottom left) aboard a ship you may operate, within 8 blocks of the hull. Each use restores up to 16 missing blocks, nearest first, to the hull recorded at launch. Every block costs one of its item, taken from containers aboard and then from your inventory, and one point of the hammer's 250 durability. Blocks without an item form, places already filled, and places a creature stands in are skipped. Restored containers start empty.

## Summoning

Bind a **Warp Anchor** (ender pearl in a ring of four copper ingots, echo shard below) by using it on your dock controller or on a hull block of your launched ship (devices such as the helm still work with it in hand). Activate it anywhere in that dimension to warp the ship launched from that dock in beside you, stopped and moored. It needs open space near you, will not move a ship with anyone aboard, has 64 uses and a one-minute recharge. The ship keeps its berth and can redock there as usual.

## Docking

To redock, return within four blocks of the destination berth center, align within ten degrees of that dock's facing, and slow below 1.6 blocks per second. The HUD's berth line reads "Ready to redock" when all three hold. Stand near the controller and select **Redock ship**. The berth must be clear and at least as large as the ship's current tier.

When a ship launches, it starts at its dock's heading and the controller reserves the berth while the hull remains visible and usable. Redocking returns the real blocks and their inventories, allowing repair and expansion again.

## Cargo

Cargo terminals, pods, crates and the reputation shop are described in the [Cargo contracts](../README.md#cargo-contracts) section of the main README. Pods load onto a moored ship like any hull block, count toward the tier's block cap and weigh 40 kg each. The three freighter patterns (Brig, Cruiser and Dreadnought) are shop-only and appear on a dock screen once its user has bought them.

## Model provenance

The model source archive is [assets-source/blockbench](../assets-source/blockbench). It holds 18 regular Blockbench models, including the connected railing’s post, side, and inventory parts, and 64 six-axis lift-cell variants. The v2 runtime palette contains twelve painted material textures. Its `.mjs` files are native Blockbench MCP input specifications; Minecraft does not generate these models procedurally at runtime. The [visual reference prompts](../assets-source/design-references/prompts-v2.md) document the art direction used to author them.
