package top.sanscraft.bde.manager;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import top.sanscraft.bde.SansCraftBDEPlugin;
import top.sanscraft.bde.gui.BdeGuiHolder;
import top.sanscraft.bde.model.BdeModel;
import top.sanscraft.bde.model.ModelInstance;
import top.sanscraft.bde.model.TurretConfig;

import java.io.File;
import java.util.*;

public class BdeGuiManager {
    private static final org.bukkit.Particle dustParticle;

    static {
        org.bukkit.Particle temp = null;
        try {
            temp = org.bukkit.Particle.valueOf("DUST");
        } catch (IllegalArgumentException e) {
            try {
                temp = org.bukkit.Particle.valueOf("REDSTONE");
            } catch (IllegalArgumentException ex) {
                // Fallback if neither is found (unlikely)
            }
        }
        dustParticle = temp;
    }

    private final SansCraftBDEPlugin plugin;
    private final Map<UUID, UUID> playerSelections = new HashMap<>();
    private final Map<UUID, BlockDisplay> activeBoundaries = new HashMap<>();
    private final Map<UUID, Boolean> playerSnapMode = new HashMap<>();
    private final Map<UUID, Integer> playerPrecision = new HashMap<>();

    public boolean getSnapMode(UUID uuid) {
        return playerSnapMode.getOrDefault(uuid, false);
    }

    public void toggleSnapMode(UUID uuid) {
        playerSnapMode.put(uuid, !getSnapMode(uuid));
    }

    public int getPrecision(UUID uuid) {
        return playerPrecision.getOrDefault(uuid, -1);
    }

    public void cyclePrecision(UUID uuid) {
        int current = getPrecision(uuid);
        int next;
        if (current == -1) next = 1;
        else if (current == 1) next = 2;
        else if (current == 2) next = 3;
        else next = -1;
        playerPrecision.put(uuid, next);
    }

    public BdeGuiManager(SansCraftBDEPlugin plugin) {
        this.plugin = plugin;
        startHighlightTask();
    }

    public void selectModel(Player player, UUID instanceId) {
        // Clear previous selection
        clearSelectionHighlight(player.getUniqueId());

        ModelInstance instance = plugin.getModelManager().getActiveInstances().get(instanceId);
        if (instance == null) {
            player.sendMessage("§cInvalid model instance.");
            return;
        }

        // Activate hitbox (safe to call multiple times or if vehicle)
        plugin.getModelManager().activateHitbox(instance);

        if (instance.getRootEntity() == null) {
            player.sendMessage("§cFailed to activate model hitbox.");
            return;
        }

        playerSelections.put(player.getUniqueId(), instanceId);

        // Spawn glowing boundary block display visible only to this player
        Interaction root = (Interaction) instance.getRootEntity();
        float h = root.getInteractionHeight();

        // Calculate actual bounding box
        ModelManager.BoundingBox box = ModelManager.calculateBounds(instance);

        BlockDisplay boundary = root.getWorld().spawn(root.getLocation(), BlockDisplay.class);
        boundary.setBlock(Bukkit.createBlockData(Material.BLACK_STAINED_GLASS));
        
        // Translate to align Bottom-South-West corner, offsetting Y passenger-mount lift (-h)
        Transformation trans = new Transformation(
                new Vector3f(box.getMinX(), box.getMinY() - h, box.getMinZ()),
                new Quaternionf(),
                new Vector3f(box.getWidth(), box.getHeight(), box.getDepth()),
                new Quaternionf()
        );
        boundary.setTransformation(trans);
        boundary.setGlowing(true);
        boundary.setGlowColorOverride(Color.BLACK);

        // Hide from all other players
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (!other.getUniqueId().equals(player.getUniqueId())) {
                other.hideEntity(plugin, boundary);
            }
        }

        root.addPassenger(boundary);
        activeBoundaries.put(player.getUniqueId(), boundary);

