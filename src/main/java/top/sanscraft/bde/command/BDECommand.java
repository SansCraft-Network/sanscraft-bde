package top.sanscraft.bde.command;

import com.google.gson.Gson;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import top.sanscraft.bde.SansCraftBDEPlugin;
import top.sanscraft.bde.converter.BlockbenchConverter;
import top.sanscraft.bde.converter.ConversionMapper;
import top.sanscraft.bde.converter.GltfConverter;
import top.sanscraft.bde.converter.ObjConverter;
import top.sanscraft.bde.converter.VoxConverter;
import top.sanscraft.bde.manager.CustomBlockManager;
import top.sanscraft.bde.manager.ModelManager;
import top.sanscraft.bde.model.BdeModel;
import top.sanscraft.bde.model.ModelInstance;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class BDECommand implements CommandExecutor, TabCompleter {
    private final SansCraftBDEPlugin plugin;

    public BDECommand(SansCraftBDEPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("sanscraft.bde")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to execute this command.");
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String subcommand = args[0].toLowerCase();
        switch (subcommand) {
            case "spawn":
                handleSpawn(sender, args);
                break;
            case "vehicles":
                handleVehiclesCatalog(sender);
                break;
            case "move":
                handleMove(sender, args);
                break;
            case "remove":
                handleRemove(sender);
                break;
            case "list":
                handleList(sender);
                break;
            case "anim":
                handleAnim(sender, args);
                break;
            case "convert":
                handleConvert(sender, args);
                break;

            case "block":
                handleBlock(sender, args);
                break;
            case "select":
                handleSelect(sender);
                break;
            case "deselect":
            case "clear":
                handleDeselect(sender);
                break;
            case "rotate":
                handleRotate(sender, args);
                break;
            case "gui":
                handleGui(sender);
                break;
            case "traction":
                handleTraction(sender);
                break;
            case "debug":
                handleDebug(sender, args);
                break;
            case "test_transform":
                handleTestTransform(sender, args);
                break;
            default:
                sendHelp(sender);
                break;
        }

        return true;
    }

    private void handleSpawn(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can run this subcommand.");
            return;
        }

        if (!(sender.hasPermission("sanscraft.bde.spawn"))) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to spawn models.");
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /bde spawn <id|filename> [scale] [x] [y] [z] [yaw]");
            return;
        }

        Player player = (Player) sender;
        String modelId = args[1];
        double scale = 1.0;
        if (args.length >= 3) {
            try {
                scale = Double.parseDouble(args[2]);
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "Scale must be a number.");
                return;
            }
        }

        Location baseLoc = player.getLocation();
        double x = baseLoc.getX();
        double y = baseLoc.getY();
        double z = baseLoc.getZ();
        float yaw = baseLoc.getYaw();

        if (args.length >= 6) {
            try {
                x = parseCoord(args[3], baseLoc.getX());
                y = parseCoord(args[4], baseLoc.getY());
                z = parseCoord(args[5], baseLoc.getZ());
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "Coordinates must be numbers or relative (~).");
                return;
            }
        }
        if (args.length >= 7) {
            try {
                yaw = (float) parseCoord(args[6], baseLoc.getYaw());
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "Yaw must be a number or relative (~).");
                return;
            }
        }

        // Parse flags
        boolean simpleVar = false;
        boolean onGroundVar = false;
        for (int i = 2; i < args.length; i++) {
            String arg = args[i].toLowerCase();
            if (arg.equals("simple") || arg.equals("-simple")) {
                simpleVar = true;
            } else if (arg.equals("onground") || arg.equals("-onground")) {
                onGroundVar = true;
            }
        }

        if (simpleVar) {
            float playerYaw = baseLoc.getYaw();
            float normalizedYaw = (playerYaw % 360 + 360) % 360;
            yaw = Math.round(normalizedYaw / 90.0f) * 90.0f;
            if (yaw >= 360) yaw -= 360;
        }

        float pitch = simpleVar ? 0.0f : baseLoc.getPitch();
        Location spawnLoc = new Location(baseLoc.getWorld(), x, y, z, yaw, pitch);

        player.sendMessage(ChatColor.YELLOW + "Loading model " + modelId + "...");
        double finalScale = scale;
        boolean finalOnGround = onGroundVar;
        boolean finalSimple = simpleVar;
        plugin.getModelManager().loadModel(modelId)
                .thenAccept(model -> {
                    // Spawn on primary thread
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        Location finalSpawnLoc = spawnLoc.clone();
                        if (finalOnGround) {
                            ModelManager.BoundingBox box = ModelManager.calculateModelBounds(model, finalScale);
                            double targetY;
                            if (args.length >= 5 && !args[4].startsWith("~")) {
                                targetY = finalSpawnLoc.getY();
                            } else {
                                Location scanLoc = finalSpawnLoc.clone();
                                double floorY = finalSpawnLoc.getY();
                                double startY = Math.min(scanLoc.getWorld().getMaxHeight(), finalSpawnLoc.getY() + 3.0);
                                double minHeight = scanLoc.getWorld().getMinHeight();
                                for (double scanY = startY; scanY >= minHeight; scanY -= 1.0) {
                                    scanLoc.setY(scanY);
                                    if (scanLoc.getBlock().getType().isSolid()) {
                                        floorY = Math.floor(scanY) + 1.0;
                                        break;
                                    }
                                }
                                targetY = floorY;
                            }
                            finalSpawnLoc.setY(targetY - box.getMinY());
                        }
                        if (finalSimple) {
                            finalSpawnLoc.setPitch(0.0f);
                        }
                        ModelInstance instance = plugin.getModelManager().spawnModel(model, finalSpawnLoc, finalScale);
                        player.sendMessage(ChatColor.GREEN + "Spawned model! UUID: " + instance.getId());
                    });
                })
                .exceptionally(ex -> {
                    player.sendMessage(ChatColor.RED + "Failed to load model: " + ex.getMessage());
                    return null;
                });
    }

    private void handleVehiclesCatalog(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can run this subcommand.");
            return;
        }

        if (!(sender.hasPermission("sanscraft.bde.vehicles"))) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to open the Vehicles Catalog.");
            return;
        }
        Player player = (Player) sender;
        plugin.getBdeGuiManager().openVehiclesCatalog(player, null);
    }

    private void handleMove(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can run this subcommand.");
            return;
        }

        if (!(sender.hasPermission("sanscraft.bde.move"))) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to move models.");
            return;
        }

        if (args.length < 4) {
            sender.sendMessage(ChatColor.RED + "Usage: /bde move <x> <y> <z> [yaw]");
            return;
        }

        Player player = (Player) sender;
        UUID selectedId = plugin.getBdeGuiManager().getSelectedModel(player.getUniqueId());
        ModelInstance target = selectedId != null ? plugin.getModelManager().getActiveInstances().get(selectedId) : findNearestModel(player.getLocation(), 6.0);

        if (target == null) {
            player.sendMessage(ChatColor.RED + "No selected or nearby block display model found.");
            return;
        }

        Location currentLoc = target.getLocation().clone();
        double x, y, z;
        float yaw = currentLoc.getYaw();

        try {
            x = parseCoord(args[1], currentLoc.getX());
            y = parseCoord(args[2], currentLoc.getY());
            z = parseCoord(args[3], currentLoc.getZ());
        } catch (NumberFormatException e) {
            player.sendMessage(ChatColor.RED + "Coordinates must be numbers or relative (~).");
            return;
        }

        if (args.length >= 5) {
            try {
                yaw = (float) parseCoord(args[4], currentLoc.getYaw());
            } catch (NumberFormatException e) {
                player.sendMessage(ChatColor.RED + "Yaw must be a number or relative (~).");
                return;
            }
        }

        Location newLoc = new Location(currentLoc.getWorld(), x, y, z, yaw, currentLoc.getPitch());
        plugin.getModelManager().teleportModel(target, newLoc);
        player.sendMessage(ChatColor.GREEN + "Moved model to: " + String.format("%.2f, %.2f, %.2f (Yaw: %.1f)", x, y, z, yaw));
    }

    private double parseCoord(String arg, double relativeTo) {
        if (arg.startsWith("~")) {
            if (arg.length() == 1) {
                return relativeTo;
            }
            return relativeTo + Double.parseDouble(arg.substring(1));
        }
        return Double.parseDouble(arg);
    }

    private void handleRemove(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can run this subcommand.");
            return;
        }

        if (!(sender.hasPermission("sanscraft.bde.remove"))) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to remove models.");
            return;
        }

        Player player = (Player) sender;
        ModelInstance nearest = findNearestModel(player.getLocation(), 6.0);
        if (nearest == null) {
            player.sendMessage(ChatColor.RED + "No block display model found nearby.");
            return;
        }

        plugin.getModelManager().removeInstance(nearest.getId());
        player.sendMessage(ChatColor.GREEN + "Nearest model removed successfully.");
    }

    private void handleList(CommandSender sender) {
        if (!(sender.hasPermission("sanscraft.bde.list"))) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to list spawned models.");
            return;
        }

        Map<UUID, ModelInstance> active = plugin.getModelManager().getActiveInstances();
        if (active.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "No active BDE models currently spawned.");
            return;
        }

        sender.sendMessage(ChatColor.GOLD + "=== Active BDE Models (" + active.size() + ") ===");
        for (ModelInstance inst : active.values()) {
            Location loc = inst.getLocation();
            sender.sendMessage(String.format("§e- §f%s §7(Scale: %.2f) at §a%s, %d, %d, %d",
                    inst.getId().toString().substring(0, 8),
                    inst.getScale(),
                    loc.getWorld().getName(),
                    loc.getBlockX(),
                    loc.getBlockY(),
                    loc.getBlockZ()
            ));
        }
    }

    private void handleAnim(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can run this subcommand.");
            return;
        }

        if (!(sender.hasPermission("sanscraft.bde.animate"))) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to animate models.");
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /bde anim <play|stop|pause|resume|speed> ...");
            return;
        }

        Player player = (Player) sender;
        String action = args[1].toLowerCase();

        ModelInstance nearest = findNearestModel(player.getLocation(), 6.0);
        UUID selectedId = plugin.getBdeGuiManager().getSelectedModel(player.getUniqueId());
        ModelInstance target = selectedId != null ? plugin.getModelManager().getActiveInstances().get(selectedId) : nearest;

        if (target == null) {
            player.sendMessage(ChatColor.RED + "No selected or nearby block display model found.");
            return;
        }

        switch (action) {
            case "play":
                if (args.length < 3) {
                    player.sendMessage(ChatColor.RED + "Usage: /bde anim play <animation_name> [loop/once]");
                    return;
                }
                String animName = args[2];
                boolean loop = args.length < 4 || args[3].equalsIgnoreCase("loop");
                plugin.getAnimationEngine().playAnimation(target, animName, loop);
                player.sendMessage(ChatColor.GREEN + "Playing animation '" + animName + "' (Loop: " + loop + ") on model.");
                break;
            case "stop":
                plugin.getAnimationEngine().stopAnimation(target.getId());
                player.sendMessage(ChatColor.GREEN + "Stopped animation on model.");
                break;
            case "pause":
                plugin.getAnimationEngine().pauseAnimation(target.getId());
                player.sendMessage(ChatColor.GREEN + "Paused animation on model.");
                break;
            case "resume":
                plugin.getAnimationEngine().resumeAnimation(target.getId());
                player.sendMessage(ChatColor.GREEN + "Resumed animation on model.");
                break;
            case "speed":
                if (args.length < 3) {
                    player.sendMessage(ChatColor.RED + "Usage: /bde anim speed <value>");
                    return;
                }
                try {
                    double speed = Double.parseDouble(args[2]);
                    plugin.getAnimationEngine().setSpeed(target.getId(), speed);
                    player.sendMessage(ChatColor.GREEN + "Set playback speed to " + speed + "x");
                } catch (NumberFormatException e) {
                    player.sendMessage(ChatColor.RED + "Speed must be a number.");
                }
                break;
            default:
                player.sendMessage(ChatColor.RED + "Unknown action: " + action + ". Use play, stop, pause, resume, or speed.");
                break;
        }
    }

    private void handleConvert(CommandSender sender, String[] args) {
        if (!(sender.hasPermission("sanscraft.bde.convert"))) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to convert models.");
            return;
        }

        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "Usage: /bde convert <blockbench|vox|gltf|glb|obj> <file> [resolution_factor/density] [target_size_blocks] [max_displays_cap]");
            return;
        }

        String type = args[1].toLowerCase();
        String filename = args[2];
        boolean isMesh = type.equals("gltf") || type.equals("glb") || type.equals("obj");
        int resolution = isMesh ? 16 : 1;

        if (args.length >= 4) {
            try {
                resolution = Integer.parseInt(args[3]);
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "Resolution factor/density must be an integer.");
                return;
            }
        }

        double targetSizeBlocks = -1.0;
        if (args.length >= 5) {
            try {
                targetSizeBlocks = Double.parseDouble(args[4]);
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "Target size in blocks must be a number.");
                return;
            }
        }

        int maxDisplaysCap = plugin.getConfig().getInt("voxels.max-display-entities", 1000);
        if (args.length >= 6) {
            try {
                maxDisplaysCap = Integer.parseInt(args[5]);
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "Maximum display entities cap must be an integer.");
                return;
            }
        }

        // Search in models directory
        File folder = new File(plugin.getDataFolder(), "models");
        File inputFile = new File(folder, filename);
        if (!inputFile.exists()) {
            sender.sendMessage(ChatColor.RED + "File not found: " + filename + " in plugins/SansCraftBDE/models/");
            return;
        }

        // Load mappings if exists
        File mappingFile = new File(plugin.getDataFolder(), "mappings/" + filename.replaceAll("\\.[^.]+$", "") + ".yml");
        ConversionMapper mapper = new ConversionMapper(plugin, mappingFile);

        BdeModel output;
        try {
            if (type.equals("blockbench")) {
                BlockbenchConverter converter = new BlockbenchConverter(plugin);
                output = converter.convert(inputFile, mapper);
            } else if (type.equals("vox")) {
                VoxConverter converter = new VoxConverter(plugin);
                output = converter.convert(inputFile, mapper, resolution);
            } else if (type.equals("gltf") || type.equals("glb")) {
                GltfConverter converter = new GltfConverter(plugin);
                output = converter.convert(inputFile, mapper, resolution, targetSizeBlocks, maxDisplaysCap);
            } else if (type.equals("obj")) {
                ObjConverter converter = new ObjConverter(plugin);
                output = converter.convert(inputFile, mapper, resolution, targetSizeBlocks, maxDisplaysCap);
            } else {
                sender.sendMessage(ChatColor.RED + "Unknown format type: " + type + ". Choose 'blockbench', 'vox', 'gltf', 'glb', or 'obj'.");
                return;
            }

            // Save output
            String outFilename = filename.replaceAll("\\.[^.]+$", "") + ".json";
            File outputFile = new File(folder, outFilename);
            try (FileWriter writer = new FileWriter(outputFile)) {
                new Gson().toJson(output, writer);
            }

            sender.sendMessage(ChatColor.GREEN + "Conversion successful! Saved as models/" + outFilename);
            sender.sendMessage(ChatColor.YELLOW + "Model contains " + output.getPassengers().size() + " display elements.");

        } catch (Exception e) {
            sender.sendMessage(ChatColor.RED + "Conversion failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void handleBlock(CommandSender sender, String[] args) {
        if (!(sender.hasPermission("sanscraft.bde.block"))) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use custom blocks.");
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /bde block give ... or /bde block link ...");
            return;
        }

        String action = args[1].toLowerCase();
        if (action.equalsIgnoreCase("give")) {
            if (args.length < 4) {
                sender.sendMessage(ChatColor.RED + "Usage: /bde block give <player> <custom_block_id> [amount]");
                return;
            }

            Player target = Bukkit.getPlayer(args[2]);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Player not found: " + args[2]);
                return;
            }

            String blockId = args[3];
            int amount = 1;
            if (args.length >= 5) {
                try {
                    amount = Integer.parseInt(args[4]);
                } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "Amount must be a number.");
                    return;
                }
            }

            CustomBlockManager.CustomBlockConfig config = plugin.getCustomBlockManager().getConfig(blockId);
            if (config == null) {
                sender.sendMessage(ChatColor.RED + "Custom block ID not registered: " + blockId);
                return;
            }

            ItemStack item = plugin.getCustomBlockManager().createCustomBlockItem(blockId, amount);
            target.getInventory().addItem(item);
            sender.sendMessage(ChatColor.GREEN + "Gave " + amount + "x " + config.displayName + " to " + target.getName());
        } else if (action.equalsIgnoreCase("link")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "Only players can link blocks.");
                return;
            }
            if (args.length < 3) {
                sender.sendMessage(ChatColor.RED + "Usage: /bde block link <custom_block_id>");
                return;
            }

            Player player = (Player) sender;
            ItemStack item = player.getInventory().getItemInMainHand();
            if (item == null || item.getType() == Material.AIR) {
                player.sendMessage(ChatColor.RED + "You must hold an item stack in your main hand.");
                return;
            }

            String blockId = args[2];
            CustomBlockManager.CustomBlockConfig config = plugin.getCustomBlockManager().getConfig(blockId);
            if (config == null) {
                player.sendMessage(ChatColor.RED + "Custom block ID not registered: " + blockId);
                return;
            }

            ItemStack linkedItem = plugin.getCustomBlockManager().createCustomBlockItem(blockId, item.getAmount(), item.getType());
            player.getInventory().setItemInMainHand(linkedItem);
            player.sendMessage(ChatColor.GREEN + "Linked the item stack in your hand to custom block: " + ChatColor.RESET + config.displayName);
        } else {
            sender.sendMessage(ChatColor.RED + "Unknown action: " + action + ". Use give or link.");
        }
    }

    private ModelInstance findNearestModel(Location loc, double maxDistance) {
        ModelInstance nearest = null;
        double minDistanceSq = maxDistance * maxDistance;

        for (ModelInstance inst : plugin.getModelManager().getActiveInstances().values()) {
            Location instLoc = inst.getLocation();
            if (instLoc == null || !instLoc.getWorld().equals(loc.getWorld())) continue;

            double distanceSq = instLoc.distanceSquared(loc);
            if (distanceSq < minDistanceSq) {
                minDistanceSq = distanceSq;
                nearest = inst;
            }
        }
        return nearest;
    }

    private void handleSelect(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can select models.");
            return;
        }

        if (!(sender.hasPermission("sanscraft.bde.select"))) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to select models.");
            return;
        }

        Player player = (Player) sender;
        ModelInstance nearest = findNearestModel(player.getLocation(), 6.0);
        if (nearest == null) {
            player.sendMessage(ChatColor.RED + "No block display model found nearby to select.");
            return;
        }

        plugin.getBdeGuiManager().selectModel(player, nearest.getId());
    }

    private void handleDeselect(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can deselect models.");
            return;
        }

        if (!(sender.hasPermission("sanscraft.bde.deselect"))) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to deselect models.");
            return;
        }

        Player player = (Player) sender;
        UUID selectedId = plugin.getBdeGuiManager().getSelectedModel(player.getUniqueId());
        if (selectedId == null) {
            player.sendMessage(ChatColor.RED + "You do not have any selected model.");
            return;
        }

        plugin.getBdeGuiManager().clearSelection(player.getUniqueId());
        player.sendMessage(ChatColor.GREEN + "Deselected BDE model.");
    }

    private void handleRotate(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can rotate models.");
            return;
        }

        if (!(sender.hasPermission("sanscraft.bde.rotate"))) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to rotate models.");
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /bde rotate <yaw_offset_degrees>");
            return;
        }

        Player player = (Player) sender;
        float angle;
        try {
            angle = Float.parseFloat(args[1]);
        } catch (NumberFormatException e) {
            player.sendMessage(ChatColor.RED + "Angle must be a number.");
            return;
        }

        UUID selectedId = plugin.getBdeGuiManager().getSelectedModel(player.getUniqueId());
        ModelInstance target = selectedId != null ? plugin.getModelManager().getActiveInstances().get(selectedId) : findNearestModel(player.getLocation(), 6.0);

        if (target == null) {
            player.sendMessage(ChatColor.RED + "No selected or nearby block display model found.");
            return;
        }

        plugin.getModelManager().rotateModel(target, angle);
        player.sendMessage(ChatColor.GREEN + "Rotated model by " + angle + " degrees.");
    }

    private void handleGui(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can open the GUI dashboard.");
            return;
        }

        if (!(sender.hasPermission("sanscraft.bde.gui"))) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to open the GUI.");
            return;
        }

        Player player = (Player) sender;
        UUID selectedId = plugin.getBdeGuiManager().getSelectedModel(player.getUniqueId());
        if (selectedId == null) {
            player.sendMessage(ChatColor.RED + "You must select a model first (use /bde select or right-click one with a Blaze Rod).");
            return;
        }

        ModelInstance instance = plugin.getModelManager().getActiveInstances().get(selectedId);
        if (instance == null) {
            player.sendMessage(ChatColor.RED + "Selected model no longer exists.");
            plugin.getBdeGuiManager().clearSelection(player.getUniqueId());
            return;
        }

        plugin.getBdeGuiManager().openMainMenu(player, instance);
    }

    private void handleTraction(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can run this subcommand.");
            return;
        }

        if (!(sender.hasPermission("sanscraft.bde.spawn"))) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to adjust model traction.");
            return;
        }
        Player player = (Player) sender;
        plugin.getBdeGuiManager().openGeneralBlockTractionMenu(player);
        player.sendMessage(ChatColor.GREEN + "Opened Global Block Traction menu.");
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== SansCraft BDE Command Guide ===");
        sender.sendMessage(ChatColor.YELLOW + "/bde spawn <id|filename> [scale] [x] [y] [z] [yaw] §7- Spawns model at coords");
        sender.sendMessage(ChatColor.YELLOW + "/bde move <x> <y> <z> [yaw] §7- Moves selected/nearest model");
        sender.sendMessage(ChatColor.YELLOW + "/bde remove §7- Removes closest model");
        sender.sendMessage(ChatColor.YELLOW + "/bde list §7- Lists spawned models");
        sender.sendMessage(ChatColor.YELLOW + "/bde anim <play|stop|pause|resume|speed> ... §7- Animates model");
        sender.sendMessage(ChatColor.YELLOW + "/bde convert <blockbench|vox|gltf|glb|obj> <file> [resolution] §7- Converts 3D model");
        sender.sendMessage(ChatColor.YELLOW + "/bde vehicles §7- Opens the vehicles catalog GUI");
        sender.sendMessage(ChatColor.YELLOW + "/bde block give <player> <block_id> [amount] §7- Gives custom block item");
        sender.sendMessage(ChatColor.YELLOW + "/bde block link <custom_block_id> §7- Links hand-held item stack");
        sender.sendMessage(ChatColor.YELLOW + "/bde select §7- Selects nearest model and shows highlights");
        sender.sendMessage(ChatColor.YELLOW + "/bde deselect / clear §7- Clears current model selection");
        sender.sendMessage(ChatColor.YELLOW + "/bde rotate <angle> §7- Rotates model by yaw degrees");
        sender.sendMessage(ChatColor.YELLOW + "/bde gui §7- Opens the GUI editor console for selected model");
        sender.sendMessage(ChatColor.YELLOW + "/bde traction §7- Opens the global block traction overrides GUI");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(Arrays.asList("spawn", "remove", "list", "anim", "convert", "block", "select", "deselect", "clear", "rotate", "gui", "move", "vehicles", "debug", "traction", "test_transform"), args[0]);
        }

        String subcommand = args[0].toLowerCase();

        if (args.length == 2) {
            if (subcommand.equals("spawn")) {
                // List files/folders in models and vehicles directories recursively
                List<String> list = new ArrayList<>();
                File modelsFolder = new File(plugin.getDataFolder(), "models");
                if (modelsFolder.exists()) {
                    collectFiles(modelsFolder, "", list);
                }
                File vehiclesFolder = new File(plugin.getDataFolder(), "vehicles");
                if (vehiclesFolder.exists()) {
                    collectFiles(vehiclesFolder, "", list);
                }
                return filter(list, args[1]);
            }
            if (subcommand.equals("anim")) {
                return filter(Arrays.asList("play", "stop", "pause", "resume", "speed"), args[1]);
            }
            if (subcommand.equals("convert")) {
                return filter(Arrays.asList("blockbench", "vox", "gltf", "glb", "obj"), args[1]);
            }
            if (subcommand.equals("block")) {
                return filter(Arrays.asList("give", "link"), args[1]);
            }
            if (subcommand.equals("rotate")) {
                return filter(Arrays.asList("-90", "-45", "-15", "15", "45", "90"), args[1]);
            }
            if (subcommand.equals("move")) {
                return Collections.singletonList("~");
            }

            if (subcommand.equals("debug")) {
                return filter(Arrays.asList("vehicles"), args[1]);
            }
        }

        if (args.length == 3) {

            if (subcommand.equals("convert")) {
                File folder = new File(plugin.getDataFolder(), "models");
                String[] files = folder.list();
                List<String> list = (files != null) ? Arrays.asList(files) : new ArrayList<>();
                return filter(list, args[2]);
            }
            if (subcommand.equals("block") && args[1].equalsIgnoreCase("give")) {
                return null; // Player names tab completion
            }
            if (subcommand.equals("block") && args[1].equalsIgnoreCase("link")) {
                return filter(new ArrayList<>(plugin.getCustomBlockManager().getRegisteredBlockIds()), args[2]);
            }
            if (subcommand.equals("anim") && args[1].equalsIgnoreCase("play")) {
                Player player = (sender instanceof Player) ? (Player) sender : null;
                if (player != null) {
                    ModelInstance nearest = findNearestModel(player.getLocation(), 10.0);
                    if (nearest != null && nearest.getModel().getDatapack() != null) {
                        Map<String, Map<String, List<String>>> anims = nearest.getModel().getDatapack().getAnimKeyframes();
                        if (anims != null) {
                            return filter(new ArrayList<>(anims.keySet()), args[2]);
                        }
                    }
                }
                return filter(Collections.singletonList("default"), args[2]);
            }
            if (subcommand.equals("spawn")) {
                return filter(Arrays.asList("1.0", "0.5", "1.5", "2.0"), args[2]);
            }
            if (subcommand.equals("move")) {
                return Collections.singletonList("~");
            }
        }

        if (subcommand.equals("spawn") && args.length >= 4) {
            List<String> list = new ArrayList<>();
            list.add("~");
            List<String> typed = Arrays.stream(args).map(String::toLowerCase).collect(Collectors.toList());
            if (!typed.contains("simple")) {
                list.add("simple");
            }
            if (!typed.contains("onground")) {
                list.add("onground");
            }
            return filter(list, args[args.length - 1]);
        }

        if (args.length == 4) {
            if (subcommand.equals("block") && args[1].equalsIgnoreCase("give")) {
                return filter(new ArrayList<>(plugin.getCustomBlockManager().getRegisteredBlockIds()), args[3]);
            }
            if (subcommand.equals("anim") && args[1].equalsIgnoreCase("play")) {
                return filter(Arrays.asList("loop", "once"), args[3]);
            }
            if (subcommand.equals("move")) {
                return Collections.singletonList("~");
            }
        }

        if (args.length == 5) {
            if (subcommand.equals("move")) {
                return Collections.singletonList("~");
            }
        }

        if (args.length == 6) {
            if (subcommand.equals("move")) {
                return Collections.singletonList("~");
            }
        }

        return new ArrayList<>();
    }

    private List<String> filter(List<String> list, String prefix) {
        String lower = prefix.toLowerCase();
        return list.stream()
                .filter(s -> s.toLowerCase().startsWith(lower))
                .sorted()
                .collect(Collectors.toList());
    }



    private void handleDebug(CommandSender sender, String[] args) {
        if (!(sender.hasPermission("sanscraft.bde.debug"))) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use debug.");
            return;
        }
        ModelManager.DEBUG_VEHICLES = !ModelManager.DEBUG_VEHICLES;
        sender.sendMessage(ChatColor.GREEN + "Vehicle debugging has been " + (ModelManager.DEBUG_VEHICLES ? "ENABLED" : "DISABLED") + ".");
    }

    // ================================================================
    // TEST TRANSFORM — animation state
    // ================================================================
    private double ttVehicleYaw = 0.0;
    private double ttVehiclePitch = 0.0;
    private double ttRelativeYaw = 0.0;
    private double ttRelativePitch = 0.0;
    private Location ttRootLocation = null;
    private org.bukkit.scheduler.BukkitTask ttAnimTask = null;

    // Persistent marker entities (teleported each tick instead of respawned)
    private org.bukkit.entity.BlockDisplay ttMarkerRoot = null;
    private org.bukkit.entity.BlockDisplay ttMarkerPivot = null;
    private org.bukkit.entity.BlockDisplay ttMarkerSeat = null;
    private org.bukkit.entity.BlockDisplay ttMarkerMuzzle = null;

    // Animation increments per tick (applied additively each server tick)
    private double ttAnimVYawPerTick = 0.0;
    private double ttAnimVPitchPerTick = 0.0;
    private double ttAnimRYawPerTick = 0.0;
    private double ttAnimRPitchPerTick = 0.0;
    private int ttAnimTicksRemaining = 0;

    private void handleTestTransform(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can run this subcommand.");
            return;
        }

        if (!(sender.hasPermission("sanscraft.bde.testtransform"))) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use test_transform.");
            return;
        }

        Player player = (Player) sender;

        if (args.length < 2) {
            sendTestTransformUsage(player);
            return;
        }

        String sub = args[1].toLowerCase();

        switch (sub) {
            case "clear":
                stopTestAnimation();
                removeTestMarkers();
                ttVehicleYaw = ttVehiclePitch = ttRelativeYaw = ttRelativePitch = 0.0;
                ttRootLocation = null;
                player.sendMessage("§aCleared all test transform state and markers.");
                return;

            case "stop":
                stopTestAnimation();
                player.sendMessage("§aStopped all animations. Current values:");
                sendTestValues(player);
                return;

            case "reset":
                stopTestAnimation();
                ttVehicleYaw = ttVehiclePitch = ttRelativeYaw = ttRelativePitch = 0.0;
                refreshTestMarkers(player);
                player.sendMessage("§aReset all values to 0. Markers updated.");
                sendTestValues(player);
                return;

            case "status":
                sendTestValues(player);
                if (ttAnimTask != null) {
                    player.sendMessage("§eAnimation active — " + ttAnimTicksRemaining + " ticks remaining.");
                } else {
                    player.sendMessage("§7No animation running.");
                }
                return;

            case "set":
                // /bde test_transform set <vyaw> <vpitch> <ryaw> <rpitch>
                stopTestAnimation();
                if (args.length >= 3) try { ttVehicleYaw = Double.parseDouble(args[2]); } catch (NumberFormatException ignored) {}
                if (args.length >= 4) try { ttVehiclePitch = Double.parseDouble(args[3]); } catch (NumberFormatException ignored) {}
                if (args.length >= 5) try { ttRelativeYaw = Double.parseDouble(args[4]); } catch (NumberFormatException ignored) {}
                if (args.length >= 6) try { ttRelativePitch = Double.parseDouble(args[5]); } catch (NumberFormatException ignored) {}
                if (ttRootLocation == null) {
                    ttRootLocation = player.getLocation().clone();
                    ttRootLocation.setPitch(0.0f);
                    ttRootLocation.setYaw(0.0f);
                }
                refreshTestMarkers(player);
                sendTestValues(player);
                return;

            case "animate":
                // /bde test_transform animate <field> <totalDelta> <seconds>
                if (args.length < 5) {
                    player.sendMessage("§cUsage: /bde test_transform animate <vyaw|vpitch|ryaw|rpitch> <delta> <seconds>");
                    player.sendMessage("§7Example: §f/bde test_transform animate vyaw 360 10");
                    player.sendMessage("§7  → rotates vehicle yaw by 360° over 10 seconds");
                    return;
                }
                String field = args[2].toLowerCase();
                double delta;
                double seconds;
                try {
                    delta = Double.parseDouble(args[3]);
                    seconds = Double.parseDouble(args[4]);
                } catch (NumberFormatException e) {
                    player.sendMessage("§cInvalid numbers. Usage: animate <field> <delta> <seconds>");
                    return;
                }
                if (seconds <= 0) {
                    player.sendMessage("§cSeconds must be positive.");
                    return;
                }
                int totalTicks = (int) (seconds * 20.0);
                double perTick = delta / totalTicks;

                switch (field) {
                    case "vyaw":  ttAnimVYawPerTick = perTick; break;
                    case "vpitch": ttAnimVPitchPerTick = perTick; break;
                    case "ryaw":  ttAnimRYawPerTick = perTick; break;
                    case "rpitch": ttAnimRPitchPerTick = perTick; break;
                    default:
                        player.sendMessage("§cUnknown field: §f" + field + "§c. Use: vyaw, vpitch, ryaw, rpitch");
                        return;
                }

                if (ttRootLocation == null) {
                    ttRootLocation = player.getLocation().clone();
                    ttRootLocation.setPitch(0.0f);
                    ttRootLocation.setYaw(0.0f);
                }

                // Start or extend animation
                ttAnimTicksRemaining = Math.max(ttAnimTicksRemaining, totalTicks);
                if (ttAnimTask == null) {
                    startTestAnimation(player);
                }

                player.sendMessage("§aAnimating §e" + field + "§a by §f" + delta + "°§a over §f" + seconds + "s§a (" + totalTicks + " ticks, " + String.format("%.4f", perTick) + "°/tick)");
                sendTestValues(player);
                return;

            default:
                // Try to parse as legacy: /bde test_transform <vYaw> <vPitch> <rYaw> <rPitch>
                stopTestAnimation();
                try { ttVehicleYaw = Double.parseDouble(args[1]); } catch (NumberFormatException e) {
                    sendTestTransformUsage(player);
                    return;
                }
                if (args.length >= 3) try { ttVehiclePitch = Double.parseDouble(args[2]); } catch (NumberFormatException ignored) {}
                if (args.length >= 4) try { ttRelativeYaw = Double.parseDouble(args[3]); } catch (NumberFormatException ignored) {}
                if (args.length >= 5) try { ttRelativePitch = Double.parseDouble(args[4]); } catch (NumberFormatException ignored) {}
                if (ttRootLocation == null) {
                    ttRootLocation = player.getLocation().clone();
                    ttRootLocation.setPitch(0.0f);
                    ttRootLocation.setYaw(0.0f);
                }
                refreshTestMarkers(player);
                sendTestValues(player);
                return;
        }
    }

    private void sendTestTransformUsage(Player player) {
        player.sendMessage("§b=== Test Transform Commands ===");
        player.sendMessage("§e/bde test_transform set <vYaw> <vPitch> <rYaw> <rPitch>");
        player.sendMessage("§7  Set values and spawn/update markers");
        player.sendMessage("§e/bde test_transform animate <field> <delta> <seconds>");
        player.sendMessage("§7  Smoothly animate a field. Fields: vyaw, vpitch, ryaw, rpitch");
        player.sendMessage("§7  Example: animate vyaw 360 10 → full rotation over 10s");
        player.sendMessage("§e/bde test_transform stop §7— pause animation");
        player.sendMessage("§e/bde test_transform reset §7— reset all values to 0");
        player.sendMessage("§e/bde test_transform status §7— show current values");
        player.sendMessage("§e/bde test_transform clear §7— remove all markers and reset");
    }

    private void sendTestValues(Player player) {
        player.sendMessage("§b=== Current Transform Values ===");
        player.sendMessage("§fVehicle Yaw/Pitch: §7" + String.format("%.2f", ttVehicleYaw) + " / " + String.format("%.2f", ttVehiclePitch));
        player.sendMessage("§fAiming Yaw/Pitch:  §7" + String.format("%.2f", ttRelativeYaw) + " / " + String.format("%.2f", ttRelativePitch));
        if (ttRootLocation != null) {
            player.sendMessage("§eRoot: §f" + fmt(ttRootLocation));
        }
        if (ttMarkerPivot != null && ttMarkerPivot.isValid()) {
            player.sendMessage("§3Pivot: §f" + fmt(ttMarkerPivot.getLocation()));
        }
        if (ttMarkerSeat != null && ttMarkerSeat.isValid()) {
            player.sendMessage("§c Seat: §e" + fmt(ttMarkerSeat.getLocation()));
        }
        if (ttMarkerMuzzle != null && ttMarkerMuzzle.isValid()) {
            player.sendMessage("§6Muzzle: §e" + fmt(ttMarkerMuzzle.getLocation()));
        }
    }

    private void startTestAnimation(Player player) {
        // Ensure markers exist
        ensureTestMarkers(player);

        ttAnimTask = new org.bukkit.scheduler.BukkitRunnable() {
            @Override
            public void run() {
                if (ttAnimTicksRemaining <= 0) {
                    // Animation complete — stop all per-tick increments
                    ttAnimVYawPerTick = ttAnimVPitchPerTick = ttAnimRYawPerTick = ttAnimRPitchPerTick = 0.0;
                    stopTestAnimation();
                    player.sendMessage("§aAnimation complete.");
                    sendTestValues(player);
                    return;
                }

                // Apply increments
                ttVehicleYaw += ttAnimVYawPerTick;
                ttVehiclePitch += ttAnimVPitchPerTick;
                ttRelativeYaw += ttAnimRYawPerTick;
                ttRelativePitch += ttAnimRPitchPerTick;
                ttAnimTicksRemaining--;

                // Clamp pitch to [-90, 90] range
                ttVehiclePitch = Math.max(-90.0, Math.min(90.0, ttVehiclePitch));
                ttRelativePitch = Math.max(-90.0, Math.min(90.0, ttRelativePitch));

                // Wrap yaw to [-180, 180]
                ttVehicleYaw = wrapAngle(ttVehicleYaw);
                ttRelativeYaw = wrapAngle(ttRelativeYaw);

                // Recalculate and teleport markers
                updateTestMarkerPositions();
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void stopTestAnimation() {
        if (ttAnimTask != null) {
            ttAnimTask.cancel();
            ttAnimTask = null;
        }
        ttAnimVYawPerTick = ttAnimVPitchPerTick = ttAnimRYawPerTick = ttAnimRPitchPerTick = 0.0;
        ttAnimTicksRemaining = 0;
    }

    private void removeTestMarkers() {
        if (ttMarkerRoot != null && ttMarkerRoot.isValid()) ttMarkerRoot.remove();
        if (ttMarkerPivot != null && ttMarkerPivot.isValid()) ttMarkerPivot.remove();
        if (ttMarkerSeat != null && ttMarkerSeat.isValid()) ttMarkerSeat.remove();
        if (ttMarkerMuzzle != null && ttMarkerMuzzle.isValid()) ttMarkerMuzzle.remove();
        ttMarkerRoot = ttMarkerPivot = ttMarkerSeat = ttMarkerMuzzle = null;

        // Also clean up any orphaned tagged entities
        if (ttRootLocation != null) {
            for (org.bukkit.entity.Entity e : ttRootLocation.getWorld().getEntities()) {
                if (e.getScoreboardTags().contains("bde_test_transform")) {
                    e.remove();
                }
            }
        }
    }

    private void ensureTestMarkers(Player player) {
        if (ttRootLocation == null) {
            ttRootLocation = player.getLocation().clone();
            ttRootLocation.setPitch(0.0f);
            ttRootLocation.setYaw(0.0f);
        }

        if (ttMarkerRoot == null || !ttMarkerRoot.isValid()) {
            ttMarkerRoot = spawnTestMarker(ttRootLocation, Material.GRAY_CONCRETE, 0.5f, "Vehicle Root");
        }
        if (ttMarkerPivot == null || !ttMarkerPivot.isValid()) {
            ttMarkerPivot = spawnTestMarker(ttRootLocation, Material.LIGHT_BLUE_CONCRETE, 0.3f, "Pivot Point");
        }
        if (ttMarkerSeat == null || !ttMarkerSeat.isValid()) {
            ttMarkerSeat = spawnTestMarker(ttRootLocation, Material.RED_CONCRETE, 0.35f, "Spectator Seat");
        }
        if (ttMarkerMuzzle == null || !ttMarkerMuzzle.isValid()) {
            ttMarkerMuzzle = spawnTestMarker(ttRootLocation, Material.GOLD_BLOCK, 0.3f, "Muzzle (0,0,2)");
        }
    }

    private void refreshTestMarkers(Player player) {
        ensureTestMarkers(player);
        updateTestMarkerPositions();
    }

    private void updateTestMarkerPositions() {
        if (ttRootLocation == null) return;

        List<Double> mountOffset = Arrays.asList(2.0, 1.0, -3.0);
        List<Double> passengerOffset = Arrays.asList(0.0, 0.5, 1.0);
        List<Double> pivotOffset = Arrays.asList(0.5, 0.0, -0.5);
        List<Double> componentOffset = Arrays.asList(0.0, 0.0, 2.0);
        double scale = 2.0;
        double frontYawOffset = 0.0;

        Location seatLoc = top.sanscraft.bde.manager.ModelTransformEngine.getSubsystemSeatPosition(
            ttRootLocation, mountOffset, passengerOffset, pivotOffset,
            scale, frontYawOffset, ttVehicleYaw, ttVehiclePitch, ttRelativeYaw, ttRelativePitch
        );

        Location compLoc = top.sanscraft.bde.manager.ModelTransformEngine.getSubsystemComponentPosition(
            ttRootLocation, mountOffset, componentOffset, null,
            scale, frontYawOffset, ttVehicleYaw, ttVehiclePitch, ttRelativeYaw, ttRelativePitch, pivotOffset
        );

        Location pivotLoc = top.sanscraft.bde.manager.ModelTransformEngine.getSubsystemSeatPosition(
            ttRootLocation, mountOffset, pivotOffset, pivotOffset,
            scale, frontYawOffset, ttVehicleYaw, ttVehiclePitch, 0.0, 0.0
        );

        // Teleport markers
        if (ttMarkerRoot != null && ttMarkerRoot.isValid()) ttMarkerRoot.teleport(ttRootLocation);
        if (ttMarkerPivot != null && ttMarkerPivot.isValid()) ttMarkerPivot.teleport(pivotLoc);
        if (ttMarkerSeat != null && ttMarkerSeat.isValid()) ttMarkerSeat.teleport(seatLoc);
        if (ttMarkerMuzzle != null && ttMarkerMuzzle.isValid()) ttMarkerMuzzle.teleport(compLoc);
    }

    private double wrapAngle(double angle) {
        angle = angle % 360.0;
        if (angle > 180.0) angle -= 360.0;
        if (angle <= -180.0) angle += 360.0;
        return angle;
    }

    private org.bukkit.entity.BlockDisplay spawnTestMarker(Location loc, Material material, float markerScale, String label) {
        org.bukkit.entity.BlockDisplay display = loc.getWorld().spawn(loc, org.bukkit.entity.BlockDisplay.class);
        display.setBlock(material.createBlockData());
        float half = markerScale / 2.0f;
        org.bukkit.util.Transformation t = new org.bukkit.util.Transformation(
            new org.joml.Vector3f(-half, -half, -half),
            new org.joml.Quaternionf(),
            new org.joml.Vector3f(markerScale, markerScale, markerScale),
            new org.joml.Quaternionf()
        );
        display.setTransformation(t);
        display.setGlowing(true);
        display.addScoreboardTag("bde_test_transform");
        display.setCustomName("§e" + label);
        display.setCustomNameVisible(true);
        display.setGravity(false);
        return display;
    }

    private String fmt(Location loc) {
        return String.format("%.4f, %.4f, %.4f", loc.getX(), loc.getY(), loc.getZ());
    }

    private void collectFiles(File dir, String currentPath, List<String> list) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String name = f.getName();
            String rel = currentPath.isEmpty() ? name : currentPath + "/" + name;
            if (f.isDirectory()) {
                list.add(rel + "/");
                collectFiles(f, rel, list);
            } else if (name.endsWith(".json")) {
                list.add(rel);
                list.add(rel.substring(0, rel.length() - 5));
            }
        }
    }
}
