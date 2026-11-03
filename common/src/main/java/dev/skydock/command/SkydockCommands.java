package dev.skydock.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import dev.skydock.Skydock;
import dev.skydock.block.DockBlockEntity;
import dev.skydock.block.SkydockBlocks;
import dev.skydock.data.PatternJson;
import dev.skydock.data.ShipPattern;
import dev.skydock.data.ShipPatterns;
import dev.skydock.ship.*;
import net.minecraft.SharedConstants;
import net.minecraft.commands.*;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
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
        dispatcher.register(root);
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
