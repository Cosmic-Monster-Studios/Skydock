# Skydock

Skydock is a from-scratch Minecraft **1.21.1** airship mod, built with **Architectury API** and **Java 21**. **NeoForge 21.1.248+** is the current target. Install the NeoForge build of **Architectury API 13.0.11+** alongside it. There is no Valkyrien Skies dependency.

This is an early alpha. Gameplay, persistence, networking, and client implementation are in `common`; the NeoForge entry point and world-render event adapter are in `neoforge`. NeoForge is the only supported platform today. A Fabric release is planned but has not been built or tested.

## Build and run

Set `JAVA_HOME` to a Java 21 JDK for the invocation, then run:

```text
./gradlew build
./gradlew :neoforge:runClient
./gradlew :neoforge:runServer
```

The installable mod is `neoforge/build/libs/skydock-neoforge-0.2.0-alpha.1.jar`. Do not install the `dev-shadow` or `sources` jars. Both client and server need Skydock and Architectury API. The development runs use `run/client` and `run/server`.

## Build your first airship

1. Craft a Scout Dock Controller or take one from the Skydock creative tab. Place it just in front of the future hull, facing the way the bow should point: the controller faces the direction you are looking, and the outline extends behind it, toward you, one block above the controller. Looking north gives a north-facing dock whose outline extends **south (+Z)**, as every dock did before 0.2. Preview it while holding a controller: the outline is green when the controller can go there and red when it cannot. The berth must be clear before the controller goes down: no solid blocks or fluids inside it, inside the build height and world border, loaded, and not overlapping another dock's berth or a launched ship. Grass, flowers and other plants do not count; they are broken, with drops, when the controller is placed. If placement is refused, chat says why and where the first block in the way is.
2. Build entirely inside the outline. Leave the hull separated from terrain; Minecraft scaffolding can support it and is excluded from assembly. Every counted block must connect face-to-face to exactly one helm.
3. Add lift cells. Each supplies 1,000 kg of lift and supports 128 structural blocks. Ordinary blocks weigh 10 kg by default. Adjacent lift cells merge visually on all six faces, so 2 × 2 × 2 envelopes and larger clusters remain intact as you expand them.
4. Add an engine and put furnace fuel in its five fuel slots. Engines progress from Brass Engine to Compound Engine, Turbine Engine, and Aether Drive; later tiers provide more thrust, better fuel efficiency, and a higher top speed.
5. Right-click the controller or a docked helm, open the **Manual** tab, choose **Inspect ship**, correct any errors, then choose **Launch ship**. If dock outlines overlap, use the intended controller. The controller stays behind and reserves the berth. The hull remains visible and walkable, but its real blocks now live in the hidden shipyard.
6. Use the helm on the launched hull. **W/S** thrust forward/reverse, **A/D** turn, and **Space/Shift** climb/descend. **C** engages cruise; if stopped, it starts at 3.6 blocks/second. **R** releases the helm so you can move about the ship while staying in first-person. Acceleration and steering ease in and out, and your view follows the deck through turns. Press **F5** at the helm or in a crew seat for a third-person ship view sized to the hull; scroll the mouse wheel or press **=**/**-** to zoom from over your shoulder out to the whole ship. Engines burn fuel in proportion to their load: full throttle burns at the full rate, 10 % throttle makes the same fuel last ten times longer, and turning in place costs 35 % of full burn. Light ships accelerate faster than heavy ones. Adequate lift permits unpowered hover; lost lift causes sinking.

   **H** toggles altitude hold: the ship holds its current height, and **Space/Shift** move the target at 3 blocks/second. **J** toggles heading lock: the heading snaps to the nearest 15°, **A/D** move it at 30°/second, and the ship steers itself to it. Both stay on after you release the helm, work with cruise, are saved with the ship, and switch off on a collision. The helm HUD shows a compass heading and a berth line with the distance, bearing, height difference and yaw error to your reserved berth, ending in "Ready to redock" when every redock condition holds.
7. Cruise holds horizontal speed and heading. **W/S** adjusts cruise speed at the helm, while **A/D** steers. Press **C** anywhere aboard to disengage and coast to a stop. Cruise disengages on collision, mooring, or fuel exhaustion; it does not plan routes or avoid obstacles. Use a crew seat to sit and **R** to stand. A mooring clamp can secure the ship within five blocks of its reserved berth.

   Crashes do damage. Hitting terrain or another ship faster than 3 blocks/second breaks hull blocks where the ship struck: faster and heavier ships break more, and harder blocks hold longer, so canvas lift cells give way before planks and planks before engines. Broken blocks drop their items, and container contents, where they were. The helm is never broken, docking speeds are always safe, and terrain is never damaged. A ship moving faster than 3 blocks/second also hurts creatures in its path; crew aboard it are safe. Turn it all off with `/gamerule skydockCollisionDamage false`.

   Repair a launched ship in the field with the **Shipwright's Hammer** (crafted from three iron ingots and two sticks set diagonally: ingots at top middle, top right and middle right, sticks at middle and bottom left; 250 durability). Use it aboard a ship you may operate, within 8 blocks of the hull: each use restores up to 16 missing blocks of the hull as it was launched, nearest to you first. Each block costs one of its item, taken from containers aboard first and then from your inventory, and one point of durability; Creative mode still pays materials. A block is skipped if it has no item form, if its place is taken, or if it would overlap a creature. Restored containers start empty. Ships launched before 0.2 remember the blocks they had when first loaded, so blocks lost earlier cannot be restored.

   Call a launched ship to you with the **Summoning Horn** (a goat horn with a gold ingot above and below and a copper ingot on each side; 64 uses). Bind it first by using it on your dock controller or on the hull of a ship launched from that dock; the tooltip shows the dock it is bound to, and the binding lasts through redocks and relaunches. Blow it anywhere in the same dimension and the ship launched from that dock appears beside you, stopped and moored, bow pointing the way you look and stern three blocks ahead. If that spot is blocked, it tries spots up to 24 blocks out and 16 blocks up; with no room it tells you to find open sky. The horn will not summon a ship while anyone is aboard, and it has a one-minute cooldown. The berth stays reserved, so the ship can still redock there.
8. To redock, return within four blocks of the destination berth center, align yaw within ten degrees of that dock's facing (heading lock makes this easy), and slow below 1.6 blocks/second. Stand near the controller and choose **Redock ship**. The berth must be clear and the same tier or larger. The real blocks and current inventories return; repair and expansion are available again.

| Tier | Interior | Filled-block cap |
| --- | --- | ---: |
| Scout | 32 × 20 × 32 | 2,048 |
| Brig | 48 × 28 × 48 | 6,144 |
| Cruiser | 64 × 36 × 64 | 12,288 |
| Dreadnought | 96 × 48 × 96 | 24,576 |

The controller is the center of the front edge. For a north-facing dock its interior starts at `controller + (-width/2, 1, 1)`; other facings turn the envelope about the controller. A launched ship starts at its dock's yaw (north 0°, east 90°, south 180°, west 270°). Hulls and patterns are stored facing north and turned with the dock, so any pattern builds in a dock facing any way. Block-entity NBT is not rotated, as with vanilla structures. Existing controllers from 0.1 read as north-facing. Larger docks center smaller ships inside their envelope. Upgrading the ship's tier happens on its next launch from the larger dock.

Controllers and ships are owned by the player who places/first claims the controller. Members of the Minecraft scoreboard team recorded at claim time share device and berth permissions. One player may drive each helm at a time.

## Shipyard and devices

The Skydock creative tab contains 17 custom blocks, the Shipwright's Hammer and the Summoning Horn. The blocks are four dock controllers, a helm, lift cell, four engine tiers, ballast, a mooring clamp, a crew seat, and four decorative blocks. The decorative blocks can be included or excluded with the dock's decoration toggle. Included decorations are cosmetic: they add no mass, consume no structural-lift allowance, and do not add lift. They still count toward the dock tier's overall filled-block cap.

Each dock tier offers three built-in construction patterns, for a catalog of twelve ships; datapacks can add more (see [Datapacks](#datapacks)), and the dock screen pages through them three at a time with the ◀ ▶ buttons. Select a pattern and decoration option in the dock interface, then assemble it with the required resources in one or more vanilla chests touching the controller. Touching double chests are supported. The full material cost is always required, including in Creative mode.

Normal use remains quick: use the helm, clamp, or seat normally to operate it. Sneak and use any of those devices to open its information screen; sneak and use a lift cell to inspect its connected cluster. Use an engine to open its five-slot fuel and instruments screen.

See [the shipyard guide](docs/shipyard.md) for the complete construction and device reference.

## Commands

These use the same validation and permissions as the controls and do not bypass launch rules:

```text
/skydock inspect <controller x y z>
/skydock launch <controller x y z>
/skydock redock <controller x y z>
/skydock summon <controller x y z>
/skydock use
/skydock target
/skydock release
/skydock cruise
/skydock control <thrust -1..1> <yaw -1..1> <climb -1..1>
/skydock status
/skydock blueprint export <controller x y z> <name>
```

`summon` calls the ship launched from that dock as the Summoning Horn does, without the item or its cooldown. `use` operates the ship block under your crosshair; `target` identifies it for diagnostics. Control commands expire after ten ticks without fresh input. The diagnostic `status` output includes ship IDs, pose, velocity, mass/lift, and shipyard coordinates. Operators see every ship; other players see only ships they own or share by team. `blueprint export` (permission level 2) saves the hull in a dock as a ship pattern; see [Datapacks](#datapacks).

## Datapacks

Override `data/skydock/skydock_mass/default.json` to change default block mass, individual block masses (including other mods), lift per cell, blocks supported per cell, dock caps, or collision damage. The `collision` object takes `min_speed` (blocks/second below which impacts are harmless), `speed_per_hardness` (extra speed needed per point of block hardness), `blocks_per_speed` and `max_blocks` (how many hull blocks one impact can break), `entity_damage_per_speed` (health per block/second of excess speed), and `damage_other_ships` (whether a ship that is hit is damaged too). Values must be finite and positive; caps are bounded at 24,576. Rules reload with `/reload` and active ships recalculate once a second. An invalid rule file is rejected as a whole; it cannot partially change the flight settings.

### Ship patterns

Ship patterns load from `data/<namespace>/skydock_patterns/<name>.json` with the id `<namespace>:<name>` and join the twelve built-in patterns. A data pattern with a built-in's id (for example `skydock:scout_cutter`) replaces it, but must keep its tier. The format:

```json
{
  "name": "My Skiff",
  "description": "A small ship for one pilot.",
  "tier": "scout",
  "palette": {
    "p": "minecraft:oak_planks",
    "h": "skydock:helm[facing=south]",
    "c": "skydock:lift_cell",
    "e": "skydock:engine",
    "l": "skydock:brass_lantern"
  },
  "decorations": ["l"],
  "layers": [
    ["...", ".p.", ".p.", "ppp", ".e."],
    ["...", ".h.", ".c.", ".l.", "..."]
  ]
}
```

- `name` and `description` are translation keys or plain text. `tier` is `scout`, `brig`, `cruiser` or `dreadnought`.
- `palette` maps one character to a block state in `/setblock` syntax (without NBT); `.` and space are air and cannot be keys. `decorations` optionally lists the palette characters that the dock's decoration toggle includes or leaves out.
- `layers` has one list of rows per y level, bottom-up; each row is a string with one character per x, and rows run from low z (the bow, nearest a north-facing controller) to high z. Coordinates are dock-local, in the north-facing frame, starting at the interior's corner on the controller's left (x = 0), so pad rows with leading dots to center a hull. Being north-framed, a pattern builds in a dock facing any way.
- A file is rejected whole, logged with its name, and ignored when it fails any check: unknown characters or blocks, cells outside the tier's interior, a block with no item form, or not exactly one helm. The other patterns still load. Assembly applies its usual checks (engines, lift, mass) when a pattern is built.
- Patterns reload with `/reload`. An assembly whose pattern a reload removed is cancelled and its materials are returned to the chests.

`/skydock blueprint export <controller x y z> <name>` (permission level 2) saves the hull in a dock as a pattern. The dock must pass **Inspect ship**; blocks in `skydock:decorations` become decorations. The name is 1 to 32 characters of `a-z`, `0-9` and `_`, and must not already be a pattern or file. A hull may use up to about 4,000 distinct block states (palette characters beyond `a-zA-Z0-9` are accented letters); beyond that the export is refused with a readable message. The file is written to `<world>/datapacks/skydock_saved/data/skydock/skydock_patterns/<name>.json`, creating the pack's `pack.mcmeta` (`pack_format` 48) if needed. The first time, run `/datapack enable "file/skydock_saved"`; after that, `/reload` adds each new blueprint to the dock screens of its tier. Block-entity contents (chest items, engine fuel) are not saved.

### Portability

This portable JSON reload system replaces the plan's direct NeoForge data-map dependency, keeping the mass interface usable by a future Fabric adapter. The block tags `skydock:not_ship` and `skydock:forbidden` control exclusion and immovable content. Waterlogged blocks and fluids must be drained before launch.

## Architecture and compatibility

Each launched ship owns a separated, chunk-ticketed region in `skydock:shipyard`. Real block entities tick there. Transfer snapshots preserve block states and block-entity components/NBT, and a saved transfer journal is flushed before moving blocks. A saved ship record retains its home berth, owner, pose, and velocity across restarts. Interrupted launches and redocks are replayed on startup. A transfer saves the ship journal and only the chunks it edited, in that order, rather than the whole world.

A parked ship goes dormant after 30 seconds with no pilot, crew or boarding players, cruise off, no movement, moored or with enough lift, and no player within 288 blocks. A dormant ship skips physics and releases its chunk tickets until a player comes near or a command, launch, redock or boarding needs it. Dormancy is not saved; every ship starts awake.

The client receives chunked hull snapshots and interpolated poses. Poses travel as a compact binary payload; after damage or repair a client that is one revision behind receives only the changed cells. Ordinary block faces are baked into reusable meshes; block-entity renderers and translucent blocks use a separate pass. Player/entity collision uses transformed voxel collision boxes near each entity, with step-up and sneak-edge handling. Yaw collision is conservative: rotated voxel shapes become enclosing axis-aligned boxes. Terrain collision stops a ship and, above the safe speed, breaks the hull blocks that struck; terrain itself is never destroyed.

Ship uses are raycast and permission-checked on the server, remapped to shipyard coordinates, and dispatched to the actual block. Menu validity is checked against the moving ship, with ordinary container packets preserved. Players remain in the overworld. Building/breaking the flying hull is disabled.

Vanilla storage and furnace-style block entities are the first compatibility target. Machines with ordinary block-entity ticks and menus can retain that behavior in the shipyard. This does not guarantee compatibility with every mod: cached world coordinates, custom menu checks, custom rendering data, world-position-dependent networks, and contraptions can need adapters. Create kinematics, general ship joints, water buoyancy, pitch/roll, and perfect ship collision are outside v1.

## Runtime testing

Use Marionette's **own Minecraft 1.21.1 NeoForge client** and stage the built Skydock jar plus its Architectury API dependency in an external scratch folder. Point Marionette's extra-mods option at that folder. Run a namespace sweep first, then independent scratch worlds for launch, movement, menus, fuel, persistence, and redock scenarios. Keep the harness, scenarios, reports, screenshots, and runtime state outside this repository.

The shared transform tests run with `./gradlew :common:test`; they also check that every player-facing string is a key in `assets/skydock/lang/en_us.json`. GitHub Actions runs `./gradlew build` on every push and pull request. A successful build alone does not validate the Minecraft runtime paths.

See [the implementation design](docs/DESIGN.md) for the platform boundaries and extension path.

## Model sources

The 82 custom block-model sources are kept in [assets-source/blockbench](assets-source/blockbench): 18 regular models, including the connected railing’s post, side, and inventory parts, plus 64 lift-cell connection variants. The v2 runtime palette contains twelve painted material textures. The `.mjs` files are inputs to native Blockbench MCP authoring; Minecraft has no runtime procedural model generator. See the [visual reference prompts](assets-source/design-references/prompts-v2.md) that guided the authored assets. Blockbench files are source archives, not Minecraft resource paths.
