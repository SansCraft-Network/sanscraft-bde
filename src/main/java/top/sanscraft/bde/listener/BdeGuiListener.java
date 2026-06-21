package top.sanscraft.bde.listener;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import top.sanscraft.bde.SansCraftBDEPlugin;
import top.sanscraft.bde.converter.BlockbenchConverter;
import top.sanscraft.bde.converter.ConversionMapper;
import top.sanscraft.bde.converter.VoxConverter;
import top.sanscraft.bde.gui.BdeGuiHolder;
import top.sanscraft.bde.manager.CustomBlockManager;
import top.sanscraft.bde.model.BdeModel;
import top.sanscraft.bde.model.ModelInstance;

import org.bukkit.entity.Entity;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.io.File;
import java.io.FileWriter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class BdeGuiListener implements Listener {
    private final SansCraftBDEPlugin plugin;
    private final Map<UUID, ChatPromptState> activePrompts = new java.util.concurrent.ConcurrentHashMap<>();

    public enum ChatPromptType {
        CREATE_SUBDIRECTORY,
        RENAME_VEHICLE,
        RENAME_SEAT,
        SAVE_VEHICLE_AS,
        SET_SEAT_YAW
    }

    public static class ChatPromptState {
        public final ChatPromptType type;
        public final String modelProjectId;
        public final UUID instanceId;
        public final Integer seatIndex;

        public ChatPromptState(ChatPromptType type, String modelProjectId, UUID instanceId, Integer seatIndex) {
            this.type = type;
            this.modelProjectId = modelProjectId;
            this.instanceId = instanceId;
            this.seatIndex = seatIndex;
        }
    }

    public BdeGuiListener(SansCraftBDEPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Interaction)) return;
        Interaction root = (Interaction) event.getRightClicked();
        if (!root.getScoreboardTags().contains("bde_root")) return;

        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        
        // Retrieve selector wand item from config
        String selectorMatStr = plugin.getConfig().getString("gui.selector-tool", "BLAZE_ROD");
        Material selectorMat = Material.matchMaterial(selectorMatStr);
        if (selectorMat == null) selectorMat = Material.BLAZE_ROD;

        if (hand.getType() == selectorMat) {
            event.setCancelled(true);

            ModelInstance instance = plugin.getModelManager().getInstanceByRoot(root);
            if (instance == null) return;

            UUID selectedId = plugin.getBdeGuiManager().getSelectedModel(player.getUniqueId());
            if (instance.getId().equals(selectedId)) {
                if (player.isSneaking()) {
                    plugin.getBdeGuiManager().clearSelection(player.getUniqueId());
                    player.sendMessage("§aDeselected BDE model.");
                } else {
                    // Already selected, open GUI
                    plugin.getBdeGuiManager().openMainMenu(player, instance);
                }
            } else {
                // Select and highlight
                plugin.getBdeGuiManager().selectModel(player, instance.getId());
            }
        } else {
            // Player right-clicked the model without the selector tool -> mount the vehicle!
            ModelInstance instance = plugin.getModelManager().getInstanceByRoot(root);
            if (instance != null) {
                event.setCancelled(true);
                plugin.getModelManager().mountPlayer(instance, player);
            }
        }
    }

    @EventHandler
    public void onPlayerInteract(org.bukkit.event.player.PlayerInteractEvent event) {
        if (event.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_AIR || event.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
            Player player = event.getPlayer();
            ItemStack hand = player.getInventory().getItemInMainHand();
            
            String selectorMatStr = plugin.getConfig().getString("gui.selector-tool", "BLAZE_ROD");
            Material selectorMat = Material.matchMaterial(selectorMatStr);
            if (selectorMat == null) selectorMat = Material.BLAZE_ROD;

            if (hand.getType() == selectorMat) {
                if (player.isSneaking()) {
                    UUID selectedId = plugin.getBdeGuiManager().getSelectedModel(player.getUniqueId());
                    if (selectedId != null) {
                        plugin.getBdeGuiManager().clearSelection(player.getUniqueId());
                        player.sendMessage("§aDeselected BDE model.");
                        event.setCancelled(true);
                    }
                }
            }
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory inv = event.getClickedInventory();
        if (inv == null || !(inv.getHolder() instanceof BdeGuiHolder)) return;

        event.setCancelled(true);
        Player player = (Player) event.getWhoClicked();
        BdeGuiHolder holder = (BdeGuiHolder) inv.getHolder();
        ItemStack clickedItem = event.getCurrentItem();
        if (clickedItem == null || clickedItem.getType() == Material.AIR) return;

        UUID modelId = holder.getSelectedModelId();
        ModelInstance instance = modelId != null ? plugin.getModelManager().getActiveInstances().get(modelId) : null;

        switch (holder.getGuiType()) {
            case MAIN_MENU:
                handleMainMenuClick(player, instance, event.getSlot());
                break;
            case MOVEMENT:
                handleMovementMenuClick(player, instance, event.getSlot(), event.isShiftClick());
                break;
            case VEHICLE:
                handleVehicleMenuClick(player, instance, holder, event.getSlot(), event.isShiftClick(), event.getClick().isRightClick(), event);
                break;
            case BLOCKS:
                handleBlocksMenuClick(player, instance, event.getSlot(), event.isShiftClick());
                break;
            case ANIMATIONS:
                handleAnimationsMenuClick(player, instance, event.getSlot(), clickedItem, event.isShiftClick());
                break;
            case CONVERTER:
                handleConverterMenuClick(player, event.getSlot(), clickedItem, event.isShiftClick());
                break;
            case VEHICLES_CATALOG:
                handleVehiclesCatalogClick(player, holder, event.getSlot(), clickedItem, event);
                break;
            case SEAT_SELECTION:
                handleSeatSelectionClick(player, holder, event.getSlot(), clickedItem);
                break;
            case SEAT_CONFIGURATION:
                handleSeatConfigurationClick(player, instance, holder, event.getSlot(), clickedItem, event);
                break;
            case SEAT_DETAIL:
                handleSeatDetailClick(player, instance, holder, event.getSlot(), clickedItem, event);
                break;
        }
    }

    private void handleMainMenuClick(Player player, ModelInstance instance, int slot) {
        if (instance == null) {
            player.closeInventory();
            return;
        }

        switch (slot) {
            case 20: // Blocks Link Menu
                plugin.getBdeGuiManager().openBlocksMenu(player, instance);
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.5f, 1.0f);
                break;
            case 21: // Movement & Rotation
                plugin.getBdeGuiManager().openMovementMenu(player, instance);
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.5f, 1.0f);
                break;
            case 23: // Vehicle Config
                plugin.getBdeGuiManager().openVehicleMenu(player, instance);
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.5f, 1.0f);
                break;
            case 24: // Animations
                plugin.getBdeGuiManager().openAnimationsMenu(player, instance);
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.5f, 1.0f);
                break;
            case 40: // Despawn Model
                plugin.getBdeGuiManager().clearSelection(player.getUniqueId());
                plugin.getModelManager().removeInstance(instance.getId());
                player.closeInventory();
                player.sendMessage("§cModel despawned successfully.");
                player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.5f, 1.0f);
                break;
            case 49: // Close
                player.closeInventory();
                break;
        }
    }

    private void handleMovementMenuClick(Player player, ModelInstance instance, int slot, boolean isShift) {
        if (instance == null) {
            player.closeInventory();
            return;
        }

        Location loc = instance.getLocation();
        double delta = isShift ? 1.0 : 0.1;

        switch (slot) {
            case 45: // Back
                plugin.getBdeGuiManager().openMainMenu(player, instance);
                return;
            case 49: // Close
                player.closeInventory();
                return;

            // Move -
            case 19: // Move X -
                loc.add(-delta, 0, 0);
                plugin.getModelManager().teleportModel(instance, loc);
                plugin.getBdeGuiManager().openMovementMenu(player, instance);
                player.playSound(player.getLocation(), Sound.ENTITY_ITEM_FRAME_ROTATE_ITEM, 0.5f, 0.8f);
                break;
            case 20: // Move Y -
                loc.add(0, -delta, 0);
                plugin.getModelManager().teleportModel(instance, loc);
                plugin.getBdeGuiManager().openMovementMenu(player, instance);
                player.playSound(player.getLocation(), Sound.ENTITY_ITEM_FRAME_ROTATE_ITEM, 0.5f, 0.8f);
                break;
            case 21: // Move Z -
                loc.add(0, 0, -delta);
                plugin.getModelManager().teleportModel(instance, loc);
                plugin.getBdeGuiManager().openMovementMenu(player, instance);
                player.playSound(player.getLocation(), Sound.ENTITY_ITEM_FRAME_ROTATE_ITEM, 0.5f, 0.8f);
                break;

            // Move +
            case 23: // Move X +
                loc.add(delta, 0, 0);
                plugin.getModelManager().teleportModel(instance, loc);
                plugin.getBdeGuiManager().openMovementMenu(player, instance);
                player.playSound(player.getLocation(), Sound.ENTITY_ITEM_FRAME_ROTATE_ITEM, 0.5f, 1.2f);
                break;
            case 24: // Move Y +
                loc.add(0, delta, 0);
                plugin.getModelManager().teleportModel(instance, loc);
                plugin.getBdeGuiManager().openMovementMenu(player, instance);
                player.playSound(player.getLocation(), Sound.ENTITY_ITEM_FRAME_ROTATE_ITEM, 0.5f, 1.2f);
                break;
            case 25: // Move Z +
                loc.add(0, 0, delta);
                plugin.getModelManager().teleportModel(instance, loc);
                plugin.getBdeGuiManager().openMovementMenu(player, instance);
                player.playSound(player.getLocation(), Sound.ENTITY_ITEM_FRAME_ROTATE_ITEM, 0.5f, 1.2f);
                break;

            // Yaw
            case 29: // Yaw -15
                plugin.getModelManager().rotateModel(instance, -15.0f);
                plugin.getBdeGuiManager().openMovementMenu(player, instance);
                player.playSound(player.getLocation(), Sound.ENTITY_ITEM_FRAME_ROTATE_ITEM, 0.5f, 0.9f);
                break;
            case 30: // Yaw -90
                plugin.getModelManager().rotateModel(instance, -90.0f);
                plugin.getBdeGuiManager().openMovementMenu(player, instance);
                player.playSound(player.getLocation(), Sound.ENTITY_ITEM_FRAME_ROTATE_ITEM, 0.5f, 0.8f);
                break;
            case 32: // Yaw +90
                plugin.getModelManager().rotateModel(instance, 90.0f);
                plugin.getBdeGuiManager().openMovementMenu(player, instance);
                player.playSound(player.getLocation(), Sound.ENTITY_ITEM_FRAME_ROTATE_ITEM, 0.5f, 1.2f);
                break;
            case 33: // Yaw +15
                plugin.getModelManager().rotateModel(instance, 15.0f);
                plugin.getBdeGuiManager().openMovementMenu(player, instance);
                player.playSound(player.getLocation(), Sound.ENTITY_ITEM_FRAME_ROTATE_ITEM, 0.5f, 1.1f);
                break;

            // Pitch
            case 38: // Pitch -15
                plugin.getModelManager().rotatePitch(instance, -15.0f);
                plugin.getBdeGuiManager().openMovementMenu(player, instance);
                player.playSound(player.getLocation(), Sound.ENTITY_ITEM_FRAME_ROTATE_ITEM, 0.5f, 0.9f);
                break;
            case 39: // Pitch -90
                plugin.getModelManager().rotatePitch(instance, -90.0f);
                plugin.getBdeGuiManager().openMovementMenu(player, instance);
                player.playSound(player.getLocation(), Sound.ENTITY_ITEM_FRAME_ROTATE_ITEM, 0.5f, 0.8f);
                break;
            case 41: // Pitch +90
                plugin.getModelManager().rotatePitch(instance, 90.0f);
                plugin.getBdeGuiManager().openMovementMenu(player, instance);
                player.playSound(player.getLocation(), Sound.ENTITY_ITEM_FRAME_ROTATE_ITEM, 0.5f, 1.2f);
                break;
            case 42: // Pitch +15
                plugin.getModelManager().rotatePitch(instance, 15.0f);
                plugin.getBdeGuiManager().openMovementMenu(player, instance);
                player.playSound(player.getLocation(), Sound.ENTITY_ITEM_FRAME_ROTATE_ITEM, 0.5f, 1.1f);
                break;
        }
    }

    private void handleVehicleMenuClick(Player player, ModelInstance instance, BdeGuiHolder holder, int slot, boolean isShift, boolean isRightClick, InventoryClickEvent event) {
        String modelProjectId = holder.getModelProjectId();
        if (modelProjectId == null && instance != null) {
            modelProjectId = instance.getModel().getProjectId();
        }

        BdeModel model = null;
        if (instance != null) {
            model = instance.getModel();
        } else if (modelProjectId != null) {
            try {
                model = plugin.getModelManager().loadModelSync(modelProjectId);
            } catch (Exception ex) {
                player.sendMessage("§cFailed to load vehicle model config: " + ex.getMessage());
                player.closeInventory();
                return;
            }
        }

        if (model == null) {
            player.closeInventory();
            return;
        }

        BdeModel.VehicleStats stats = model.getVehicleStats();

        switch (slot) {
            case 45: // Back
                if (instance != null) {
                    plugin.getBdeGuiManager().openMainMenu(player, instance);
                } else {
                    File currentDir = plugin.getBdeGuiManager().getPlayerCatalogDirectory(player.getUniqueId());
                    plugin.getBdeGuiManager().openVehiclesCatalog(player, currentDir);
                }
                return;
            case 49: // Close
                player.closeInventory();
                return;

            case 13: // Toggle/Cycle Vehicle Mode
                if (stats == null) {
                    BdeModel.VehicleStats newStats = new BdeModel.VehicleStats();
                    model.setVehicleStats(newStats);
                    model.setType("armor_stand");
                } else {
                    if (isShift) {
                        model.setVehicleStats(null);
                        model.setType("armor_stand");
                    } else {
                        String currentType = model.getType() != null ? model.getType().toLowerCase() : "armor_stand";
                        if (currentType.contains("minecart")) {
                            model.setType("boat");
                        } else if (currentType.contains("boat")) {
                            model.setType("armor_stand");
                        } else {
                            model.setType("minecart");
                        }
                    }
                }
                if (model.getLocalFilePath() != null && model.isVehicleLibrary()) {
                    plugin.getModelManager().saveModelConfig(model);
                }
                if (instance != null) {
                    recreateModelInstance(instance, player);
                } else {
                    plugin.getBdeGuiManager().openVehicleMenu(player, null, model, modelProjectId);
                }
                return;

            case 14: // Rename catalog name
                if (stats != null && !isRightClick) {
                    player.closeInventory();
                    player.sendMessage("§ePlease type the new catalog name in chat (or type 'cancel' to exit):");
                    activePrompts.put(player.getUniqueId(), new ChatPromptState(ChatPromptType.RENAME_VEHICLE, modelProjectId, holder.getSelectedModelId(), null));
                }
                return;

            case 15: // Set catalog icon using cursor item
                if (stats != null) {
                    ItemStack cursorItem = event.getCursor();
                    if (cursorItem != null && cursorItem.getType() != Material.AIR) {
                        BdeModel.VehicleConfig cfg = model.getVehicle();
                        if (cfg == null) {
                            model.ensureVehicleConfig();
                            cfg = model.getVehicle();
                        }
                        String matName = cursorItem.getType().name();
                        cfg.setIcon(matName);
                        if (model.getLocalFilePath() != null && model.isVehicleLibrary()) {
                            plugin.getModelManager().saveModelConfig(model);
                        }
                        player.sendMessage("§aVehicle catalog icon updated to " + matName);
                        plugin.getBdeGuiManager().openVehicleMenu(player, instance, model, modelProjectId);
                    } else {
                        player.sendMessage("§cPlease hold the item you want to use as an icon on your cursor, then click this slot.");
                    }
                }
                return;

            case 16: // Configure seats
                if (stats != null) {
                    plugin.getBdeGuiManager().openSeatConfigMenu(player, instance, model, modelProjectId);
                }
                return;

            case 17: // Adjust Driving Front (Yaw Offset)
                if (stats != null) {
                    BdeModel.VehicleConfig cfg = model.getVehicle();
                    if (cfg == null) {
                        model.ensureVehicleConfig();
                        cfg = model.getVehicle();
                    }
                    double offset = cfg.getFrontYawOffset();
                    double newOffset;
                    if (isRightClick) {
                        newOffset = (offset - 90.0 + 360.0) % 360.0;
                    } else {
                        newOffset = (offset + 90.0) % 360.0;
                    }
                    cfg.setFrontYawOffset(newOffset);
                    if (model.getLocalFilePath() != null && model.isVehicleLibrary()) {
                        plugin.getModelManager().saveModelConfig(model);
                    }
                    if (instance != null) {
                        recreateModelInstance(instance, player);
                    } else {
                        plugin.getBdeGuiManager().openVehicleMenu(player, null, model, modelProjectId);
                    }
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, isRightClick ? 0.8f : 1.2f);
                }
                return;

            case 52: // Save vehicle configuration
                if (stats != null) {
                    if (model.getLocalFilePath() != null && model.isVehicleLibrary()) {
                        plugin.getModelManager().saveModelConfig(model);
                        player.sendMessage("§aVehicle configuration saved successfully to vehicles/" + model.getLocalFilePath() + ".json");
                        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1f);
                        plugin.getBdeGuiManager().openVehicleMenu(player, instance, model, modelProjectId);
                    } else {
                        player.sendMessage("§cThis vehicle is not saved yet. Please use 'Save As' to write it.");
                    }
                }
                return;

            case 53: // Save vehicle configuration as...
                if (stats != null) {
                    player.closeInventory();
                    player.sendMessage("§ePlease type the file path/name you want to save this vehicle to (e.g. cars/sport_car) or type 'cancel' to exit:");
                    activePrompts.put(player.getUniqueId(), new ChatPromptState(ChatPromptType.SAVE_VEHICLE_AS, modelProjectId, holder.getSelectedModelId(), null));
                }
                return;
        }

        if (stats == null) return;

        double factor = isRightClick ? -1.0 : 1.0;
        switch (slot) {
            case 29: // Acceleration
                stats.setAcceleration(Math.max(0.0, stats.getAcceleration() + 0.005 * factor));
                break;
            case 30: // Deceleration
                stats.setDeceleration(Math.max(0.0, stats.getDeceleration() + 0.005 * factor));
                break;
            case 31: // Top Speed
                stats.setTopSpeed(Math.max(0.0, stats.getTopSpeed() + 0.05 * factor));
                break;
            case 32: // Reverse Speed
                stats.setReverseSpeed(Math.max(0.0, stats.getReverseSpeed() + 0.02 * factor));
                break;
            case 33: // Turn Speed
                stats.setTurnSpeed(Math.max(0.0, stats.getTurnSpeed() + 0.5 * factor));
                break;
            default:
                return;
        }

        model.setVehicleStats(stats);
        if (model.getLocalFilePath() != null && model.isVehicleLibrary()) {
            plugin.getModelManager().saveModelConfig(model);
        }
        plugin.getBdeGuiManager().openVehicleMenu(player, instance, model, modelProjectId);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, isRightClick ? 0.8f : 1.2f);
    }

    private void handleVehiclesCatalogClick(Player player, BdeGuiHolder holder, int slot, ItemStack clickedItem, InventoryClickEvent event) {
        if (slot == 49) { // Close
            player.closeInventory();
            return;
        }

        File currentDir = plugin.getBdeGuiManager().getPlayerCatalogDirectory(player.getUniqueId());
        File rootDir = new File(plugin.getDataFolder(), "vehicles");

        if (slot == 45) { // Up to Parent Folder
            if (!currentDir.equals(rootDir)) {
                File parent = currentDir.getParentFile();
                if (parent != null) {
                    plugin.getBdeGuiManager().openVehiclesCatalog(player, parent);
                }
            }
            return;
        }

        if (slot == 46) { // Create Subdirectory
            player.closeInventory();
            player.sendMessage("§ePlease type the folder name you want to create in chat (or type 'cancel' to exit):");
            activePrompts.put(player.getUniqueId(), new ChatPromptState(ChatPromptType.CREATE_SUBDIRECTORY, null, null, null));
            return;
        }

        if (clickedItem.getType() == Material.CHEST) {
            String folderName = ChatColor.stripColor(clickedItem.getItemMeta().getDisplayName()).replace("[Folder] ", "").trim();
            File newDir = new File(currentDir, folderName);
            plugin.getBdeGuiManager().openVehiclesCatalog(player, newDir);
            return;
        }

        // It is a vehicle json model file
        String relPath = null;
        if (clickedItem.hasItemMeta() && clickedItem.getItemMeta().hasLore()) {
            for (String line : clickedItem.getItemMeta().getLore()) {
                if (line.startsWith("§8Path: ")) {
                    relPath = ChatColor.stripColor(line).replace("Path: ", "").trim();
                    break;
                }
            }
        }

        if (relPath != null) {
            final String finalPath = relPath;
            if (event.getClick().isRightClick()) {
                // Right click: open vehicle config editor directly on the file
                try {
                    BdeModel model = plugin.getModelManager().loadModelSync(finalPath.replace(".json", ""));
                    if (model != null) {
                        plugin.getBdeGuiManager().openVehicleMenu(player, null, model, finalPath.replace(".json", ""));
                    }
                } catch (Exception ex) {
                    player.sendMessage("§cFailed to load vehicle configuration: " + ex.getMessage());
                }
            } else {
                // Left click: spawn vehicle
                player.closeInventory();
                player.sendMessage("§eSpawning model " + finalPath + "...");
                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        BdeModel model = plugin.getModelManager().loadModelSync(finalPath.replace(".json", ""));
                        if (model != null) {
                            Location spawnLoc = player.getLocation().clone();
                            
                            // Snap yaw to nearest 90-degree direction and set pitch to 0.0f
                            float playerYaw = spawnLoc.getYaw();
                            float normalizedYaw = (playerYaw % 360 + 360) % 360;
                            float snappedYaw = Math.round(normalizedYaw / 90.0f) * 90.0f;
                            if (snappedYaw >= 360) snappedYaw -= 360;
                            spawnLoc.setYaw(snappedYaw);
                            spawnLoc.setPitch(0.0f);

                            // Auto-align to ground height using downward block raycast and model bounds
                            top.sanscraft.bde.manager.ModelManager.BoundingBox box = top.sanscraft.bde.manager.ModelManager.calculateModelBounds(model, 1.0);
                            double floorY = spawnLoc.getY();
                            Location scanLoc = spawnLoc.clone();
                            double startY = Math.min(scanLoc.getWorld().getMaxHeight(), spawnLoc.getY() + 3.0);
                            double minHeight = scanLoc.getWorld().getMinHeight();
                            for (double scanY = startY; scanY >= minHeight; scanY -= 1.0) {
                                scanLoc.setY(scanY);
                                if (scanLoc.getBlock().getType().isSolid()) {
                                    floorY = Math.floor(scanY) + 1.0;
                                    break;
                                }
                            }
                            spawnLoc.setY(floorY - box.getMinY());

                            plugin.getModelManager().spawnModel(model, spawnLoc, 1.0);
                            player.sendMessage("§aSpawned model successfully!");
                        }
                    } catch (Exception ex) {
                        player.sendMessage("§cFailed to spawn model: " + ex.getMessage());
                    }
                });
            }
        }
    }

    private void handleSeatSelectionClick(Player player, BdeGuiHolder holder, int slot, ItemStack clickedItem) {
        if (slot == 22) { // Close
            player.closeInventory();
            return;
        }

        if (clickedItem.getType() == Material.BARRIER || clickedItem.getType() == Material.BLACK_STAINED_GLASS_PANE) {
            return;
        }

        ModelInstance instance = plugin.getModelManager().getActiveInstances().get(holder.getSelectedModelId());
        if (instance == null) {
            player.closeInventory();
            return;
        }

        boolean isDriver = false;
        int passengerIndex = -1;
        if (clickedItem.hasItemMeta() && clickedItem.getItemMeta().hasLore()) {
            for (String line : clickedItem.getItemMeta().getLore()) {
                String clean = ChatColor.stripColor(line);
                if (clean.contains("Type: driver")) {
                    isDriver = true;
                }
                if (clean.startsWith("Index: ")) {
                    try {
                        passengerIndex = Integer.parseInt(clean.replace("Index: ", "").trim());
                    } catch (NumberFormatException ignored) {}
                }
            }
        }

        player.closeInventory();

        if (isDriver) {
            Entity seat = instance.getDriverSeat() != null ? instance.getDriverSeat() : instance.getVehicleRoot();
            if (seat != null) {
                // Check if already occupied
                if (!seat.getPassengers().isEmpty()) {
                    player.sendMessage("§cThis seat is already occupied.");
                    return;
                }
                seat.addPassenger(player);
                plugin.getModelManager().getInputTracker().inject(player);
                player.sendMessage("§aMounted vehicle as driver! Use WASD to steer, Shift to dismount.");
            }
        } else if (passengerIndex >= 0) {
            List<org.bukkit.entity.ArmorStand> activeSeats = instance.getPassengerSeats();
            org.bukkit.entity.ArmorStand targetSeat = null;
            for (org.bukkit.entity.ArmorStand stand : activeSeats) {
                if (stand.getScoreboardTags().contains("bde_seat_" + passengerIndex)) {
                    targetSeat = stand;
                    break;
                }
            }
            if (targetSeat != null) {
                if (!targetSeat.getPassengers().isEmpty()) {
                    player.sendMessage("§cThis seat is already occupied.");
                    return;
                }
                targetSeat.addPassenger(player);
                player.sendMessage("§aMounted vehicle as co-passenger! Shift to dismount.");
            }
        }
    }

    private void handleSeatConfigurationClick(Player player, ModelInstance instance, BdeGuiHolder holder, int slot, ItemStack clickedItem, InventoryClickEvent event) {
        if (slot == 49) { // Close
            player.closeInventory();
            return;
        }

        String modelProjectId = holder.getModelProjectId();
        if (modelProjectId == null && instance != null) {
            modelProjectId = instance.getModel().getProjectId();
        }

        BdeModel model = null;
        if (instance != null) {
            model = instance.getModel();
        } else if (modelProjectId != null) {
            try {
                model = plugin.getModelManager().loadModelSync(modelProjectId);
            } catch (Exception ex) {
                player.sendMessage("§cFailed to load model configuration: " + ex.getMessage());
                player.closeInventory();
                return;
            }
        }

        if (model == null) {
            player.closeInventory();
            return;
        }

        if (slot == 45) { // Back to Vehicle config Menu
            plugin.getBdeGuiManager().openVehicleMenu(player, instance, model, modelProjectId);
            return;
        }

        BdeModel.VehicleConfig cfg = model.getVehicle();
        if (cfg == null) {
            model.ensureVehicleConfig();
            cfg = model.getVehicle();
        }

        if (slot == 46) { // Add Passenger Seat
            BdeModel.PassengerSeatConfig newSeat = new BdeModel.PassengerSeatConfig();
            newSeat.setName("Passenger Seat " + (cfg.getPassengerSeats().size() + 1));
            newSeat.setIcon("MINECART");
            newSeat.setOffset(java.util.Arrays.asList(0.0, 0.0, 0.0));
            newSeat.setYaw(0.0);
            cfg.getPassengerSeats().add(newSeat);

            if (model.getLocalFilePath() != null && model.isVehicleLibrary()) {
                plugin.getModelManager().saveModelConfig(model);
            }

            if (instance != null) {
                Location currentLoc = instance.getLocation();
                double currentScale = instance.getScale();
                plugin.getBdeGuiManager().clearSelection(player.getUniqueId());
                plugin.getModelManager().removeInstance(instance.getId());
                ModelInstance newInstance = plugin.getModelManager().spawnModel(model, currentLoc, currentScale);
                plugin.getBdeGuiManager().selectModel(player, newInstance.getId());
                plugin.getBdeGuiManager().openSeatConfigMenu(player, newInstance, model, modelProjectId);
            } else {
                plugin.getBdeGuiManager().openSeatConfigMenu(player, null, model, modelProjectId);
            }
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 1.5f);
            player.sendMessage("§aAdded new passenger seat.");
            return;
        }

        if (clickedItem.getType() == Material.BLACK_STAINED_GLASS_PANE || clickedItem.getType() == Material.ARROW) {
            return;
        }

        // Determine which seat was clicked
        List<Integer> slots = java.util.Arrays.asList(
            11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
        );

        int seatIndex = -2;
        if (slot == 10) {
            seatIndex = -1; // Driver
        } else {
            int idx = slots.indexOf(slot);
            if (idx >= 0 && idx < cfg.getPassengerSeats().size()) {
                seatIndex = idx;
            }
        }

        if (seatIndex != -2) {
            plugin.getBdeGuiManager().openSeatDetailMenu(player, instance, model, modelProjectId, seatIndex);
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.5f, 1.0f);
        }
    }

    private void handleSeatDetailClick(Player player, ModelInstance instance, BdeGuiHolder holder, int slot, ItemStack clickedItem, InventoryClickEvent event) {
        if (slot == 40) { // Close
            player.closeInventory();
            return;
        }

        String modelProjectId = holder.getModelProjectId();
        if (modelProjectId == null && instance != null) {
            modelProjectId = instance.getModel().getProjectId();
        }

        BdeModel model = null;
        if (instance != null) {
            model = instance.getModel();
        } else if (modelProjectId != null) {
            try {
                model = plugin.getModelManager().loadModelSync(modelProjectId);
            } catch (Exception ex) {
                player.sendMessage("§cFailed to load model configuration: " + ex.getMessage());
                player.closeInventory();
                return;
            }
        }

        if (model == null) {
            player.closeInventory();
            return;
        }

        int seatIndex = holder.getSeatIndex();

        if (slot == 36) { // Back to Seat Configuration Menu
            plugin.getBdeGuiManager().openSeatConfigMenu(player, instance, model, modelProjectId);
            return;
        }

        BdeModel.VehicleConfig cfg = model.getVehicle();
        if (cfg == null) {
            model.ensureVehicleConfig();
            cfg = model.getVehicle();
        }

        if (slot == 20) { // Rename Seat
            player.closeInventory();
            if (seatIndex == -1) {
                player.sendMessage("§ePlease type the new name for the Driver seat in chat (or type 'cancel' to exit):");
            } else {
                player.sendMessage("§ePlease type the new name for passenger seat #" + (seatIndex + 1) + " in chat (or type 'cancel' to exit):");
            }
            activePrompts.put(player.getUniqueId(), new ChatPromptState(ChatPromptType.RENAME_SEAT, modelProjectId, holder.getSelectedModelId(), seatIndex));
            return;
        }

        if (slot == 21) { // Set Seat Icon
            ItemStack cursorItem = event.getCursor();
            if (cursorItem != null && cursorItem.getType() != Material.AIR) {
                String matName = cursorItem.getType().name();
                if (seatIndex == -1) {
                    cfg.setDriverSeatIcon(matName);
                } else if (seatIndex >= 0 && seatIndex < cfg.getPassengerSeats().size()) {
                    cfg.getPassengerSeats().get(seatIndex).setIcon(matName);
                }
                if (model.getLocalFilePath() != null && model.isVehicleLibrary()) {
                    plugin.getModelManager().saveModelConfig(model);
                }
                player.sendMessage("§aSeat icon updated to " + matName);
                plugin.getBdeGuiManager().openSeatDetailMenu(player, instance, model, modelProjectId, seatIndex);
            } else {
                player.sendMessage("§cPlease hold the item you want to use as an icon on your cursor, then click this slot.");
            }
            return;
        }

        if (slot == 22 || slot == 23 || slot == 24) { // Edit Offset X, Y, Z
            double delta = event.isShiftClick() ? 0.01 : 0.1;
            if (event.getClick().isRightClick()) {
                delta = -delta;
            }

            List<Double> offset;
            if (seatIndex == -1) {
                offset = cfg.getSeatOffset() != null ? new java.util.ArrayList<>(cfg.getSeatOffset()) : new java.util.ArrayList<>(java.util.Arrays.asList(0.0, 0.0, 0.0));
            } else {
                List<BdeModel.PassengerSeatConfig> pSeats = cfg.getPassengerSeats();
                if (seatIndex >= 0 && seatIndex < pSeats.size()) {
                    offset = pSeats.get(seatIndex).getOffset() != null ? new java.util.ArrayList<>(pSeats.get(seatIndex).getOffset()) : new java.util.ArrayList<>(java.util.Arrays.asList(0.0, 0.0, 0.0));
                } else {
                    return;
                }
            }

            while (offset.size() < 3) {
                offset.add(0.0);
            }

            int axisIndex = slot - 22; // 0 for X, 1 for Y, 2 for Z
            offset.set(axisIndex, offset.get(axisIndex) + delta);

            if (seatIndex == -1) {
                cfg.setSeatOffset(offset);
            } else {
                cfg.getPassengerSeats().get(seatIndex).setOffset(offset);
            }

            if (model.getLocalFilePath() != null && model.isVehicleLibrary()) {
                plugin.getModelManager().saveModelConfig(model);
            }

            if (instance != null) {
                Location currentLoc = instance.getLocation();
                double currentScale = instance.getScale();
                plugin.getBdeGuiManager().clearSelection(player.getUniqueId());
                plugin.getModelManager().removeInstance(instance.getId());
                ModelInstance newInstance = plugin.getModelManager().spawnModel(model, currentLoc, currentScale);
                plugin.getBdeGuiManager().selectModel(player, newInstance.getId());
                plugin.getBdeGuiManager().openSeatDetailMenu(player, newInstance, model, modelProjectId, seatIndex);
            } else {
                plugin.getBdeGuiManager().openSeatDetailMenu(player, null, model, modelProjectId, seatIndex);
            }
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, event.getClick().isRightClick() ? 0.8f : 1.2f);
            return;
        }

        if (slot == 26) { // Seat Yaw Rotation (Compass)
            double yawVal;
            if (seatIndex == -1) {
                yawVal = cfg.getDriverSeatYaw();
            } else {
                yawVal = cfg.getPassengerSeats().get(seatIndex).getYaw();
            }

            if (event.getClick().isRightClick()) {
                // Prompt chat input for custom double yaw rotation
                player.closeInventory();
                player.sendMessage("§ePlease type the custom seat rotation angle (in degrees, decimals allowed) or type 'cancel' to exit:");
                activePrompts.put(player.getUniqueId(), new ChatPromptState(ChatPromptType.SET_SEAT_YAW, modelProjectId, holder.getSelectedModelId(), seatIndex));
            } else {
                // Left click: cycle cardinally
                double newYaw = (yawVal + 90.0) % 360.0;
                if (seatIndex == -1) {
                    cfg.setDriverSeatYaw(newYaw);
                } else {
                    cfg.getPassengerSeats().get(seatIndex).setYaw(newYaw);
                }

                if (model.getLocalFilePath() != null && model.isVehicleLibrary()) {
                    plugin.getModelManager().saveModelConfig(model);
                }

                if (instance != null) {
                    Location currentLoc = instance.getLocation();
                    double currentScale = instance.getScale();
                    plugin.getBdeGuiManager().clearSelection(player.getUniqueId());
                    plugin.getModelManager().removeInstance(instance.getId());
                    ModelInstance newInstance = plugin.getModelManager().spawnModel(model, currentLoc, currentScale);
                    plugin.getBdeGuiManager().selectModel(player, newInstance.getId());
                    plugin.getBdeGuiManager().openSeatDetailMenu(player, newInstance, model, modelProjectId, seatIndex);
                } else {
                    plugin.getBdeGuiManager().openSeatDetailMenu(player, null, model, modelProjectId, seatIndex);
                }
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 1.2f);
            }
            return;
        }

        if (slot == 25) { // Delete Seat
            if (seatIndex >= 0) {
                List<BdeModel.PassengerSeatConfig> pSeats = cfg.getPassengerSeats();
                if (seatIndex < pSeats.size()) {
                    pSeats.remove(seatIndex);
                    if (model.getLocalFilePath() != null && model.isVehicleLibrary()) {
                        plugin.getModelManager().saveModelConfig(model);
                    }
                    player.sendMessage("§aPassenger seat deleted.");

                    if (instance != null) {
                        Location currentLoc = instance.getLocation();
                        double currentScale = instance.getScale();
                        plugin.getBdeGuiManager().clearSelection(player.getUniqueId());
                        plugin.getModelManager().removeInstance(instance.getId());
                        ModelInstance newInstance = plugin.getModelManager().spawnModel(model, currentLoc, currentScale);
                        plugin.getBdeGuiManager().selectModel(player, newInstance.getId());
                        plugin.getBdeGuiManager().openSeatConfigMenu(player, newInstance, model, modelProjectId);
                    } else {
                        plugin.getBdeGuiManager().openSeatConfigMenu(player, null, model, modelProjectId);
                    }
                    player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_BREAK, 0.5f, 1.0f);
                }
            }
            return;
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        ChatPromptState state = activePrompts.remove(player.getUniqueId());
        if (state == null) return;

        event.setCancelled(true);
        String text = event.getMessage().trim();
        if (text.equalsIgnoreCase("cancel")) {
            player.sendMessage("§cOperation cancelled.");
            reopenMenu(player, state);
            return;
        }

        switch (state.type) {
            case CREATE_SUBDIRECTORY:
                File currentDir = plugin.getBdeGuiManager().getPlayerCatalogDirectory(player.getUniqueId());
                File newDir = new File(currentDir, text);
                if (newDir.exists()) {
                    player.sendMessage("§cFolder already exists.");
                } else {
                    if (newDir.mkdirs()) {
                        player.sendMessage("§aFolder created successfully.");
                    } else {
                        player.sendMessage("§cFailed to create folder.");
                    }
                }
                Bukkit.getScheduler().runTask(plugin, () -> {
                    plugin.getBdeGuiManager().openVehiclesCatalog(player, currentDir);
                });
                break;

            case RENAME_VEHICLE:
                if (state.modelProjectId != null) {
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        try {
                            BdeModel model = plugin.getModelManager().loadModelSync(state.modelProjectId);
                            if (model != null) {
                                BdeModel.VehicleConfig cfg = model.getVehicle();
                                if (cfg == null) {
                                    model.ensureVehicleConfig();
                                    cfg = model.getVehicle();
                                }
                                cfg.setName(text);
                                if (model.getLocalFilePath() != null && model.isVehicleLibrary()) {
                                    plugin.getModelManager().saveModelConfig(model);
                                }
                                player.sendMessage("§aVehicle name updated to " + text);
                            }
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                ModelInstance inst = state.instanceId != null ? plugin.getModelManager().getActiveInstances().get(state.instanceId) : null;
                                try {
                                    BdeModel updatedModel = plugin.getModelManager().loadModelSync(state.modelProjectId);
                                    plugin.getBdeGuiManager().openVehicleMenu(player, inst, updatedModel, state.modelProjectId);
                                } catch (Exception ignored) {}
                            });
                        } catch (Exception ex) {
                            player.sendMessage("§cFailed to rename vehicle: " + ex.getMessage());
                        }
                    });
                }
                break;

            case RENAME_SEAT:
                if (state.modelProjectId != null && state.seatIndex != null) {
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        try {
                            BdeModel model = plugin.getModelManager().loadModelSync(state.modelProjectId);
                            if (model != null) {
                                BdeModel.VehicleConfig cfg = model.getVehicle();
                                if (cfg == null) {
                                    model.ensureVehicleConfig();
                                    cfg = model.getVehicle();
                                }
                                if (state.seatIndex == -1) {
                                    cfg.setDriverSeatName(text);
                                } else {
                                    List<BdeModel.PassengerSeatConfig> pSeats = cfg.getPassengerSeats();
                                    if (state.seatIndex >= 0 && state.seatIndex < pSeats.size()) {
                                        pSeats.get(state.seatIndex).setName(text);
                                    }
                                }
                                if (model.getLocalFilePath() != null && model.isVehicleLibrary()) {
                                    plugin.getModelManager().saveModelConfig(model);
                                }
                                player.sendMessage("§aSeat name updated to " + text);
                            }
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                ModelInstance inst = state.instanceId != null ? plugin.getModelManager().getActiveInstances().get(state.instanceId) : null;
                                try {
                                    BdeModel updatedModel = plugin.getModelManager().loadModelSync(state.modelProjectId);
                                    plugin.getBdeGuiManager().openSeatDetailMenu(player, inst, updatedModel, state.modelProjectId, state.seatIndex);
                                } catch (Exception ignored) {}
                            });
                        } catch (Exception ex) {
                            player.sendMessage("§cFailed to rename seat: " + ex.getMessage());
                        }
                    });
                }
                break;

            case SAVE_VEHICLE_AS:
                if (text.isEmpty() || text.contains("..") || text.startsWith("/")) {
                    player.sendMessage("§cInvalid file path name.");
                    reopenMenu(player, state);
                    break;
                }
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    try {
                        ModelInstance inst = state.instanceId != null ? plugin.getModelManager().getActiveInstances().get(state.instanceId) : null;
                        BdeModel model = null;
                        if (inst != null) {
                            model = inst.getModel();
                        } else if (state.modelProjectId != null) {
                            model = plugin.getModelManager().loadModelSync(state.modelProjectId);
                        }
                        if (model != null) {
                            String cleanPath = text.replace("\\", "/");
                            while (cleanPath.startsWith("/")) cleanPath = cleanPath.substring(1);
                            while (cleanPath.endsWith("/")) cleanPath = cleanPath.substring(0, cleanPath.length() - 1);
                            if (cleanPath.endsWith(".json")) {
                                cleanPath = cleanPath.substring(0, cleanPath.length() - 5);
                            }
                            model.setLocalFilePath(cleanPath);
                            model.setIsVehicleLibrary(true);
                            if (model.getVehicleStats() == null) {
                                model.setVehicleStats(new BdeModel.VehicleStats());
                            }
                            plugin.getModelManager().saveModelConfig(model);
                            player.sendMessage("§aVehicle successfully saved as: §fvehicles/" + cleanPath + ".json");
                            final String finalNewPath = cleanPath;
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                if (inst != null) {
                                    recreateModelInstance(inst, player);
                                } else {
                                    try {
                                        BdeModel updated = plugin.getModelManager().loadModelSync(finalNewPath);
                                        plugin.getBdeGuiManager().openVehicleMenu(player, null, updated, finalNewPath);
                                    } catch (Exception ignored) {}
                                }
                            });
                        }
                    } catch (Exception ex) {
                        player.sendMessage("§cFailed to save vehicle: " + ex.getMessage());
                    }
                });
                break;

            case SET_SEAT_YAW:
                double yawValue;
                try {
                    yawValue = Double.parseDouble(text);
                } catch (NumberFormatException e) {
                    player.sendMessage("§cInvalid angle. Please enter a valid number.");
                    reopenMenu(player, state);
                    break;
                }
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    try {
                        BdeModel model = plugin.getModelManager().loadModelSync(state.modelProjectId);
                        if (model != null) {
                            BdeModel.VehicleConfig cfg = model.getVehicle();
                            if (cfg == null) {
                                model.ensureVehicleConfig();
                                cfg = model.getVehicle();
                            }
                            if (state.seatIndex == -1) {
                                cfg.setDriverSeatYaw(yawValue);
                            } else {
                                List<BdeModel.PassengerSeatConfig> pSeats = cfg.getPassengerSeats();
                                if (state.seatIndex >= 0 && state.seatIndex < pSeats.size()) {
                                    pSeats.get(state.seatIndex).setYaw(yawValue);
                                }
                            }
                            if (model.getLocalFilePath() != null && model.isVehicleLibrary()) {
                                plugin.getModelManager().saveModelConfig(model);
                            }
                            player.sendMessage("§aSeat rotation yaw set to " + yawValue + "°");
                        }
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            ModelInstance inst = state.instanceId != null ? plugin.getModelManager().getActiveInstances().get(state.instanceId) : null;
                            if (inst != null) {
                                Location currentLoc = inst.getLocation();
                                double currentScale = inst.getScale();
                                plugin.getBdeGuiManager().clearSelection(player.getUniqueId());
                                plugin.getModelManager().removeInstance(inst.getId());
                                ModelInstance newInstance = plugin.getModelManager().spawnModel(model, currentLoc, currentScale);
                                plugin.getBdeGuiManager().selectModel(player, newInstance.getId());
                                plugin.getBdeGuiManager().openSeatDetailMenu(player, newInstance, model, state.modelProjectId, state.seatIndex);
                            } else {
                                plugin.getBdeGuiManager().openSeatDetailMenu(player, null, model, state.modelProjectId, state.seatIndex);
                            }
                        });
                    } catch (Exception ex) {
                        player.sendMessage("§cFailed to set seat yaw: " + ex.getMessage());
                    }
                });
                break;
        }
    }

    private void reopenMenu(Player player, ChatPromptState state) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            switch (state.type) {
                case CREATE_SUBDIRECTORY:
                    plugin.getBdeGuiManager().openVehiclesCatalog(player, plugin.getBdeGuiManager().getPlayerCatalogDirectory(player.getUniqueId()));
                    break;
                case RENAME_VEHICLE:
                    try {
                        ModelInstance inst = state.instanceId != null ? plugin.getModelManager().getActiveInstances().get(state.instanceId) : null;
                        BdeModel model = plugin.getModelManager().loadModelSync(state.modelProjectId);
                        plugin.getBdeGuiManager().openVehicleMenu(player, inst, model, state.modelProjectId);
                    } catch (Exception ignored) {}
                    break;
                case RENAME_SEAT:
                    try {
                        ModelInstance inst = state.instanceId != null ? plugin.getModelManager().getActiveInstances().get(state.instanceId) : null;
                        BdeModel model = plugin.getModelManager().loadModelSync(state.modelProjectId);
                        plugin.getBdeGuiManager().openSeatDetailMenu(player, inst, model, state.modelProjectId, state.seatIndex);
                    } catch (Exception ignored) {}
                    break;
                case SAVE_VEHICLE_AS:
                    try {
                        ModelInstance inst = state.instanceId != null ? plugin.getModelManager().getActiveInstances().get(state.instanceId) : null;
                        BdeModel model = null;
                        if (inst != null) {
                            model = inst.getModel();
                        } else if (state.modelProjectId != null) {
                            model = plugin.getModelManager().loadModelSync(state.modelProjectId);
                        }
                        plugin.getBdeGuiManager().openVehicleMenu(player, inst, model, state.modelProjectId);
                    } catch (Exception ignored) {}
                    break;
                case SET_SEAT_YAW:
                    try {
                        ModelInstance inst = state.instanceId != null ? plugin.getModelManager().getActiveInstances().get(state.instanceId) : null;
                        BdeModel model = plugin.getModelManager().loadModelSync(state.modelProjectId);
                        plugin.getBdeGuiManager().openSeatDetailMenu(player, inst, model, state.modelProjectId, state.seatIndex);
                    } catch (Exception ignored) {}
                    break;
            }
        });
    }

    private void handleBlocksMenuClick(Player player, ModelInstance instance, int slot, boolean isShift) {
        if (instance == null) {
            player.closeInventory();
            return;
        }

        CustomBlockManager.CustomBlockConfig config = plugin.getCustomBlockManager().getConfig(instance.getModel().getProjectId());

        switch (slot) {
            case 45: // Back
                plugin.getBdeGuiManager().openMainMenu(player, instance);
                return;
            case 49: // Close
                player.closeInventory();
                return;

            case 13: // Link / Cycle / Delete
                List<Material> materials = java.util.Arrays.asList(
                    Material.GRASS_BLOCK,
                    Material.BARRIER,
                    Material.STONE,
                    Material.BARREL,
                    Material.CHEST,
                    Material.OAK_PLANKS,
                    Material.GLASS
                );

                if (config == null) {
                    plugin.getCustomBlockManager().setBlockDefaultLink(instance.getModel().getProjectId(), Material.GRASS_BLOCK);
                } else {
                    if (isShift) {
                        plugin.getCustomBlockManager().unlinkBlockDefault(instance.getModel().getProjectId());
                    } else {
                        int currentIndex = materials.indexOf(config.material);
                        int nextIndex = (currentIndex + 1) % materials.size();
                        if (nextIndex == -1) nextIndex = 0;
                        plugin.getCustomBlockManager().setBlockDefaultLink(instance.getModel().getProjectId(), materials.get(nextIndex));
                    }
                }
                plugin.getBdeGuiManager().openBlocksMenu(player, instance);
                player.playSound(player.getLocation(), Sound.BLOCK_GRASS_PLACE, 0.5f, isShift ? 0.6f : 1.2f);
                break;
        }
    }

    private void recreateModelInstance(ModelInstance instance, Player player) {
        Location currentLoc = instance.getLocation();
        double currentScale = instance.getScale();
        BdeModel model = instance.getModel();

        plugin.getBdeGuiManager().clearSelection(player.getUniqueId());
        plugin.getModelManager().removeInstance(instance.getId());

        ModelInstance newInstance = plugin.getModelManager().spawnModel(model, currentLoc, currentScale);
        plugin.getBdeGuiManager().selectModel(player, newInstance.getId());
        plugin.getBdeGuiManager().openVehicleMenu(player, newInstance);
        player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 0.5f, 1.0f);
    }

    private void handleAnimationsMenuClick(Player player, ModelInstance instance, int slot, ItemStack item, boolean isShift) {
        if (instance == null) {
            player.closeInventory();
            return;
        }

        switch (slot) {
            case 45: // Back
                plugin.getBdeGuiManager().openMainMenu(player, instance);
                return;
            case 49: // Close
                player.closeInventory();
                return;
            case 40: // Pause/Resume Toggle
                if (plugin.getAnimationEngine().isPlaying(instance.getId())) {
                    if (plugin.getAnimationEngine().isPaused(instance.getId())) {
                        plugin.getAnimationEngine().resumeAnimation(instance.getId());
                        player.sendMessage("§aResumed animation playback.");
                    } else {
                        plugin.getAnimationEngine().pauseAnimation(instance.getId());
                        player.sendMessage("§ePaused animation playback.");
                    }
                    plugin.getBdeGuiManager().openAnimationsMenu(player, instance);
                }
                return;
            case 41: // Speed 0.25x
                plugin.getAnimationEngine().setSpeed(instance.getId(), 0.25);
                player.sendMessage("§bPlayback speed set to 0.25x");
                return;
            case 42: // Speed 0.5x
                plugin.getAnimationEngine().setSpeed(instance.getId(), 0.5);
                player.sendMessage("§bPlayback speed set to 0.5x");
                return;
            case 43: // Speed 1.0x
                plugin.getAnimationEngine().setSpeed(instance.getId(), 1.0);
                player.sendMessage("§bPlayback speed set to 1.0x (Default)");
                return;
            case 44: // Speed 1.5x
                plugin.getAnimationEngine().setSpeed(instance.getId(), 1.5);
                player.sendMessage("§bPlayback speed set to 1.5x");
                return;
            case 46: // Speed 2.0x
                plugin.getAnimationEngine().setSpeed(instance.getId(), 2.0);
                player.sendMessage("§bPlayback speed set to 2.0x");
                return;
        }

        // Animation list slots (10-34)
        if (slot >= 10 && slot <= 34 && item.getType() == Material.WRITTEN_BOOK) {
            String animName = ChatColor.stripColor(item.getItemMeta().getDisplayName());
            boolean loop = !isShift;

            plugin.getAnimationEngine().playAnimation(instance, animName, loop);
            player.sendMessage("§aPlaying animation '" + animName + "' (Loop: " + loop + ")");
            plugin.getBdeGuiManager().openAnimationsMenu(player, instance);
        }
    }

    private void handleConverterMenuClick(Player player, int slot, ItemStack item, boolean isShift) {
        if (slot == 45) { // Back
            UUID selectedId = plugin.getBdeGuiManager().getSelectedModel(player.getUniqueId());
            if (selectedId != null) {
                ModelInstance instance = plugin.getModelManager().getActiveInstances().get(selectedId);
                if (instance != null) {
                    plugin.getBdeGuiManager().openMainMenu(player, instance);
                    return;
                }
            }
            player.closeInventory();
            return;
        }
        if (slot == 49) { // Close
            player.closeInventory();
            return;
        }

        // Converter slots (10-34)
        if (slot >= 10 && slot <= 34) {
            String filename = ChatColor.stripColor(item.getItemMeta().getDisplayName());
            File folder = new File(plugin.getDataFolder(), "models");
            File inputFile = new File(folder, filename);

            if (!inputFile.exists()) {
                player.sendMessage("§cSource file not found.");
                return;
            }

            if (item.getType() == Material.CLAY_BALL) {
                // Convert VOX file
                int resolution = isShift ? 2 : 1;
                player.sendMessage("§eConverting voxel model " + filename + " (downsample factor: " + resolution + ")...");
                player.closeInventory();

                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    try {
                        File mappingFile = new File(plugin.getDataFolder(), "mappings/" + filename.replaceAll("\\.[^.]+$", "") + ".yml");
                        ConversionMapper mapper = new ConversionMapper(plugin, mappingFile);
                        VoxConverter converter = new VoxConverter(plugin);
                        BdeModel output = converter.convert(inputFile, mapper, resolution);

                        String outFilename = filename.replaceAll("\\.[^.]+$", "") + ".json";
                        File outputFile = new File(folder, outFilename);
                        try (FileWriter writer = new FileWriter(outputFile)) {
                            new com.google.gson.Gson().toJson(output, writer);
                        }

                        player.sendMessage("§aConversion successful! Saved as §fmodels/" + outFilename + " §7(" + output.getPassengers().size() + " elements)");
                    } catch (Exception e) {
                        player.sendMessage("§cConversion failed: " + e.getMessage());
                    }
                });
            } else if (item.getType() == Material.ARMOR_STAND) {
                // Spawn converted JSON BDE model
                player.sendMessage("§eLoading and spawning model " + filename + "...");
                player.closeInventory();

                plugin.getModelManager().loadModel(filename.replace(".json", ""))
                        .thenAccept(model -> {
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                ModelInstance instance = plugin.getModelManager().spawnModel(model, player.getLocation(), 1.0);
                                plugin.getBdeGuiManager().selectModel(player, instance.getId());
                                player.sendMessage("§aSpawned model successfully! Visual highlight and GUI console are active.");
                            });
                        })
                        .exceptionally(ex -> {
                            player.sendMessage("§cFailed to load model: " + ex.getMessage());
                            return null;
                        });
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        plugin.getBdeGuiManager().clearSelection(event.getPlayer().getUniqueId());
        plugin.getModelManager().getInputTracker().uninject(event.getPlayer());
        activePrompts.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        plugin.getBdeGuiManager().clearSelection(event.getPlayer().getUniqueId());
        plugin.getModelManager().getInputTracker().uninject(event.getPlayer());
        activePrompts.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onEntityDismount(org.bukkit.event.entity.EntityDismountEvent event) {
        if (event.getEntity() instanceof Player) {
            plugin.getModelManager().handleDismount((Player) event.getEntity(), event.getDismounted());
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        // Hide existing highlights from joining players
        plugin.getBdeGuiManager().filterBoundaryForNewPlayer(event.getPlayer());
    }
}
