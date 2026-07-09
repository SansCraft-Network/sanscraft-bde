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
import java.util.Set;
import java.util.UUID;

public class BdeGuiListener implements Listener {
    private final SansCraftBDEPlugin plugin;
    private final Map<UUID, ChatPromptState> activePrompts = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, Long> lastDropTimestamp = new java.util.concurrent.ConcurrentHashMap<>();

    public enum ChatPromptType {
        CREATE_SUBDIRECTORY,
        RENAME_VEHICLE,
        RENAME_SEAT,
        SAVE_VEHICLE_AS,
        SET_SEAT_YAW,
        SET_GLOBAL_BLOCK_TRACTION,
        SET_VEHICLE_BLOCK_TRACTION,
        RENAME_SUBSYSTEM,
        EDIT_SUBSYSTEM_TAG,
        RENAME_WEAPON,
        EDIT_WEAPON_MODEL,
        EDIT_WEAPON_SOUND,
        EDIT_WEAPON_FLY_PARTICLE,
        EDIT_WEAPON_IMPACT_PARTICLE,
        EDIT_SUBSYSTEM_BDE_MODEL_ID,
        EDIT_SUBSYSTEM_MOUNT_OFFSET,
        EDIT_SUBSYSTEM_LAUNCH_OFFSET,
        EDIT_SUBSYSTEM_MIN_YAW,
        EDIT_SUBSYSTEM_MAX_YAW,
        EDIT_SUBSYSTEM_MIN_PITCH,
        EDIT_SUBSYSTEM_MAX_PITCH,
        EDIT_SUBSYSTEM_CAMERA_OFFSET,
        EDIT_SUBSYSTEM_PIVOT_OFFSET
    }

    public static class ChatPromptState {
        public final ChatPromptType type;
        public final String modelProjectId;
        public final UUID instanceId;
        public final Integer seatIndex;
        public final String blockMaterial;

        public ChatPromptState(ChatPromptType type, String modelProjectId, UUID instanceId, Integer seatIndex) {
            this(type, modelProjectId, instanceId, seatIndex, null);
        }