        player.sendMessage("§aSelected BDE model! Open the dashboard with §f/bde gui §aor by right-clicking it again.");
    }

    public UUID getSelectedModel(UUID playerUuid) {
        return playerSelections.get(playerUuid);
    }

    public void clearSelection(UUID playerUuid) {
        UUID selectedId = playerSelections.remove(playerUuid);
        clearSelectionHighlight(playerUuid);
        
        // If no other player has this model selected, deactivate the hitbox!
        if (selectedId != null) {
            boolean remainsSelected = false;
            for (UUID otherSelection : playerSelections.values()) {
                if (otherSelection.equals(selectedId)) {
                    remainsSelected = true;
                    break;
                }
            }
            if (!remainsSelected) {
                ModelInstance instance = plugin.getModelManager().getActiveInstances().get(selectedId);
                if (instance != null) {
                    plugin.getModelManager().deactivateHitbox(instance);
                }
            }
        }
    }

    public void clearSelectionHighlight(UUID playerUuid) {
        BlockDisplay boundary = activeBoundaries.remove(playerUuid);
        if (boundary != null) {
            boundary.remove();
        }
    }

    public void updateSelectionHighlight(UUID instanceId) {
        for (Map.Entry<UUID, UUID> entry : playerSelections.entrySet()) {
            if (entry.getValue().equals(instanceId)) {
                Player player = Bukkit.getPlayer(entry.getKey());
                if (player != null && player.isOnline()) {
                    selectModel(player, instanceId);
                }
            }
        }
    }

    public void filterBoundaryForNewPlayer(Player player) {
        for (BlockDisplay boundary : activeBoundaries.values()) {
            player.hideEntity(plugin, boundary);
        }
    }

    public void cleanupAll() {
        for (BlockDisplay boundary : activeBoundaries.values()) {
            boundary.remove();
        }
        activeBoundaries.clear();
        playerSelections.clear();
    }

    private void startHighlightTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Map.Entry<UUID, UUID> entry : playerSelections.entrySet()) {
                    Player player = Bukkit.getPlayer(entry.getKey());
                    if (player == null || !player.isOnline()) continue;

                    ModelInstance instance = plugin.getModelManager().getActiveInstances().get(entry.getValue());
                    if (instance == null || instance.getRootEntity() == null || !instance.getRootEntity().isValid()) {
                        continue;
                    }

                    Interaction root = (Interaction) instance.getRootEntity();
                    if (root.getWorld().equals(player.getWorld())) {
                        ModelManager.BoundingBox box = ModelManager.calculateBounds(instance);
                        drawWireframe(player, root.getLocation(), box);
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 10L); // every 0.5s
    }

    private void drawWireframe(Player player, Location loc, ModelManager.BoundingBox box) {
        double minX = loc.getX() + box.getMinX();
        double maxX = loc.getX() + box.getMaxX();
        double minY = loc.getY() + box.getMinY();
        double maxY = loc.getY() + box.getMaxY();
        double minZ = loc.getZ() + box.getMinZ();
        double maxZ = loc.getZ() + box.getMaxZ();

        org.bukkit.Particle.DustOptions cyanDust = new org.bukkit.Particle.DustOptions(org.bukkit.Color.fromRGB(0, 255, 255), 1.0f);

        // 4 bottom edges
        traceLine(player, minX, minY, minZ, maxX, minY, minZ, cyanDust);
        traceLine(player, minX, minY, minZ, minX, minY, maxZ, cyanDust);
        traceLine(player, maxX, minY, minZ, maxX, minY, maxZ, cyanDust);
        traceLine(player, minX, minY, maxZ, maxX, minY, maxZ, cyanDust);

        // 4 top edges
        traceLine(player, minX, maxY, minZ, maxX, maxY, minZ, cyanDust);
        traceLine(player, minX, maxY, minZ, minX, maxY, maxZ, cyanDust);
        traceLine(player, maxX, maxY, minZ, maxX, maxY, maxZ, cyanDust);
        traceLine(player, minX, maxY, maxZ, maxX, maxY, maxZ, cyanDust);

        // 4 vertical edges
        traceLine(player, minX, minY, minZ, minX, maxY, minZ, cyanDust);
        traceLine(player, maxX, minY, minZ, maxX, maxY, minZ, cyanDust);
        traceLine(player, minX, minY, maxZ, minX, maxY, maxZ, cyanDust);
        traceLine(player, maxX, minY, maxZ, maxX, maxY, maxZ, cyanDust);
    }

    private void traceLine(Player player, double x1, double y1, double z1, double x2, double y2, double z2, org.bukkit.Particle.DustOptions dust) {
        double dist = Math.sqrt(Math.pow(x2 - x1, 2) + Math.pow(y2 - y1, 2) + Math.pow(z2 - z1, 2));
        int steps = (int) Math.max(1, dist * 4.0); // 4 particles per block
        for (int i = 0; i <= steps; i++) {
            double ratio = (double) i / steps;
            double px = x1 + (x2 - x1) * ratio;
            double py = y1 + (y2 - y1) * ratio;
            double pz = z1 + (z2 - z1) * ratio;
            if (dustParticle != null) {
                player.spawnParticle(dustParticle, px, py, pz, 1, 0.0, 0.0, 0.0, 0.0, dust);
            }
        }
    }

    // --- GUI Builders ---

    public void openMainMenu(Player player, ModelInstance instance) {
        BdeGuiHolder holder = new BdeGuiHolder(BdeGuiHolder.GuiType.MAIN_MENU, instance.getId());
        Inventory inv = Bukkit.createInventory(holder, 54, "§8BDE Model Dashboard");

        // Fill border
        ItemStack pane = createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 54; i++) {
            if (i < 9 || i >= 45 || i % 9 == 0 || i % 9 == 8) {
                inv.setItem(i, pane);
            }
        }

        Location loc = instance.getLocation();
        inv.setItem(13, createGuiItem(Material.GOLDEN_CARROT, "§6Model Information",
                "§7Model ID: §f" + instance.getModel().getProjectId(),
                "§7Instance ID: §f" + instance.getId().toString().substring(0, 8),
                "§7Location: §a" + loc.getWorld().getName() + ", " + String.format("%.3f", loc.getX()) + ", " + String.format("%.3f", loc.getY()) + ", " + String.format("%.3f", loc.getZ()),
                "§7Scale: §b" + String.format("%.2f", instance.getScale()),
                "§7Passengers: §e" + (instance.getModel().getPassengers() != null ? instance.getModel().getPassengers().size() : 0)
        ));

        boolean canLink = player.hasPermission("sanscraft.bde.block.link");
        inv.setItem(20, createGuiItem(Material.GRASS_BLOCK,
                "§6Blocks Category Menu" + (canLink ? "" : " §c[LOCKED]"),
                canLink ? "§7Link this model to a custom block material." : "§cYou don't have permission to use this."
        ));

        boolean canMoveOrRotate = player.hasPermission("sanscraft.bde.move") || player.hasPermission("sanscraft.bde.rotate");
        inv.setItem(21, createGuiItem(Material.COMPASS,
                "§bMovement & Rotation Menu" + (canMoveOrRotate ? "" : " §c[LOCKED]"),
                canMoveOrRotate ? "§7Adjust translation, yaw, and pitch." : "§cYou don't have permission to use this."
        ));

        inv.setItem(22, createGuiItem(Material.SHIELD,
                "§6Collision Settings Menu",
                "§7Configure physical collision, scan range,",
                "§7and hitbox merging/normalization."
        ));

        boolean canVehicle = player.hasPermission("sanscraft.bde.vehicles");
        inv.setItem(23, createGuiItem(Material.MINECART,
                "§eVehicle Config Menu" + (canVehicle ? "" : " §c[LOCKED]"),
                canVehicle ? "§7Enable vehicle mode, cycle types, and edit stats." : "§cYou don't have permission to use this."
        ));

        boolean canAnimate = player.hasPermission("sanscraft.bde.animate");
        inv.setItem(24, createGuiItem(Material.MUSIC_DISC_13,
                "§dAnimations Menu" + (canAnimate ? "" : " §c[LOCKED]"),
                canAnimate ? "§7Select and control model animations." : "§cYou don't have permission to use this."
        ));

        boolean canDespawn = player.hasPermission("sanscraft.bde.remove");
        inv.setItem(40, createGuiItem(Material.LAVA_BUCKET,
                "§4Despawn Model" + (canDespawn ? "" : " §c[LOCKED]"),
                canDespawn ? "§7Deletes this BDE model from the world." : "§cYou don't have permission to use this."
        ));

        inv.setItem(49, createGuiItem(Material.BARRIER, "§cClose Menu"));

        player.openInventory(inv);
    }

    public void openMovementMenu(Player player, ModelInstance instance) {
        BdeGuiHolder holder = new BdeGuiHolder(BdeGuiHolder.GuiType.MOVEMENT, instance.getId());
        Inventory inv = Bukkit.createInventory(holder, 54, "§8BDE Movement & Rotation");

        ItemStack pane = createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 54; i++) {
            if (i < 9 || i >= 45 || i % 9 == 0 || i % 9 == 8) {
                inv.setItem(i, pane);
            }
        }

        Location loc = instance.getLocation();
        inv.setItem(13, createGuiItem(Material.GOLDEN_CARROT, "§6Position Information",
                "§7X: §f" + String.format("%.3f", loc.getX()),
                "§7Y: §f" + String.format("%.3f", loc.getY()),
                "§7Z: §f" + String.format("%.3f", loc.getZ()),
                "§7Yaw: §b" + String.format("%.1f°", loc.getYaw()),
                "§7Pitch: §b" + String.format("%.1f°", loc.getPitch())
        ));

        // Translation
        inv.setItem(19, createGuiItem(Material.RED_CONCRETE, "§cMove X -", "§7Click to move -0.1 blocks.", "§7Shift-Click to move -1.0 blocks."));
        inv.setItem(20, createGuiItem(Material.RED_CONCRETE, "§cMove Y -", "§7Click to move -0.1 blocks.", "§7Shift-Click to move -1.0 blocks."));
        inv.setItem(21, createGuiItem(Material.RED_CONCRETE, "§cMove Z -", "§7Click to move -0.1 blocks.", "§7Shift-Click to move -1.0 blocks."));

        inv.setItem(23, createGuiItem(Material.GREEN_CONCRETE, "§aMove X +", "§7Click to move +0.1 blocks.", "§7Shift-Click to move +1.0 blocks."));
        inv.setItem(24, createGuiItem(Material.GREEN_CONCRETE, "§aMove Y +", "§7Click to move +0.1 blocks.", "§7Shift-Click to move +1.0 blocks."));
        inv.setItem(25, createGuiItem(Material.GREEN_CONCRETE, "§aMove Z +", "§7Click to move +0.1 blocks.", "§7Shift-Click to move +1.0 blocks."));

        // Yaw
        inv.setItem(29, createGuiItem(Material.CLOCK, "§dYaw -15°", "§7Rotate left by 15 degrees."));
        inv.setItem(30, createGuiItem(Material.CLOCK, "§dYaw -90°", "§7Rotate left by 90 degrees."));
        inv.setItem(31, createGuiItem(Material.COMPASS, "§bYaw Info", "§7Current Yaw: §f" + String.format("%.1f°", loc.getYaw())));
        inv.setItem(32, createGuiItem(Material.CLOCK, "§dYaw +90°", "§7Rotate right by 90 degrees."));
        inv.setItem(33, createGuiItem(Material.CLOCK, "§dYaw +15°", "§7Rotate right by 15 degrees."));

        // Pitch
        inv.setItem(38, createGuiItem(Material.SPYGLASS, "§ePitch -15°", "§7Pitch up by 15 degrees."));
        inv.setItem(39, createGuiItem(Material.SPYGLASS, "§ePitch -90°", "§7Pitch up by 90 degrees."));
        inv.setItem(40, createGuiItem(Material.COMPASS, "§ePitch Info", "§7Current Pitch: §f" + String.format("%.1f°", loc.getPitch())));
        inv.setItem(41, createGuiItem(Material.SPYGLASS, "§ePitch +90°", "§7Pitch down by 90 degrees."));
        inv.setItem(42, createGuiItem(Material.SPYGLASS, "§ePitch +15°", "§7Pitch down by 15 degrees."));

        inv.setItem(45, createGuiItem(Material.ARROW, "§7Back to Main Menu"));
        inv.setItem(49, createGuiItem(Material.BARRIER, "§cClose Menu"));

        boolean canMove = player.hasPermission("sanscraft.bde.move");
        boolean canRotate = player.hasPermission("sanscraft.bde.rotate");

        int[] moveSlots = {19, 20, 21, 23, 24, 25};
        int[] rotationSlots = {29, 30, 32, 33, 38, 39, 41, 42};

        if (!canMove) {
            ItemStack lockedMove = createGuiItem(Material.BARRIER, "§cMovement Locked", "§7You don't have permission to move models.");
            for (int slot : moveSlots) {
                inv.setItem(slot, lockedMove);
            }
        }

        if (!canRotate) {
            ItemStack lockedRotate = createGuiItem(Material.BARRIER, "§cRotation Locked", "§7You don't have permission to rotate models.");
            for (int slot : rotationSlots) {
                inv.setItem(slot, lockedRotate);
            }
        }

        player.openInventory(inv);
    }

    public void openVehicleMenu(Player player, ModelInstance instance) {
        if (instance != null) {
            openVehicleMenu(player, instance, instance.getModel(), instance.getModel().getProjectId());
        }
    }

    public void openVehicleMenu(Player player, ModelInstance instance, BdeModel model, String modelProjectId) {
        BdeGuiHolder holder = new BdeGuiHolder(BdeGuiHolder.GuiType.VEHICLE, 
                instance != null ? instance.getId() : null, 
                modelProjectId);
        
        String title = instance != null ? "§8BDE Vehicle Config" : "§8Vehicle Config (File)";
        Inventory inv = Bukkit.createInventory(holder, 54, title);

        ItemStack pane = createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 54; i++) {
            if (i < 9 || i >= 45 || i % 9 == 0 || i % 9 == 8) {
                inv.setItem(i, pane);
            }
        }

        BdeModel.VehicleStats stats = model.getVehicleStats();

        if (stats == null) {
            inv.setItem(13, createGuiItem(Material.MINECART, "§6Vehicle Mode: §cDISABLED",
                    "§7This model is currently static.",
                    " ",
                    "§eClick to enable Vehicle Mode."
            ));
        } else {
            inv.setItem(13, createGuiItem(Material.MINECART, "§6Vehicle Mode: §aENABLED",
                    "§7Type: §e" + (model.getType() != null ? model.getType().toUpperCase() : "ARMOR_STAND"),
                    " ",
                    "§eClick to cycle type (Stand -> Minecart -> Boat)",
                    "§eShift-Click to disable Vehicle Mode"
            ));

            inv.setItem(28, createGuiItem(Material.DIAMOND_BOOTS, "§bVehicle Traction Multiplier: §e" + String.format("%.2f", stats.getTraction()),
                    "§7Multiplies block traction to adjust drift.",
                    " ",
                    "§aLeft-Click to increase by 0.05",
                    "§cRight-Click to decrease by 0.05"
            ));

            inv.setItem(34, createGuiItem(Material.SLIME_BLOCK, "§bVehicle Traction Overrides",
                    "§7Configure block-specific overrides for this vehicle.",
                    " ",
                    "§eClick to manage traction overrides."
            ));

            inv.setItem(29, createGuiItem(Material.FEATHER, "§bAcceleration",
                    "§7Value: §f" + String.format("%.4f", stats.getAcceleration()),
                    " ",
                    "§aLeft-Click to increase by 0.005",
                    "§cRight-Click to decrease by 0.005"
            ));

            inv.setItem(30, createGuiItem(Material.COAL, "§bDeceleration",
                    "§7Value: §f" + String.format("%.4f", stats.getDeceleration()),
                    " ",
                    "§aLeft-Click to increase by 0.005",
                    "§cRight-Click to decrease by 0.005"
            ));

            inv.setItem(31, createGuiItem(Material.SUGAR, "§bTop Speed",
                    "§7Value: §f" + String.format("%.3f", stats.getTopSpeed()),
                    " ",
                    "§aLeft-Click to increase by 0.05",
                    "§cRight-Click to decrease by 0.05"
            ));

            inv.setItem(32, createGuiItem(Material.SADDLE, "§bReverse Speed",
                    "§7Value: §f" + String.format("%.3f", stats.getReverseSpeed()),
                    " ",
                    "§aLeft-Click to increase by 0.02",
                    "§cRight-Click to decrease by 0.02"
            ));

            inv.setItem(33, createGuiItem(Material.BLAZE_POWDER, "§bTurn Speed",
                    "§7Value: §f" + String.format("%.2f", stats.getTurnSpeed()),
                    " ",
                    "§aLeft-Click to increase by 0.5",
                    "§cRight-Click to decrease by 0.5"
            ));

            // Catalog Settings
            BdeModel.VehicleConfig cfg = model.getVehicle();
            if (cfg == null) {
                model.ensureVehicleConfig();
                cfg = model.getVehicle();
            }

            String catName = cfg.getName();
            if (catName == null || catName.isEmpty()) {
                catName = modelProjectId != null ? modelProjectId : "None";
            }
            String catIcon = cfg.getIcon();
            Material catMat = Material.matchMaterial(catIcon);
            if (catMat == null) catMat = Material.MINECART;

            inv.setItem(14, createGuiItem(Material.NAME_TAG, "§bCatalog Name: §e" + catName,
                    "§7Left-click to change catalog display name."
            ));

            inv.setItem(15, createGuiItem(catMat, "§bCatalog Icon: §e" + catMat.name(),
                    "§7Click with an item on your cursor to set as catalog icon."
            ));

            inv.setItem(16, createGuiItem(Material.ARMOR_STAND, "§bConfigure Seats",
                    "§7Click to configure custom seat names and icons."
            ));



            inv.setItem(22, createGuiItem(Material.TARGET, "§6Configure Subsystems & Weapons",
                    "§7Configure operator seats, displays, weapons and projectiles."
            ));

            inv.setItem(17, createGuiItem(Material.COMPASS, "§bDriving Front (Yaw Offset): §e" + model.getFrontYawOffset() + "°",
                    "§7Adjusts the driving front direction of the model",
                    "§7to match the direction of travel.",
                    " ",
                    "§eLeft-Click to add 90°",
                    "§eRight-Click to cycle backwards (subtract 90°)"
            ));

            // Save & Save As buttons
            String localPath = model.getLocalFilePath();
            if (localPath != null && model.isVehicleLibrary()) {
                inv.setItem(52, createGuiItem(Material.LIME_DYE, "§aSave Vehicle",
                        "§7Saves all changes back to:",
                        "§fvehicles/" + localPath + ".json",
                        " ",
                        "§eClick to save changes."
                ));
            } else {
                inv.setItem(52, createGuiItem(Material.GRAY_DYE, "§cSave Vehicle",
                        "§7This vehicle is not registered/saved yet.",
                        "§7Please use 'Save As' to write it first."
                ));
            }

            inv.setItem(53, createGuiItem(Material.WRITABLE_BOOK, "§eSave Vehicle As...",
                    "§7Saves this vehicle configuration",
                    "§7to a new file path.",
                    " ",
                    "§eClick to save under a new path."
            ));
        }

        if (instance != null) {
            inv.setItem(45, createGuiItem(Material.ARROW, "§7Back to Main Menu"));
        } else {
            inv.setItem(45, createGuiItem(Material.ARROW, "§7Back to Vehicles Catalog"));
        }
        inv.setItem(49, createGuiItem(Material.BARRIER, "§cClose Menu"));

        player.openInventory(inv);
    }

    public void openBlocksMenu(Player player, ModelInstance instance) {
        BdeGuiHolder holder = new BdeGuiHolder(BdeGuiHolder.GuiType.BLOCKS, instance.getId());
        Inventory inv = Bukkit.createInventory(holder, 54, "§8BDE Blocks Link Config");

        ItemStack pane = createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 54; i++) {
            if (i < 9 || i >= 45 || i % 9 == 0 || i % 9 == 8) {
                inv.setItem(i, pane);
            }
        }

        CustomBlockManager.CustomBlockConfig config = plugin.getCustomBlockManager().getConfig(instance.getModel().getProjectId());

        if (config == null) {
            inv.setItem(13, createGuiItem(Material.GRASS_BLOCK, "§6Linked Block Material: §cNOT LINKED (Default: GRASS_BLOCK)",
                    "§7This model is not linked to a custom block definition yet.",
                    " ",
                    "§eClick to link model to a GRASS_BLOCK default."
            ));
        } else {
            inv.setItem(13, createGuiItem(config.material, "§6Linked Block Material: §a" + config.material.name(),
                    "§7Custom Block ID: §f" + config.id,
                    "§7Display Name: §f" + config.displayName,
                    "§7Scale: §b" + String.format("%.2f", config.scale),
                    " ",
                    "§eClick to cycle linked block material",
                    "§eShift-Click to remove block link"
            ));
        }

        inv.setItem(45, createGuiItem(Material.ARROW, "§7Back to Main Menu"));
        inv.setItem(49, createGuiItem(Material.BARRIER, "§cClose Menu"));

    }

    public void openCollisionSettingsMenu(Player player, ModelInstance instance) {
        BdeGuiHolder holder = new BdeGuiHolder(BdeGuiHolder.GuiType.COLLISION_SETTINGS, instance.getId());
        Inventory inv = Bukkit.createInventory(holder, 54, "§8BDE Collision Settings");

        ItemStack pane = createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 54; i++) {
            if (i < 9 || i >= 45 || i % 9 == 0 || i % 9 == 8) {
                inv.setItem(i, pane);
            }
        }

        BdeModel model = instance.getModel();
        boolean isCollidable = model.getCollidable() != null && model.getCollidable();

        inv.setItem(13, createGuiItem(Material.SHIELD,
                "§6Collision Mode: " + (isCollidable ? "§aENABLED" : "§cDISABLED"),
                "§7Toggle whether this model has physical collision.",
                " ",
                "§eClick to toggle collision."
        ));

        float threshold = model.getHitboxScanThreshold() != null ? model.getHitboxScanThreshold() : 1.5f;
        inv.setItem(20, createGuiItem(Material.SPYGLASS, "§bHitbox Scan Threshold: §e" + String.format("%.2f", threshold),
                "§7Determines how block displays are clustered into hitboxes.",
                "§7Larger values result in fewer, larger hitboxes.",
                "§7Smaller values result in more, smaller hitboxes.",
                " ",
                "§aLeft-Click to increase by 0.1",
                "§cRight-Click to decrease by 0.1"
        ));

        float mergeDist = model.getHitboxMergeDistance() != null ? model.getHitboxMergeDistance() : 2.0f;
        inv.setItem(22, createGuiItem(Material.COMPASS, "§bHitbox Merge Distance: §e" + String.format("%.2f", mergeDist),
                "§7Maximum distance between hitboxes to consider merging.",
                "§7Hitboxes closer than this may be combined.",
                " ",
                "§aLeft-Click to increase by 0.2",
                "§cRight-Click to decrease by 0.2"
        ));

        float mergeVolume = model.getHitboxMergeVolumeLimit() != null ? model.getHitboxMergeVolumeLimit() : 1.5f;
        inv.setItem(24, createGuiItem(Material.BUCKET, "§bMerge Volume Limit: §e" + String.format("%.2f", mergeVolume),
                "§7Maximum volume growth ratio allowed when merging.",
                "§7For example, 1.5 means the combined box cannot",
                "§7exceed 150% of the sum of the original volumes.",
                " ",
                "§aLeft-Click to increase by 0.05",
                "§cRight-Click to decrease by 0.05"
        ));

        inv.setItem(45, createGuiItem(Material.ARROW, "§7Back to Main Menu"));
        inv.setItem(49, createGuiItem(Material.BARRIER, "§cClose Menu"));

        player.openInventory(inv);
    }

    public void openAnimationsMenu(Player player, ModelInstance instance) {
        BdeGuiHolder holder = new BdeGuiHolder(BdeGuiHolder.GuiType.ANIMATIONS, instance.getId());
        Inventory inv = Bukkit.createInventory(holder, 54, "§8BDE Animations Menu");

        ItemStack pane = createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 54; i++) {
            if (i < 9 || i >= 45 || i % 9 == 0 || i % 9 == 8) {
                inv.setItem(i, pane);
            }
        }

        inv.setItem(45, createGuiItem(Material.ARROW, "§7Back to Main Menu"));
        inv.setItem(49, createGuiItem(Material.BARRIER, "§cClose Menu"));

        // Playback Status controls
        boolean isPlaying = plugin.getAnimationEngine().isPlaying(instance.getId());
        ItemStack statusItem;
        if (isPlaying) {
            statusItem = createGuiItem(Material.LIME_DYE, "§aPause Animation", "§7Click to pause current playbacks.");
        } else {
            statusItem = createGuiItem(Material.GRAY_DYE, "§7No Active Animation", "§7Play an animation below first.");
        }
        inv.setItem(40, statusItem);

        inv.setItem(41, createGuiItem(Material.COAL, "§bSpeed 0.25x"));
        inv.setItem(42, createGuiItem(Material.IRON_INGOT, "§bSpeed 0.5x"));
        inv.setItem(43, createGuiItem(Material.GOLD_INGOT, "§bSpeed 1.0x (Default)"));
        inv.setItem(44, createGuiItem(Material.DIAMOND, "§bSpeed 1.5x"));
        inv.setItem(46, createGuiItem(Material.NETHERITE_INGOT, "§bSpeed 2.0x"));

        // Populate animation list
        BdeModel model = instance.getModel();
        int slot = 10;
        if (model.getDatapack() != null && model.getDatapack().getAnimKeyframes() != null) {
            for (String animName : model.getDatapack().getAnimKeyframes().keySet()) {
                if (slot >= 35) break; // Limit to viewable area
                if (slot % 9 == 0 || slot % 9 == 8) slot++;

                inv.setItem(slot, createGuiItem(Material.WRITTEN_BOOK, "§e" + animName,
                        "§7Click to §aPlay Loop",
                        "§7Shift-Click to §bPlay Once"
                ));
                slot++;
            }
        }

        player.openInventory(inv);
    }

    public void openConverterMenu(Player player) {
        BdeGuiHolder holder = new BdeGuiHolder(BdeGuiHolder.GuiType.CONVERTER, null);
        Inventory inv = Bukkit.createInventory(holder, 54, "§8BDE Model Manager");

        ItemStack pane = createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 54; i++) {
            if (i < 9 || i >= 45 || i % 9 == 0 || i % 9 == 8) {
                inv.setItem(i, pane);
            }
        }

        inv.setItem(45, createGuiItem(Material.ARROW, "§7Back to Main Menu"));
        inv.setItem(49, createGuiItem(Material.BARRIER, "§cClose Menu"));

        // Read files in models folder
        File folder = new File(plugin.getDataFolder(), "models");
        File[] files = folder.listFiles();
        int slot = 10;
        if (files != null) {
            for (File file : files) {
                if (slot >= 35) break;
                if (slot % 9 == 0 || slot % 9 == 8) slot++;

                String name = file.getName();
                if (name.endsWith(".vox")) {
                    inv.setItem(slot, createGuiItem(Material.CLAY_BALL, "§6" + name,
                            "§7Voxel Voxel model.",
                            "§eClick to Convert (Normal)",
                            "§eShift-Click to Convert (Downsample x2)"
                    ));
                } else if (name.endsWith(".json")) {
                    inv.setItem(slot, createGuiItem(Material.ARMOR_STAND, "§a" + name,
                            "§7BDE Model File.",
                            "§eClick to Spawn at your location."
                    ));
                }
                slot++;
            }
        }

        player.openInventory(inv);
    }

    private ItemStack createGuiItem(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(Arrays.asList(lore));
            item.setItemMeta(meta);
        }
        return item;
    }

    private final Map<UUID, File> playerCatalogDirectory = new HashMap<>();

    public File getPlayerCatalogDirectory(UUID uuid) {
        File dir = playerCatalogDirectory.get(uuid);
        if (dir == null) {
            dir = new File(plugin.getDataFolder(), "models");
            playerCatalogDirectory.put(uuid, dir);
        }
        return dir;
    }

    public void setPlayerCatalogDirectory(UUID uuid, File dir) {
        playerCatalogDirectory.put(uuid, dir);
    }

    public void openVehiclesCatalog(Player player, File directory) {
        if (directory == null) {
            directory = new File(plugin.getDataFolder(), "vehicles");
        }
        if (!directory.exists()) {
            directory.mkdirs();
        }
        playerCatalogDirectory.put(player.getUniqueId(), directory);

        BdeGuiHolder holder = new BdeGuiHolder(BdeGuiHolder.GuiType.VEHICLES_CATALOG, null, null);
        Inventory inv = Bukkit.createInventory(holder, 54, "§8Vehicles Catalog");

        ItemStack pane = createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 54; i++) {
            if (i < 9 || i >= 45 || i % 9 == 0 || i % 9 == 8) {
                inv.setItem(i, pane);
            }
        }

        File rootModelsDir = new File(plugin.getDataFolder(), "vehicles");
        if (!directory.equals(rootModelsDir)) {
            inv.setItem(45, createGuiItem(Material.ARROW, "§eUp to Parent Folder", "§7Go back to the parent directory."));
        }

        inv.setItem(46, createGuiItem(Material.WRITABLE_BOOK, "§aCreate Subdirectory", "§7Click to create a folder here."));
        inv.setItem(49, createGuiItem(Material.BARRIER, "§cClose Menu"));

        File[] files = directory.listFiles();
        int slot = 10;
        if (files != null) {
            Arrays.sort(files, (f1, f2) -> {
                if (f1.isDirectory() && !f2.isDirectory()) return -1;
                if (!f1.isDirectory() && f2.isDirectory()) return 1;
                return f1.getName().compareToIgnoreCase(f2.getName());
            });

            for (File file : files) {
                if (slot >= 44) break;
                if (slot % 9 == 0 || slot % 9 == 8) slot++;

                String name = file.getName();
                if (file.isDirectory()) {
                    inv.setItem(slot, createGuiItem(Material.CHEST, "§e[Folder] " + name,
                            "§7Click to open folder."
                    ));
                    slot++;
                } else if (name.endsWith(".json")) {
                    try {
                        String relPath = getRelativePath(file);
                        BdeModel m = plugin.getModelManager().loadModelSync(relPath.replace(".json", ""));
                        if (m != null && m.getVehicle() != null) {
                            String displayName = m.getVehicle().getName();
                            if (displayName == null || displayName.isEmpty()) {
                                displayName = name.substring(0, name.length() - 5);
                            }
                            String iconMatName = m.getVehicle().getIcon();
                            Material mat = Material.matchMaterial(iconMatName);
                            if (mat == null) mat = Material.MINECART;

                            inv.setItem(slot, createGuiItem(mat, "§a" + displayName,
                                    "§7File: §f" + relPath,
                                    "§7Type: §e" + m.getVehicle().getType().toUpperCase(),
                                    " ",
                                    "§eLeft-Click to spawn",
                                    "§eRight-Click to configure",
                                    "§8Path: " + relPath
                            ));
                            slot++;
                        }
                    } catch (Exception ignored) {}
                }
            }
        }

        player.openInventory(inv);
    }

    public void openSeatSelectionMenu(Player player, ModelInstance instance) {
        BdeGuiHolder holder = new BdeGuiHolder(BdeGuiHolder.GuiType.SEAT_SELECTION, instance.getId());
        Inventory inv = Bukkit.createInventory(holder, 27, "§8Select a Seat");

        ItemStack pane = createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 27; i++) {
            if (i < 9 || i >= 18 || i == 9 || i == 17) {
                inv.setItem(i, pane);
            }
        }

        inv.setItem(22, createGuiItem(Material.BARRIER, "§cClose Menu"));

        BdeModel model = instance.getModel();
        BdeModel.VehicleConfig cfg = model.getVehicle();
        
        Player driver = null;
        if (instance.getDriverSeat() != null) {
            for (Entity p : instance.getDriverSeat().getPassengers()) {
                if (p instanceof Player) {
                    driver = (Player) p;
                    break;
                }
            }
        } else if (instance.getVehicleRoot() != null) {
            for (Entity p : instance.getVehicleRoot().getPassengers()) {
                if (p instanceof Player) {
                    driver = (Player) p;
                    break;
                }
            }
        }

        String drName = cfg != null ? cfg.getDriverSeatName() : "Driver Seat";
        String drIcon = cfg != null ? cfg.getDriverSeatIcon() : "SADDLE";
        Material drMat = Material.matchMaterial(drIcon);
        if (drMat == null) drMat = Material.SADDLE;

        if (driver == null) {
            inv.setItem(10, createGuiItem(drMat, "§a" + drName, "§7Click to sit in this seat.", "§8Type: driver"));
        } else {
            inv.setItem(10, createGuiItem(Material.BARRIER, "§c" + drName + " (Occupied)", "§7Occupied by: §f" + driver.getName()));
        }

        List<BdeModel.PassengerSeatConfig> pSeats = cfg != null ? cfg.getPassengerSeats() : new ArrayList<>();
        List<org.bukkit.entity.ArmorStand> activeSeats = instance.getPassengerSeats();

        for (int i = 0; i < pSeats.size(); i++) {
            BdeModel.PassengerSeatConfig pcfg = pSeats.get(i);
            String psName = pcfg.getName();
            String psIcon = pcfg.getIcon();
            Material psMat = Material.matchMaterial(psIcon);
            if (psMat == null) psMat = Material.MINECART;

            Player occupant = null;
            for (org.bukkit.entity.ArmorStand stand : activeSeats) {
                if (stand.getScoreboardTags().contains("bde_seat_" + i)) {
                    for (Entity p : stand.getPassengers()) {
                        if (p instanceof Player) {
                            occupant = (Player) p;
                            break;
                        }
                    }
                    break;
                }
            }

            int itemSlot = 11 + i;
            if (itemSlot >= 17) break;

            if (occupant == null) {
                inv.setItem(itemSlot, createGuiItem(psMat, "§a" + psName, "§7Click to sit in this seat.", "§8Type: passenger", "§8Index: " + i));
            } else {
                inv.setItem(itemSlot, createGuiItem(Material.BARRIER, "§c" + psName + " (Occupied)", "§7Occupied by: §f" + occupant.getName()));
            }
        }

        player.openInventory(inv);
    }

    public void openSeatConfigMenu(Player player, ModelInstance instance, BdeModel model, String modelProjectId) {
        BdeGuiHolder holder = new BdeGuiHolder(BdeGuiHolder.GuiType.SEAT_CONFIGURATION, 
                instance != null ? instance.getId() : null, 
                modelProjectId);
        Inventory inv = Bukkit.createInventory(holder, 54, "§8Configure Seats");

        ItemStack pane = createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 54; i++) {
            if (i < 9 || i >= 45 || i % 9 == 0 || i % 9 == 8) {
                inv.setItem(i, pane);
            }
        }

        inv.setItem(45, createGuiItem(Material.ARROW, "§7Back to Vehicle Menu"));
        inv.setItem(46, createGuiItem(Material.SLIME_BALL, "§aAdd Passenger Seat",
                "§7Click to add a new passenger seat to this vehicle."
        ));
        inv.setItem(49, createGuiItem(Material.BARRIER, "§cClose Menu"));

        BdeModel.VehicleConfig cfg = model.getVehicle();
        if (cfg == null) {
            model.ensureVehicleConfig();
            cfg = model.getVehicle();
        }

        String drName = cfg.getDriverSeatName();
        String drIcon = cfg.getDriverSeatIcon();
        Material drMat = Material.matchMaterial(drIcon);
        if (drMat == null) drMat = Material.SADDLE;

        List<Double> drOffset = cfg.getSeatOffset() != null ? cfg.getSeatOffset() : Arrays.asList(0.0, 0.0, 0.0);
        double drYaw = cfg.getDriverSeatYaw();

        inv.setItem(10, createGuiItem(drMat, "§eSeat: " + drName,
                "§7Role: §fDriver",
                "§7Icon: §f" + drMat.name(),
                "§7Offset: §f" + String.format("%.2f, %.2f, %.2f", drOffset.get(0), drOffset.get(1), drOffset.get(2)),
                "§7Rotation: §f" + String.format("%.1f°", drYaw),
                " ",
                "§eClick to edit seat details",
                "§8Type: driver"
        ));

        List<BdeModel.PassengerSeatConfig> pSeats = cfg.getPassengerSeats();
        List<Integer> slots = Arrays.asList(
            11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
        );

        for (int i = 0; i < pSeats.size(); i++) {
            if (i >= slots.size()) break;
            BdeModel.PassengerSeatConfig pcfg = pSeats.get(i);
            String psName = pcfg.getName();
            String psIcon = pcfg.getIcon();
            Material psMat = Material.matchMaterial(psIcon);
            if (psMat == null) psMat = Material.MINECART;

            List<Double> psOffset = pcfg.getOffset() != null ? pcfg.getOffset() : Arrays.asList(0.0, 0.0, 0.0);
            double psYaw = pcfg.getYaw();

            inv.setItem(slots.get(i), createGuiItem(psMat, "§eSeat: " + psName,
                    "§7Role: §fPassenger",
                    "§7Icon: §f" + psMat.name(),
                    "§7Offset: §f" + String.format("%.2f, %.2f, %.2f", psOffset.get(0), psOffset.get(1), psOffset.get(2)),
                    "§7Rotation: §f" + String.format("%.1f°", psYaw),
                    " ",
                    "§eClick to edit seat details",
                    "§8Type: passenger",
                    "§8Index: " + i
            ));
        }

        player.openInventory(inv);
    }

    public void openSeatDetailMenu(Player player, ModelInstance instance, BdeModel model, String modelProjectId, int seatIndex) {
        BdeGuiHolder holder = new BdeGuiHolder(BdeGuiHolder.GuiType.SEAT_DETAIL, 
                instance != null ? instance.getId() : null, 
                modelProjectId, seatIndex);
        Inventory inv = Bukkit.createInventory(holder, 45, "§8Seat Editor");

        ItemStack pane = createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 45; i++) {
            if (i < 9 || i >= 36 || i % 9 == 0 || i % 9 == 8) {
                inv.setItem(i, pane);
            }
        }

        BdeModel.VehicleConfig cfg = model.getVehicle();
        if (cfg == null) {
            model.ensureVehicleConfig();
            cfg = model.getVehicle();
        }

        String seatName;
        String seatIcon;
        List<Double> offset;
        double yawVal;
        boolean isDriver = (seatIndex == -1);

        if (isDriver) {
            seatName = cfg.getDriverSeatName();
            seatIcon = cfg.getDriverSeatIcon();
            offset = cfg.getSeatOffset() != null ? cfg.getSeatOffset() : Arrays.asList(0.0, 0.0, 0.0);
            yawVal = cfg.getDriverSeatYaw();
        } else {
            BdeModel.PassengerSeatConfig pcfg = cfg.getPassengerSeats().get(seatIndex);
            seatName = pcfg.getName();
            seatIcon = pcfg.getIcon();
            offset = pcfg.getOffset() != null ? pcfg.getOffset() : Arrays.asList(0.0, 0.0, 0.0);
            yawVal = pcfg.getYaw();
        }

        Material seatMat = Material.matchMaterial(seatIcon);
        if (seatMat == null) seatMat = isDriver ? Material.SADDLE : Material.MINECART;

        List<Double> finalOffset = new ArrayList<>(offset);
        while (finalOffset.size() < 3) {
            finalOffset.add(0.0);
        }

        inv.setItem(13, createGuiItem(seatMat, "§eSeat: " + seatName,
                "§7Role: §f" + (isDriver ? "Driver" : "Passenger"),
                "§7Icon: §f" + seatMat.name(),
                "§7Offset: §f" + String.format("%.2f, %.2f, %.2f", finalOffset.get(0), finalOffset.get(1), finalOffset.get(2)),
                "§7Rotation: §f" + String.format("%.1f°", yawVal)
        ));

        // Preferences
        boolean snap = getSnapMode(player.getUniqueId());
        int precision = getPrecision(player.getUniqueId());
        
        inv.setItem(18, createGuiItem(
                snap ? Material.LIME_CONCRETE : Material.RED_CONCRETE,
                snap ? "§aSnap Mode: §lON" : "§cSnap Mode: §lOFF",
                "§7Snaps seat yaw to nearest cardinal direction",
                "§7(0°, 90°, 180°, 270°) when using 'Offset Seat to Here'.",
                " ",
                "§eClick to toggle Snap Mode"
        ));
        
        String precStr = precision == -1 ? "Off" : (precision + " Decimals");
        inv.setItem(27, createGuiItem(
                Material.COMPARATOR,
                "§bRounding Precision: §e" + precStr,
                "§7Rounds seat offset coordinates when using",
                "§7'Offset Seat to Here'.",
                " ",
                "§eClick to cycle precision (Off -> 1 -> 2 -> 3)"
        ));

        inv.setItem(19, createGuiItem(
                Material.ENDER_PEARL,
                "§aOffset Seat to Here",
                "§7Aligns this seat to your current position",
                "§7relative to the vehicle root.",
                " ",
                "§eClick to calculate and apply offset"
        ));

        inv.setItem(20, createGuiItem(Material.NAME_TAG, "§bRename Seat",
                "§7Click to rename this seat in chat."
        ));
        inv.setItem(21, createGuiItem(Material.PAINTING, "§bSet Seat Icon",
                "§7Click with an item on your cursor to set as icon."
        ));

        inv.setItem(22, createGuiItem(Material.RED_WOOL, "§cEdit Offset X: §f" + String.format("%.2f", finalOffset.get(0)),
                "§7Controls left/right seat position.",
                " ",
                "§aLeft-Click to add 0.1",
                "§cRight-Click to subtract 0.1",
                "§aShift-Left-Click to add 0.01",
                "§cShift-Right-Click to subtract 0.01"
        ));
        inv.setItem(23, createGuiItem(Material.GREEN_WOOL, "§aEdit Offset Y: §f" + String.format("%.2f", finalOffset.get(1)),
                "§7Controls seat height.",
                " ",
                "§aLeft-Click to add 0.1",
                "§cRight-Click to subtract 0.1",
                "§aShift-Left-Click to add 0.01",
                "§cShift-Right-Click to subtract 0.01"
        ));
        inv.setItem(24, createGuiItem(Material.BLUE_WOOL, "§9Edit Offset Z: §f" + String.format("%.2f", finalOffset.get(2)),
                "§7Controls forward/backward seat position.",
                " ",
                "§aLeft-Click to add 0.1",
                "§cRight-Click to subtract 0.1",
                "§aShift-Left-Click to add 0.01",
                "§cShift-Right-Click to subtract 0.01"
        ));

        inv.setItem(26, createGuiItem(Material.COMPASS, "§bSeat Rotation (Yaw): §e" + String.format("%.1f°", yawVal),
                "§7Sets player's facing direction while riding.",
                " ",
                "§eLeft-Click to cycle cardinally (0° -> 90° -> 180° -> 270°)",
                "§eRight-Click to type a custom double angle in chat"
        ));

        if (!isDriver) {
            inv.setItem(25, createGuiItem(Material.REDSTONE_BLOCK, "§cDelete Passenger Seat",
                    "§7Deletes this passenger seat from the vehicle."
            ));
        }

        inv.setItem(36, createGuiItem(Material.ARROW, "§7Back to Seats List"));
        inv.setItem(40, createGuiItem(Material.BARRIER, "§cClose Menu"));

        player.openInventory(inv);
    }

    private String getRelativePath(File file) {
        File base = new File(plugin.getDataFolder(), "vehicles");
        String baseUri = base.toURI().toString();
        String fileUri = file.toURI().toString();
        if (fileUri.startsWith(baseUri)) {
            return fileUri.substring(baseUri.length());
        }
        return file.getName();
    }

    public void openGeneralBlockTractionMenu(Player player) {
        BdeGuiHolder holder = new BdeGuiHolder(BdeGuiHolder.GuiType.GENERAL_BLOCK_TRACTION, null, null, -1);
        Inventory inv = Bukkit.createInventory(holder, 54, "§8Global Block Traction");

        ItemStack pane = createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 54; i++) {
            if (i < 9 || i >= 45 || i % 9 == 0 || i % 9 == 8) {
                inv.setItem(i, pane);
            }
        }

        inv.setItem(49, createGuiItem(Material.SLIME_BALL, "§aRegister Block Override",
                "§7Click this with a block item on your cursor",
                "§7to register a new traction override for that block type!"
        ));
        inv.setItem(45, createGuiItem(Material.BARRIER, "§cClose Menu"));

        Set<String> blockNames = new LinkedHashSet<>();
        org.bukkit.configuration.ConfigurationSection section = plugin.getConfig().getConfigurationSection("block-traction");
        if (section != null) {
            blockNames.addAll(section.getKeys(false));
        }
        blockNames.add("BLUE_ICE");
        blockNames.add("PACKED_ICE");
        blockNames.add("ICE");
        blockNames.add("SLIME_BLOCK");
        blockNames.add("SOUL_SAND");

        int slot = 9;
        for (String key : blockNames) {
            if (slot >= 45) break;
            while (slot < 45 && (slot % 9 == 0 || slot % 9 == 8)) {
                slot++;
            }
            if (slot >= 45) break;

            Material mat = Material.matchMaterial(key);
            if (mat == null) continue;

            double val = section != null && section.contains(key) ? section.getDouble(key) : getHardcodedBlockTraction(mat);
            inv.setItem(slot, createGuiItem(mat, "§eBlock: §f" + mat.name(),
                    "§7Traction: §e" + val,
                    " ",
                    "§bLeft-Click to edit traction",
                    "§cRight-Click to delete override"
            ));
            slot++;
        }

        player.openInventory(inv);
    }

    private double getHardcodedBlockTraction(Material mat) {
        switch (mat) {
            case BLUE_ICE: return 0.02;
            case PACKED_ICE: return 0.05;
            case ICE: return 0.1;
            case SLIME_BLOCK: return 0.2;
            case SOUL_SAND: return 0.4;
            default: return 1.0;
        }
    }

    public void openVehicleBlockOverridesMenu(Player player, ModelInstance instance, BdeModel model, String modelProjectId) {
        BdeGuiHolder holder = new BdeGuiHolder(BdeGuiHolder.GuiType.VEHICLE_BLOCK_OVERRIDES, 
                instance != null ? instance.getId() : null, 
                modelProjectId);
        Inventory inv = Bukkit.createInventory(holder, 54, "§8Vehicle Traction Overrides");

        ItemStack pane = createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 54; i++) {
            if (i < 9 || i >= 45 || i % 9 == 0 || i % 9 == 8) {
                inv.setItem(i, pane);
            }
        }

        inv.setItem(49, createGuiItem(Material.SLIME_BALL, "§aRegister Block Override",
                "§7Click this with a block item on your cursor",
                "§7to register a new traction override for that block type!"
        ));
        
        inv.setItem(45, createGuiItem(Material.ARROW, "§7Back to Vehicle Config"));
        inv.setItem(46, createGuiItem(Material.BARRIER, "§cClose Menu"));

        BdeModel.VehicleStats stats = model.getVehicleStats();
        if (stats != null && stats.getBlockOverrides() != null) {
            int slot = 9;
            for (Map.Entry<String, Double> entry : stats.getBlockOverrides().entrySet()) {
                if (slot >= 45) break;
                while (slot < 45 && (slot % 9 == 0 || slot % 9 == 8)) {
                    slot++;
                }
                if (slot >= 45) break;

                Material mat = Material.matchMaterial(entry.getKey());
                if (mat == null) continue;

                inv.setItem(slot, createGuiItem(mat, "§eBlock: §f" + mat.name(),
                        "§7Traction Override: §e" + entry.getValue(),
                        " ",
                        "§bLeft-Click to edit override",
                        "§cRight-Click to delete override"
                ));
                slot++;
            }
        }

        player.openInventory(inv);
    }

    public void openSubsystemListMenu(Player player, ModelInstance instance, BdeModel model, String modelProjectId) {
        BdeGuiHolder holder = new BdeGuiHolder(BdeGuiHolder.GuiType.SUBSYSTEM_LIST,
                instance != null ? instance.getId() : null,
                modelProjectId);
        Inventory inv = Bukkit.createInventory(holder, 54, "§8Subsystems Configuration");

        ItemStack pane = createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 54; i++) {
            if (i < 9 || i >= 45 || i % 9 == 0 || i % 9 == 8) {
                inv.setItem(i, pane);
            }
        }

        inv.setItem(45, createGuiItem(Material.ARROW, "§7Back to Vehicle Config"));
        inv.setItem(46, createGuiItem(Material.ANVIL, "§aAdd New Subsystem", "§7Click to mount a new weapon subsystem."));
        inv.setItem(49, createGuiItem(Material.BARRIER, "§cClose Menu"));

        BdeModel.VehicleConfig cfg = model.getVehicle();
        if (cfg != null && cfg.getSubsystems() != null) {
            List<Integer> slots = java.util.Arrays.asList(
                11, 12, 13, 14, 15, 16,
                19, 20, 21, 22, 23, 24, 25,
                28, 29, 30, 31, 32, 33, 34,
                37, 38, 39, 40, 41, 42, 43
            );
            for (int i = 0; i < cfg.getSubsystems().size(); i++) {
                if (i >= slots.size()) break;
                BdeModel.SubsystemConfig sub = cfg.getSubsystems().get(i);
                inv.setItem(slots.get(i), createGuiItem(Material.TARGET, "§eSubsystem: §l" + sub.getName(),
                        "§7Operator Seat Index: §f" + (sub.getControllerSeatIndex() == -1 ? "Driver" : "Passenger " + (sub.getControllerSeatIndex() + 1)),
                        "§7Display Tag: §f" + sub.getDisplayTag(plugin.getModelManager()),
                        "§7Weapon Modes Count: §f" + (sub.getWeaponModes(plugin.getModelManager()) != null ? sub.getWeaponModes(plugin.getModelManager()).size() : 0),
                        " ",
                        "§bLeft-Click to edit details",
                        "§cRight-Click to delete subsystem"
                ));
            }
        }

        player.openInventory(inv);
    }

    public void openSubsystemDetailMenu(Player player, ModelInstance instance, BdeModel model, String modelProjectId, int subsystemIndex) {
        BdeGuiHolder holder = new BdeGuiHolder(BdeGuiHolder.GuiType.SUBSYSTEM_DETAIL,
                instance != null ? instance.getId() : null,
                modelProjectId, null, subsystemIndex, null);
        Inventory inv = Bukkit.createInventory(holder, 54, "§8Subsystem Details");

        ItemStack pane = createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 54; i++) {
            if (i < 9 || i >= 45 || i % 9 == 0 || i % 9 == 8) {
                inv.setItem(i, pane);
            }
        }

        BdeModel.VehicleConfig cfg = model.getVehicle();
        if (cfg != null && subsystemIndex >= 0 && subsystemIndex < cfg.getSubsystems().size()) {
            BdeModel.SubsystemConfig sub = cfg.getSubsystems().get(subsystemIndex);
            ModelManager mm = plugin.getModelManager();
            
            inv.setItem(13, createGuiItem(Material.NAME_TAG, "§eSubsystem Name: §l" + sub.getName(),
                    "§7Current name of the subsystem mounting slot.",
                    " ",
                    "§eLeft-Click to change name."
            ));

            inv.setItem(14, createGuiItem(Material.SADDLE, "§eOperator Seat Index: §f" + sub.getControllerSeatIndex(),
                    "§7Seat index that controls this subsystem.",
                    "§7-1 = Driver, 0+ = Passenger Seats.",
                    " ",
                    "§eLeft-Click to cycle seat index."
            ));

            // Slot 27: Link Turret Template
            String turretId = sub.getTurretId();
            if (turretId != null && !turretId.isEmpty()) {
                inv.setItem(27, createGuiItem(Material.CROSSBOW, "§eLinked Turret Template: §b§l" + turretId,
                        "§7Subsystem is configured by this turret template.",
                        " ",
                        "§eLeft-Click to change linked template.",
                        "§cRight-Click to unlink template."
                ));
            } else {
                inv.setItem(27, createGuiItem(Material.CROSSBOW, "§cNo Turret Template Linked",
                        "§7Subsystem will not mount a weapon or model",
                        "§7until a turret template is linked.",
                        " ",
                        "§eLeft-Click to link a turret template."
                ));
            }

            // Slot 26: Export Subsystem as Turret Template
            inv.setItem(26, createGuiItem(Material.WRITABLE_BOOK, "§6Export Subsystem as Turret Template",
                    "§7Export subsystem settings to a reusable",
                    "§7turret configuration template.",
                    " ",
                    "§eClick to export."
            ));

            // Slot 28: Mounted BDE Model ID (Read-only, inherited)
            String subModelId = sub.getBdeModelId(mm);
            inv.setItem(28, createGuiItem(Material.GOLDEN_HORSE_ARMOR, "§eMounted BDE Model ID",
                    "§7Model ID of BDE model defined in the linked turret.",
                    "§7Current (Inherited): §f" + (subModelId != null ? subModelId : "None"),
                    " ",
                    "§cRead-only (Defined on Turret template)"
            ));

            // Slot 29: Mounting Point Offset (Vehicle-specific, editable)
            List<Double> mountOffset = sub.getMountOffset();
            if (mountOffset == null) mountOffset = Arrays.asList(0.0, 0.0, 0.0);
            List<Double> finalMountOffset = new ArrayList<>(mountOffset);
            while (finalMountOffset.size() < 3) finalMountOffset.add(0.0);

            inv.setItem(29, createGuiItem(Material.DISPENSER, "§eMounting Point Offset",
                    "§7Chassis mounting coordinates relative to driver's seat.",
                    "§7Current (Vehicle): §f" + String.format("%.2f, %.2f, %.2f", finalMountOffset.get(0), finalMountOffset.get(1), finalMountOffset.get(2)),
                    " ",
                    "§eLeft-Click to enter interactive placement mode",
                    "§eRight-Click to type custom coordinates"
            ));

            // Slot 30: Launching Point Offset (Read-only, inherited)
            List<Double> launchOffset = sub.getLaunchOffset(mm);
            if (launchOffset == null) launchOffset = Arrays.asList(0.0, 0.0, 0.0);
            List<Double> finalLaunchOffset = new ArrayList<>(launchOffset);
            while (finalLaunchOffset.size() < 3) finalLaunchOffset.add(0.0);

            inv.setItem(30, createGuiItem(Material.FIREWORK_ROCKET, "§eLaunching Point Offset",
                    "§7Muzzle offset from subsystem model origin.",
                    "§7Current (Inherited): §f" + String.format("%.2f, %.2f, %.2f", finalLaunchOffset.get(0), finalLaunchOffset.get(1), finalLaunchOffset.get(2)),
                    " ",
                    "§cRead-only (Defined on Turret template)"
            ));

            // Slot 31: FOV Yaw Clamps (Read-only, inherited)
            Double minYaw = sub.getFovMinYaw(mm);
            Double maxYaw = sub.getFovMaxYaw(mm);
            inv.setItem(31, createGuiItem(Material.COMPASS, "§eFOV Yaw Clamps",
                    "§7Min (Inherited): §f" + (minYaw != null ? minYaw + "°" : "Unlimited"),
                    "§7Max (Inherited): §f" + (maxYaw != null ? maxYaw + "°" : "Unlimited"),
                    " ",
                    "§cRead-only (Defined on Turret template)"
            ));

            // Slot 32: FOV Pitch Clamps (Read-only, inherited)
            Double minPitch = sub.getFovMinPitch(mm);
            Double maxPitch = sub.getFovMaxPitch(mm);
            inv.setItem(32, createGuiItem(Material.SPYGLASS, "§eFOV Pitch Clamps",
                    "§7Min (Inherited): §f" + (minPitch != null ? minPitch + "°" : "Unlimited"),
                    "§7Max (Inherited): §f" + (maxPitch != null ? maxPitch + "°" : "Unlimited"),
                    " ",
                    "§cRead-only (Defined on Turret template)"
            ));

            // Slot 33: Weapon-Cam Camera Offset (Read-only, inherited)
            List<Double> cameraOffset = sub.getCameraOffset(mm);
            if (cameraOffset == null) cameraOffset = Arrays.asList(0.0, 0.0, 0.0);
            List<Double> finalCameraOffset = new ArrayList<>(cameraOffset);
            while (finalCameraOffset.size() < 3) finalCameraOffset.add(0.0);

            inv.setItem(33, createGuiItem(Material.ENDER_EYE, "§eWeapon-Cam Camera Offset",
                    "§7First-person spectator seat offset relative to subsystem.",
                    "§7Current (Inherited): §f" + String.format("%.2f, %.2f, %.2f", finalCameraOffset.get(0), finalCameraOffset.get(1), finalCameraOffset.get(2)),
                    " ",
                    "§cRead-only (Defined on Turret template)"
            ));

            // Slot 34: Pivot Point Offset (Read-only, inherited)
            List<Double> pivotOffset = sub.getPivotOffset(mm);
            if (pivotOffset == null) pivotOffset = Arrays.asList(0.0, 0.0, 0.0);
            List<Double> finalPivotOffset = new ArrayList<>(pivotOffset);
            while (finalPivotOffset.size() < 3) finalPivotOffset.add(0.0);

            inv.setItem(34, createGuiItem(Material.PISTON, "§ePivot Point Offset",
                    "§7Rotation center relative to subsystem origin.",
                    "§7Current (Inherited): §f" + String.format("%.2f, %.2f, %.2f", finalPivotOffset.get(0), finalPivotOffset.get(1), finalPivotOffset.get(2)),
                    " ",
                    "§cRead-only (Defined on Turret template)"
            ));

            // Slot 22: Weapon Modes (Shows list of linked projectile IDs or overrides)
            List<BdeModel.ProjectileConfig> resolvedModes = sub.getWeaponModes(mm);
            List<String> modesLore = new ArrayList<>();
            boolean isOverridden = sub.getProjectileOverrides() != null && !sub.getProjectileOverrides().isEmpty();
            if (isOverridden) {
                modesLore.add("§6§lProjectiles (Custom Overrides active):");
            } else {
                modesLore.add("§7Projectiles (Turret Defaults):");
            }
            if (resolvedModes.isEmpty()) {
                modesLore.add(" §7- None");
            } else {
                for (BdeModel.ProjectileConfig pc : resolvedModes) {
                    modesLore.add(" §f• " + pc.getName() + " §7(Dmg: " + pc.getDamage() + ", Spd: " + pc.getSpeed() + ")");
                }
            }
            modesLore.add(" ");
            modesLore.add("§eLeft-Click to override projectiles.");
            if (isOverridden) {
                modesLore.add("§cRight-Click to reset to turret defaults.");
            }

            inv.setItem(22, createGuiItem(Material.BOW, "§6Weapon Modes (Projectiles)", modesLore.toArray(new String[0])));

            // Slot 16: Interactive Placement Mode
            inv.setItem(16, createGuiItem(Material.ARMOR_STAND, "§a§lInteractive Placement Mode",
                    "§7Enter interactive visual editor to place",
                    "§7this subsystem in the world using your crosshair.",
                    " ",
                    "§eClick to enter placement mode."
            ));

            // Slot 38: Edit Mount Offset X
            inv.setItem(38, createGuiItem(Material.RED_WOOL, "§cEdit Mount Offset X: §f" + String.format("%.2f", finalMountOffset.get(0)),
                    "§7Controls left/right turret position.",
                    " ",
                    "§aLeft-Click to add 0.1",
                    "§cRight-Click to subtract 0.1",
                    "§aShift-Left-Click to add 0.01",
                    "§cShift-Right-Click to subtract 0.01"
            ));

            // Slot 39: Edit Mount Offset Y
            inv.setItem(39, createGuiItem(Material.GREEN_WOOL, "§aEdit Mount Offset Y: §f" + String.format("%.2f", finalMountOffset.get(1)),
                    "§7Controls turret height.",
                    " ",
                    "§aLeft-Click to add 0.1",
                    "§cRight-Click to subtract 0.1",
                    "§aShift-Left-Click to add 0.01",
                    "§cShift-Right-Click to subtract 0.01"
            ));

            // Slot 40: Edit Mount Offset Z
            inv.setItem(40, createGuiItem(Material.BLUE_WOOL, "§9Edit Mount Offset Z: §f" + String.format("%.2f", finalMountOffset.get(2)),
                    "§7Controls forward/backward turret position.",
                    " ",
                    "§aLeft-Click to add 0.1",
                    "§cRight-Click to subtract 0.1",
                    "§aShift-Left-Click to add 0.01",
                    "§cShift-Right-Click to subtract 0.01"
            ));
        }

        inv.setItem(45, createGuiItem(Material.ARROW, "§7Back to Subsystem List"));
        inv.setItem(49, createGuiItem(Material.BARRIER, "§cClose Menu"));

        player.openInventory(inv);
    }

    public void openTurretLinkMenu(Player player, ModelInstance instance, BdeModel model, String modelProjectId, int subsystemIndex) {
        BdeGuiHolder holder = new BdeGuiHolder(BdeGuiHolder.GuiType.TURRET_LINK_MENU,
                instance != null ? instance.getId() : null,
                modelProjectId, null, subsystemIndex, null);
        Inventory inv = Bukkit.createInventory(holder, 54, "§8Select Turret Template");

        ItemStack pane = createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 54; i++) {
            if (i < 9 || i >= 45 || i % 9 == 0 || i % 9 == 8) {
                inv.setItem(i, pane);
            }
        }

        ModelManager mm = plugin.getModelManager();
        List<String> turretIds = mm.getAvailableTurretIds();

        int slot = 9;
        for (String tId : turretIds) {
            if (slot >= 45) break;
            if (slot % 9 == 0 || slot % 9 == 8) slot++;

            TurretConfig tc = mm.getTurretTemplate(tId);
            String name = tc != null ? tc.getName() : tId;
            inv.setItem(slot, createGuiItem(Material.CROSSBOW, "§e§l" + name,
                    "§7ID: §f" + tId,
                    tc != null && tc.getBdeModelId() != null ? "§7Model: §f" + tc.getBdeModelId() : "§7Model: None",
                    " ",
                    "§eClick to link this turret template."
            ));
            slot++;
        }

        inv.setItem(45, createGuiItem(Material.ARROW, "§7Back to Details"));
        inv.setItem(49, createGuiItem(Material.BARRIER, "§cClose Menu"));

        player.openInventory(inv);
    }

    public void openTurretCatalog(Player player) {
        BdeGuiHolder holder = new BdeGuiHolder(BdeGuiHolder.GuiType.TURRET_CATALOG, null);
        Inventory inv = Bukkit.createInventory(holder, 54, "§8Turret Templates");

        ItemStack pane = createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 54; i++) {
            if (i < 9 || i >= 45 || i % 9 == 0 || i % 9 == 8) {
                inv.setItem(i, pane);
            }
        }

        inv.setItem(46, createGuiItem(Material.ANVIL, "§aCreate New Turret", "§7Click to create a new turret template."));
        inv.setItem(49, createGuiItem(Material.BARRIER, "§cClose Menu"));

        ModelManager mm = plugin.getModelManager();
        List<String> turretIds = mm.getAvailableTurretIds();
        int slot = 9;
        for (String tId : turretIds) {
            if (slot >= 45) break;
            if (slot % 9 == 0 || slot % 9 == 8) slot++;

            TurretConfig tc = mm.getTurretTemplate(tId);
            String displayName = tc != null ? tc.getName() : tId;
            int projCount = tc != null && tc.getProjectileIds() != null ? tc.getProjectileIds().size() : 0;
            String modelId = tc != null && tc.getBdeModelId() != null ? tc.getBdeModelId() : "None";

            inv.setItem(slot, createGuiItem(Material.CROSSBOW, "§e§l" + displayName,
                    "§7ID: §f" + tId,
                    "§7BDE Model: §f" + modelId,
                    "§7Default Projectiles: §f" + projCount,
                    " ",
                    "§eLeft-Click to configure",
                    "§cRight-Click to delete"
            ));
            slot++;
        }

        player.openInventory(inv);
    }

    public void openTurretEditor(Player player, String turretId) {
        TurretConfig tc = plugin.getModelManager().getTurretTemplate(turretId);
        if (tc == null) {
            player.sendMessage("§cTurret template not found.");
            return;
        }

        BdeGuiHolder holder = new BdeGuiHolder(BdeGuiHolder.GuiType.TURRET_EDITOR, null);
        holder.setTurretId(turretId);
        Inventory inv = Bukkit.createInventory(holder, 54, "§8Turret Editor: " + tc.getName());

        ItemStack pane = createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 54; i++) {
            if (i < 9 || i >= 45 || i % 9 == 0 || i % 9 == 8) {
                inv.setItem(i, pane);
            }
        }

        inv.setItem(4, createGuiItem(Material.GOLDEN_CARROT, "§6Turret Info",
                "§7ID: §f" + tc.getId(),
                "§7Name: §f" + tc.getName()
        ));

        inv.setItem(10, createGuiItem(Material.NAME_TAG, "§eRename Turret",
                "§7Current Name: §f" + tc.getName(),
                " ",
                "§eClick to rename in chat."
        ));

        inv.setItem(11, createGuiItem(Material.GOLDEN_HORSE_ARMOR, "§eSet BDE Model ID",
                "§7Current Model: §f" + (tc.getBdeModelId() != null ? tc.getBdeModelId() : "None"),
                " ",
                "§eClick to edit model ID in chat."
        ));

        inv.setItem(12, createGuiItem(Material.OAK_SIGN, "§eSet Display Tag",
                "§7Current Tag: §f" + (tc.getDisplayTag() != null ? tc.getDisplayTag() : "None"),
                " ",
                "§eClick to edit tag in chat."
        ));

        // Offsets
        List<Double> pivot = tc.getPivotOffset() != null ? tc.getPivotOffset() : Arrays.asList(0.0, 0.0, 0.0);
        List<Double> launch = tc.getLaunchOffset() != null ? tc.getLaunchOffset() : Arrays.asList(0.0, 0.0, 0.0);
        List<Double> camera = tc.getCameraOffset() != null ? tc.getCameraOffset() : Arrays.asList(0.0, 0.0, 0.0);

        while (pivot.size() < 3) pivot.add(0.0);
        while (launch.size() < 3) launch.add(0.0);
        while (camera.size() < 3) camera.add(0.0);

        inv.setItem(14, createGuiItem(Material.PISTON, "§ePivot Offset",
                "§7Offset: §f" + String.format("%.2f, %.2f, %.2f", pivot.get(0), pivot.get(1), pivot.get(2)),
                " ",
                "§7Defined relative to turret base origin.",
                "§aClick to enter interactive placement (pivot stage only)"
        ));

        inv.setItem(15, createGuiItem(Material.FIREWORK_ROCKET, "§eMuzzle (Launch) Offset",
                "§7Offset: §f" + String.format("%.2f, %.2f, %.2f", launch.get(0), launch.get(1), launch.get(2)),
                " ",
                "§7Defined relative to turret pivot point.",
                "§aClick to enter interactive placement (muzzle stage only)"
        ));

        inv.setItem(16, createGuiItem(Material.ENDER_EYE, "§eCamera Offset",
                "§7Offset: §f" + String.format("%.2f, %.2f, %.2f", camera.get(0), camera.get(1), camera.get(2)),
                " ",
                "§7First-person operator viewpoint.",
                "§aClick to enter interactive placement (camera stage only)"
        ));

        inv.setItem(22, createGuiItem(Material.ARMOR_STAND, "§a§lInteractive Placement Mode",
                "§7Launches visual editor with your crosshair.",
                "§7Spawns the turret model floating in front of you.",
                " ",
                "§eClick to calibrate Pivot, Muzzle, and Camera."
        ));

        // FOV Yaw/Pitch Clamps
        inv.setItem(28, createGuiItem(Material.COMPASS, "§eFOV Min Yaw: §f" + (tc.getFovMinYaw() != null ? tc.getFovMinYaw() + "°" : "None"),
                "§aLeft-Click to add 5.0°",
                "§cRight-Click to subtract 5.0°",
                "§aShift-Left-Click to add 1.0°",
                "§cShift-Right-Click to subtract 1.0°"
        ));

        inv.setItem(29, createGuiItem(Material.COMPASS, "§eFOV Max Yaw: §f" + (tc.getFovMaxYaw() != null ? tc.getFovMaxYaw() + "°" : "None"),
                "§aLeft-Click to add 5.0°",
                "§cRight-Click to subtract 5.0°",
                "§aShift-Left-Click to add 1.0°",
                "§cShift-Right-Click to subtract 1.0°"
        ));

        inv.setItem(30, createGuiItem(Material.SPYGLASS, "§eFOV Min Pitch: §f" + (tc.getFovMinPitch() != null ? tc.getFovMinPitch() + "°" : "None"),
                "§aLeft-Click to add 5.0°",
                "§cRight-Click to subtract 5.0°",
                "§aShift-Left-Click to add 1.0°",
                "§cShift-Right-Click to subtract 1.0°"
        ));

        inv.setItem(31, createGuiItem(Material.SPYGLASS, "§eFOV Max Pitch: §f" + (tc.getFovMaxPitch() != null ? tc.getFovMaxPitch() + "°" : "None"),
                "§aLeft-Click to add 5.0°",
                "§cRight-Click to subtract 5.0°",
                "§aShift-Left-Click to add 1.0°",
                "§cShift-Right-Click to subtract 1.0°"
        ));

        // Projectiles
        List<String> projs = tc.getProjectileIds();
        List<String> lore = new ArrayList<>();
        lore.add("§7Currently linked default projectiles:");
        if (projs == null || projs.isEmpty()) {
            lore.add(" §7- None");
        } else {
            for (String pId : projs) {
                lore.add(" §f• " + pId);
            }
        }
        lore.add(" ");
        lore.add("§eClick to link/unlink default projectiles.");
        inv.setItem(37, createGuiItem(Material.BOW, "§6Default Projectiles", lore.toArray(new String[0])));

        inv.setItem(45, createGuiItem(Material.ARROW, "§7Back to Catalog"));
        inv.setItem(49, createGuiItem(Material.BARRIER, "§cClose Menu"));
        inv.setItem(53, createGuiItem(Material.LIME_DYE, "§aSave Changes", "§7Writes config changes to disk."));

        player.openInventory(inv);
    }

    public void openTurretProjectileLinkMenu(Player player, String turretId) {
        TurretConfig tc = plugin.getModelManager().getTurretTemplate(turretId);
        if (tc == null) return;

        BdeGuiHolder holder = new BdeGuiHolder(BdeGuiHolder.GuiType.TURRET_PROJECTILE_LINK, null);
        holder.setTurretId(turretId);
        Inventory inv = Bukkit.createInventory(holder, 54, "§8Default Projectiles: " + tc.getName());

        ItemStack pane = createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 54; i++) {
            if (i < 9 || i >= 45 || i % 9 == 0 || i % 9 == 8) {
                inv.setItem(i, pane);
            }
        }

        inv.setItem(45, createGuiItem(Material.ARROW, "§7Back to Editor"));
        inv.setItem(49, createGuiItem(Material.BARRIER, "§cClose Menu"));

        ModelManager mm = plugin.getModelManager();
        List<String> available = mm.getAvailableProjectileIds();
        List<String> linked = tc.getProjectileIds() != null ? tc.getProjectileIds() : new ArrayList<>();

        int slot = 9;
        for (String pId : available) {
            if (slot >= 45) break;
            if (slot % 9 == 0 || slot % 9 == 8) slot++;

            boolean isLinked = linked.contains(pId);
            Material mat = isLinked ? Material.LIME_DYE : Material.GRAY_DYE;
            String status = isLinked ? "§a§lLINKED" : "§7UNLINKED";
            
            inv.setItem(slot, createGuiItem(mat, "§e§l" + pId,
                    "§7Status: " + status,
                    " ",
                    "§eClick to toggle link status."
            ));
            slot++;
        }

        player.openInventory(inv);
    }

    public void openProjectileCatalog(Player player) {
        BdeGuiHolder holder = new BdeGuiHolder(BdeGuiHolder.GuiType.PROJECTILE_CATALOG, null);
        Inventory inv = Bukkit.createInventory(holder, 54, "§8Projectile Templates");

        ItemStack pane = createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 54; i++) {
            if (i < 9 || i >= 45 || i % 9 == 0 || i % 9 == 8) {
                inv.setItem(i, pane);
            }
        }

        inv.setItem(46, createGuiItem(Material.ANVIL, "§aCreate New Projectile", "§7Click to create a new projectile template."));
        inv.setItem(49, createGuiItem(Material.BARRIER, "§cClose Menu"));

        ModelManager mm = plugin.getModelManager();
        List<String> projIds = mm.getAvailableProjectileIds();
        int slot = 9;
        for (String pId : projIds) {
            if (slot >= 45) break;
            if (slot % 9 == 0 || slot % 9 == 8) slot++;

            BdeModel.ProjectileConfig pc = mm.getProjectileConfig(pId);
            String displayName = pc != null ? pc.getName() : pId;
            double damage = pc != null ? pc.getDamage() : 0.0;
            double speed = pc != null ? pc.getSpeed() : 0.0;

            inv.setItem(slot, createGuiItem(Material.ARROW, "§e§l" + displayName,
                    "§7ID: §f" + pId,
                    "§7Damage: §f" + damage,
                    "§7Speed: §f" + speed,
                    " ",
                    "§eLeft-Click to configure",
                    "§cRight-Click to delete"
            ));
            slot++;
        }

        player.openInventory(inv);
    }

    public void openProjectileEditor(Player player, String projectileId) {
        BdeModel.ProjectileConfig pc = plugin.getModelManager().getProjectileConfig(projectileId);
        if (pc == null) {
            player.sendMessage("§cProjectile template not found.");
            return;
        }

        BdeGuiHolder holder = new BdeGuiHolder(BdeGuiHolder.GuiType.PROJECTILE_EDITOR, null);
        holder.setProjectileId(projectileId);
        Inventory inv = Bukkit.createInventory(holder, 54, "§8Projectile Editor: " + pc.getName());

        ItemStack pane = createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 54; i++) {
            if (i < 9 || i >= 45 || i % 9 == 0 || i % 9 == 8) {
                inv.setItem(i, pane);
            }
        }

        inv.setItem(4, createGuiItem(Material.GOLDEN_CARROT, "§6Projectile Info",
                "§7ID: §f" + projectileId,
                "§7Name: §f" + pc.getName()
        ));

        // Row 2 (Identity & Physics)
        inv.setItem(10, createGuiItem(Material.NAME_TAG, "§eRename Projectile",
                "§7Current Name: §f" + pc.getName(),
                " ",
                "§eClick to rename in chat."
        ));

        inv.setItem(11, createGuiItem(Material.GOLDEN_HORSE_ARMOR, "§eSet BDE Model ID",
                "§7Current Model: §f" + (pc.getBdeModelId() != null ? pc.getBdeModelId() : "None"),
                " ",
                "§eClick to edit model ID in chat."
        ));

        inv.setItem(19, createGuiItem(Material.SUGAR, "§bSpeed: §e" + String.format("%.2f", pc.getSpeed()),
                "§aLeft-Click to add 0.1",
                "§cRight-Click to subtract 0.1",
                "§aShift-Left-Click to add 0.5",
                "§cShift-Right-Click to subtract 0.5"
        ));

        inv.setItem(20, createGuiItem(Material.DIAMOND_SWORD, "§bDamage: §e" + String.format("%.2f", pc.getDamage()),
                "§aLeft-Click to add 1.0",
                "§cRight-Click to subtract 1.0",
                "§aShift-Left-Click to add 5.0",
                "§cShift-Right-Click to subtract 5.0"
        ));

        inv.setItem(21, createGuiItem(Material.CLOCK, "§bCooldown: §e" + String.format("%.2f s", pc.getCooldown()),
                "§aLeft-Click to add 0.1",
                "§cRight-Click to subtract 0.1",
                "§aShift-Left-Click to add 0.5",
                "§cShift-Right-Click to subtract 0.5"
        ));

        inv.setItem(22, createGuiItem(pc.isHasGravity() ? Material.FEATHER : Material.STRUCTURE_VOID,
                "§bHas Gravity: " + (pc.isHasGravity() ? "§a§lYES" : "§c§lNO"),
                "§eClick to toggle gravity."
        ));

        // Row 3 (Impact & Effects)
        inv.setItem(28, createGuiItem(Material.TNT, "§bOn Hit Action: §e" + pc.getOnHit().toUpperCase(),
                "§eClick to cycle action (explode -> despawn -> laser)."
        ));

        inv.setItem(29, createGuiItem(Material.FIRE_CHARGE, "§bExplosion Power: §e" + String.format("%.2f", pc.getExplosionPower()),
                "§aLeft-Click to add 0.5",
                "§cRight-Click to subtract 0.5",
                "§aShift-Left-Click to add 0.1",
                "§cShift-Right-Click to subtract 0.1"
        ));

        inv.setItem(30, createGuiItem(pc.isDestroyBlocks() ? Material.COBBLESTONE : Material.BARRIER,
                "§bDestroy Blocks: " + (pc.isDestroyBlocks() ? "§a§lYES" : "§c§lNO"),
                "§eClick to toggle block destruction."
        ));

        inv.setItem(31, createGuiItem(pc.isVanillaExplosionDamage() ? Material.IRON_SWORD : Material.WOODEN_SWORD,
                "§bVanilla Explosion Damage: " + (pc.isVanillaExplosionDamage() ? "§a§lYES" : "§c§lNO"),
                "§eClick to toggle vanilla explosion calculation."
        ));

        inv.setItem(33, createGuiItem(Material.NOTE_BLOCK, "§eLaunch Sound",
                "§7Sound: §f" + pc.getLaunchSound(),
                "§7Volume: §f" + pc.getLaunchVolume() + " §7| Pitch: §f" + pc.getLaunchPitch(),
                " ",
                "§eClick to edit sound string in chat."
        ));

        inv.setItem(34, createGuiItem(Material.BLAZE_POWDER, "§eFly Particle",
                "§7Particle: §f" + pc.getFlyParticle() + " §7(Count: " + pc.getFlyParticleCount() + ")",
                " ",
                "§eClick to edit particle type in chat."
        ));

        inv.setItem(35, createGuiItem(Material.FIREWORK_STAR, "§eImpact Particle",
                "§7Particle: §f" + pc.getImpactParticle() + " §7(Count: " + pc.getImpactParticleCount() + ")",
                " ",
                "§eClick to edit particle type in chat."
        ));

        // Row 4 (Lock-On)
        inv.setItem(37, createGuiItem(pc.isLockOn() ? Material.ENDER_EYE : Material.OBSERVER,
                "§bLock-On Mode: " + (pc.isLockOn() ? "§a§lENABLED" : "§c§lDISABLED"),
                "§eClick to toggle lock-on guidance."
        ));

        inv.setItem(38, createGuiItem(Material.BOW, "§bLock Range: §e" + String.format("%.1f blocks", pc.getLockRange()),
                "§aLeft-Click to add 5.0",
                "§cRight-Click to subtract 5.0",
                "§aShift-Left-Click to add 1.0",
                "§cShift-Right-Click to subtract 1.0"
        ));

        inv.setItem(39, createGuiItem(Material.COMPASS, "§bLock Angle: §e" + String.format("%.1f°", pc.getLockAngle()),
                "§aLeft-Click to add 5.0",
                "§cRight-Click to subtract 5.0",
                "§aShift-Left-Click to add 1.0",
                "§cShift-Right-Click to subtract 1.0"
        ));

        inv.setItem(40, createGuiItem(Material.CLOCK, "§bLock Time: §e" + String.format("%.1f s", pc.getLockTime()),
                "§aLeft-Click to add 0.5",
                "§cRight-Click to subtract 0.5",
                "§aShift-Left-Click to add 0.1",
                "§cShift-Right-Click to subtract 0.1"
        ));

        // Row 5 (Base Point and Direction Vector)
        List<Double> bp = pc.getBasePoint();
        List<Double> dv = pc.getDirectionVector();
        while (bp.size() < 3) bp.add(0.0);
        while (dv.size() < 3) dv.add(0.0);

        inv.setItem(41, createGuiItem(Material.PISTON, "§eModel Base Point (Offset)",
                "§7Offset: §f" + String.format("%.2f, %.2f, %.2f", bp.get(0), bp.get(1), bp.get(2)),
                " ",
                "§7Offsets display relative to launch point.",
                "§eClick to type custom coords in chat."
        ));

        inv.setItem(42, createGuiItem(Material.ARROW, "§eModel Direction Vector",
                "§7Vector: §f" + String.format("%.2f, %.2f, %.2f", dv.get(0), dv.get(1), dv.get(2)),
                " ",
                "§7Model forward pointing vector.",
                "§eClick to type custom vector in chat."
        ));

        // Ammo requirement (issue #6)
        String ammoDisplay;
        if (pc.getAmmoType() == null) {
            ammoDisplay = "§aNone §7(fires freely)";
        } else {
            top.sanscraft.bde.manager.BdeAmmoConfig.AmmoConfig ac =
                    plugin.getBdeAmmoConfig().getRegisteredAmmo().get(pc.getAmmoType());
            ammoDisplay = ac != null
                    ? ("§f" + ac.name + " §7(" + pc.getAmmoType() + ")")
                    : ("§c" + pc.getAmmoType() + " §4(unregistered!)");
        }
        inv.setItem(23, createGuiItem(Material.SPECTRAL_ARROW, "§bRequired Ammo: " + ammoDisplay,
                "§7Ammo type consumed when this weapon fires.",
                "§7Drawn from the turret's own storage first,",
                "§7then the vehicle-shared ammo pool.",
                " ",
                "§aLeft-Click: §fcycle to next registered ammo",
                "§cRight-Click: §fclear (no ammo required)"
        ));

        inv.setItem(24, createGuiItem(Material.FLINT, "§bAmmo Per Shot: §e" + pc.getAmmoPerShot(),
                "§7Units consumed each time this weapon fires.",
                " ",
                "§aLeft-Click: §f+1",
                "§cRight-Click: §f-1 §7(min 1)"
        ));

        inv.setItem(45, createGuiItem(Material.ARROW, "§7Back to Catalog"));
        inv.setItem(49, createGuiItem(Material.BARRIER, "§cClose Menu"));
        inv.setItem(53, createGuiItem(Material.LIME_DYE, "§aSave Changes", "§7Writes config changes to disk."));

        player.openInventory(inv);
    }

    public void openSubsystemProjectileOverrideMenu(Player player, ModelInstance instance, BdeModel model, String modelProjectId, int subsystemIndex) {
        BdeGuiHolder holder = new BdeGuiHolder(BdeGuiHolder.GuiType.SUBSYSTEM_PROJECTILE_OVERRIDE,
                instance != null ? instance.getId() : null,
                modelProjectId, null, subsystemIndex, null);
        Inventory inv = Bukkit.createInventory(holder, 54, "§8Projectile Overrides");

        ItemStack pane = createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 54; i++) {
            if (i < 9 || i >= 45 || i % 9 == 0 || i % 9 == 8) {
                inv.setItem(i, pane);
            }
        }

        inv.setItem(45, createGuiItem(Material.ARROW, "§7Back to Subsystem"));
        inv.setItem(46, createGuiItem(Material.RED_DYE, "§cReset to Turret Defaults", "§7Clears all custom projectile overrides."));
        inv.setItem(49, createGuiItem(Material.BARRIER, "§cClose Menu"));

        BdeModel.VehicleConfig cfg = model.getVehicle();
        if (cfg != null && subsystemIndex >= 0 && subsystemIndex < cfg.getSubsystems().size()) {
            BdeModel.SubsystemConfig sub = cfg.getSubsystems().get(subsystemIndex);
            
            ModelManager mm = plugin.getModelManager();
            List<String> available = mm.getAvailableProjectileIds();
            
            // Determine defaults from linked turret
            List<String> defaults = new ArrayList<>();
            if (sub.getTurretId() != null && !sub.getTurretId().isEmpty()) {
                TurretConfig tc = mm.getTurretTemplate(sub.getTurretId());
                if (tc != null && tc.getProjectileIds() != null) {
                    defaults.addAll(tc.getProjectileIds());
                }
            }

            List<String> overrides = sub.getProjectileOverrides() != null ? sub.getProjectileOverrides() : new ArrayList<>();

            int slot = 9;
            for (String pId : available) {
                if (slot >= 45) break;
                if (slot % 9 == 0 || slot % 9 == 8) slot++;

                boolean isOverridden = overrides.contains(pId);
                boolean isDefault = defaults.contains(pId);

                Material mat;
                String status;
                if (isOverridden) {
                    mat = Material.LIME_DYE;
                    status = "§a§lOVERRIDDEN ACTIVE";
                } else if (isDefault && overrides.isEmpty()) {
                    mat = Material.GREEN_DYE;
                    status = "§2§lACTIVE (Turret Default)";
                } else {
                    mat = Material.GRAY_DYE;
                    status = "§7INACTIVE";
                }

                String defaultTag = isDefault ? " §7(Turret Default)" : "";

                inv.setItem(slot, createGuiItem(mat, "§e§l" + pId + defaultTag,
                        "§7Status: " + status,
                        " ",
                        "§eClick to toggle override status."
                ));
                slot++;
            }
        }

        player.openInventory(inv);
    }
}
