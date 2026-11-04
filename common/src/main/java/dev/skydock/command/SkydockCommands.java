package dev.skydock.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import dev.skydock.Skydock;
import dev.skydock.block.DockBlockEntity;
import dev.skydock.block.SkydockBlocks;
import dev.skydock.cargo.*;
import dev.skydock.data.PatternJson;
import dev.skydock.data.ShipPattern;
import dev.skydock.data.ShipPatterns;
import dev.skydock.ship.*;
import net.minecraft.SharedConstants;
import net.minecraft.commands.*;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import static net.minecraft.commands.Commands.*;

public final class SkydockCommands {
    private static final Pattern BLUEPRINT_NAME = Pattern.compile("[a-z0-9_]{1,32}");
    /** The world datapack that exported hulls are written into; {@code /datapack enable "file/skydock_saved"} turns it on. */
    private static final String SAVED_PACK = "skydock_saved";

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = literal("skydock").executes(ctx -> {
            ctx.getSource().sendSuccess(() -> Component.translatable("message.skydock.usage"), false); return 1;
        });
        root.then(literal("inspect").then(argument("controller", BlockPosArgument.blockPos()).executes(ctx -> { ShipManager.inspect(ctx.getSource().getPlayerOrException(), BlockPosArgument.getLoadedBlockPos(ctx, "controller")); return 1; })));
        root.then(literal("launch").then(argument("controller", BlockPosArgument.blockPos()).executes(ctx -> { ShipManager.launch(ctx.getSource().getPlayerOrException(), BlockPosArgument.getLoadedBlockPos(ctx, "controller")); return 1; })));
        root.then(literal("redock").then(argument("controller", BlockPosArgument.blockPos()).executes(ctx -> { ShipManager.redockNearest(ctx.getSource().getPlayerOrException(), BlockPosArgument.getLoadedBlockPos(ctx, "controller")); return 1; })));
        root.then(literal("summon").then(argument("controller", BlockPosArgument.blockPos()).executes(ctx -> {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            return ShipManager.summon(player, player.level().dimension(), BlockPosArgument.getBlockPos(ctx, "controller")) ? 1 : 0;
        })));
        root.then(literal("use").executes(ctx -> { ShipInteractions.useFromLook(ctx.getSource().getPlayerOrException()); return 1; }));
        root.then(literal("target").executes(ctx -> {
            var hit = ShipInteractions.pick(ctx.getSource().getPlayerOrException());
            ctx.getSource().sendSuccess(() -> Component.literal(hit == null ? "No ship block in reach." :
                    hit.ship().id + " | local=" + hit.localHit().getBlockPos().toShortString() + " | " + hit.ship().state(hit.localHit().getBlockPos()) +
                            " | reach=" + Math.sqrt(hit.distance())), false);
            return hit == null ? 0 : 1;
        }));
        root.then(literal("release").executes(ctx -> { ShipManager.release(ctx.getSource().getPlayerOrException()); return 1; }));
        root.then(literal("cruise").executes(ctx -> { ShipManager.toggleCruise(ctx.getSource().getPlayerOrException()); return 1; }));
        root.then(literal("control").then(argument("thrust", FloatArgumentType.floatArg(-1, 1)).then(argument("yaw", FloatArgumentType.floatArg(-1, 1)).then(argument("climb", FloatArgumentType.floatArg(-1, 1)).executes(ctx -> {
            ShipManager.control(ctx.getSource().getPlayerOrException(), FloatArgumentType.getFloat(ctx, "thrust"), FloatArgumentType.getFloat(ctx, "yaw"), FloatArgumentType.getFloat(ctx, "climb")); return 1;
        })))));
        root.then(literal("status").executes(ctx -> {
            // Operators see every ship; players see only ships they may use, so other crews' positions and pilots stay private.
            ServerPlayer viewer = ctx.getSource().getPlayer();
            var ships = ShipManager.ships(ctx.getSource().getLevel()).stream()
                    .filter(ship -> viewer == null || ctx.getSource().hasPermission(2) || ShipManager.permitted(viewer, ship)).toList();
            ctx.getSource().sendSuccess(() -> Component.literal("Skydock ships: " + ships.size()), false);
            for (Ship ship : ships) ctx.getSource().sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                    "%s | %s | blocks=%d mass=%.0f lift=%.0f | pose=%.3f,%.3f,%.3f yaw=%.3f | velocity=%.3f,%.3f,%.3f | yard=%s | cruise=%s target=%.3f turn=%.3f | moored=%s blocked=%s pilot=%s",
                    ship.id, ship.tier, ship.blocks.size(), ship.mass, ship.lift, ship.pose.x(), ship.pose.y(), ship.pose.z(), ship.pose.yaw(),
                    ship.velocity.x, ship.velocity.y, ship.velocity.z, ship.yard.toShortString(), ship.cruise, ship.cruiseSpeed, ship.yawVelocity, ship.moored, ship.blocked, ship.pilot)), false);
            return ships.size();
        }));
        root.then(literal("blueprint").requires(source -> source.hasPermission(2)).then(literal("export")
                .then(argument("controller", BlockPosArgument.blockPos()).then(argument("name", StringArgumentType.word()).executes(ctx ->
                        exportBlueprint(ctx.getSource(), BlockPosArgument.getLoadedBlockPos(ctx, "controller"), StringArgumentType.getString(ctx, "name")))))));
        root.then(literal("repair-yard").requires(source -> source.hasPermission(2)).then(argument("ship", UuidArgument.uuid())
                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(ShipSavedData.get(ctx.getSource().getServer()).ships.keySet().stream().map(java.util.UUID::toString), builder))
                .executes(ctx -> repairYard(ctx.getSource(), UuidArgument.getUuid(ctx, "ship")))));
        root.then(cargoCommands());
        dispatcher.register(root);
    }

    /** Re-pastes a launched ship's stored hull into its shipyard region after the region's chunks were deleted, and wakes it. */
    private static int repairYard(CommandSourceStack source, java.util.UUID id) {
        Ship ship = ShipManager.get(source.getServer(), id);
        if (ship == null) { source.sendFailure(Component.translatable("message.skydock.repair_yard_no_ship", id.toString())); return 0; }
        if (!ship.phase.equals("active")) { source.sendFailure(Component.translatable("message.skydock.repair_yard_transfer", id.toString())); return 0; }
        if (source.getServer().getLevel(ShipManager.SHIPYARD) == null) { source.sendFailure(Component.translatable("message.skydock.shipyard_missing")); return 0; }
        int pasted = ShipManager.repairYard(source.getServer(), ship);
        source.sendSuccess(() -> Component.translatable("message.skydock.repair_yard_done", pasted, id.toString()), true);
        return pasted;
    }

    /** The most reputation the command may set, leaving headroom below the integer limit that every gain saturates at. */
    static final int MAX_REPUTATION = 1_000_000_000;
    /** How many terminals the list command prints. */
    static final int LISTED_TERMINALS = 20;

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> cargoCommands() {
        var cargo = literal("cargo").requires(s -> s.hasPermission(2));
        cargo.then(literal("reputation").then(argument("player", EntityArgument.player())
                .executes(ctx -> reputation(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"), null))
                .then(literal("set").then(argument("amount", IntegerArgumentType.integer(0, MAX_REPUTATION)).executes(ctx ->
                        reputation(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"), IntegerArgumentType.getInteger(ctx, "amount")))))));
        cargo.then(literal("terminal")
                .then(literal("list").executes(ctx -> listTerminals(ctx.getSource())))
                .then(literal("place").executes(ctx -> placeTerminal(ctx.getSource()))));
        cargo.then(literal("offer").then(argument("terminal", BlockPosArgument.blockPos()).then(literal("refresh").executes(ctx -> {
            ServerPlayer player = ctx.getSource().getPlayerOrException(); BlockPos pos = BlockPosArgument.getBlockPos(ctx, "terminal");
            CargoSavedData data = CargoSavedData.get(ctx.getSource().getServer()); CargoProfile profile = data.profile(player.getUUID());
            GlobalPos at = GlobalPos.of(player.level().dimension(), pos);
            boolean had = profile.offers.remove(at) != null | profile.offersRefreshed.remove(at) != null;
            data.setDirty(); CargoService.sync(player);
            ctx.getSource().sendSuccess(() -> Component.translatable("message.skydock.cargo_cmd_refreshed", pos.toShortString()), true);
            return had ? 1 : 0;
        }))));
        var crate = literal("crate");
        for (CrateRank rank : CrateRank.values()) crate.then(literal(rank.name().toLowerCase(Locale.ROOT)).executes(ctx -> {
            ServerPlayer player = ctx.getSource().getPlayerOrException(); ItemStack stack = new ItemStack(SkydockBlocks.CRATES.get(rank).get());
            if (!player.getInventory().add(stack)) player.drop(stack, false);
            ctx.getSource().sendSuccess(() -> Component.translatable("message.skydock.cargo_cmd_crate", stack.getHoverName()), true);
            return 1;
        }));
        cargo.then(crate);
        return cargo;
    }

    private static int reputation(CommandSourceStack source, ServerPlayer target, Integer set) {
        CargoProfile profile = CargoSavedData.get(source.getServer()).profile(target.getUUID());
        if (set != null) {
            profile.reputation = set; profile.spent = Math.min(profile.spent, set); CargoSavedData.get(source.getServer()).setDirty(); CargoService.sync(target);
            source.sendSuccess(() -> Component.translatable("message.skydock.cargo_cmd_reputation_set", target.getDisplayName(), set), true);
        } else {
            source.sendSuccess(() -> Component.translatable("message.skydock.cargo_cmd_reputation", target.getDisplayName(), profile.reputation, profile.balance(), Component.translatable(CargoRanks.forReputation(profile.reputation).key())), false);
        }
        return profile.reputation;
    }

    /** The count, then the {@link #LISTED_TERMINALS} nearest to the caller (to 0, 0 in the overworld from the console). */
    private static int listTerminals(CommandSourceStack source) {
        var terminals = CargoSavedData.get(source.getServer()).terminals.values();
        GlobalPos from = source.getEntity() == null ? GlobalPos.of(net.minecraft.world.level.Level.OVERWORLD, BlockPos.ZERO) : GlobalPos.of(source.getLevel().dimension(), BlockPos.containing(source.getPosition()));
        List<CargoSavedData.Terminal> nearest = CargoSavedData.nearest(terminals, from, LISTED_TERMINALS);
        source.sendSuccess(() -> Component.translatable("message.skydock.cargo_cmd_terminals", terminals.size(), nearest.size()), false);
        for (CargoSavedData.Terminal t : nearest) source.sendSuccess(() -> Component.translatable(t.generated() ? "message.skydock.cargo_cmd_terminal_generated" : "message.skydock.cargo_cmd_terminal_predicted",
                t.pos().dimension().location().toString(), t.pos().pos().toShortString(), t.pad().toShortString()), false);
        return terminals.size();
    }

    private static int placeTerminal(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        HitResult hit = player.pick(player.blockInteractionRange(), 0, false);
        if (!(hit instanceof BlockHitResult block) || hit.getType() != HitResult.Type.BLOCK || !(player.level().getBlockEntity(block.getBlockPos()) instanceof CargoTerminalBlockEntity terminal)) {
            source.sendFailure(Component.translatable("message.skydock.cargo_cmd_no_terminal")); return 0;
        }
        CargoSavedData.get(source.getServer()).registerTerminal(GlobalPos.of(player.level().dimension(), terminal.getBlockPos()), terminal.pad());
        source.sendSuccess(() -> Component.translatable("message.skydock.cargo_cmd_placed", terminal.getBlockPos().toShortString(), terminal.pad().toShortString()), true);
        return 1;
    }

    /** Writes the hull standing in a dock that passes inspection as a pattern file in the world's saved-hulls datapack. */
    private static int exportBlueprint(CommandSourceStack source, BlockPos pos, String name) {
        if (!BLUEPRINT_NAME.matcher(name).matches()) { source.sendFailure(Component.translatable("message.skydock.blueprint_bad_name")); return 0; }
        if (!(source.getLevel().getBlockEntity(pos) instanceof DockBlockEntity dock)) { source.sendFailure(Component.translatable("message.skydock.no_dock")); return 0; }
        if (dock.assemblyJob() != null) { source.sendFailure(Component.translatable("message.skydock.wait_assembly")); return 0; }
        DockValidation scan = DockValidation.scan(source.getLevel(), dock);
        if (!scan.valid()) { source.sendFailure(Component.translatable("message.skydock.blueprint_invalid", scan.summary())); return 0; }
        ResourceLocation id = Skydock.id(name);
        Path pack = source.getServer().getWorldPath(LevelResource.DATAPACK_DIR).resolve(SAVED_PACK);
        Path file = pack.resolve("data").resolve(Skydock.ID).resolve("skydock_patterns").resolve(name + ".json");
        if (Files.exists(file) || ShipPatterns.get(id).isPresent()) { source.sendFailure(Component.translatable("message.skydock.blueprint_exists", name)); return 0; }
        JsonObject json;
        try {
            // Scanned cells are already in the north frame, so the file rebuilds correctly in a dock facing any way.
            List<ShipPattern.Block> cells = scan.blocks().entrySet().stream()
                    .map(e -> new ShipPattern.Block(e.getKey(), e.getValue(), e.getValue().is(SkydockBlocks.DECORATIONS))).toList();
            json = PatternJson.write(new ShipPattern(id, name, "pattern.skydock.saved.description", dock.tier(), cells), BlockStateParser::serialize);
            PatternJson.parse(id, json, PatternJson::state); // refuse anything the loader would reject
        } catch (IllegalArgumentException ex) {
            source.sendFailure(Component.translatable("message.skydock.blueprint_unsupported", ex.getMessage())); return 0;
        }
        try {
            Files.createDirectories(file.getParent());
            Path meta = pack.resolve("pack.mcmeta");
            if (!Files.exists(meta)) {
                JsonObject info = new JsonObject();
                info.addProperty("pack_format", SharedConstants.DATA_PACK_FORMAT); // 48 for 1.21.1
                info.addProperty("description", "Skydock saved hulls");
                JsonObject root = new JsonObject();
                root.add("pack", info);
                Files.writeString(meta, new GsonBuilder().setPrettyPrinting().create().toJson(root));
            }
            Files.writeString(file, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(json), StandardOpenOption.CREATE_NEW);
        } catch (IOException ex) {
            Skydock.LOGGER.error("Could not write Skydock blueprint {}", file, ex);
            source.sendFailure(Component.translatable("message.skydock.blueprint_write_failed", ex.getMessage())); return 0;
        }
        source.sendSuccess(() -> Component.translatable("message.skydock.blueprint_saved", name, SAVED_PACK), true);
        return 1;
    }
}