        public ChatPromptState(ChatPromptType type, String modelProjectId, UUID instanceId, Integer seatIndex, String blockMaterial) {
            this.type = type;
            this.modelProjectId = modelProjectId;
            this.instanceId = instanceId;
            this.seatIndex = seatIndex;
            this.blockMaterial = blockMaterial;
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
                    if (!player.hasPermission("sanscraft.bde.gui")) {
                        player.sendMessage("§cYou don't have permission to open the GUI.");
                        return;
                    }
                    plugin.getBdeGuiManager().openMainMenu(player, instance);
                }
            } else {
                // Select and highlight
                if (!player.hasPermission("sanscraft.bde.select")) {
                    player.sendMessage("§cYou don't have permission to select models.");
                    return;
                }
                plugin.getBdeGuiManager().selectModel(player, instance.getId());
            }
        } else {
            // Player right-clicked the model without the selector tool -> mount the vehicle!
            ModelInstance instance = plugin.getModelManager().getInstanceByRoot(root);
            if (instance != null) {
                event.setCancelled(true);
                ModelInstance ridden = getRiddenVehicle(player);
                if (ridden != null && ridden.equals(instance)) {
                    // Right clicking own vehicle while riding! Trigger subsystem weapon instead.
                    BdeModel.SubsystemConfig controlledSub = getControlledSubsystem(player, instance);
                    if (controlledSub != null) {
                        plugin.getModelManager().triggerSubsystemAction(instance, controlledSub, player);
                    }
                    return;
                }
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
            case GENERAL_BLOCK_TRACTION:
                handleGeneralBlockTractionClick(player, holder, event.getSlot(), clickedItem, event);
                break;
            case VEHICLE_BLOCK_OVERRIDES:
                handleVehicleBlockOverridesClick(player, instance, holder, event.getSlot(), clickedItem, event);
                break;
            case SUBSYSTEM_LIST:
                handleSubsystemListClick(player, instance, holder, event.getSlot(), clickedItem, event);
                break;
            case SUBSYSTEM_DETAIL:
                handleSubsystemDetailClick(player, instance, holder, event.getSlot(), clickedItem, event);
                break;
            case WEAPON_MODE_LIST:
                handleWeaponModeListClick(player, instance, holder, event.getSlot(), clickedItem, event);
                break;
            case WEAPON_MODE_DETAIL:
                handleWeaponModeDetailClick(player, instance, holder, event.getSlot(), clickedItem, event);
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
                boolean canMoveOrRotate = player.hasPermission("sanscraft.bde.move") || player.hasPermission("sanscraft.bde.rotate");
                if (!canMoveOrRotate) {
                    player.sendMessage("§cYou do not have permission to move or rotate models.");
                    player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                    return;
                }
                plugin.getBdeGuiManager().openMovementMenu(player, instance);
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.5f, 1.0f);
                break;
            case 23: // Vehicle Config
                if (!player.hasPermission("sanscraft.bde.vehicles")) {
                    player.sendMessage("§cYou do not have permission to use vehicles.");
                    player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                    return;
                }
                plugin.getBdeGuiManager().openVehicleMenu(player, instance);
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.5f, 1.0f);
                break;
            case 24: // Animations
                if (!player.hasPermission("sanscraft.bde.animate")) {
                    player.sendMessage("§cYou do not have permission to use animations.");
                    player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                    return;
                }
                plugin.getBdeGuiManager().openAnimationsMenu(player, instance);
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.5f, 1.0f);
                break;
            case 40: // Despawn Model
                if (!player.hasPermission("sanscraft.bde.remove")) {
                    player.sendMessage("§cYou do not have permission to despawn models.");
                    player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                    return;
                }
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

        Set<Integer> moveSlots = Set.of(19, 20, 21, 23, 24, 25);
        Set<Integer> rotateSlots = Set.of(29, 30, 32, 33, 38, 39, 41, 42);

        if (moveSlots.contains(slot) && !player.hasPermission("sanscraft.bde.move")) {
            player.sendMessage("§cYou don't have permission to move models.");
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            return;
        }

        if (rotateSlots.contains(slot) && !player.hasPermission("sanscraft.bde.rotate")) {
            player.sendMessage("§cYou don't have permission to rotate models.");
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
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

            case 34: // Traction overrides menu
                if (stats != null) {
                    plugin.getBdeGuiManager().openVehicleBlockOverridesMenu(player, instance, model, modelProjectId);
                }
                return;

            case 22: // Configure Subsystems & Weapons
                if (stats != null) {
                    plugin.getBdeGuiManager().openSubsystemListMenu(player, instance, model, modelProjectId);
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
            case 28: // Traction Multiplier
                stats.setTraction(Math.max(0.0, stats.getTraction() + 0.05 * factor));
                break;
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
                plugin.getModelManager().getInputTracker().inject(player);
                plugin.getModelManager().giveSubsystemControllerItem(player, instance, passengerIndex);
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

        if (slot == 18) {
            plugin.getBdeGuiManager().toggleSnapMode(player.getUniqueId());
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 1.2f);
            plugin.getBdeGuiManager().openSeatDetailMenu(player, instance, model, modelProjectId, seatIndex);
            return;
        }

        if (slot == 27) {
            plugin.getBdeGuiManager().cyclePrecision(player.getUniqueId());
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 1.2f);
            plugin.getBdeGuiManager().openSeatDetailMenu(player, instance, model, modelProjectId, seatIndex);
            return;
        }

        if (slot == 19) {
            if (instance == null || instance.getVehicleRoot() == null) {
                player.sendMessage("§cYou can only align offsets when there is an active spawned instance in the world.");
                return;
            }

            List<Double> relOffset = getPlayerRelativeOffset(player, instance, model);
            double px = relOffset.get(0);
            double py = relOffset.get(1);
            double pz = relOffset.get(2);

            Location pLoc = player.getLocation();
            Location vLoc = instance.getVehicleRoot().getLocation();

            // Calculate facing yaw relative to vehicle root
            double psYaw = (pLoc.getYaw() - vLoc.getYaw()) % 360.0;
            int precision = plugin.getBdeGuiManager().getPrecision(player.getUniqueId());
            if (plugin.getBdeGuiManager().getSnapMode(player.getUniqueId())) {
                double snapped = Math.round(psYaw / 90.0) * 90.0;
                psYaw = (snapped % 360.0 + 360.0) % 360.0;
            } else {
                psYaw = (psYaw % 360.0 + 360.0) % 360.0;
                if (precision >= 0) {
                    double factor = Math.pow(10, precision);
                    psYaw = Math.round(psYaw * factor) / factor;
                }
            }

            // Update seat stats
            if (seatIndex == -1) {
                cfg.setSeatOffset(java.util.Arrays.asList(px, py, pz));
                cfg.setDriverSeatYaw(psYaw);
            } else {
                if (seatIndex >= 0 && seatIndex < cfg.getPassengerSeats().size()) {
                    cfg.getPassengerSeats().get(seatIndex).setOffset(java.util.Arrays.asList(px, py, pz));
                    cfg.getPassengerSeats().get(seatIndex).setYaw(psYaw);
                }
            }

            // Save config
            if (model.getLocalFilePath() != null && model.isVehicleLibrary()) {
                plugin.getModelManager().saveModelConfig(model);
            }

            // Recreate instance
            Location currentLoc = instance.getLocation();
            double currentScale = instance.getScale();
            plugin.getBdeGuiManager().clearSelection(player.getUniqueId());
            plugin.getModelManager().removeInstance(instance.getId());
            ModelInstance newInstance = plugin.getModelManager().spawnModel(model, currentLoc, currentScale);
            plugin.getBdeGuiManager().selectModel(player, newInstance.getId());
            
            // Reopen GUI
            plugin.getBdeGuiManager().openSeatDetailMenu(player, newInstance, model, modelProjectId, seatIndex);
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 1.5f);
            player.sendMessage("§aSeat aligned to player's current location relative to vehicle center!");
            return;
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

            case SET_GLOBAL_BLOCK_TRACTION:
                double globalVal;
                try {
                    globalVal = Double.parseDouble(text);
                } catch (NumberFormatException e) {
                    player.sendMessage("§cInvalid traction value. Please enter a valid decimal number.");
                    reopenMenu(player, state);
                    break;
                }
                plugin.getConfig().set("block-traction." + state.blockMaterial, globalVal);
                plugin.saveConfig();
                player.sendMessage("§aGeneral traction override for " + state.blockMaterial + " set to " + globalVal);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    plugin.getBdeGuiManager().openGeneralBlockTractionMenu(player);
                });
                break;

            case SET_VEHICLE_BLOCK_TRACTION:
                double vehicleVal;
                try {
                    vehicleVal = Double.parseDouble(text);
                } catch (NumberFormatException e) {
                    player.sendMessage("§cInvalid traction value. Please enter a valid decimal number.");
                    reopenMenu(player, state);
                    break;
                }
                if (state.modelProjectId != null) {
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        try {
                            BdeModel m = plugin.getModelManager().loadModelSync(state.modelProjectId);
                            if (m != null) {
                                BdeModel.VehicleStats s = m.getVehicleStats();
                                if (s != null) {
                                    s.getBlockOverrides().put(state.blockMaterial, vehicleVal);
                                    if (m.getLocalFilePath() != null && m.isVehicleLibrary()) {
                                        plugin.getModelManager().saveModelConfig(m);
                                    }
                                    player.sendMessage("§aTraction override for " + state.blockMaterial + " on this vehicle set to " + vehicleVal);
                                }
                            }
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                ModelInstance inst = state.instanceId != null ? plugin.getModelManager().getActiveInstances().get(state.instanceId) : null;
                                try {
                                    BdeModel updatedModel = plugin.getModelManager().loadModelSync(state.modelProjectId);
                                    plugin.getBdeGuiManager().openVehicleBlockOverridesMenu(player, inst, updatedModel, state.modelProjectId);
                                } catch (Exception ignored) {}
                            });
                        } catch (Exception ex) {
                            player.sendMessage("§cFailed to set vehicle traction override: " + ex.getMessage());
                        }
                    });
                }
                break;

            case RENAME_SUBSYSTEM:
                if (state.modelProjectId != null && state.blockMaterial != null) {
                    int subIdx = Integer.parseInt(state.blockMaterial);
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        try {
                            BdeModel m = plugin.getModelManager().loadModelSync(state.modelProjectId);
                            if (m != null && m.getVehicle() != null && subIdx >= 0 && subIdx < m.getVehicle().getSubsystems().size()) {
                                m.getVehicle().getSubsystems().get(subIdx).setName(text);
                                if (m.getLocalFilePath() != null && m.isVehicleLibrary()) {
                                    plugin.getModelManager().saveModelConfig(m);
                                }
                                player.sendMessage("§aSubsystem renamed to " + text);
                            }
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                ModelInstance inst = state.instanceId != null ? plugin.getModelManager().getActiveInstances().get(state.instanceId) : null;
                                try {
                                    BdeModel updatedModel = plugin.getModelManager().loadModelSync(state.modelProjectId);
                                    plugin.getBdeGuiManager().openSubsystemDetailMenu(player, inst, updatedModel, state.modelProjectId, subIdx);
                                } catch (Exception ignored) {}
                            });
                        } catch (Exception ex) {
                            player.sendMessage("§cFailed to rename subsystem: " + ex.getMessage());
                        }
                    });
                }
                break;

            case EDIT_SUBSYSTEM_TAG:
                if (state.modelProjectId != null && state.blockMaterial != null) {
                    int subIdx = Integer.parseInt(state.blockMaterial);
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        try {
                            BdeModel m = plugin.getModelManager().loadModelSync(state.modelProjectId);
                            if (m != null && m.getVehicle() != null && subIdx >= 0 && subIdx < m.getVehicle().getSubsystems().size()) {
                                m.getVehicle().getSubsystems().get(subIdx).setDisplayTag(text);
                                if (m.getLocalFilePath() != null && m.isVehicleLibrary()) {
                                    plugin.getModelManager().saveModelConfig(m);
                                }
                                player.sendMessage("§aSubsystem display tag set to " + text);
                            }
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                ModelInstance inst = state.instanceId != null ? plugin.getModelManager().getActiveInstances().get(state.instanceId) : null;
                                try {
                                    BdeModel updatedModel = plugin.getModelManager().loadModelSync(state.modelProjectId);
                                    plugin.getBdeGuiManager().openSubsystemDetailMenu(player, inst, updatedModel, state.modelProjectId, subIdx);
                                } catch (Exception ignored) {}
                            });
                        } catch (Exception ex) {
                            player.sendMessage("§cFailed to set display tag: " + ex.getMessage());
                        }
                    });
                }
                break;

            case EDIT_SUBSYSTEM_BDE_MODEL_ID:
                if (state.modelProjectId != null && state.blockMaterial != null) {
                    int subIdx = Integer.parseInt(state.blockMaterial);
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        try {
                            BdeModel m = plugin.getModelManager().loadModelSync(state.modelProjectId);
                            if (m != null && m.getVehicle() != null && subIdx >= 0 && subIdx < m.getVehicle().getSubsystems().size()) {
                                m.getVehicle().getSubsystems().get(subIdx).setBdeModelId(text);
                                if (m.getLocalFilePath() != null && m.isVehicleLibrary()) {
                                    plugin.getModelManager().saveModelConfig(m);
                                }
                                player.sendMessage("§aSubsystem mounted BDE Model ID set to " + text);
                            }
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                ModelInstance inst = state.instanceId != null ? plugin.getModelManager().getActiveInstances().get(state.instanceId) : null;
                                try {
                                    BdeModel updatedModel = plugin.getModelManager().loadModelSync(state.modelProjectId);
                                    if (inst != null) {
                                        recreateModelInstance(inst, player);
                                        UUID selectedId = plugin.getBdeGuiManager().getSelectedModel(player.getUniqueId());
                                        ModelInstance newInst = plugin.getModelManager().getActiveInstances().get(selectedId);
                                        plugin.getBdeGuiManager().openSubsystemDetailMenu(player, newInst, updatedModel, state.modelProjectId, subIdx);
                                    } else {
                                        plugin.getBdeGuiManager().openSubsystemDetailMenu(player, null, updatedModel, state.modelProjectId, subIdx);
                                    }
                                } catch (Exception ignored) {}
                            });
                        } catch (Exception ex) {
                            player.sendMessage("§cFailed to set model ID: " + ex.getMessage());
                        }
                    });
                }
                break;

            case EDIT_SUBSYSTEM_MOUNT_OFFSET:
                if (state.modelProjectId != null && state.blockMaterial != null) {
                    int subIdx = Integer.parseInt(state.blockMaterial);
                    List<Double> coords;
                    try {
                        coords = parseCoords(text);
                    } catch (IllegalArgumentException e) {
                        player.sendMessage("§c" + e.getMessage());
                        reopenMenu(player, state);
                        break;
                    }
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        try {
                            BdeModel m = plugin.getModelManager().loadModelSync(state.modelProjectId);
                            if (m != null && m.getVehicle() != null && subIdx >= 0 && subIdx < m.getVehicle().getSubsystems().size()) {
                                m.getVehicle().getSubsystems().get(subIdx).setMountOffset(coords);
                                if (m.getLocalFilePath() != null && m.isVehicleLibrary()) {
                                    plugin.getModelManager().saveModelConfig(m);
                                }
                                player.sendMessage("§aSubsystem mount offset set to " + text);
                            }
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                ModelInstance inst = state.instanceId != null ? plugin.getModelManager().getActiveInstances().get(state.instanceId) : null;
                                try {
                                    BdeModel updatedModel = plugin.getModelManager().loadModelSync(state.modelProjectId);
                                    if (inst != null) {
                                        recreateModelInstance(inst, player);
                                        UUID selectedId = plugin.getBdeGuiManager().getSelectedModel(player.getUniqueId());
                                        ModelInstance newInst = plugin.getModelManager().getActiveInstances().get(selectedId);
                                        plugin.getBdeGuiManager().openSubsystemDetailMenu(player, newInst, updatedModel, state.modelProjectId, subIdx);
                                    } else {
                                        plugin.getBdeGuiManager().openSubsystemDetailMenu(player, null, updatedModel, state.modelProjectId, subIdx);
                                    }
                                } catch (Exception ignored) {}
                            });
                        } catch (Exception ex) {
                            player.sendMessage("§cFailed to set mount offset: " + ex.getMessage());
                        }
                    });
                }
                break;

            case EDIT_SUBSYSTEM_LAUNCH_OFFSET:
                if (state.modelProjectId != null && state.blockMaterial != null) {
                    int subIdx = Integer.parseInt(state.blockMaterial);
                    List<Double> coords;
                    try {
                        coords = parseCoords(text);
                    } catch (IllegalArgumentException e) {
                        player.sendMessage("§c" + e.getMessage());
                        reopenMenu(player, state);
                        break;
                    }
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        try {
                            BdeModel m = plugin.getModelManager().loadModelSync(state.modelProjectId);
                            if (m != null && m.getVehicle() != null && subIdx >= 0 && subIdx < m.getVehicle().getSubsystems().size()) {
                                m.getVehicle().getSubsystems().get(subIdx).setLaunchOffset(coords);
                                if (m.getLocalFilePath() != null && m.isVehicleLibrary()) {
                                    plugin.getModelManager().saveModelConfig(m);
                                }
                                player.sendMessage("§aSubsystem launch offset set to " + text);
                            }
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                ModelInstance inst = state.instanceId != null ? plugin.getModelManager().getActiveInstances().get(state.instanceId) : null;
                                try {
                                    BdeModel updatedModel = plugin.getModelManager().loadModelSync(state.modelProjectId);
                                    if (inst != null) {
                                        recreateModelInstance(inst, player);
                                        UUID selectedId = plugin.getBdeGuiManager().getSelectedModel(player.getUniqueId());
                                        ModelInstance newInst = plugin.getModelManager().getActiveInstances().get(selectedId);
                                        plugin.getBdeGuiManager().openSubsystemDetailMenu(player, newInst, updatedModel, state.modelProjectId, subIdx);
                                    } else {
                                        plugin.getBdeGuiManager().openSubsystemDetailMenu(player, null, updatedModel, state.modelProjectId, subIdx);
                                    }
                                } catch (Exception ignored) {}
                            });
                        } catch (Exception ex) {
                            player.sendMessage("§cFailed to set launch offset: " + ex.getMessage());
                        }
                    });
                }
                break;

            case EDIT_SUBSYSTEM_MIN_YAW:
                if (state.modelProjectId != null && state.blockMaterial != null) {
                    int subIdx = Integer.parseInt(state.blockMaterial);
                    Double val = null;
                    if (!text.equalsIgnoreCase("none") && !text.equalsIgnoreCase("unlimited")) {
                        try {
                            val = Double.parseDouble(text);
                        } catch (NumberFormatException e) {
                            player.sendMessage("§cInvalid angle. Please enter a valid decimal number or 'none'.");
                            reopenMenu(player, state);
                            break;
                        }
                    }
                    final Double finalVal = val;
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        try {
                            BdeModel m = plugin.getModelManager().loadModelSync(state.modelProjectId);
                            if (m != null && m.getVehicle() != null && subIdx >= 0 && subIdx < m.getVehicle().getSubsystems().size()) {
                                m.getVehicle().getSubsystems().get(subIdx).setFovMinYaw(finalVal);
                                if (m.getLocalFilePath() != null && m.isVehicleLibrary()) {
                                    plugin.getModelManager().saveModelConfig(m);
                                }
                                player.sendMessage("§aSubsystem minimum look yaw limit set to " + (finalVal != null ? finalVal + "°" : "Unlimited"));
                            }
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                ModelInstance inst = state.instanceId != null ? plugin.getModelManager().getActiveInstances().get(state.instanceId) : null;
                                try {
                                    BdeModel updatedModel = plugin.getModelManager().loadModelSync(state.modelProjectId);
                                    if (inst != null) {
                                        recreateModelInstance(inst, player);
                                        UUID selectedId = plugin.getBdeGuiManager().getSelectedModel(player.getUniqueId());
                                        ModelInstance newInst = plugin.getModelManager().getActiveInstances().get(selectedId);
                                        plugin.getBdeGuiManager().openSubsystemDetailMenu(player, newInst, updatedModel, state.modelProjectId, subIdx);
                                    } else {
                                        plugin.getBdeGuiManager().openSubsystemDetailMenu(player, null, updatedModel, state.modelProjectId, subIdx);
                                    }
                                } catch (Exception ignored) {}
                            });
                        } catch (Exception ex) {
                            player.sendMessage("§cFailed to set FOV limit: " + ex.getMessage());
                        }
                    });
                }
                break;

            case EDIT_SUBSYSTEM_MAX_YAW:
                if (state.modelProjectId != null && state.blockMaterial != null) {
                    int subIdx = Integer.parseInt(state.blockMaterial);
                    Double val = null;
                    if (!text.equalsIgnoreCase("none") && !text.equalsIgnoreCase("unlimited")) {
                        try {
                            val = Double.parseDouble(text);
                        } catch (NumberFormatException e) {
                            player.sendMessage("§cInvalid angle. Please enter a valid decimal number or 'none'.");
                            reopenMenu(player, state);
                            break;
                        }
                    }
                    final Double finalVal = val;
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        try {
                            BdeModel m = plugin.getModelManager().loadModelSync(state.modelProjectId);
                            if (m != null && m.getVehicle() != null && subIdx >= 0 && subIdx < m.getVehicle().getSubsystems().size()) {
                                m.getVehicle().getSubsystems().get(subIdx).setFovMaxYaw(finalVal);
                                if (m.getLocalFilePath() != null && m.isVehicleLibrary()) {
                                    plugin.getModelManager().saveModelConfig(m);
                                }
                                player.sendMessage("§aSubsystem maximum look yaw limit set to " + (finalVal != null ? finalVal + "°" : "Unlimited"));
                            }
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                ModelInstance inst = state.instanceId != null ? plugin.getModelManager().getActiveInstances().get(state.instanceId) : null;
                                try {
                                    BdeModel updatedModel = plugin.getModelManager().loadModelSync(state.modelProjectId);
                                    if (inst != null) {
                                        recreateModelInstance(inst, player);
                                        UUID selectedId = plugin.getBdeGuiManager().getSelectedModel(player.getUniqueId());
                                        ModelInstance newInst = plugin.getModelManager().getActiveInstances().get(selectedId);
                                        plugin.getBdeGuiManager().openSubsystemDetailMenu(player, newInst, updatedModel, state.modelProjectId, subIdx);
                                    } else {
                                        plugin.getBdeGuiManager().openSubsystemDetailMenu(player, null, updatedModel, state.modelProjectId, subIdx);
                                    }
                                } catch (Exception ignored) {}
                            });
                        } catch (Exception ex) {
                            player.sendMessage("§cFailed to set FOV limit: " + ex.getMessage());
                        }
                    });
                }
                break;

            case EDIT_SUBSYSTEM_MIN_PITCH:
                if (state.modelProjectId != null && state.blockMaterial != null) {
                    int subIdx = Integer.parseInt(state.blockMaterial);
                    Double val = null;
                    if (!text.equalsIgnoreCase("none") && !text.equalsIgnoreCase("unlimited")) {
                        try {
                            val = Double.parseDouble(text);
                        } catch (NumberFormatException e) {
                            player.sendMessage("§cInvalid angle. Please enter a valid decimal number or 'none'.");
                            reopenMenu(player, state);
                            break;
                        }
                    }
                    final Double finalVal = val;
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        try {
                            BdeModel m = plugin.getModelManager().loadModelSync(state.modelProjectId);
                            if (m != null && m.getVehicle() != null && subIdx >= 0 && subIdx < m.getVehicle().getSubsystems().size()) {
                                m.getVehicle().getSubsystems().get(subIdx).setFovMinPitch(finalVal);
                                if (m.getLocalFilePath() != null && m.isVehicleLibrary()) {
                                    plugin.getModelManager().saveModelConfig(m);
                                }
                                player.sendMessage("§aSubsystem minimum look pitch limit set to " + (finalVal != null ? finalVal + "°" : "Unlimited"));
                            }
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                ModelInstance inst = state.instanceId != null ? plugin.getModelManager().getActiveInstances().get(state.instanceId) : null;
                                try {
                                    BdeModel updatedModel = plugin.getModelManager().loadModelSync(state.modelProjectId);
                                    if (inst != null) {
                                        recreateModelInstance(inst, player);
                                        UUID selectedId = plugin.getBdeGuiManager().getSelectedModel(player.getUniqueId());
                                        ModelInstance newInst = plugin.getModelManager().getActiveInstances().get(selectedId);
                                        plugin.getBdeGuiManager().openSubsystemDetailMenu(player, newInst, updatedModel, state.modelProjectId, subIdx);
                                    } else {
                                        plugin.getBdeGuiManager().openSubsystemDetailMenu(player, null, updatedModel, state.modelProjectId, subIdx);
                                    }
                                } catch (Exception ignored) {}
                            });
                        } catch (Exception ex) {
                            player.sendMessage("§cFailed to set FOV limit: " + ex.getMessage());
                        }
                    });
                }
                break;

            case EDIT_SUBSYSTEM_MAX_PITCH:
                if (state.modelProjectId != null && state.blockMaterial != null) {
                    int subIdx = Integer.parseInt(state.blockMaterial);
                    Double val = null;
                    if (!text.equalsIgnoreCase("none") && !text.equalsIgnoreCase("unlimited")) {
                        try {
                            val = Double.parseDouble(text);
                        } catch (NumberFormatException e) {
                            player.sendMessage("§cInvalid angle. Please enter a valid decimal number or 'none'.");
                            reopenMenu(player, state);
                            break;
                        }
                    }
                    final Double finalVal = val;
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        try {
                            BdeModel m = plugin.getModelManager().loadModelSync(state.modelProjectId);
                            if (m != null && m.getVehicle() != null && subIdx >= 0 && subIdx < m.getVehicle().getSubsystems().size()) {
                                m.getVehicle().getSubsystems().get(subIdx).setFovMaxPitch(finalVal);
                                if (m.getLocalFilePath() != null && m.isVehicleLibrary()) {
                                    plugin.getModelManager().saveModelConfig(m);
                                }
                                player.sendMessage("§aSubsystem maximum look pitch limit set to " + (finalVal != null ? finalVal + "°" : "Unlimited"));
                            }
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                ModelInstance inst = state.instanceId != null ? plugin.getModelManager().getActiveInstances().get(state.instanceId) : null;
                                try {
                                    BdeModel updatedModel = plugin.getModelManager().loadModelSync(state.modelProjectId);
                                    if (inst != null) {
                                        recreateModelInstance(inst, player);
                                        UUID selectedId = plugin.getBdeGuiManager().getSelectedModel(player.getUniqueId());
                                        ModelInstance newInst = plugin.getModelManager().getActiveInstances().get(selectedId);
                                        plugin.getBdeGuiManager().openSubsystemDetailMenu(player, newInst, updatedModel, state.modelProjectId, subIdx);
                                    } else {
                                        plugin.getBdeGuiManager().openSubsystemDetailMenu(player, null, updatedModel, state.modelProjectId, subIdx);
                                    }
                                } catch (Exception ignored) {}
                            });
                        } catch (Exception ex) {
                            player.sendMessage("§cFailed to set FOV limit: " + ex.getMessage());
                        }
                    });
                }
                break;

            case EDIT_SUBSYSTEM_CAMERA_OFFSET:
                if (state.modelProjectId != null && state.blockMaterial != null) {
                    int subIdx = Integer.parseInt(state.blockMaterial);
                    List<Double> coords;
                    try {
                        coords = parseCoords(text);
                    } catch (IllegalArgumentException e) {
                        player.sendMessage("§c" + e.getMessage());
                        reopenMenu(player, state);
                        break;
                    }
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        try {
                            BdeModel m = plugin.getModelManager().loadModelSync(state.modelProjectId);
                            if (m != null && m.getVehicle() != null && subIdx >= 0 && subIdx < m.getVehicle().getSubsystems().size()) {
                                m.getVehicle().getSubsystems().get(subIdx).setCameraOffset(coords);
                                if (m.getLocalFilePath() != null && m.isVehicleLibrary()) {
                                    plugin.getModelManager().saveModelConfig(m);
                                }
                                player.sendMessage("§aSubsystem Weapon-Cam camera offset set to " + text);
                            }
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                ModelInstance inst = state.instanceId != null ? plugin.getModelManager().getActiveInstances().get(state.instanceId) : null;
                                try {
                                    BdeModel updatedModel = plugin.getModelManager().loadModelSync(state.modelProjectId);
                                    if (inst != null) {
                                        recreateModelInstance(inst, player);
                                        UUID selectedId = plugin.getBdeGuiManager().getSelectedModel(player.getUniqueId());
                                        ModelInstance newInst = plugin.getModelManager().getActiveInstances().get(selectedId);
                                        plugin.getBdeGuiManager().openSubsystemDetailMenu(player, newInst, updatedModel, state.modelProjectId, subIdx);
                                    } else {
                                        plugin.getBdeGuiManager().openSubsystemDetailMenu(player, null, updatedModel, state.modelProjectId, subIdx);
                                    }
                                } catch (Exception ignored) {}
                            });
                        } catch (Exception ex) {
                            player.sendMessage("§cFailed to set camera offset: " + ex.getMessage());
                        }
                    });
                }
                break;

            case EDIT_SUBSYSTEM_PIVOT_OFFSET:
                if (state.modelProjectId != null && state.blockMaterial != null) {
                    int subIdx = Integer.parseInt(state.blockMaterial);
                    List<Double> coords;
                    try {
                        coords = parseCoords(text);
                    } catch (IllegalArgumentException e) {
                        player.sendMessage("§c" + e.getMessage());
                        reopenMenu(player, state);
                        break;
                    }
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        try {
                            BdeModel m = plugin.getModelManager().loadModelSync(state.modelProjectId);
                            if (m != null && m.getVehicle() != null && subIdx >= 0 && subIdx < m.getVehicle().getSubsystems().size()) {
                                m.getVehicle().getSubsystems().get(subIdx).setPivotOffset(coords);
                                if (m.getLocalFilePath() != null && m.isVehicleLibrary()) {
                                    plugin.getModelManager().saveModelConfig(m);
                                }
                                player.sendMessage("§aSubsystem pivot point offset set to " + text);
                            }
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                ModelInstance inst = state.instanceId != null ? plugin.getModelManager().getActiveInstances().get(state.instanceId) : null;
                                try {
                                    BdeModel updatedModel = plugin.getModelManager().loadModelSync(state.modelProjectId);
                                    if (inst != null) {
                                        recreateModelInstance(inst, player);
                                        UUID selectedId = plugin.getBdeGuiManager().getSelectedModel(player.getUniqueId());
                                        ModelInstance newInst = plugin.getModelManager().getActiveInstances().get(selectedId);
                                        plugin.getBdeGuiManager().openSubsystemDetailMenu(player, newInst, updatedModel, state.modelProjectId, subIdx);
                                    } else {
                                        plugin.getBdeGuiManager().openSubsystemDetailMenu(player, null, updatedModel, state.modelProjectId, subIdx);
                                    }
                                } catch (Exception ignored) {}
                            });
                        } catch (Exception ex) {
                            player.sendMessage("§cFailed to set pivot offset: " + ex.getMessage());
                        }
                    });
                }
                break;

            case RENAME_WEAPON:
                if (state.modelProjectId != null && state.blockMaterial != null && state.seatIndex != null) {
                    int subIdx = Integer.parseInt(state.blockMaterial);
                    int modeIdx = state.seatIndex;
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        try {
                            BdeModel m = plugin.getModelManager().loadModelSync(state.modelProjectId);
                            if (m != null && m.getVehicle() != null && subIdx >= 0 && subIdx < m.getVehicle().getSubsystems().size()) {
                                BdeModel.SubsystemConfig sub = m.getVehicle().getSubsystems().get(subIdx);
                                if (sub.getWeaponModes() != null && modeIdx >= 0 && modeIdx < sub.getWeaponModes().size()) {
                                    sub.getWeaponModes().get(modeIdx).setName(text);
                                    if (m.getLocalFilePath() != null && m.isVehicleLibrary()) {
                                        plugin.getModelManager().saveModelConfig(m);
                                    }
                                    player.sendMessage("§aWeapon mode renamed to " + text);
                                }
                            }
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                ModelInstance inst = state.instanceId != null ? plugin.getModelManager().getActiveInstances().get(state.instanceId) : null;
                                try {
                                    BdeModel updatedModel = plugin.getModelManager().loadModelSync(state.modelProjectId);
                                    plugin.getBdeGuiManager().openWeaponModeDetailMenu(player, inst, updatedModel, state.modelProjectId, subIdx, modeIdx);
                                } catch (Exception ignored) {}
                            });
                        } catch (Exception ex) {
                            player.sendMessage("§cFailed to rename weapon mode: " + ex.getMessage());
                        }
                    });
                }
                break;

            case EDIT_WEAPON_MODEL:
                if (state.modelProjectId != null && state.blockMaterial != null && state.seatIndex != null) {
                    int subIdx = Integer.parseInt(state.blockMaterial);
                    int modeIdx = state.seatIndex;
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        try {
                            BdeModel m = plugin.getModelManager().loadModelSync(state.modelProjectId);
                            if (m != null && m.getVehicle() != null && subIdx >= 0 && subIdx < m.getVehicle().getSubsystems().size()) {
                                BdeModel.SubsystemConfig sub = m.getVehicle().getSubsystems().get(subIdx);
                                if (sub.getWeaponModes() != null && modeIdx >= 0 && modeIdx < sub.getWeaponModes().size()) {
                                    sub.getWeaponModes().get(modeIdx).setBdeModelId(text);
                                    if (m.getLocalFilePath() != null && m.isVehicleLibrary()) {
                                        plugin.getModelManager().saveModelConfig(m);
                                    }
                                    player.sendMessage("§aWeapon model ID set to " + text);
                                }
                            }
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                ModelInstance inst = state.instanceId != null ? plugin.getModelManager().getActiveInstances().get(state.instanceId) : null;
                                try {
                                    BdeModel updatedModel = plugin.getModelManager().loadModelSync(state.modelProjectId);
                                    plugin.getBdeGuiManager().openWeaponModeDetailMenu(player, inst, updatedModel, state.modelProjectId, subIdx, modeIdx);
                                } catch (Exception ignored) {}
                            });
                        } catch (Exception ex) {
                            player.sendMessage("§cFailed to set weapon model ID: " + ex.getMessage());
                        }
                    });
                }
                break;

            case EDIT_WEAPON_SOUND:
                if (state.modelProjectId != null && state.blockMaterial != null && state.seatIndex != null) {
                    int subIdx = Integer.parseInt(state.blockMaterial);
                    int modeIdx = state.seatIndex;
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        try {
                            BdeModel m = plugin.getModelManager().loadModelSync(state.modelProjectId);
                            if (m != null && m.getVehicle() != null && subIdx >= 0 && subIdx < m.getVehicle().getSubsystems().size()) {
                                BdeModel.SubsystemConfig sub = m.getVehicle().getSubsystems().get(subIdx);
                                if (sub.getWeaponModes() != null && modeIdx >= 0 && modeIdx < sub.getWeaponModes().size()) {
                                    sub.getWeaponModes().get(modeIdx).setLaunchSound(text);
                                    if (m.getLocalFilePath() != null && m.isVehicleLibrary()) {
                                        plugin.getModelManager().saveModelConfig(m);
                                    }
                                    player.sendMessage("§aWeapon launch sound set to " + text);
                                }
                            }
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                ModelInstance inst = state.instanceId != null ? plugin.getModelManager().getActiveInstances().get(state.instanceId) : null;
                                try {
                                    BdeModel updatedModel = plugin.getModelManager().loadModelSync(state.modelProjectId);
                                    plugin.getBdeGuiManager().openWeaponModeDetailMenu(player, inst, updatedModel, state.modelProjectId, subIdx, modeIdx);
                                } catch (Exception ignored) {}
                            });
                        } catch (Exception ex) {
                            player.sendMessage("§cFailed to set weapon launch sound: " + ex.getMessage());
                        }
                    });
                }
                break;

            case EDIT_WEAPON_FLY_PARTICLE:
                if (state.modelProjectId != null && state.blockMaterial != null && state.seatIndex != null) {
                    int subIdx = Integer.parseInt(state.blockMaterial);
                    int modeIdx = state.seatIndex;
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        try {
                            BdeModel m = plugin.getModelManager().loadModelSync(state.modelProjectId);
                            if (m != null && m.getVehicle() != null && subIdx >= 0 && subIdx < m.getVehicle().getSubsystems().size()) {
                                BdeModel.SubsystemConfig sub = m.getVehicle().getSubsystems().get(subIdx);
                                if (sub.getWeaponModes() != null && modeIdx >= 0 && modeIdx < sub.getWeaponModes().size()) {
                                    sub.getWeaponModes().get(modeIdx).setFlyParticle(text);
                                    if (m.getLocalFilePath() != null && m.isVehicleLibrary()) {
                                        plugin.getModelManager().saveModelConfig(m);
                                    }
                                    player.sendMessage("§aWeapon flight particle set to " + text);
                                }
                            }
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                ModelInstance inst = state.instanceId != null ? plugin.getModelManager().getActiveInstances().get(state.instanceId) : null;
                                try {
                                    BdeModel updatedModel = plugin.getModelManager().loadModelSync(state.modelProjectId);
                                    plugin.getBdeGuiManager().openWeaponModeDetailMenu(player, inst, updatedModel, state.modelProjectId, subIdx, modeIdx);
                                } catch (Exception ignored) {}
                            });
                        } catch (Exception ex) {
                            player.sendMessage("§cFailed to set weapon flight particle: " + ex.getMessage());
                        }
                    });
                }
                break;

            case EDIT_WEAPON_IMPACT_PARTICLE:
                if (state.modelProjectId != null && state.blockMaterial != null && state.seatIndex != null) {
                    int subIdx = Integer.parseInt(state.blockMaterial);
                    int modeIdx = state.seatIndex;
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        try {
                            BdeModel m = plugin.getModelManager().loadModelSync(state.modelProjectId);
                            if (m != null && m.getVehicle() != null && subIdx >= 0 && subIdx < m.getVehicle().getSubsystems().size()) {
                                BdeModel.SubsystemConfig sub = m.getVehicle().getSubsystems().get(subIdx);
                                if (sub.getWeaponModes() != null && modeIdx >= 0 && modeIdx < sub.getWeaponModes().size()) {
                                    sub.getWeaponModes().get(modeIdx).setImpactParticle(text);
                                    if (m.getLocalFilePath() != null && m.isVehicleLibrary()) {
                                        plugin.getModelManager().saveModelConfig(m);
                                    }
                                    player.sendMessage("§aWeapon impact particle set to " + text);
                                }
                            }
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                ModelInstance inst = state.instanceId != null ? plugin.getModelManager().getActiveInstances().get(state.instanceId) : null;
                                try {
                                    BdeModel updatedModel = plugin.getModelManager().loadModelSync(state.modelProjectId);
                                    plugin.getBdeGuiManager().openWeaponModeDetailMenu(player, inst, updatedModel, state.modelProjectId, subIdx, modeIdx);
                                } catch (Exception ignored) {}
                            });
                        } catch (Exception ex) {
                            player.sendMessage("§cFailed to set weapon impact particle: " + ex.getMessage());
                        }
                    });
                }
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
                case SET_GLOBAL_BLOCK_TRACTION:
                    plugin.getBdeGuiManager().openGeneralBlockTractionMenu(player);
                    break;
                case SET_VEHICLE_BLOCK_TRACTION:
                    try {
                        ModelInstance inst = state.instanceId != null ? plugin.getModelManager().getActiveInstances().get(state.instanceId) : null;
                        BdeModel model = plugin.getModelManager().loadModelSync(state.modelProjectId);
                        plugin.getBdeGuiManager().openVehicleBlockOverridesMenu(player, inst, model, state.modelProjectId);
                    } catch (Exception ignored) {}
                    break;
                case RENAME_SUBSYSTEM:
                case EDIT_SUBSYSTEM_TAG:
                case EDIT_SUBSYSTEM_BDE_MODEL_ID:
                case EDIT_SUBSYSTEM_MOUNT_OFFSET:
                case EDIT_SUBSYSTEM_LAUNCH_OFFSET:
                case EDIT_SUBSYSTEM_MIN_YAW:
                case EDIT_SUBSYSTEM_MAX_YAW:
                case EDIT_SUBSYSTEM_MIN_PITCH:
                case EDIT_SUBSYSTEM_MAX_PITCH:
                case EDIT_SUBSYSTEM_CAMERA_OFFSET:
                    try {
                        int subIdx = Integer.parseInt(state.blockMaterial);
                        ModelInstance inst = state.instanceId != null ? plugin.getModelManager().getActiveInstances().get(state.instanceId) : null;
                        BdeModel m = plugin.getModelManager().loadModelSync(state.modelProjectId);
                        plugin.getBdeGuiManager().openSubsystemDetailMenu(player, inst, m, state.modelProjectId, subIdx);
                    } catch (Exception ignored) {}
                    break;
                case RENAME_WEAPON:
                case EDIT_WEAPON_MODEL:
                case EDIT_WEAPON_SOUND:
                case EDIT_WEAPON_FLY_PARTICLE:
                case EDIT_WEAPON_IMPACT_PARTICLE:
                    try {
                        int subIdx = Integer.parseInt(state.blockMaterial);
                        int modeIdx = state.seatIndex;
                        ModelInstance inst = state.instanceId != null ? plugin.getModelManager().getActiveInstances().get(state.instanceId) : null;
                        BdeModel m = plugin.getModelManager().loadModelSync(state.modelProjectId);
                        plugin.getBdeGuiManager().openWeaponModeDetailMenu(player, inst, m, state.modelProjectId, subIdx, modeIdx);
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

    private void handleGeneralBlockTractionClick(Player player, BdeGuiHolder holder, int slot, ItemStack clickedItem, InventoryClickEvent event) {
        if (slot == 45) { // Close
            player.closeInventory();
            return;
        }

        if (slot == 49) { // Register block override
            ItemStack cursorItem = event.getCursor();
            if (cursorItem != null && cursorItem.getType() != Material.AIR) {
                if (!cursorItem.getType().isBlock()) {
                    player.sendMessage("§cItem must be a valid block!");
                    return;
                }
                String matName = cursorItem.getType().name();
                player.closeInventory();
                player.sendMessage("§ePlease type the traction value (double, e.g. 0.8) for " + matName + " in chat (or type 'cancel' to exit):");
                activePrompts.put(player.getUniqueId(), new ChatPromptState(ChatPromptType.SET_GLOBAL_BLOCK_TRACTION, null, null, null, matName));
            } else {
                player.sendMessage("§cPlease hold the block item you want to override on your cursor, then click this slot.");
            }
            return;
        }

        // Check if player clicked an overridden block item
        if (clickedItem != null && clickedItem.getType() != Material.AIR) {
            String matName = clickedItem.getType().name();
            if (event.getClick().isRightClick()) {
                // Delete override from config.yml
                plugin.getConfig().set("block-traction." + matName, null);
                plugin.saveConfig();
                player.sendMessage("§aDeleted general traction override for block: " + matName);
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 0.8f);
                plugin.getBdeGuiManager().openGeneralBlockTractionMenu(player);
            } else {
                // Edit override value in chat
                player.closeInventory();
                player.sendMessage("§ePlease type the new traction value (double, e.g. 0.8) for " + matName + " in chat (or type 'cancel' to exit):");
                activePrompts.put(player.getUniqueId(), new ChatPromptState(ChatPromptType.SET_GLOBAL_BLOCK_TRACTION, null, null, null, matName));
            }
        }
    }

    private void handleVehicleBlockOverridesClick(Player player, ModelInstance instance, BdeGuiHolder holder, int slot, ItemStack clickedItem, InventoryClickEvent event) {
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
                player.sendMessage("§cFailed to load model: " + ex.getMessage());
                player.closeInventory();
                return;
            }
        }

        if (model == null) {
            player.closeInventory();
            return;
        }

        BdeModel.VehicleStats stats = model.getVehicleStats();
        if (stats == null) {
            player.closeInventory();
            return;
        }

        if (slot == 45) { // Back to Vehicle Config
            plugin.getBdeGuiManager().openVehicleMenu(player, instance, model, modelProjectId);
            return;
        }

        if (slot == 46) { // Close
            player.closeInventory();
            return;
        }

        if (slot == 49) { // Register block override
            ItemStack cursorItem = event.getCursor();
            if (cursorItem != null && cursorItem.getType() != Material.AIR) {
                if (!cursorItem.getType().isBlock()) {
                    player.sendMessage("§cItem must be a valid block!");
                    return;
                }
                String matName = cursorItem.getType().name();
                player.closeInventory();
                player.sendMessage("§ePlease type the custom traction override (double, e.g. 0.5) for " + matName + " on this vehicle in chat (or type 'cancel' to exit):");
                activePrompts.put(player.getUniqueId(), new ChatPromptState(ChatPromptType.SET_VEHICLE_BLOCK_TRACTION, modelProjectId, holder.getSelectedModelId(), null, matName));
            } else {
                player.sendMessage("§cPlease hold the block item you want to override on your cursor, then click this slot.");
            }
            return;
        }

        // Check if player clicked an overridden block item
        if (clickedItem != null && clickedItem.getType() != Material.AIR) {
            String matName = clickedItem.getType().name();
            if (event.getClick().isRightClick()) {
                // Delete override from vehicle json
                stats.getBlockOverrides().remove(matName);
                if (model.getLocalFilePath() != null && model.isVehicleLibrary()) {
                    plugin.getModelManager().saveModelConfig(model);
                }
                player.sendMessage("§aDeleted traction override for block: " + matName);
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 0.8f);
                plugin.getBdeGuiManager().openVehicleBlockOverridesMenu(player, instance, model, modelProjectId);
            } else {
                // Edit override value in chat
                player.closeInventory();
                player.sendMessage("§ePlease type the custom traction override (double, e.g. 0.5) for " + matName + " on this vehicle in chat (or type 'cancel' to exit):");
                activePrompts.put(player.getUniqueId(), new ChatPromptState(ChatPromptType.SET_VEHICLE_BLOCK_TRACTION, modelProjectId, holder.getSelectedModelId(), null, matName));
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        plugin.getBdeGuiManager().clearSelection(event.getPlayer().getUniqueId());
        plugin.getModelManager().getInputTracker().uninject(event.getPlayer());
        plugin.getModelManager().restorePlayerItem(event.getPlayer());
        activePrompts.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        plugin.getBdeGuiManager().clearSelection(event.getPlayer().getUniqueId());
        plugin.getModelManager().getInputTracker().uninject(event.getPlayer());
        plugin.getModelManager().restorePlayerItem(event.getPlayer());
        activePrompts.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPlayerDeath(org.bukkit.event.entity.PlayerDeathEvent event) {
        Player player = event.getEntity();
        ItemStack original = plugin.getModelManager().getOriginalHotbarItem(player.getUniqueId());
        if (original != null) {
            event.getDrops().removeIf(item -> item != null && item.hasItemMeta() && 
                "§e§lSubsystem Operator Controls".equals(item.getItemMeta().getDisplayName()));
            
            if (original.getType() != org.bukkit.Material.AIR) {
                event.getDrops().add(original);
            }
            plugin.getModelManager().clearOriginalHotbarItem(player.getUniqueId());
        }
    }

    @EventHandler
    public void onEntityDismount(org.bukkit.event.entity.EntityDismountEvent event) {
        if (event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity();
            Entity seat = event.getDismounted();
            
            boolean isBdeSeat = seat.getScoreboardTags().contains("bde_seat") || 
                                seat.getScoreboardTags().contains("bde_driver_seat");
                                
            if (isBdeSeat && seat.isValid() && !player.isDead() && player.getHealth() > 0.0) {
                if (!player.isSneaking()) {
                    event.setCancelled(true);
                    return;
                }
            }
            
            plugin.getModelManager().handleDismount(player, seat);
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        // Hide existing highlights from joining players
        plugin.getBdeGuiManager().filterBoundaryForNewPlayer(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityDamageByEntity(org.bukkit.event.entity.EntityDamageByEntityEvent event) {
        Entity target = event.getEntity();
        if (!(target instanceof Interaction)) return;
        Interaction hitbox = (Interaction) target;
        if (!hitbox.getScoreboardTags().contains("bde_root")) return;

        ModelInstance instance = plugin.getModelManager().getInstanceByRoot(hitbox);
        if (instance == null || instance.getModel().getVehicleStats() == null) return;

        Entity damager = event.getDamager();
        if (damager instanceof Player) {
            Player player = (Player) damager;
            ModelInstance ridden = getRiddenVehicle(player);
            if (ridden != null && ridden.equals(instance)) {
                // Attacking own vehicle! Cancel the attack completely and trigger subsystem action instead.
                event.setCancelled(true);
                BdeModel.SubsystemConfig controlledSub = getControlledSubsystem(player, instance);
                if (controlledSub != null) {
                    plugin.getModelManager().triggerSubsystemAction(instance, controlledSub, player);
                }
                return;
            }
        }

        event.setCancelled(true);

        // Check for custom damage in metadata
        double dmg = event.getFinalDamage();
        if (damager.hasMetadata("bde_damage")) {
            dmg = damager.getMetadata("bde_damage").getFirst().asDouble();
        }

        double hp = instance.getCurrentHp();
        hp = Math.max(0.0, hp - dmg);
        instance.setCurrentHp(hp);

        // Visual / Audio feedback
        Location hitLoc = hitbox.getLocation();
        hitLoc.getWorld().playSound(hitLoc, org.bukkit.Sound.ENTITY_ITEM_BREAK, 1.0f, 0.8f);
        try {
            hitLoc.getWorld().spawnParticle(org.bukkit.Particle.valueOf("DAMAGE_INDICATOR"), hitLoc, 5, 0.2, 0.2, 0.2, 0.1);
        } catch (Exception ignored) {}

        // Notify attacker
        if (damager instanceof Player) {
            Player p = (Player) damager;
            p.sendMessage("§cVehicle HP: §l" + String.format("%.1f", hp) + "§7/§f" + String.format("%.1f", instance.getModel().getVehicleStats().getMaxHp()));
        } else if (damager instanceof org.bukkit.entity.Projectile) {
            org.bukkit.entity.Projectile proj = (org.bukkit.entity.Projectile) damager;
            if (proj.getShooter() instanceof Player) {
                Player p = (Player) proj.getShooter();
                p.sendMessage("§cVehicle HP: §l" + String.format("%.1f", hp) + "§7/§f" + String.format("%.1f", instance.getModel().getVehicleStats().getMaxHp()));
            }
        }

        if (hp <= 0.0) {
            hitLoc.getWorld().createExplosion(hitLoc, 2.0f, false, false);
            plugin.getModelManager().removeInstance(instance.getId());
        }
    }

    @EventHandler
    public void onPlayerSwapHandItems(org.bukkit.event.player.PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        ModelInstance instance = getRiddenVehicle(player);
        if (instance == null) return;

        event.setCancelled(true);
        instance.toggleWeaponCam(player.getUniqueId());

        boolean active = instance.isWeaponCamActive(player.getUniqueId());
        player.sendMessage(active ? "§aWeapon-Cam View: §lENABLED" : "§cWeapon-Cam View: §lDISABLED");
    }

    @EventHandler
    public void onPlayerItemHeld(org.bukkit.event.player.PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        top.sanscraft.bde.manager.ModelManager modelManager = plugin.getModelManager();
        top.sanscraft.bde.manager.ModelManager.PlacementSession session = modelManager.getPlacementSession(player.getUniqueId());
        if (session != null) {
            event.setCancelled(true);
            int prevSlot = event.getPreviousSlot();
            int newSlot = event.getNewSlot();
            int diff = newSlot - prevSlot;
            if (diff > 5) {
                diff = -1;
            } else if (diff < -5) {
                diff = 1;
            }
            session.distance = Math.max(0.5, Math.min(25.0, session.distance + diff * 0.2));
            player.getInventory().setHeldItemSlot(prevSlot);
            return;
        }

        ModelInstance instance = getRiddenVehicle(player);
        if (instance == null || instance.getModel().getVehicle() == null) return;

        BdeModel.SubsystemConfig controlledSub = getControlledSubsystem(player, instance);
        if (controlledSub == null || controlledSub.getWeaponModes().isEmpty()) return;

        event.setCancelled(true);
        player.getInventory().setHeldItemSlot(event.getPreviousSlot());

        int delta = event.getNewSlot() - event.getPreviousSlot();
        if (delta == 8) delta = -1;
        if (delta == -8) delta = 1;

        List<String> modes = controlledSub.getWeaponModes().stream().map(BdeModel.ProjectileConfig::getName).collect(java.util.stream.Collectors.toList());
        instance.cycleSubsystemMode(player.getUniqueId(), controlledSub.getName(), delta, modes);

        int newIdx = instance.getSubsystemMode(player.getUniqueId(), controlledSub.getName());
        String newMode = controlledSub.getWeaponModes().get(newIdx).getName();
        player.sendMessage("§eSubsystem §6" + controlledSub.getName() + " §eMode: §f§l" + newMode.toUpperCase());
    }

    @EventHandler
    public void onPlayerInteractWeapon(org.bukkit.event.player.PlayerInteractEvent event) {
        Player player = event.getPlayer();
        long lastDrop = lastDropTimestamp.getOrDefault(player.getUniqueId(), 0L);
        if (System.currentTimeMillis() - lastDrop < 100) {
            return;
        }

        ModelInstance instance = getRiddenVehicle(player);
        if (instance == null || instance.getModel().getVehicle() == null) return;

        BdeModel.SubsystemConfig controlledSub = getControlledSubsystem(player, instance);
        if (controlledSub == null) return;

        if (event.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_AIR || event.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK ||
            event.getAction() == org.bukkit.event.block.Action.LEFT_CLICK_AIR || event.getAction() == org.bukkit.event.block.Action.LEFT_CLICK_BLOCK) {
            event.setCancelled(true);
            plugin.getModelManager().triggerSubsystemAction(instance, controlledSub, player);
        }
    }

    private ModelInstance getRiddenVehicle(Player player) {
        Entity vehicle = player.getVehicle();
        if (vehicle == null) return null;

        String instIdStr = null;
        if (vehicle.hasMetadata("bde_instance_id")) {
            instIdStr = vehicle.getMetadata("bde_instance_id").get(0).asString();
        } else {
            Entity parent = vehicle.getVehicle();
            if (parent != null && parent.hasMetadata("bde_instance_id")) {
                instIdStr = parent.getMetadata("bde_instance_id").get(0).asString();
            }
        }

        if (instIdStr == null) return null;
        try {
            return plugin.getModelManager().getActiveInstances().get(UUID.fromString(instIdStr));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private BdeModel.SubsystemConfig getControlledSubsystem(Player player, ModelInstance instance) {
        Entity vehicle = player.getVehicle();
        if (vehicle == null) return null;

        int seatIndex = -2;
        if (vehicle.getScoreboardTags().contains("bde_driver_seat")) {
            seatIndex = -1;
        } else {
            for (String tag : vehicle.getScoreboardTags()) {
                if (tag.startsWith("bde_seat_")) {
                    try {
                        seatIndex = Integer.parseInt(tag.substring(9));
                        break;
                    } catch (NumberFormatException ignored) {}
                }
            }
        }

        if (seatIndex == -2) return null;

        for (BdeModel.SubsystemConfig sub : instance.getModel().getVehicle().getSubsystems()) {
            if (sub.getControllerSeatIndex() == seatIndex) {
                return sub;
            }
        }
        return null;
    }

    private void handleSubsystemListClick(Player player, ModelInstance instance, BdeGuiHolder holder, int slot, ItemStack clickedItem, InventoryClickEvent event) {
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
                player.sendMessage("§cFailed to load model: " + ex.getMessage());
                player.closeInventory();
                return;
            }
        }
        if (model == null) {
            player.closeInventory();
            return;
        }

        BdeModel.VehicleConfig cfg = model.getVehicle();
        if (cfg == null) {
            model.ensureVehicleConfig();
            cfg = model.getVehicle();
        }

        if (slot == 45) { // Back to Vehicle menu
            plugin.getBdeGuiManager().openVehicleMenu(player, instance, model, modelProjectId);
            return;
        }

        if (slot == 46) { // Add Subsystem
            BdeModel.SubsystemConfig sub = new BdeModel.SubsystemConfig();
            sub.setName("Subsystem " + (cfg.getSubsystems().size() + 1));
            sub.setDisplayTag("sub_" + cfg.getSubsystems().size());
            cfg.getSubsystems().add(sub);
            if (model.getLocalFilePath() != null && model.isVehicleLibrary()) {
                plugin.getModelManager().saveModelConfig(model);
            }
            if (instance != null) {
                recreateModelInstance(instance, player);
                UUID selectedId = plugin.getBdeGuiManager().getSelectedModel(player.getUniqueId());
                ModelInstance newInst = plugin.getModelManager().getActiveInstances().get(selectedId);
                plugin.getBdeGuiManager().openSubsystemListMenu(player, newInst, model, modelProjectId);
            } else {
                plugin.getBdeGuiManager().openSubsystemListMenu(player, null, model, modelProjectId);
            }
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 1.5f);
            return;
        }

        // Clicked a subsystem item
        List<Integer> slots = java.util.Arrays.asList(
            11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
        );
        int idx = slots.indexOf(slot);
        if (idx >= 0 && idx < cfg.getSubsystems().size()) {
            if (event.getClick().isRightClick()) {
                cfg.getSubsystems().remove(idx);
                if (model.getLocalFilePath() != null && model.isVehicleLibrary()) {
                    plugin.getModelManager().saveModelConfig(model);
                }
                player.sendMessage("§aSubsystem deleted.");
                if (instance != null) {
                    recreateModelInstance(instance, player);
                    UUID selectedId = plugin.getBdeGuiManager().getSelectedModel(player.getUniqueId());
                    ModelInstance newInst = plugin.getModelManager().getActiveInstances().get(selectedId);
                    plugin.getBdeGuiManager().openSubsystemListMenu(player, newInst, model, modelProjectId);
                } else {
                    plugin.getBdeGuiManager().openSubsystemListMenu(player, null, model, modelProjectId);
                }
                player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_BREAK, 0.5f, 1.0f);
            } else {
                plugin.getBdeGuiManager().openSubsystemDetailMenu(player, instance, model, modelProjectId, idx);
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.5f, 1.0f);
            }
        }
    }

    private void handleSubsystemDetailClick(Player player, ModelInstance instance, BdeGuiHolder holder, int slot, ItemStack clickedItem, InventoryClickEvent event) {
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
                player.sendMessage("§cFailed to load model: " + ex.getMessage());
                player.closeInventory();
                return;
            }
        }
        if (model == null) {
            player.closeInventory();
            return;
        }

        BdeModel.VehicleConfig cfg = model.getVehicle();
        if (cfg == null) {
            player.closeInventory();
            return;
        }

        int subIndex = holder.getSubsystemIndex();
        if (subIndex < 0 || subIndex >= cfg.getSubsystems().size()) {
            player.closeInventory();
            return;
        }
        BdeModel.SubsystemConfig sub = cfg.getSubsystems().get(subIndex);

        if (slot == 45) { // Back to Subsystem list
            plugin.getBdeGuiManager().openSubsystemListMenu(player, instance, model, modelProjectId);
            return;
        }

        if (slot == 13) { // Rename Subsystem
            player.closeInventory();
            player.sendMessage("§ePlease type the new name for the subsystem in chat (or type 'cancel' to exit):");
            activePrompts.put(player.getUniqueId(), new ChatPromptState(ChatPromptType.RENAME_SUBSYSTEM, modelProjectId, holder.getSelectedModelId(), null, String.valueOf(subIndex)));
            return;
        }

        if (slot == 14) { // Cycle controller seat index
            int maxSeats = cfg.getPassengerSeats() != null ? cfg.getPassengerSeats().size() : 0;
            int current = sub.getControllerSeatIndex();
            int next = current + 1;
            if (next >= maxSeats) {
                next = -1; // back to driver
            }
            sub.setControllerSeatIndex(next);
            if (model.getLocalFilePath() != null && model.isVehicleLibrary()) {
                plugin.getModelManager().saveModelConfig(model);
            }
            player.sendMessage("§aSubsystem operator seat updated to: " + (next == -1 ? "Driver (-1)" : "Passenger " + (next + 1)));
            plugin.getBdeGuiManager().openSubsystemDetailMenu(player, instance, model, modelProjectId, subIndex);
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 1.2f);
            return;
        }

        if (slot == 15) { // Edit Display Tag
            player.closeInventory();
            player.sendMessage("§ePlease type the scoreboard tag identifying this subsystem's displays in chat (or type 'cancel' to exit):");
            activePrompts.put(player.getUniqueId(), new ChatPromptState(ChatPromptType.EDIT_SUBSYSTEM_TAG, modelProjectId, holder.getSelectedModelId(), null, String.valueOf(subIndex)));
            return;
        }

        if (slot == 16) { // Interactive Placement Mode
            plugin.getModelManager().startPlacementSession(player, instance != null ? instance.getId() : null, modelProjectId, subIndex);
            return;
        }

        if (slot == 22) { // Open Weapon Modes List
            plugin.getBdeGuiManager().openWeaponModeListMenu(player, instance, model, modelProjectId, subIndex);
            return;
        }

        if (slot == 28) { // Edit subsystem BDE model ID
            player.closeInventory();
            player.sendMessage("§ePlease type the Model ID to mount as a subsystem (or type 'cancel' to exit):");
            activePrompts.put(player.getUniqueId(), new ChatPromptState(ChatPromptType.EDIT_SUBSYSTEM_BDE_MODEL_ID, modelProjectId, holder.getSelectedModelId(), null, String.valueOf(subIndex)));
            return;
        }

        if (slot == 29) { // Mounting Point Offset
            if (event.getClick().isRightClick()) {
                player.closeInventory();
                player.sendMessage("§ePlease type the mounting offset as X, Y, Z (decimals allowed, or type 'cancel' to exit):");
                activePrompts.put(player.getUniqueId(), new ChatPromptState(ChatPromptType.EDIT_SUBSYSTEM_MOUNT_OFFSET, modelProjectId, holder.getSelectedModelId(), null, String.valueOf(subIndex)));
            } else {
                if (instance == null || instance.getVehicleRoot() == null) {
                    player.sendMessage("§cYou can only align offsets when there is an active spawned instance in the world.");
                    return;
                }
                List<Double> offset = getPlayerRelativeOffset(player, instance, model);
                sub.setMountOffset(offset);
                if (model.getLocalFilePath() != null && model.isVehicleLibrary()) {
                    plugin.getModelManager().saveModelConfig(model);
                }
                recreateModelInstance(instance, player);
                UUID selectedId = plugin.getBdeGuiManager().getSelectedModel(player.getUniqueId());
                ModelInstance newInst = plugin.getModelManager().getActiveInstances().get(selectedId);
                plugin.getBdeGuiManager().openSubsystemDetailMenu(player, newInst, model, modelProjectId, subIndex);
                player.sendMessage("§aSubsystem mounting point aligned to player location!");
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 1.5f);
            }
            return;
        }

        if (slot == 30) { // Launching Point Offset
            if (event.getClick().isRightClick()) {
                player.closeInventory();
                player.sendMessage("§ePlease type the launching offset relative to subsystem as X, Y, Z (decimals allowed, or type 'cancel' to exit):");
                activePrompts.put(player.getUniqueId(), new ChatPromptState(ChatPromptType.EDIT_SUBSYSTEM_LAUNCH_OFFSET, modelProjectId, holder.getSelectedModelId(), null, String.valueOf(subIndex)));
            } else {
                if (instance == null || instance.getVehicleRoot() == null) {
                    player.sendMessage("§cYou can only align offsets when there is an active spawned instance in the world.");
                    return;
                }
                List<Double> offset = getPlayerRelativeOffset(player, instance, model);
                List<Double> mount = sub.getMountOffset();
                if (mount != null && mount.size() == 3) {
                    offset.set(0, offset.get(0) - mount.get(0));
                    offset.set(1, offset.get(1) - mount.get(1));
                    offset.set(2, offset.get(2) - mount.get(2));
                }
                int precision = plugin.getBdeGuiManager().getPrecision(player.getUniqueId());
                if (precision >= 0) {
                    double factor = Math.pow(10, precision);
                    offset.set(0, Math.round(offset.get(0) * factor) / factor);
                    offset.set(1, Math.round(offset.get(1) * factor) / factor);
                    offset.set(2, Math.round(offset.get(2) * factor) / factor);
                }
                sub.setLaunchOffset(offset);
                if (model.getLocalFilePath() != null && model.isVehicleLibrary()) {
                    plugin.getModelManager().saveModelConfig(model);
                }
                recreateModelInstance(instance, player);
                UUID selectedId = plugin.getBdeGuiManager().getSelectedModel(player.getUniqueId());
                ModelInstance newInst = plugin.getModelManager().getActiveInstances().get(selectedId);
                plugin.getBdeGuiManager().openSubsystemDetailMenu(player, newInst, model, modelProjectId, subIndex);
                player.sendMessage("§aSubsystem launching point aligned to player location!");
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 1.5f);
            }
            return;
        }

        if (slot == 31) { // FOV Yaw Clamps
            if (event.getClick().isRightClick()) {
                player.closeInventory();
                player.sendMessage("§ePlease type the maximum look yaw limit relative to vehicle root (in degrees, or type 'cancel' to exit):");
                activePrompts.put(player.getUniqueId(), new ChatPromptState(ChatPromptType.EDIT_SUBSYSTEM_MAX_YAW, modelProjectId, holder.getSelectedModelId(), null, String.valueOf(subIndex)));
            } else {
                player.closeInventory();
                player.sendMessage("§ePlease type the minimum look yaw limit relative to vehicle root (in degrees, or type 'cancel' to exit):");
                activePrompts.put(player.getUniqueId(), new ChatPromptState(ChatPromptType.EDIT_SUBSYSTEM_MIN_YAW, modelProjectId, holder.getSelectedModelId(), null, String.valueOf(subIndex)));
            }
            return;
        }

        if (slot == 32) { // FOV Pitch Clamps
            if (event.getClick().isRightClick()) {
                player.closeInventory();
                player.sendMessage("§ePlease type the maximum look pitch limit relative to vehicle root (in degrees, or type 'cancel' to exit):");
                activePrompts.put(player.getUniqueId(), new ChatPromptState(ChatPromptType.EDIT_SUBSYSTEM_MAX_PITCH, modelProjectId, holder.getSelectedModelId(), null, String.valueOf(subIndex)));
            } else {
                player.closeInventory();
                player.sendMessage("§ePlease type the minimum look pitch limit relative to vehicle root (in degrees, or type 'cancel' to exit):");
                activePrompts.put(player.getUniqueId(), new ChatPromptState(ChatPromptType.EDIT_SUBSYSTEM_MIN_PITCH, modelProjectId, holder.getSelectedModelId(), null, String.valueOf(subIndex)));
            }
            return;
        }

        if (slot == 33) { // Weapon-Cam Camera Offset
            if (event.getClick().isRightClick()) {
                player.closeInventory();
                player.sendMessage("§ePlease type the Weapon-Cam camera offset relative to subsystem mount point as X, Y, Z (decimals allowed, or type 'cancel' to exit):");
                activePrompts.put(player.getUniqueId(), new ChatPromptState(ChatPromptType.EDIT_SUBSYSTEM_CAMERA_OFFSET, modelProjectId, holder.getSelectedModelId(), null, String.valueOf(subIndex)));
            } else {
                if (instance == null || instance.getVehicleRoot() == null) {
                    player.sendMessage("§cYou can only align offsets when there is an active spawned instance in the world.");
                    return;
                }
                List<Double> offset = getPlayerRelativeOffset(player, instance, model);
                List<Double> mount = sub.getMountOffset();
                if (mount != null && mount.size() == 3) {
                    offset.set(0, offset.get(0) - mount.get(0));
                    offset.set(1, offset.get(1) - mount.get(1));
                    offset.set(2, offset.get(2) - mount.get(2));
                }
                int precision = plugin.getBdeGuiManager().getPrecision(player.getUniqueId());
                if (precision >= 0) {
                    double factor = Math.pow(10, precision);
                    offset.set(0, Math.round(offset.get(0) * factor) / factor);
                    offset.set(1, Math.round(offset.get(1) * factor) / factor);
                    offset.set(2, Math.round(offset.get(2) * factor) / factor);
                }
                sub.setCameraOffset(offset);
                if (model.getLocalFilePath() != null && model.isVehicleLibrary()) {
                    plugin.getModelManager().saveModelConfig(model);
                }
                recreateModelInstance(instance, player);
                UUID selectedId = plugin.getBdeGuiManager().getSelectedModel(player.getUniqueId());
                ModelInstance newInst = plugin.getModelManager().getActiveInstances().get(selectedId);
                plugin.getBdeGuiManager().openSubsystemDetailMenu(player, newInst, model, modelProjectId, subIndex);
                player.sendMessage("§aSubsystem camera offset aligned to player location!");
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 1.5f);
            }
            return;
        }

        if (slot == 34) { // Pivot Point Offset
            if (event.getClick().isRightClick()) {
                player.closeInventory();
                player.sendMessage("§ePlease type the Pivot Point Offset relative to subsystem origin as X, Y, Z (decimals allowed, or type 'cancel' to exit):");
                activePrompts.put(player.getUniqueId(), new ChatPromptState(ChatPromptType.EDIT_SUBSYSTEM_PIVOT_OFFSET, modelProjectId, holder.getSelectedModelId(), null, String.valueOf(subIndex)));
            } else {
                if (instance == null || instance.getVehicleRoot() == null) {
                    player.sendMessage("§cYou can only align offsets when there is an active spawned instance in the world.");
                    return;
                }
                List<Double> offset = getPlayerRelativeOffset(player, instance, model);
                List<Double> mount = sub.getMountOffset();
                if (mount != null && mount.size() == 3) {
                    offset.set(0, offset.get(0) - mount.get(0));
                    offset.set(1, offset.get(1) - mount.get(1));
                    offset.set(2, offset.get(2) - mount.get(2));
                }
                int precision = plugin.getBdeGuiManager().getPrecision(player.getUniqueId());
                if (precision >= 0) {
                    double factor = Math.pow(10, precision);
                    offset.set(0, Math.round(offset.get(0) * factor) / factor);
                    offset.set(1, Math.round(offset.get(1) * factor) / factor);
                    offset.set(2, Math.round(offset.get(2) * factor) / factor);
                }
                sub.setPivotOffset(offset);
                if (model.getLocalFilePath() != null && model.isVehicleLibrary()) {
                    plugin.getModelManager().saveModelConfig(model);
                }
                recreateModelInstance(instance, player);
                UUID selectedId = plugin.getBdeGuiManager().getSelectedModel(player.getUniqueId());
                ModelInstance newInst = plugin.getModelManager().getActiveInstances().get(selectedId);
                plugin.getBdeGuiManager().openSubsystemDetailMenu(player, newInst, model, modelProjectId, subIndex);
                player.sendMessage("§aSubsystem pivot point offset aligned to player location!");
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 1.5f);
            }
            return;
        }
    }

    private void handleWeaponModeListClick(Player player, ModelInstance instance, BdeGuiHolder holder, int slot, ItemStack clickedItem, InventoryClickEvent event) {
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
                player.sendMessage("§cFailed to load model: " + ex.getMessage());
                player.closeInventory();
                return;
            }
        }
        if (model == null) {
            player.closeInventory();
            return;
        }

        BdeModel.VehicleConfig cfg = model.getVehicle();
        if (cfg == null) {
            player.closeInventory();
            return;
        }

        int subIndex = holder.getSubsystemIndex();
        if (subIndex < 0 || subIndex >= cfg.getSubsystems().size()) {
            player.closeInventory();
            return;
        }
        BdeModel.SubsystemConfig sub = cfg.getSubsystems().get(subIndex);

        if (slot == 45) { // Back to Subsystem Details
            plugin.getBdeGuiManager().openSubsystemDetailMenu(player, instance, model, modelProjectId, subIndex);
            return;
        }

        if (slot == 46) { // Add Weapon Mode
            BdeModel.ProjectileConfig newProj = new BdeModel.ProjectileConfig();
            newProj.setName("Dumb Fire " + (sub.getWeaponModes().size() + 1));
            sub.getWeaponModes().add(newProj);
            if (model.getLocalFilePath() != null && model.isVehicleLibrary()) {
                plugin.getModelManager().saveModelConfig(model);
            }
            plugin.getBdeGuiManager().openWeaponModeListMenu(player, instance, model, modelProjectId, subIndex);
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 1.5f);
            return;
        }

        // Clicked a weapon mode item
        List<Integer> slots = java.util.Arrays.asList(
            11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
        );
        int idx = slots.indexOf(slot);
        if (idx >= 0 && idx < sub.getWeaponModes().size()) {
            if (event.getClick().isRightClick()) {
                sub.getWeaponModes().remove(idx);
                if (model.getLocalFilePath() != null && model.isVehicleLibrary()) {
                    plugin.getModelManager().saveModelConfig(model);
                }
                player.sendMessage("§aWeapon mode deleted.");
                plugin.getBdeGuiManager().openWeaponModeListMenu(player, instance, model, modelProjectId, subIndex);
                player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_BREAK, 0.5f, 1.0f);
            } else {
                plugin.getBdeGuiManager().openWeaponModeDetailMenu(player, instance, model, modelProjectId, subIndex, idx);
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.5f, 1.0f);
            }
        }
    }

    private void handleWeaponModeDetailClick(Player player, ModelInstance instance, BdeGuiHolder holder, int slot, ItemStack clickedItem, InventoryClickEvent event) {
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
                player.sendMessage("§cFailed to load model: " + ex.getMessage());
                player.closeInventory();
                return;
            }
        }
        if (model == null) {
            player.closeInventory();
            return;
        }

        BdeModel.VehicleConfig cfg = model.getVehicle();
        if (cfg == null) {
            player.closeInventory();
            return;
        }

        int subIndex = holder.getSubsystemIndex();
        int modeIndex = holder.getWeaponModeIndex();
        if (subIndex < 0 || subIndex >= cfg.getSubsystems().size()) {
            player.closeInventory();
            return;
        }
        BdeModel.SubsystemConfig sub = cfg.getSubsystems().get(subIndex);
        if (sub.getWeaponModes() == null || modeIndex < 0 || modeIndex >= sub.getWeaponModes().size()) {
            player.closeInventory();
            return;
        }
        BdeModel.ProjectileConfig proj = sub.getWeaponModes().get(modeIndex);

        if (slot == 45) { // Back to Weapon Modes List
            plugin.getBdeGuiManager().openWeaponModeListMenu(player, instance, model, modelProjectId, subIndex);
            return;
        }

        boolean isRightClick = event.getClick().isRightClick();
        double factor = isRightClick ? -1.0 : 1.0;

        switch (slot) {
            case 10: // Rename Weapon
                player.closeInventory();
                player.sendMessage("§ePlease type the new name for the weapon mode in chat (or type 'cancel' to exit):");
                activePrompts.put(player.getUniqueId(), new ChatPromptState(ChatPromptType.RENAME_WEAPON, modelProjectId, holder.getSelectedModelId(), modeIndex, String.valueOf(subIndex)));
                return;

            case 11: // Damage
                proj.setDamage(Math.max(0.0, proj.getDamage() + 1.0 * factor));
                break;

            case 12: // Speed
                proj.setSpeed(Math.max(0.1, proj.getSpeed() + 0.1 * factor));
                break;

            case 13: // Cooldown
                proj.setCooldown(Math.max(0.1, proj.getCooldown() + 0.1 * factor));
                break;

            case 14: // Gravity toggle
                proj.setHasGravity(!proj.isHasGravity());
                break;

            case 15: // On Hit toggle
                String hit = proj.getOnHit();
                if ("despawn".equalsIgnoreCase(hit)) {
                    proj.setOnHit("explode");
                } else if ("explode".equalsIgnoreCase(hit)) {
                    proj.setOnHit("laser");
                } else {
                    proj.setOnHit("despawn");
                }
                break;

            case 16: // Explosion Power
                proj.setExplosionPower(Math.max(0.0, proj.getExplosionPower() + 0.5 * factor));
                break;

            case 19: // Destroy Blocks toggle
                proj.setDestroyBlocks(!proj.isDestroyBlocks());
                break;

            case 20: // Vanilla Explosion Damage toggle
                proj.setVanillaExplosionDamage(!proj.isVanillaExplosionDamage());
                break;

            case 21: // Lock-On Homing toggle
                proj.setLockOn(!proj.isLockOn());
                break;

            case 22: // Lock Range
                proj.setLockRange(Math.max(0.0, proj.getLockRange() + 5.0 * factor));
                break;

            case 23: // Lock Angle
                proj.setLockAngle(Math.max(0.0, Math.min(180.0, proj.getLockAngle() + 5.0 * factor)));
                break;

            case 24: // Lock Time
                proj.setLockTime(Math.max(0.1, proj.getLockTime() + 0.5 * factor));
                break;

            case 28: // Edit BDE Projectile Model ID
                player.closeInventory();
                player.sendMessage("§ePlease type the BDE model ID to use for the projectile display in chat (or type 'cancel' to exit):");
                activePrompts.put(player.getUniqueId(), new ChatPromptState(ChatPromptType.EDIT_WEAPON_MODEL, modelProjectId, holder.getSelectedModelId(), modeIndex, String.valueOf(subIndex)));
                return;

            case 29: // Edit Launch Sound
                player.closeInventory();
                player.sendMessage("§ePlease type the launch sound key (e.g. ENTITY_GENERIC_EXPLODE) in chat (or type 'cancel' to exit):");
                activePrompts.put(player.getUniqueId(), new ChatPromptState(ChatPromptType.EDIT_WEAPON_SOUND, modelProjectId, holder.getSelectedModelId(), modeIndex, String.valueOf(subIndex)));
                return;

            case 30: // Edit Fly Particle
                player.closeInventory();
                player.sendMessage("§ePlease type the flight particle type (e.g. DUST) in chat (or type 'cancel' to exit):");
                activePrompts.put(player.getUniqueId(), new ChatPromptState(ChatPromptType.EDIT_WEAPON_FLY_PARTICLE, modelProjectId, holder.getSelectedModelId(), modeIndex, String.valueOf(subIndex)));
                return;

            case 31: // Edit Impact Particle
                player.closeInventory();
                player.sendMessage("§ePlease type the impact particle type (e.g. EXPLOSION) in chat (or type 'cancel' to exit):");
                activePrompts.put(player.getUniqueId(), new ChatPromptState(ChatPromptType.EDIT_WEAPON_IMPACT_PARTICLE, modelProjectId, holder.getSelectedModelId(), modeIndex, String.valueOf(subIndex)));
                return;

            default:
                return;
        }

        if (model.getLocalFilePath() != null && model.isVehicleLibrary()) {
            plugin.getModelManager().saveModelConfig(model);
        }
        plugin.getBdeGuiManager().openWeaponModeDetailMenu(player, instance, model, modelProjectId, subIndex, modeIndex);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, isRightClick ? 0.8f : 1.2f);
    }

    private List<Double> getPlayerRelativeOffset(Player player, ModelInstance instance, BdeModel model) {
        Location pLoc = player.getLocation();
        Location vLoc = instance.getVehicleRoot().getLocation();
        double scale = instance.getScale();
        double frontYawOffset = model.getFrontYawOffset();

        // 1. delta = pLoc - vLoc
        double dx = pLoc.getX() - vLoc.getX();
        double dy = pLoc.getY() - vLoc.getY();
        double dz = pLoc.getZ() - vLoc.getZ();

        // 2. Reverse vehicle root yaw rotation
        double revYawRad = Math.toRadians(-vLoc.getYaw());
        double cos = Math.cos(revYawRad);
        double sin = Math.sin(revYawRad);
        double rx = dx * cos - dz * sin;
        double ry = dy;
        double rz = dx * sin + dz * cos;

        // 3. Reverse front yaw rotation
        if (frontYawOffset != 0.0) {
            double radFront = Math.toRadians(-frontYawOffset);
            double cosF = Math.cos(radFront);
            double sinF = Math.sin(radFront);
            double tempX = rx * cosF - rz * sinF;
            rz = rx * sinF + rz * cosF;
            rx = tempX;
        }

        // 4. Divide by scale
        rx /= scale;
        ry /= scale;
        rz /= scale;

        // 5. Add driver seat offset
        List<Double> dsOffset = model.getSeatOffset();
        double px, py, pz;
        if (dsOffset != null && dsOffset.size() == 3) {
            px = rx + dsOffset.get(0);
            py = ry + dsOffset.get(1);
            pz = rz + dsOffset.get(2);
        } else {
            px = rx;
            py = ry;
            pz = rz;
        }

        // 6. Rounding precision
        int precision = plugin.getBdeGuiManager().getPrecision(player.getUniqueId());
        if (precision >= 0) {
            double factor = Math.pow(10, precision);
            px = Math.round(px * factor) / factor;
            py = Math.round(py * factor) / factor;
            pz = Math.round(pz * factor) / factor;
        }
        return java.util.Arrays.asList(px, py, pz);
    }

    private List<Double> parseCoords(String text) throws IllegalArgumentException {
        String[] parts = text.split(",");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Coordinates must be formatted as: X, Y, Z");
        }
        try {
            double x = Double.parseDouble(parts[0].trim());
            double y = Double.parseDouble(parts[1].trim());
            double z = Double.parseDouble(parts[2].trim());
            return java.util.Arrays.asList(x, y, z);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Coordinate values must be valid decimals.");
        }
    }

    @EventHandler
    public void onPlayerDropItem(org.bukkit.event.player.PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        ModelInstance instance = getRiddenVehicle(player);
        if (instance == null || instance.getModel().getVehicle() == null) return;
        
        BdeModel.SubsystemConfig controlledSub = getControlledSubsystem(player, instance);
        if (controlledSub == null) return;
        
        event.setCancelled(true);
        lastDropTimestamp.put(player.getUniqueId(), System.currentTimeMillis());
        
        boolean wasd = !instance.isSubsystemWasdAiming(controlledSub.getName());
        instance.setSubsystemWasdAiming(controlledSub.getName(), wasd);
        
        player.sendMessage(wasd ? "§aSubsystem Aiming Mode: §f§lWASD CONTROLS" : "§aSubsystem Aiming Mode: §f§lCAMERA CONTROLS");
        player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, wasd ? 1.5f : 0.8f);
    }

    @EventHandler
    public void onPlayerInteractPlacement(org.bukkit.event.player.PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!plugin.getModelManager().isEditingPlacement(player)) return;
        
        event.setCancelled(true);
        
        if (event.getAction() == org.bukkit.event.block.Action.LEFT_CLICK_AIR || event.getAction() == org.bukkit.event.block.Action.LEFT_CLICK_BLOCK) {
            if (player.isSneaking()) {
                plugin.getModelManager().cancelPlacementSession(player);
            } else {
                plugin.getModelManager().advancePlacementStep(player);
            }
        }
    }

    @EventHandler
    public void onInventoryClickController(org.bukkit.event.inventory.InventoryClickEvent event) {
        if (event.getCurrentItem() != null && event.getCurrentItem().hasItemMeta() && 
            "§e§lSubsystem Operator Controls".equals(event.getCurrentItem().getItemMeta().getDisplayName())) {
            event.setCancelled(true);
        }
    }
}
