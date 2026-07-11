package top.sanscraft.bde.manager;

import com.google.gson.Gson;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.Color;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.util.Transformation;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import top.sanscraft.bde.SansCraftBDEPlugin;
import top.sanscraft.bde.model.BdeModel;
import top.sanscraft.bde.model.TurretConfig;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.util.RayTraceResult;
import net.kyori.adventure.text.Component;
import top.sanscraft.bde.model.ModelInstance;

public class ModelManager {
    public static boolean DEBUG_VEHICLES = false;

    public static boolean isVersion1_20_5_OrHigher() {
        try {
            Class.forName("org.bukkit.inventory.meta.components.FoodComponent");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static boolean paperTeleportFlagsAvailable = false;
    private static Object[] retainPassengersFlags = null;

    static {
        try {
            Class<?> teleportFlagClass = Class.forName("io.papermc.paper.entity.TeleportFlag");
            Class<?> entityStateClass = Class.forName("io.papermc.paper.entity.TeleportFlag$EntityState");
            if (entityStateClass.isEnum()) {
                Object retainPassengers = null;
                for (Object constant : entityStateClass.getEnumConstants()) {
                    if (constant.toString().equals("RETAIN_PASSENGERS")) {
                        retainPassengers = constant;
                        break;
                    }
                }
                if (retainPassengers != null) {
                    retainPassengersFlags = (Object[]) java.lang.reflect.Array.newInstance(teleportFlagClass, 1);
                    retainPassengersFlags[0] = retainPassengers;
                    paperTeleportFlagsAvailable = true;
                }
            }
        } catch (Throwable t) {
            // Not available
        }
    }

    public static boolean teleportKeepPassengers(Entity entity, Location location) {
        if (paperTeleportFlagsAvailable && retainPassengersFlags != null) {
            try {
                java.lang.reflect.Method method = entity.getClass().getMethod("teleport", Location.class, retainPassengersFlags.getClass());
                Object result = method.invoke(entity, location, retainPassengersFlags);
                if (result instanceof Boolean) {
                    boolean success = (boolean) result;
                    if (DEBUG_VEHICLES && !success) {
                        Bukkit.getLogger().warning("[BDE Debug] Paper teleport keep passengers returned false for " + entity.getType());
                    }
                    return success;
                }
                return true;
            } catch (Throwable t) {
                if (DEBUG_VEHICLES) {
                    Bukkit.getLogger().warning("[BDE Debug] Failed to invoke Paper teleport keep passengers: " + t.getMessage());
                }
            }
        }
        boolean success = entity.teleport(location);
        if (DEBUG_VEHICLES && !success) {
            Bukkit.getLogger().warning("[BDE Debug] Standard teleport returned false for " + entity.getType());
        }
        return success;
    }

    private final SansCraftBDEPlugin plugin;
    private final Map<UUID, ModelInstance> activeInstances = new HashMap<>();
    private final Gson gson = new Gson();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final PlayerInputTracker inputTracker;

    private final List<CustomProjectile> activeProjectiles = new ArrayList<>();
    private final Map<UUID, LockSession> activeLockSessions = new HashMap<>(); // key: playerId -> LockSession
    private final Map<UUID, Map<String, Long>> subsystemCooldowns = new HashMap<>(); // key: playerId -> (subsystemName -> lastUseTimestamp)
    private final Map<UUID, ItemStack> originalHotbarItems = new HashMap<>();

    private final Map<String, TurretConfig> turretTemplates = new ConcurrentHashMap<>();
    private final Map<String, BdeModel.ProjectileConfig> projectileTemplates = new ConcurrentHashMap<>();

    public enum PlacementStep {
        PIVOT_OFFSET,
        MOUNT_POINT,
        LAUNCH_POINT,
        CAMERA_OFFSET
    }

    public static class PlacementSession {
        public final UUID originalInstanceId;
        public UUID instanceId;
        public final int subsystemIndex;
        public PlacementStep step;
        public double distance = 4.0;
        public final java.util.Set<String> lockedAxes = new java.util.HashSet<>();
        public final java.util.Map<String, Double> lockedValues = new java.util.HashMap<>();
        public double lastRx = 0.0;
        public double lastRy = 0.0;
        public double lastRz = 0.0;

        public UUID tempSubsystemInstanceId = null;
        public Location subsystemOriginWorldLoc = null;
        public Location vehicleOriginalLoc = null;
        public double vehicleOriginalScale = 1.0;
        public String vehicleModelId = null;

        public boolean standaloneTurret = false;
        public String turretId = null;

        public PlacementSession(UUID instanceId, int subsystemIndex) {
            this.originalInstanceId = instanceId;
            this.instanceId = instanceId;
            this.subsystemIndex = subsystemIndex;
            this.step = PlacementStep.PIVOT_OFFSET;
        }
    }

    private final Map<UUID, PlacementSession> placementSessions = new ConcurrentHashMap<>();

    public PlacementSession getPlacementSession(UUID playerId) {
        return placementSessions.get(playerId);
    }

    public ModelManager(SansCraftBDEPlugin plugin) {
        this.plugin = plugin;
        this.inputTracker = new PlayerInputTracker(plugin);
        
        // Ensure directories exist
        new File(plugin.getDataFolder(), "turrets").mkdirs();
        new File(plugin.getDataFolder(), "projectiles").mkdirs();
        
        startVehicleTick();
        startProjectileTick();
        runPlacementTickTask();
    }

    public TurretConfig loadTurretConfigSync(String id) throws Exception {
        File file = new File(plugin.getDataFolder(), "turrets/" + id + ".json");
        if (!file.exists()) {
            throw new IOException("Turret template file not found: " + id);
        }
        try (FileReader reader = new FileReader(file)) {
            TurretConfig turret = gson.fromJson(reader, TurretConfig.class);
            turret.setId(id);
            return turret;
        }
    }

    public void saveTurretConfig(TurretConfig turret) {
        String id = turret.getId();
        if (id == null || id.isEmpty()) {
            plugin.getLogger().warning("Failed to save turret: id is null or empty.");
            return;
        }
        File file = new File(plugin.getDataFolder(), "turrets/" + id + ".json");
        file.getParentFile().mkdirs();
        try (FileWriter writer = new FileWriter(file)) {
            gson.toJson(turret, writer);
            turretTemplates.put(id, turret);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save turret config for " + id + ": " + e.getMessage());
        }
    }

    public TurretConfig getTurretTemplate(String id) {
        if (id == null || id.isEmpty()) return null;
        return turretTemplates.computeIfAbsent(id, k -> {
            try {
                return loadTurretConfigSync(k);
            } catch (Exception e) {
                return null;
            }
        });
    }

    public void clearTurretTemplatesCache() {
        turretTemplates.clear();
    }

    public List<String> getAvailableTurretIds() {
        List<String> list = new ArrayList<>();
        File folder = new File(plugin.getDataFolder(), "turrets");
        if (folder.exists() && folder.isDirectory()) {
            File[] files = folder.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isFile() && f.getName().endsWith(".json")) {
                        list.add(f.getName().substring(0, f.getName().length() - 5));
                    }
                }
            }
        }
        return list;
    }

    public BdeModel.ProjectileConfig loadProjectileConfigSync(String id) throws Exception {
        File file = new File(plugin.getDataFolder(), "projectiles/" + id + ".json");
        if (!file.exists()) {
            throw new IOException("Projectile template file not found: " + id);
        }
        try (FileReader reader = new FileReader(file)) {
            BdeModel.ProjectileConfig config = gson.fromJson(reader, BdeModel.ProjectileConfig.class);
            config.setName(id);
            return config;
        }
    }

    public void saveProjectileConfig(String id, BdeModel.ProjectileConfig config) {
        if (id == null || id.isEmpty()) {
            plugin.getLogger().warning("Failed to save projectile: id is null or empty.");
            return;
        }
        File file = new File(plugin.getDataFolder(), "projectiles/" + id + ".json");
        file.getParentFile().mkdirs();
        try (FileWriter writer = new FileWriter(file)) {
            gson.toJson(config, writer);
            projectileTemplates.put(id, config);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save projectile config for " + id + ": " + e.getMessage());
        }
    }

    public BdeModel.ProjectileConfig getProjectileConfig(String id) {
        if (id == null || id.isEmpty()) return null;
        return projectileTemplates.computeIfAbsent(id, k -> {
            try {
                return loadProjectileConfigSync(k);
            } catch (Exception e) {
                return null;
            }
        });
    }

    public void clearProjectileTemplatesCache() {
        projectileTemplates.clear();
    }

    public List<String> getAvailableProjectileIds() {
        List<String> list = new ArrayList<>();
        File folder = new File(plugin.getDataFolder(), "projectiles");
        if (folder.exists() && folder.isDirectory()) {
            File[] files = folder.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isFile() && f.getName().endsWith(".json")) {
                        list.add(f.getName().substring(0, f.getName().length() - 5));
                    }
                }
            }
        }
        return list;
    }

    public boolean deleteTurretConfig(String id) {
        if (id == null || id.isEmpty()) return false;
        turretTemplates.remove(id);
        File file = new File(plugin.getDataFolder(), "turrets/" + id + ".json");
        return file.exists() && file.delete();
    }

    public boolean deleteProjectileConfig(String id) {
        if (id == null || id.isEmpty()) return false;
        projectileTemplates.remove(id);
        File file = new File(plugin.getDataFolder(), "projectiles/" + id + ".json");
        return file.exists() && file.delete();
    }

    public PlayerInputTracker getInputTracker() {
        return inputTracker;
    }

    /**
     * Loads a model definition asynchronously by ID or filename.
     */
    public CompletableFuture<BdeModel> loadModel(String id) {
        CompletableFuture<BdeModel> future = new CompletableFuture<>();

        // If ID is numeric (6+ digits), fetch from API or cache
        if (id.matches("\\d{5,10}")) {
            File cacheFile = new File(plugin.getDataFolder(), "cache/" + id + ".json");
            long cacheDurationMs = plugin.getConfig().getLong("cache-duration-minutes", 1440) * 60 * 1000;

            if (cacheFile.exists() && (System.currentTimeMillis() - cacheFile.lastModified() < cacheDurationMs)) {
                try (FileReader reader = new FileReader(cacheFile)) {
                    BdeModel model = gson.fromJson(reader, BdeModel.class);
                    future.complete(model);
                    return future;
                } catch (IOException e) {
                    plugin.getLogger().warning("Failed to read cached model " + id + ", falling back to API: " + e.getMessage());
                }
            }

            // Fetch from API
            String apiEndpoint = plugin.getConfig().getString("api-endpoint", "https://block-display.com/server-api");
            String url = apiEndpoint + "?id=" + id;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();

            sendAsyncWithRedirect(request, 0)
                    .thenAccept(response -> {
                        if (response.statusCode() == 200) {
                            try {
                                BdeModel.BdeResponse apiResponse = gson.fromJson(response.body(), BdeModel.BdeResponse.class);
                                if (apiResponse != null && apiResponse.getContent() != null) {
                                    BdeModel model = apiResponse.getContent();
                                    // Cache locally
                                    cacheFile.getParentFile().mkdirs();
                                    try (FileWriter writer = new FileWriter(cacheFile)) {
                                        gson.toJson(model, writer);
                                    } catch (IOException e) {
                                        plugin.getLogger().warning("Failed to cache model " + id + ": " + e.getMessage());
                                    }
                                    future.complete(model);
                                } else {
                                    future.completeExceptionally(new Exception("BDE API returned empty content."));
                                }
                            } catch (Exception e) {
                                future.completeExceptionally(e);
                            }
                        } else {
                            future.completeExceptionally(new Exception("BDE API returned status code " + response.statusCode()));
                        }
                    })
                    .exceptionally(ex -> {
                        // Fallback to cache if request failed but cache exists
                        if (cacheFile.exists()) {
                            try (FileReader reader = new FileReader(cacheFile)) {
                                BdeModel model = gson.fromJson(reader, BdeModel.class);
                                future.complete(model);
                                plugin.getLogger().info("Successfully fell back to expired cache for model " + id);
                                return null;
                            } catch (IOException ignored) {}
                        }
                        future.completeExceptionally(ex);
                        return null;
                    });
        } else {
            // Local file lookup (check vehicles directory first, then models)
            File file = new File(plugin.getDataFolder(), "vehicles/" + id + ".json");
            boolean isVehicle = true;
            if (!file.exists()) {
                file = new File(plugin.getDataFolder(), "vehicles/" + id);
            }
            if (!file.exists() || file.isDirectory()) {
                isVehicle = false;
                file = new File(plugin.getDataFolder(), "models/" + id + ".json");
                if (!file.exists()) {
                    file = new File(plugin.getDataFolder(), "models/" + id);
                    if (!file.exists()) {
                        future.completeExceptionally(new IOException("Model/Vehicle file not found: " + id));
                        return future;
                    }
                }
            }

            final boolean finalIsVehicle = isVehicle;
            final File finalFile = file;
            try (FileReader reader = new FileReader(finalFile)) {
                BdeModel model = gson.fromJson(reader, BdeModel.class);
                model.setLocalFilePath(id);
                model.setIsVehicleLibrary(finalIsVehicle);
                future.complete(model);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        }

        return future;
    }

    private CompletableFuture<HttpResponse<String>> sendAsyncWithRedirect(HttpRequest request, int redirectCount) {
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenCompose(response -> {
                    int status = response.statusCode();
                    if ((status == 301 || status == 302 || status == 307 || status == 308) && redirectCount < 5) {
                        Optional<String> locationOpt = response.headers().firstValue("Location");
                        if (locationOpt.isPresent()) {
                            try {
                                URI redirectUri = request.uri().resolve(locationOpt.get());
                                HttpRequest nextRequest = HttpRequest.newBuilder()
                                        .uri(redirectUri)
                                        .GET()
                                        .build();
                                return sendAsyncWithRedirect(nextRequest, redirectCount + 1);
                            } catch (Exception e) {
                                return CompletableFuture.failedFuture(e);
                            }
                        }
                    }
                    return CompletableFuture.completedFuture(response);
                });
    }

    /**
     * Loads a model definition synchronously by ID or filename.
     */
    public BdeModel loadModelSync(String id) throws Exception {
        if (id.matches("\\d{5,10}")) {
            File cacheFile = new File(plugin.getDataFolder(), "cache/" + id + ".json");
            long cacheDurationMs = plugin.getConfig().getLong("cache-duration-minutes", 1440) * 60 * 1000;
            if (cacheFile.exists() && (System.currentTimeMillis() - cacheFile.lastModified() < cacheDurationMs)) {
                try (FileReader reader = new FileReader(cacheFile)) {
                    return gson.fromJson(reader, BdeModel.class);
                } catch (IOException ignored) {}
            }
            try {
                return loadModel(id).get(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                if (cacheFile.exists()) {
                    try (FileReader reader = new FileReader(cacheFile)) {
                        return gson.fromJson(reader, BdeModel.class);
                    } catch (IOException ignored) {}
                }
                throw new Exception("Model not cached and failed to download: " + e.getMessage(), e);
            }
        } else {
            File file = new File(plugin.getDataFolder(), "vehicles/" + id + ".json");
            boolean isVehicle = true;
            if (!file.exists()) {
                file = new File(plugin.getDataFolder(), "vehicles/" + id);
            }
            if (!file.exists() || file.isDirectory()) {
                isVehicle = false;
                file = new File(plugin.getDataFolder(), "models/" + id + ".json");
                if (!file.exists()) {
                    file = new File(plugin.getDataFolder(), "models/" + id);
                    if (!file.exists()) {
                        throw new IOException("Model/Vehicle file not found: " + id);
                    }
                }
            }
            try (FileReader reader = new FileReader(file)) {
                BdeModel model = gson.fromJson(reader, BdeModel.class);
                model.setLocalFilePath(id);
                model.setIsVehicleLibrary(isVehicle);
                return model;
            }
        }
    }

    /**
     * Spawns a model in the world with scaling.
     */
    public static float getMountHeight(Entity root, float interactionHeight) {
        if (root == null) return 0f;
        if (root instanceof org.bukkit.entity.Minecart) {
            return 0.4825f;
        }
        if (root instanceof org.bukkit.entity.Boat) {
            return 0.45f;
        }
        if (root instanceof org.bukkit.entity.Interaction) {
            return interactionHeight;
        }
        return 0f;
    }

    /**
     * Spawns a model in the world with scaling.
     */
    public ModelInstance spawnModel(BdeModel model, Location location, double scale) {
        ModelInstance instance = new ModelInstance(model, location, scale);
        String uuidStr = instance.getId().toString();

        // Calculate precise hitbox bounds from the model SNBTs beforehand
        BoundingBox box = calculateModelBounds(model, scale);
        float radius = Math.max(
            Math.max(Math.abs(box.minX), Math.abs(box.maxX)),
            Math.max(Math.abs(box.minZ), Math.abs(box.maxZ))
        );
        float interactionHeight = Math.max(0.1f, box.maxY);

        Entity vehicleRoot = null;
        Interaction root = null;

        // If a vehicle: spawn physical root (always an invisible ArmorStand to hold displays without passenger limits)
        if (model.getVehicleStats() != null) {
            org.bukkit.entity.ArmorStand stand = location.getWorld().spawn(location, org.bukkit.entity.ArmorStand.class);
            stand.setInvisible(true);
            stand.setMarker(true);
            stand.setSmall(true);
            stand.setBasePlate(false);
            stand.setGravity(false);
            stand.setRotation(location.getYaw(), 0.0f);
            vehicleRoot = stand;
            
            vehicleRoot.addScoreboardTag("bde_vehicle_root");
            vehicleRoot.addScoreboardTag("bde_model_" + uuidStr);
            vehicleRoot.setMetadata("bde_instance_id", new FixedMetadataValue(plugin, uuidStr));
            instance.setVehicleRoot(vehicleRoot);

            // Spawn the actual driver seat according to type, and mount it to vehicleRoot
            String type = model.getType() != null ? model.getType().toLowerCase() : "armor_stand";
            Entity driverSeat;
            if (type.contains("minecart")) {
                driverSeat = location.getWorld().spawn(location, org.bukkit.entity.Minecart.class);
            } else if (type.contains("boat")) {
                driverSeat = location.getWorld().spawn(location, org.bukkit.entity.Boat.class);
            } else {
                org.bukkit.entity.ArmorStand dsStand = location.getWorld().spawn(location, org.bukkit.entity.ArmorStand.class);
                dsStand.setInvisible(true);
                dsStand.setMarker(true);
                dsStand.setSmall(true);
                dsStand.setBasePlate(false);
                dsStand.setGravity(false);
                driverSeat = dsStand;
            }
            double dsYaw = 0.0;
            if (model.getVehicle() != null) {
                dsYaw = model.getVehicle().getDriverSeatYaw();
            }
            float finalDsYaw = (float) ((location.getYaw() + dsYaw) % 360.0);
            driverSeat.setRotation(finalDsYaw, location.getPitch());
            
            driverSeat.addScoreboardTag("bde_driver_seat");
            driverSeat.addScoreboardTag("bde_model_" + uuidStr);
            driverSeat.setMetadata("bde_instance_id", new FixedMetadataValue(plugin, uuidStr));
            vehicleRoot.addPassenger(driverSeat);
            instance.setDriverSeat(driverSeat);

            // Determine hitbox configs (custom or dynamically generated)
            List<BdeModel.HitboxConfig> hbConfigs = model.getVehicle() != null ? model.getVehicle().getHitboxes() : null;
            if (hbConfigs == null || hbConfigs.isEmpty()) {
                hbConfigs = generateDynamicHitboxes(model);
            }
            if (hbConfigs.isEmpty()) {
                // Fallback to single default hitbox
                BdeModel.HitboxConfig fallback = new BdeModel.HitboxConfig();
                fallback.setOffset(model.getSeatOffset() != null ? model.getSeatOffset() : Arrays.asList(0.0, 0.0, 0.0));
                fallback.setWidth(radius * 2f);
                fallback.setHeight(interactionHeight);
                hbConfigs.add(fallback);
            }

            for (BdeModel.HitboxConfig hc : hbConfigs) {
                Location hbLoc = ModelTransformEngine.getSeatPosition(location, hc.getOffset(), model.getSeatOffset(), scale, model.getFrontYawOffset());
                hbLoc.setYaw(location.getYaw());
                Interaction hb = location.getWorld().spawn(hbLoc, Interaction.class);
                hb.setInteractionWidth((float) (hc.getWidth() * scale));
                hb.setInteractionHeight((float) (hc.getHeight() * scale));
                hb.setGravity(false);
                hb.setInvulnerable(true);
                hb.addScoreboardTag("bde_root");
                hb.addScoreboardTag("bde_model_" + uuidStr);
                hb.setMetadata("bde_instance_id", new FixedMetadataValue(plugin, uuidStr));
                
                instance.getHitboxes().add(hb);
                if (root == null) {
                    root = hb;
                }
            }
            instance.setRootEntity(root);

            // Spawn co-passenger seat ArmorStands immediately
            List<List<Double>> pOffsets = model.getPassengerOffsets();
            if (pOffsets != null) {
                for (int i = 0; i < pOffsets.size(); i++) {
                    double psYaw = 0.0;
                    if (model.getVehicle() != null && i < model.getVehicle().getPassengerSeats().size()) {
                        psYaw = model.getVehicle().getPassengerSeats().get(i).getYaw();
                    }
                    
                    // Spawn the actual riding seat ArmorStand
                    Location seatLoc = ModelTransformEngine.getSeatPosition(location, pOffsets.get(i), model.getSeatOffset(), scale, model.getFrontYawOffset());
                    seatLoc.setYaw((float) ((location.getYaw() + psYaw) % 360.0));
                    
                    org.bukkit.entity.ArmorStand seat = location.getWorld().spawn(seatLoc, org.bukkit.entity.ArmorStand.class);
                    seat.setInvisible(true);
                    seat.setMarker(true);
                    seat.setSmall(true);
                    seat.setBasePlate(false);
                    seat.setGravity(false);
                    seat.setSilent(true);
                    seat.addScoreboardTag("bde_seat");
                    seat.addScoreboardTag("bde_seat_" + i);
                    seat.addScoreboardTag("bde_model_" + uuidStr);
                    seat.setMetadata("bde_instance_id", new FixedMetadataValue(plugin, uuidStr));
                    
                    instance.getPassengerSeats().add(seat);
                }
            }
        }

        // Spawn passengers (block/item displays)
        if (model != null && model.getPassengers() != null) {
            List<String> flatPassengers = new ArrayList<>();
            for (String passengerSnbt : model.getPassengers()) {
                List<String> split = splitObjects(passengerSnbt);
                for (String p : split) {
                    collectAllPassengers(p, flatPassengers);
                }
            }

            int count = 0;
            for (int i = 0; i < flatPassengers.size(); i++) {
                String passengerSnbt = flatPassengers.get(i);
                try {
                    // Detect entity type from SNBT id field
                    boolean isItemDisplay = isItemDisplaySnbt(passengerSnbt);
                    boolean isTextDisplay = isTextDisplaySnbt(passengerSnbt);
                    String typeStr = isItemDisplay ? "ItemDisplay" : (isTextDisplay ? "TextDisplay" : "BlockDisplay");

                    Display display;
                    if (isItemDisplay) {
                        ItemDisplay itemDisplay = location.getWorld().spawn(location, ItemDisplay.class);
                        applyItemDisplayData(itemDisplay, passengerSnbt, scale);
                        display = itemDisplay;
                    } else if (isTextDisplay) {
                        TextDisplay textDisplay = location.getWorld().spawn(location, TextDisplay.class);
                        applyTextDisplayData(textDisplay, passengerSnbt);
                        display = textDisplay;
                    } else {
                        BlockDisplay blockDisplay = location.getWorld().spawn(location, BlockDisplay.class);
                        applyBlockDisplayData(blockDisplay, passengerSnbt, scale);
                        display = blockDisplay;
                    }

                    display.addScoreboardTag("bde_passenger");
                    display.addScoreboardTag("bde_model_" + uuidStr);

                    // Add passenger identifier tag (e.g. bde_0)
                    String passengerTag = parsePassengerTag(passengerSnbt);
                    if (passengerTag != null) {
                        display.addScoreboardTag(passengerTag);
                    }

                    // Apply shared transformation
                    float mountHeight = getMountHeight(vehicleRoot, interactionHeight);
                    float transformYaw = (vehicleRoot != null) ? 0.0f : (float) location.getYaw();
                    applyTransformation(display, passengerSnbt, scale, mountHeight, model, vehicleRoot != null, transformYaw, (float) location.getPitch(), vehicleRoot != null);

                    // Mount display or teleport to absolute position
                    if (vehicleRoot != null) {
                        vehicleRoot.addPassenger(display);
                        // Enable client-side smooth interpolation for teleports/rotations!
                        display.setTeleportDuration(1);
                        display.setInterpolationDuration(1);
                        display.setInterpolationDelay(0);
                    } else {
                        display.teleport(location);
                    }

                    instance.addPassenger(display);
                    count++;
                } catch (Exception e) {
                    plugin.getLogger().log(java.util.logging.Level.WARNING, "Failed to spawn passenger " + i + ": " + passengerSnbt, e);
                }
            }
        } else {
            plugin.getLogger().warning("Model passengers list is null for model: " + model.getProjectId());
        }

        activeInstances.put(instance.getId(), instance);

        // Spawn mounted subsystem BDE models if configured
        if (model.getVehicle() != null && model.getVehicle().getSubsystems() != null) {
            for (int subIdx = 0; subIdx < model.getVehicle().getSubsystems().size(); subIdx++) {
                BdeModel.SubsystemConfig sub = model.getVehicle().getSubsystems().get(subIdx);
                String subModelId = sub.getBdeModelId(this);
                if (subModelId != null && !subModelId.isEmpty()) {
                    try {
                        BdeModel subModel = loadModelSync(subModelId);
                        if (subModel != null && subModel.getPassengers() != null) {
                            List<String> flatSubPassengers = new ArrayList<>();
                            for (String pSnbt : subModel.getPassengers()) {
                                List<String> split = splitObjects(pSnbt);
                                for (String p : split) {
                                    collectAllPassengers(p, flatSubPassengers);
                                }
                            }

                            for (int i = 0; i < flatSubPassengers.size(); i++) {
                                String passengerSnbt = flatSubPassengers.get(i);
                                boolean isItemDisplay = isItemDisplaySnbt(passengerSnbt);
                                boolean isTextDisplay = isTextDisplaySnbt(passengerSnbt);
                                Display display;
                                if (isItemDisplay) {
                                    ItemDisplay itemDisplay = location.getWorld().spawn(location, ItemDisplay.class);
                                    applyItemDisplayData(itemDisplay, passengerSnbt, scale);
                                    display = itemDisplay;
                                } else if (isTextDisplay) {
                                    TextDisplay textDisplay = location.getWorld().spawn(location, TextDisplay.class);
                                    applyTextDisplayData(textDisplay, passengerSnbt);
                                    display = textDisplay;
                                } else {
                                    BlockDisplay blockDisplay = location.getWorld().spawn(location, BlockDisplay.class);
                                    applyBlockDisplayData(blockDisplay, passengerSnbt, scale);
                                    display = blockDisplay;
                                }

                                display.addScoreboardTag("bde_passenger");
                                display.addScoreboardTag("bde_model_" + uuidStr);
                                String subDisplayTag = sub.getDisplayTag(this);
                                if (subDisplayTag != null) {
                                    display.addScoreboardTag(subDisplayTag);
                                }

                                // Set metadata to identify this as part of a subsystem model
                                display.setMetadata("bde_subsystem_model_id", new FixedMetadataValue(plugin, subModelId));
                                display.setMetadata("bde_subsystem_index", new FixedMetadataValue(plugin, i));
                                display.setMetadata("bde_subsystem_parent_index", new FixedMetadataValue(plugin, subIdx));

                                // Initial transformation
                                float mHeight = getMountHeight(vehicleRoot, interactionHeight);
                                updateSubsystemTransform(display, scale, mHeight, model, (float) location.getYaw(), (float) location.getPitch(), location.getYaw(), 0.0, sub);

                                if (vehicleRoot != null) {
                                    vehicleRoot.addPassenger(display);
                                    display.setTeleportDuration(1);
                                    display.setInterpolationDuration(1);
                                    display.setInterpolationDelay(0);
                                } else {
                                    display.teleport(location);
                                }
                                instance.addPassenger(display);
                            }
                        }
                    } catch (Exception e) {
                        plugin.getLogger().log(java.util.logging.Level.WARNING, "Failed to load subsystem BDE model: " + subModelId, e);
                    }
                }
            }
        }

        return instance;
    }

    /**
     * Rescales a spawned model instance and updates its display entities in real-time.
     */
    public void updateScale(ModelInstance instance, double newScale) {
        instance.setScale(newScale);

        List<String> snbts = instance.getModel().getPassengers();
        if (snbts != null) {
            List<String> flatSnbts = new ArrayList<>();
            for (String s : snbts) {
                List<String> split = splitObjects(s);
                for (String p : split) {
                    collectAllPassengers(p, flatSnbts);
                }
            }

            for (Display display : instance.getPassengers()) {
                try {
                    int index = getPassengerIndexFromTags(display);
                    if (index >= 0 && index < flatSnbts.size()) {
                        String snbt = flatSnbts.get(index);
                        if (display instanceof BlockDisplay) {
                            applyBlockDisplayData((BlockDisplay) display, snbt, newScale);
                        } else if (display instanceof ItemDisplay) {
                            applyItemDisplayData((ItemDisplay) display, snbt, newScale);
                        } else if (display instanceof TextDisplay) {
                            applyTextDisplayData((TextDisplay) display, snbt);
                        }
                    }
                } catch (Exception e) {
                    plugin.getLogger().log(java.util.logging.Level.WARNING, "Failed to update scale for display: " + display, e);
                }
            }
        }

        updateModelTransforms(instance);
    }

    public void updateModelTransforms(ModelInstance instance) {
        double scale = instance.getScale();
        BoundingBox box = calculateModelBounds(instance.getModel(), scale);
        float radius = Math.max(
            Math.max(Math.abs(box.minX), Math.abs(box.maxX)),
            Math.max(Math.abs(box.minZ), Math.abs(box.maxZ))
        );
        float interactionHeight = Math.max(0.1f, box.maxY);

        List<String> snbts = instance.getModel().getPassengers();
        if (snbts != null) {
            List<String> flatSnbts = new ArrayList<>();
            for (String s : snbts) {
                List<String> split = splitObjects(s);
                for (String p : split) {
                    collectAllPassengers(p, flatSnbts);
                }
            }

            Entity root = instance.getRootEntity();
            Entity vehicleRoot = instance.getVehicleRoot();
            boolean isPassenger = (vehicleRoot != null);
            float mountHeight = getMountHeight(vehicleRoot != null ? vehicleRoot : root, interactionHeight);

            Location loc = instance.getLocation();
            float yaw = (float) loc.getYaw();
            float pitch = (float) loc.getPitch();
            if (isPassenger) {
                if (isVersion1_20_5_OrHigher()) {
                    yaw = 0.0f;
                } else {
                    float spawnYaw = (float) instance.getSpawnLocation().getYaw();
                    yaw = (float) ((yaw - spawnYaw) % 360.0);
                }
            }

            for (Display display : instance.getPassengers()) {
                try {
                    int index = getPassengerIndexFromTags(display);
                    if (index >= 0 && index < flatSnbts.size()) {
                        String snbt = flatSnbts.get(index);
                        applyTransformation(display, snbt, scale, mountHeight, instance.getModel(), isPassenger, yaw, pitch, vehicleRoot != null);
                    }
                } catch (Exception e) {
                    plugin.getLogger().log(java.util.logging.Level.WARNING, "Failed to update transforms: " + display, e);
                }
            }

            if (root instanceof Interaction) {
                Interaction interaction = (Interaction) root;
                interaction.setInteractionWidth(radius * 2f);
                interaction.setInteractionHeight(interactionHeight);
            }

            if (vehicleRoot != null) {
                updateSeatLocations(instance);
                updateHitboxLocation(instance);
            } else if (root != null) {
                Location hitboxLoc = ModelTransformEngine.getHitboxPosition(loc, instance.getModel(), scale);
                hitboxLoc.setYaw(loc.getYaw());
                root.teleport(hitboxLoc);
            }

            if (plugin.getBdeGuiManager() != null) {
                plugin.getBdeGuiManager().updateSelectionHighlight(instance.getId());
            }
        }
    }

    private int getPassengerIndexFromTags(Display display) {
        for (String tag : display.getScoreboardTags()) {
            if (tag.startsWith("bde_") && !tag.equals("bde_passenger") && !tag.startsWith("bde_model_") && !tag.equals("bde_root")) {
                try {
                    return Integer.parseInt(tag.substring(4));
                } catch (NumberFormatException ignored) {}
            }
        }
        return -1;
    }

    private String parsePassengerTag(String snbt) {
        int tagsIdx = snbt.indexOf("Tags:[");
        if (tagsIdx == -1) return null;
        int endTags = snbt.indexOf("]", tagsIdx);
        if (endTags == -1) return null;
        String tagsContent = snbt.substring(tagsIdx + 6, endTags);
        // Extracts the tag starting with bde_
        for (String tag : tagsContent.split(",")) {
            tag = tag.trim().replace("\"", "");
            if (tag.startsWith("bde_")) {
                return tag;
            }
        }
        return null;
    }

    /**
     * Determines if an SNBT passenger string describes an item_display entity.
     */
    private static boolean isItemDisplaySnbt(String snbt) {
        String clean = snbt.replaceAll("\\s", "");
        return clean.contains("id:\"minecraft:item_display\"") ||
               clean.contains("id:item_display") ||
               clean.contains("id:'minecraft:item_display'") ||
               clean.contains("id:\"item_display\"");
    }

    private static boolean isTextDisplaySnbt(String snbt) {
        String clean = snbt.replaceAll("\\s", "");
        return clean.contains("id:\"minecraft:text_display\"") ||
               clean.contains("id:text_display") ||
               clean.contains("id:'minecraft:text_display'") ||
               clean.contains("id:\"text_display\"");
    }

    private void applyTextDisplayData(TextDisplay display, String snbt) {
        // Extract and set text
        String textJson = extractKeyString(snbt, "text", 0);

        if (textJson != null) {
            try {
                net.kyori.adventure.text.Component component = net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson().deserialize(textJson);
                display.text(component);
            } catch (Exception e) {
                display.setText(textJson);
            }
        }

        // Text opacity
        int opIdx = snbt.indexOf("text_opacity:");
        if (opIdx != -1) {
            int opacity = parseIntAfter(snbt, opIdx + "text_opacity:".length());
            if (opacity >= 0 && opacity <= 255) {
                display.setTextOpacity((byte) opacity);
            }
        }

        // Background color
        int bgIdx = snbt.indexOf("background:");
        if (bgIdx != -1) {
            int bgVal = parseIntAfter(snbt, bgIdx + "background:".length());
            if (bgVal == 0) {
                display.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            } else if (bgVal != -1) {
                int alpha = (bgVal >> 24) & 0xFF;
                int red = (bgVal >> 16) & 0xFF;
                int green = (bgVal >> 8) & 0xFF;
                int blue = bgVal & 0xFF;
                display.setBackgroundColor(Color.fromARGB(alpha, red, green, blue));
            }
        }

        // Alignment
        String alignment = extractKeyString(snbt, "alignment", 0);
        if (alignment != null) {
            try {
                display.setAlignment(TextDisplay.TextAlignment.valueOf(alignment.toUpperCase()));
            } catch (IllegalArgumentException ignored) {}
        }

        // Line width
        int lwIdx = snbt.indexOf("line_width:");
        if (lwIdx != -1) {
            int lwVal = parseIntAfter(snbt, lwIdx + "line_width:".length());
            if (lwVal > 0) {
                display.setLineWidth(lwVal);
            }
        }

        // Default background
        int dbIdx = snbt.indexOf("default_background:");
        if (dbIdx != -1) {
            int start = dbIdx + "default_background:".length();
            boolean dbVal = snbt.startsWith("true", start) || snbt.startsWith("1b", start);
            display.setDefaultBackground(dbVal);
        }
    }

    /**
     * Applies block-specific data (block state) to a BlockDisplay from parsed SNBT.
     */
    private void applyBlockDisplayData(BlockDisplay display, String snbt, double scale) {
        // Extract and set block state
        String blockName = "";
        int nameIdx = snbt.indexOf("Name:\"");
        if (nameIdx != -1) {
            int endName = snbt.indexOf("\"", nameIdx + 6);
            blockName = snbt.substring(nameIdx + 6, endName);
        }

        StringBuilder props = new StringBuilder();
        int propIdx = snbt.indexOf("Properties:{");
        if (propIdx != -1) {
            int endProp = snbt.indexOf("}", propIdx);
            String propContent = snbt.substring(propIdx + 12, endProp);
            String[] parts = propContent.split(",");
            props.append("[");
            for (int i = 0; i < parts.length; i++) {
                String[] kv = parts[i].split(":");
                if (kv.length == 2) {
                    props.append(kv[0].trim()).append("=").append(kv[1].trim().replace("\"", ""));
                    if (i < parts.length - 1) props.append(",");
                }
            }
            props.append("]");
        }

        if (!blockName.isEmpty()) {
            try {
                BlockData blockData = Bukkit.createBlockData(blockName + props.toString());
                display.setBlock(blockData);
            } catch (Exception e) {
                display.setBlock(Bukkit.createBlockData("minecraft:stone"));
            }
        }
    }

    /**
     * Applies item-specific data (item stack, CustomModelData) to an ItemDisplay from parsed SNBT.
     */
    private void applyItemDisplayData(ItemDisplay display, String snbt, double scale) {
        // Parse the item id from SNBT:
        // item:{id:"minecraft:diamond_sword",Count:1b,tag:{CustomModelData:12345}}
        // or item:{id:"minecraft:paper",count:1,components:{"minecraft:custom_model_data":12345}}
        int itemIdx = snbt.indexOf("item");
        String itemId = (itemIdx != -1) ? extractKeyString(snbt, "id", itemIdx) : null;

        Material material = Material.PAPER; // default fallback
        if (itemId != null) {
            // Strip minecraft: prefix for Material lookup
            String matName = itemId.replace("minecraft:", "").toUpperCase();
            try {
                material = Material.valueOf(matName);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Unknown item material '" + itemId + "', falling back to PAPER");
            }
        }

        ItemStack stack = new ItemStack(material, 1);

        // Custom head textures parsing and application
        if (material == Material.PLAYER_HEAD) {
            if (DEBUG_VEHICLES) {
                plugin.getLogger().info("[BDE Debug] Player head passenger SNBT: " + snbt);
            }
            String base64 = extractValueProperty(snbt);
            if (DEBUG_VEHICLES) {
                plugin.getLogger().info("[BDE Debug] Extracted base64: " + (base64 != null ? (base64.length() > 30 ? base64.substring(0, 30) + "..." : base64) : "null"));
            }
            if (base64 != null) {
                ItemMeta meta = stack.getItemMeta();
                if (meta instanceof org.bukkit.inventory.meta.SkullMeta) {
                    org.bukkit.inventory.meta.SkullMeta skullMeta = (org.bukkit.inventory.meta.SkullMeta) meta;
                    boolean applied = false;
                    try {
                        String urlStr = extractSkinUrl(base64);
                        if (DEBUG_VEHICLES) {
                            plugin.getLogger().info("[BDE Debug] Extracted skin URL: " + urlStr);
                        }
                        if (urlStr != null) {
                            org.bukkit.profile.PlayerProfile profile = Bukkit.createProfile(UUID.randomUUID());
                            org.bukkit.profile.PlayerTextures textures = profile.getTextures();
                            textures.setSkin(new java.net.URI(urlStr).toURL());
                            profile.setTextures(textures);
                            skullMeta.setOwnerProfile(profile);
                            stack.setItemMeta(skullMeta);
                            applied = true;
                            if (DEBUG_VEHICLES) {
                                plugin.getLogger().info("[BDE Debug] Successfully applied player head texture via Bukkit Profile API");
                            }
                        }
                    } catch (Throwable t) {
                        plugin.getLogger().warning("Failed to apply player head texture via Bukkit API: " + t.getMessage());
                    }

                    if (!applied) {
                        // Fallback to reflection
                        try {
                            Class<?> gameProfileClass = Class.forName("com.mojang.authlib.GameProfile");
                            java.lang.reflect.Constructor<?> gpConstructor = gameProfileClass.getConstructor(UUID.class, String.class);
                            Object gameProfile = gpConstructor.newInstance(UUID.randomUUID(), "");
                            
                            Class<?> propertyClass = Class.forName("com.mojang.authlib.properties.Property");
                            java.lang.reflect.Constructor<?> propConstructor = propertyClass.getConstructor(String.class, String.class);
                            Object property = propConstructor.newInstance("textures", base64);
                            
                            java.lang.reflect.Method getProperties = gameProfileClass.getMethod("getProperties");
                            Object propertiesMap = getProperties.invoke(gameProfile);
                            java.lang.reflect.Method put = propertiesMap.getClass().getMethod("put", Object.class, Object.class);
                            put.invoke(propertiesMap, "textures", property);
                            
                            java.lang.reflect.Field profileField = skullMeta.getClass().getDeclaredField("profile");
                            profileField.setAccessible(true);
                            profileField.set(skullMeta, gameProfile);
                            stack.setItemMeta(skullMeta);
                        } catch (Exception ex) {
                            plugin.getLogger().warning("Failed to apply custom skull texture reflection: " + ex.getMessage());
                        }
                    }
                }
            }
        }

        // Parse CustomModelData
        int cmd = parseCustomModelData(snbt);
        if (cmd > 0) {
            ItemMeta meta = stack.getItemMeta();
            if (meta != null) {
                meta.setCustomModelData(cmd);
                stack.setItemMeta(meta);
            }
        }

        display.setItemStack(stack);

        // Parse item display transform mode (e.g., head, thirdperson_righthand, fixed, etc.)
        String transformMode = extractKeyString(snbt, "item_display", 0);
        if (transformMode != null) {
            try {
                display.setItemDisplayTransform(
                    ItemDisplay.ItemDisplayTransform.valueOf(transformMode.toUpperCase()));
            } catch (IllegalArgumentException ignored) {}
        }
    }

    /**
     * Extracts a quoted string value for a key in SNBT, tolerating spaces and backslash escapes.
     */
    private String extractKeyString(String snbt, String key, int startFrom) {
        int idx = snbt.indexOf(key, startFrom);
        if (idx == -1) return null;

        int colon = snbt.indexOf(":", idx + key.length());
        if (colon == -1) return null;

        // Find the opening quote character (' or ") after the colon
        int startQuote = -1;
        char quote = 0;
        for (int i = colon + 1; i < snbt.length(); i++) {
            char c = snbt.charAt(i);
            if (c == '\'' || c == '"') {
                startQuote = i;
                quote = c;
                break;
            }
            if (c == ',' || c == '}' || c == ']') break;
        }

        if (startQuote == -1) return null;

        // Read until the matching closing quote, respecting backslash escapes
        StringBuilder sb = new StringBuilder();
        boolean escaped = false;
        for (int i = startQuote + 1; i < snbt.length(); i++) {
            char c = snbt.charAt(i);
            if (escaped) {
                sb.append(c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == quote) {
                return sb.toString();
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Parses CustomModelData from SNBT. Handles both legacy tag:{CustomModelData:N}
     * and modern components:{"minecraft:custom_model_data":N} formats.
     */
    private int parseCustomModelData(String snbt) {
        // Legacy: tag:{CustomModelData:12345}
        int cmdIdx = snbt.indexOf("CustomModelData:");
        if (cmdIdx != -1) {
            return parseIntAfter(snbt, cmdIdx + "CustomModelData:".length());
        }
        // Modern: "minecraft:custom_model_data":12345
        cmdIdx = snbt.indexOf("custom_model_data\":");
        if (cmdIdx != -1) {
            return parseIntAfter(snbt, cmdIdx + "custom_model_data\":".length());
        }
        return -1;
    }

    /**
     * Parses an integer starting at the given position in a string, stopping at non-digit chars.
     */
    private int parseIntAfter(String s, int pos) {
        StringBuilder sb = new StringBuilder();
        for (int i = pos; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isDigit(c) || (c == '-' && sb.isEmpty())) {
                sb.append(c);
            } else {
                break;
            }
        }
        if (sb.isEmpty()) return -1;
        try {
            return Integer.parseInt(sb.toString());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Applies the transformation matrix from SNBT to any Display entity (block or item).
     */
    private void applyTransformation(Display display, String snbt, double scale, float mountHeight, BdeModel model, boolean isPassenger, float yaw, float pitch, boolean hasVehicleRoot) {
        applyTransformation(display, snbt, scale, mountHeight, model, isPassenger, yaw, pitch, hasVehicleRoot, null, null);
    }

    private void applyTransformation(Display display, String snbt, double scale, float mountHeight, BdeModel model, boolean isPassenger, float yaw, float pitch, boolean hasVehicleRoot, List<Double> basePoint, List<Double> directionVector) {
        int transIdx = snbt.indexOf("transformation:[");
        if (transIdx != -1) {
            int endTrans = snbt.indexOf("]", transIdx);
            String matrixStr = snbt.substring(transIdx + 16, endTrans);
            String[] parts = matrixStr.split(",");
            if (parts.length == 16) {
                float[] m = new float[16];
                for (int i = 0; i < 16; i++) {
                    String p = parts[i].trim().toLowerCase();
                    if (p.endsWith("f")) p = p.substring(0, p.length() - 1);
                    m[i] = Float.parseFloat(p);
                }

                Matrix4f matrix = new Matrix4f();
                matrix.setTransposed(m);

                // Apply direction vector rotation to align with +Z (0,0,1)
                if (directionVector != null && directionVector.size() == 3) {
                    Vector3f from = new Vector3f(
                        (float) directionVector.get(0).doubleValue(),
                        (float) directionVector.get(1).doubleValue(),
                        (float) directionVector.get(2).doubleValue()
                    );
                    if (from.length() > 1e-4) {
                        from.normalize();
                        Vector3f to = new Vector3f(0, 0, 1);
                        if (from.distance(to) > 1e-4) {
                            Quaternionf rot = new Quaternionf().rotationTo(from, to);
                            matrix.rotate(rot);
                        }
                    }
                }

                // Apply base point translation
                if (basePoint != null && basePoint.size() == 3) {
                    matrix.translate((float) -basePoint.get(0).doubleValue(), (float) -basePoint.get(1).doubleValue(), (float) -basePoint.get(2).doubleValue());
                }

                Matrix4f finalMatrix;
                if (isPassenger) {
                    finalMatrix = ModelTransformEngine.getDisplayPassengerMatrix(matrix, scale, mountHeight, model, yaw, pitch, hasVehicleRoot);
                } else {
                    finalMatrix = ModelTransformEngine.getDisplayWorldLocalMatrix(matrix, scale, model, yaw, pitch);
                }

                Transformation transformation = ModelTransformEngine.decomposeToTransformation(finalMatrix);
                display.setTransformation(transformation);
            }
        }
    }

    public void activateHitbox(ModelInstance instance) {
        if (instance.getModel().getVehicleStats() != null) return;
        if (instance.getRootEntity() != null) return;

        Location loc = instance.getLocation();
        BoundingBox box = calculateModelBounds(instance.getModel(), instance.getScale());
        float radius = Math.max(
            Math.max(Math.abs(box.minX), Math.abs(box.maxX)),
            Math.max(Math.abs(box.minZ), Math.abs(box.maxZ))
        );
        float interactionHeight = Math.max(0.1f, box.maxY);

        Location hitboxLoc = ModelTransformEngine.getHitboxPosition(loc, instance.getModel(), instance.getScale());
        hitboxLoc.setYaw(loc.getYaw());
        
        Interaction root = loc.getWorld().spawn(hitboxLoc, Interaction.class);
        root.setInteractionWidth(radius * 2f);
        root.setInteractionHeight(interactionHeight);
        root.setGravity(false);
        root.setInvulnerable(true);
        root.addScoreboardTag("bde_root");
        root.addScoreboardTag("bde_model_" + instance.getId().toString());
        root.setMetadata("bde_instance_id", new FixedMetadataValue(plugin, instance.getId().toString()));
        instance.setRootEntity(root);
        instance.getHitboxes().add(root);

        updateModelTransforms(instance);
    }

    public void deactivateHitbox(ModelInstance instance) {
        if (instance.getModel().getVehicleStats() != null) return;
        
        Location loc = instance.getLocation();
        instance.setCurrentLocation(loc);

        for (Interaction hb : instance.getHitboxes()) {
            if (hb != null && hb.isValid()) {
                hb.remove();
            }
        }
        instance.getHitboxes().clear();

        if (instance.getRootEntity() != null) {
            instance.getRootEntity().remove();
            instance.setRootEntity(null);
        }

        updateModelTransforms(instance);
    }

    public void rotateModel(ModelInstance instance, float angleDegrees) {
        Location loc = instance.getLocation();
        float newYaw = (loc.getYaw() + angleDegrees) % 360;
        loc.setYaw(newYaw);
        instance.setCurrentLocation(loc);
        teleportModel(instance, loc);
        updateModelTransforms(instance);
    }

    public void rotatePitch(ModelInstance instance, float angleDegrees) {
        Location loc = instance.getLocation();
        float newPitch = (loc.getPitch() + angleDegrees) % 360;
        loc.setPitch(newPitch);
        instance.setCurrentLocation(loc);
        teleportModel(instance, loc);
        updateModelTransforms(instance);
    }

    public List<BdeModel.HitboxConfig> generateDynamicHitboxes(BdeModel model) {
        List<BdeModel.HitboxConfig> hitboxes = new ArrayList<>();
        if (model == null || model.getPassengers() == null) return hitboxes;

        List<String> flatPassengers = new ArrayList<>();
        for (String passengerSnbt : model.getPassengers()) {
            List<String> split = splitObjects(passengerSnbt);
            for (String p : split) {
                collectAllPassengers(p, flatPassengers);
            }
        }

        List<Vector3f> translations = new ArrayList<>();
        for (String snbt : flatPassengers) {
            float[] m = new float[16];
            int transIdx = snbt.indexOf("transformation:[");
            if (transIdx != -1) {
                int endTrans = snbt.indexOf("]", transIdx);
                if (endTrans != -1) {
                    String matrixStr = snbt.substring(transIdx + 16, endTrans);
                    String[] parts = matrixStr.split(",");
                    if (parts.length == 16) {
                        for (int i = 0; i < 16; i++) {
                            String p = parts[i].trim().toLowerCase();
                            if (p.endsWith("f")) p = p.substring(0, p.length() - 1);
                            try {
                                m[i] = Float.parseFloat(p);
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                }
            } else {
                m[0] = 1f; m[5] = 1f; m[10] = 1f; m[15] = 1f;
            }
            Matrix4f matrix = new Matrix4f();
            matrix.setTransposed(m);
            Vector3f translation = new Vector3f();
            matrix.getTranslation(translation);
            translations.add(translation);
        }

        List<List<Vector3f>> clusters = new ArrayList<>();
        float threshold = 1.5f;
        for (Vector3f t : translations) {
            List<List<Vector3f>> matchingClusters = new ArrayList<>();
            for (List<Vector3f> cluster : clusters) {
                for (Vector3f member : cluster) {
                    if (member.distance(t) < threshold) {
                        matchingClusters.add(cluster);
                        break;
                    }
                }
            }
            if (matchingClusters.isEmpty()) {
                List<Vector3f> newCluster = new ArrayList<>();
                newCluster.add(t);
                clusters.add(newCluster);
            } else {
                List<Vector3f> main = matchingClusters.get(0);
                main.add(t);
                for (int i = 1; i < matchingClusters.size(); i++) {
                    main.addAll(matchingClusters.get(i));
                    clusters.remove(matchingClusters.get(i));
                }
            }
        }

        for (int i = 0; i < clusters.size(); i++) {
            List<Vector3f> cluster = clusters.get(i);
            float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
            float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            float minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
            for (Vector3f v : cluster) {
                minX = Math.min(minX, v.x);
                maxX = Math.max(maxX, v.x);
                minY = Math.min(minY, v.y);
                maxY = Math.max(maxY, v.y);
                minZ = Math.min(minZ, v.z);
                maxZ = Math.max(maxZ, v.z);
            }

            double widthX = (maxX - minX) + 1.0;
            double widthZ = (maxZ - minZ) + 1.0;
            double heightY = (maxY - minY) + 1.0;

            double centerX = (minX + maxX) / 2.0;
            double centerY = minY;
            double centerZ = (minZ + maxZ) / 2.0;

            BdeModel.HitboxConfig hc = new BdeModel.HitboxConfig();
            hc.setOffset(Arrays.asList(centerX, centerY, centerZ));
            hc.setWidth(Math.max(0.5, Math.max(widthX, widthZ)));
            hc.setHeight(Math.max(0.5, heightY));
            hc.setName("auto_" + i);
            hitboxes.add(hc);
        }
        return hitboxes;
    }

    public void updateHitboxLocations(ModelInstance instance, Location targetLoc) {
        BdeModel model = instance.getModel();
        List<BdeModel.HitboxConfig> hbConfigs = model.getVehicle() != null ? model.getVehicle().getHitboxes() : null;
        if (hbConfigs == null || hbConfigs.isEmpty()) {
            hbConfigs = generateDynamicHitboxes(model);
        }
        if (hbConfigs.isEmpty()) {
            BdeModel.HitboxConfig fallback = new BdeModel.HitboxConfig();
            fallback.setOffset(model.getSeatOffset() != null ? model.getSeatOffset() : Arrays.asList(0.0, 0.0, 0.0));
            BoundingBox bounds = calculateModelBounds(model, instance.getScale());
            fallback.setWidth(bounds.getWidth());
            fallback.setHeight(bounds.getHeight());
            hbConfigs.add(fallback);
        }

        List<Interaction> activeHitboxes = instance.getHitboxes();
        for (int i = 0; i < activeHitboxes.size(); i++) {
            if (i >= hbConfigs.size()) break;
            Interaction hb = activeHitboxes.get(i);
            if (hb != null && hb.isValid()) {
                Location hbLoc = ModelTransformEngine.getSeatPosition(targetLoc, hbConfigs.get(i).getOffset(), model.getSeatOffset(), instance.getScale(), model.getFrontYawOffset());
                hbLoc.setYaw(targetLoc.getYaw());
                hb.teleport(hbLoc);
            }
        }
    }

    public void updateHitboxLocation(ModelInstance instance) {
        if (instance.getVehicleRoot() != null && instance.getVehicleRoot().isValid()) {
            updateHitboxLocations(instance, instance.getVehicleRoot().getLocation());
        }
    }

    public List<Double> getSubsystemOffset(BdeModel model, String tag) {
        if (model.getPassengers() == null) return Arrays.asList(0.0, 0.0, 0.0);
        List<String> flatPassengers = new ArrayList<>();
        for (String s : model.getPassengers()) {
            List<String> split = splitObjects(s);
            for (String p : split) {
                collectAllPassengers(p, flatPassengers);
            }
        }
        for (String snbt : flatPassengers) {
            if (snbt.contains("Tags:[") && snbt.contains(tag)) {
                int transIdx = snbt.indexOf("transformation:[");
                if (transIdx != -1) {
                    int endTrans = snbt.indexOf("]", transIdx);
                    if (endTrans != -1) {
                        String matrixStr = snbt.substring(transIdx + 16, endTrans);
                        String[] parts = matrixStr.split(",");
                        if (parts.length == 16) {
                            float[] m = new float[16];
                            for (int i = 0; i < 16; i++) {
                                String p = parts[i].trim().toLowerCase();
                                if (p.endsWith("f")) p = p.substring(0, p.length() - 1);
                                m[i] = Float.parseFloat(p);
                            }
                            Matrix4f matrix = new Matrix4f();
                            matrix.setTransposed(m);
                            Vector3f translation = new Vector3f();
                            matrix.getTranslation(translation);
                            return Arrays.asList((double) translation.x, (double) translation.y, (double) translation.z);
                        }
                    }
                }
            }
        }
        return Arrays.asList(0.0, 0.0, 0.0);
    }

    public void updateSeatLocations(ModelInstance instance) {
        Entity vehicleRoot = instance.getVehicleRoot();
        if (vehicleRoot != null && vehicleRoot.isValid()) {
            List<List<Double>> pOffsets = instance.getModel().getPassengerOffsets();
            List<org.bukkit.entity.ArmorStand> activeSeats = instance.getPassengerSeats();
            double scale = instance.getScale();
            for (org.bukkit.entity.ArmorStand seat : activeSeats) {
                for (int i = 0; i < pOffsets.size(); i++) {
                    if (seat.getScoreboardTags().contains("bde_seat_" + i)) {
                        Location seatLoc = null;
                        double psYaw = 0.0;
                        
                        Player rider = null;
                        for (Entity passenger : seat.getPassengers()) {
                            if (passenger instanceof Player) {
                                rider = (Player) passenger;
                                break;
                            }
                        }

                        boolean camActive = false;
                        if (rider != null && instance.isWeaponCamActive(rider.getUniqueId()) && instance.getModel().getVehicle() != null) {
                            for (BdeModel.SubsystemConfig sub : instance.getModel().getVehicle().getSubsystems()) {
                                if (sub.getControllerSeatIndex() == i) {
                                    double subYaw;
                                    double subPitch;
                                    if (instance.isSubsystemWasdAiming(sub.getName())) {
                                        subYaw = instance.getSubsystemAimYaw(sub.getName(), vehicleRoot.getLocation().getYaw());
                                        subPitch = instance.getSubsystemAimPitch(sub.getName(), 0.0);
                                    } else {
                                        subYaw = rider.getLocation().getYaw();
                                        subPitch = rider.getLocation().getPitch();
                                    }
                                    double relativeYaw = ((subYaw - vehicleRoot.getLocation().getYaw()) % 360.0 + 540.0) % 360.0 - 180.0;
                                    if (sub.getFovMinYaw(this) != null) relativeYaw = Math.max(sub.getFovMinYaw(this), relativeYaw);
                                    if (sub.getFovMaxYaw(this) != null) relativeYaw = Math.min(sub.getFovMaxYaw(this), relativeYaw);
                                    double relativePitch = subPitch;
                                    if (sub.getFovMinPitch(this) != null) relativePitch = Math.max(sub.getFovMinPitch(this), relativePitch);
                                    if (sub.getFovMaxPitch(this) != null) relativePitch = Math.min(sub.getFovMaxPitch(this), relativePitch);

                                    List<Double> mountOffset = sub.getMountOffset();
                                    if (mountOffset == null || mountOffset.isEmpty()) {
                                        mountOffset = getSubsystemOffset(instance.getModel(), sub.getDisplayTag(this));
                                    }
                                    List<Double> cameraOffset = sub.getCameraOffset(this);
                                    if (cameraOffset == null || cameraOffset.isEmpty()) {
                                        cameraOffset = Arrays.asList(0.0, 0.0, 0.0);
                                    }

                                    seatLoc = ModelTransformEngine.getSubsystemComponentPosition(
                                        vehicleRoot.getLocation(),
                                        mountOffset,
                                        cameraOffset,
                                        instance.getModel().getSeatOffset(),
                                        scale,
                                        instance.getModel().getFrontYawOffset(),
                                        vehicleRoot.getLocation().getYaw(),
                                        vehicleRoot.getLocation().getPitch(),
                                        relativeYaw,
                                        relativePitch,
                                        sub.getPivotOffset(this)
                                    );
                                    psYaw = relativeYaw;
                                    camActive = true;
                                    break;
                                }
                            }
                        }

                        if (!camActive) {
                            seatLoc = ModelTransformEngine.getSeatPosition(vehicleRoot.getLocation(), pOffsets.get(i), instance.getModel().getSeatOffset(), scale, instance.getModel().getFrontYawOffset());
                            if (instance.getModel().getVehicle() != null && i < instance.getModel().getVehicle().getPassengerSeats().size()) {
                                psYaw = instance.getModel().getVehicle().getPassengerSeats().get(i).getYaw();
                            }
                        }

                        seatLoc.setYaw((float) ((vehicleRoot.getLocation().getYaw() + psYaw) % 360.0));
                        teleportKeepPassengers(seat, seatLoc);
                        break;
                    }
                }
            }

            Entity driverSeat = instance.getDriverSeat();
            if (driverSeat != null && driverSeat.isValid()) {
                Player driverRider = null;
                for (Entity passenger : driverSeat.getPassengers()) {
                    if (passenger instanceof Player) {
                        driverRider = (Player) passenger;
                        break;
                    }
                }

                boolean driverCamActive = false;
                if (driverRider != null && instance.isWeaponCamActive(driverRider.getUniqueId()) && instance.getModel().getVehicle() != null) {
                    for (BdeModel.SubsystemConfig sub : instance.getModel().getVehicle().getSubsystems()) {
                        if (sub.getControllerSeatIndex() == -1) {
                            double relativeYaw = ((driverRider.getLocation().getYaw() - vehicleRoot.getLocation().getYaw()) % 360.0 + 540.0) % 360.0 - 180.0;
                            if (sub.getFovMinYaw(this) != null) relativeYaw = Math.max(sub.getFovMinYaw(this), relativeYaw);
                            if (sub.getFovMaxYaw(this) != null) relativeYaw = Math.min(sub.getFovMaxYaw(this), relativeYaw);
                            double relativePitch = driverRider.getLocation().getPitch();
                            if (sub.getFovMinPitch(this) != null) relativePitch = Math.max(sub.getFovMinPitch(this), relativePitch);
                            if (sub.getFovMaxPitch(this) != null) relativePitch = Math.min(sub.getFovMaxPitch(this), relativePitch);

                            List<Double> mountOffset = sub.getMountOffset();
                            if (mountOffset == null || mountOffset.isEmpty()) {
                                mountOffset = getSubsystemOffset(instance.getModel(), sub.getDisplayTag(this));
                            }
                            List<Double> cameraOffset = sub.getCameraOffset(this);
                            if (cameraOffset == null || cameraOffset.isEmpty()) {
                                cameraOffset = Arrays.asList(0.0, 0.0, 0.0);
                            }

                            Location dsLoc = ModelTransformEngine.getSubsystemComponentPosition(
                                vehicleRoot.getLocation(),
                                mountOffset,
                                cameraOffset,
                                instance.getModel().getSeatOffset(),
                                scale,
                                instance.getModel().getFrontYawOffset(),
                                vehicleRoot.getLocation().getYaw(),
                                vehicleRoot.getLocation().getPitch(),
                                relativeYaw,
                                relativePitch,
                                sub.getPivotOffset(this)
                            );
                            
                            if (vehicleRoot.getPassengers().contains(driverSeat)) {
                                vehicleRoot.removePassenger(driverSeat);
                            }
                            
                            dsLoc.setYaw((float) ((vehicleRoot.getLocation().getYaw() + relativeYaw) % 360.0));
                            teleportKeepPassengers(driverSeat, dsLoc);
                            driverCamActive = true;
                            break;
                        }
                    }
                }

                if (!driverCamActive) {
                    if (!vehicleRoot.getPassengers().contains(driverSeat)) {
                        driverSeat.teleport(vehicleRoot.getLocation());
                        vehicleRoot.addPassenger(driverSeat);
                    }
                    double dsYaw = 0.0;
                    if (instance.getModel().getVehicle() != null) {
                        dsYaw = instance.getModel().getVehicle().getDriverSeatYaw();
                    }
                    float finalDsYaw = (float) ((vehicleRoot.getLocation().getYaw() + dsYaw) % 360.0);
                    driverSeat.setRotation(finalDsYaw, driverSeat.getLocation().getPitch());
                }
            }
        }
    }

    public ModelInstance getInstanceByRoot(Entity root) {
        if (root == null) return null;
        if (!root.hasMetadata("bde_instance_id")) return null;
        String uuidStr = root.getMetadata("bde_instance_id").getFirst().asString();
        try {
            return activeInstances.get(UUID.fromString(uuidStr));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public Map<UUID, ModelInstance> getActiveInstances() {
        return activeInstances;
    }

    public void removeInstance(UUID id) {
        ModelInstance instance = activeInstances.remove(id);
        if (instance != null) {
            instance.cleanup();
        }
    }

    public void cleanupAll() {
        for (ModelInstance instance : new ArrayList<>(activeInstances.values())) {
            instance.cleanup();
        }
        activeInstances.clear();
    }

    public static List<String> parsePassengers(String snbt) {
        List<String> list = new ArrayList<>();
        int idx = snbt.indexOf("Passengers:[");
        if (idx == -1) {
            idx = snbt.indexOf("passengers:[");
        }
        if (idx == -1) {
            return list;
        }

        int start = idx + "Passengers:[".length();
        int depth = 1;
        int end = -1;
        for (int i = start; i < snbt.length(); i++) {
            char c = snbt.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) {
                    end = i;
                    break;
                }
            }
        }

        if (end == -1) {
            return list;
        }

        String content = snbt.substring(start, end);
        int braceDepth = 0;
        int objStart = -1;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '{') {
                if (braceDepth == 0) {
                    objStart = i;
                }
                braceDepth++;
            } else if (c == '}') {
                braceDepth--;
                if (braceDepth == 0 && objStart != -1) {
                    list.add(content.substring(objStart, i + 1));
                    objStart = -1;
                }
            }
        }
        return list;
    }

    public static String removePassengersTag(String snbt) {
        int idx = snbt.indexOf("Passengers:[");
        if (idx == -1) {
            idx = snbt.indexOf("passengers:[");
        }
        if (idx == -1) {
            return snbt;
        }

        int start = idx + "Passengers:[".length();
        int depth = 1;
        int end = -1;
        for (int i = start; i < snbt.length(); i++) {
            char c = snbt.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) {
                    end = i;
                    break;
                }
            }
        }

        if (end == -1) {
            return snbt;
        }

        String prefix = snbt.substring(0, idx);
        String suffix = snbt.substring(end + 1);

        prefix = prefix.trim();
        suffix = suffix.trim();
        if (prefix.endsWith(",") && suffix.startsWith(",")) {
            return prefix + suffix.substring(1);
        } else if (prefix.endsWith(",")) {
            if (suffix.startsWith("}")) {
                return prefix.substring(0, prefix.length() - 1) + suffix;
            }
            return prefix + suffix;
        } else if (suffix.startsWith(",")) {
            return prefix + suffix.substring(1);
        }
        return prefix + suffix;
    }

    public static void collectAllPassengers(String snbt, List<String> flatList) {
        String cleanSnbt = removePassengersTag(snbt);
        flatList.add(cleanSnbt);

        List<String> nested = parsePassengers(snbt);
        for (String child : nested) {
            collectAllPassengers(child, flatList);
        }
    }

    public static List<String> splitObjects(String snbt) {
        List<String> list = new ArrayList<>();
        if (snbt == null || snbt.trim().isEmpty()) {
            return list;
        }
        snbt = snbt.trim();
        if (snbt.startsWith("[") && snbt.endsWith("]")) {
            snbt = snbt.substring(1, snbt.length() - 1).trim();
        }

        int depth = 0;
        int start = 0;
        boolean inQuotes = false;
        char quoteChar = 0;

        for (int i = 0; i < snbt.length(); i++) {
            char c = snbt.charAt(i);
            if (inQuotes) {
                if (c == quoteChar) {
                    int escapeCount = 0;
                    for (int j = i - 1; j >= 0; j--) {
                        if (snbt.charAt(j) == '\\') {
                            escapeCount++;
                        } else {
                            break;
                        }
                    }
                    if (escapeCount % 2 == 0) {
                        inQuotes = false;
                    }
                }
            } else {
                if (c == '"' || c == '\'') {
                    inQuotes = true;
                    quoteChar = c;
                } else if (c == '{' || c == '[') {
                    depth++;
                } else if (c == '}' || c == ']') {
                    depth--;
                } else if (c == ',' && depth == 0) {
                    String part = snbt.substring(start, i).trim();
                    if (!part.isEmpty()) {
                        list.add(part);
                    }
                    start = i + 1;
                }
            }
        }
        if (start < snbt.length()) {
            String part = snbt.substring(start).trim();
            if (!part.isEmpty()) {
                list.add(part);
            }
        }
        return list;
    }

    private static String extractSkinUrl(String base64) {
        try {
            byte[] decoded = Base64.getDecoder().decode(base64);
            String json = new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
            int idx = json.indexOf("http://textures.minecraft.net/texture/");
            if (idx == -1) {
                idx = json.indexOf("https://textures.minecraft.net/texture/");
            }
            if (idx != -1) {
                int end = json.indexOf("\"", idx);
                if (end != -1) {
                    String url = json.substring(idx, end);
                    if (url.startsWith("http://")) {
                        url = "https://" + url.substring(7);
                    }
                    return url;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    public void teleportModel(ModelInstance instance, Location newLoc) {
        instance.setCurrentLocation(newLoc.clone());
        instance.setCurrentYaw((float) newLoc.getYaw());
        instance.setVelocityX(0.0);
        instance.setVelocityZ(0.0);
        instance.setCurrentSpeed(0.0);
        if (instance.getVehicleRoot() != null) {
            teleportKeepPassengers(instance.getVehicleRoot(), newLoc);
            updateHitboxLocations(instance, newLoc);
        } else {
            for (Display display : instance.getPassengers()) {
                display.teleport(newLoc);
            }
            if (instance.getRootEntity() != null) {
                Location hitboxLoc = ModelTransformEngine.getHitboxPosition(newLoc, instance.getModel(), instance.getScale());
                hitboxLoc.setYaw(newLoc.getYaw());
                
                Entity root = instance.getRootEntity();
                List<Entity> passengers = new ArrayList<>(root.getPassengers());
                for (Entity passenger : passengers) {
                    root.removePassenger(passenger);
                }
                root.teleport(hitboxLoc);
                for (Entity passenger : passengers) {
                    root.addPassenger(passenger);
                }
            }
        }
        if (plugin.getBdeGuiManager() != null) {
            plugin.getBdeGuiManager().updateSelectionHighlight(instance.getId());
        }
    }

    public void mountPlayer(ModelInstance instance, Player player) {
        if (instance.getVehicleRoot() == null) return;
        Entity root = instance.getVehicleRoot();

        Player currentDriver = null;
        if (instance.getDriverSeat() != null) {
            for (Entity p : instance.getDriverSeat().getPassengers()) {
                if (p instanceof Player) {
                    currentDriver = (Player) p;
                    break;
                }
            }
        } else {
            for (Entity p : root.getPassengers()) {
                if (p instanceof Player) {
                    currentDriver = (Player) p;
                    break;
                }
            }
        }

        boolean driverFree = (currentDriver == null);
        
        List<org.bukkit.entity.ArmorStand> passengerSeats = instance.getPassengerSeats();
        List<org.bukkit.entity.ArmorStand> vacantPassengerSeats = new ArrayList<>();
        for (org.bukkit.entity.ArmorStand seat : passengerSeats) {
            if (seat.isValid() && seat.getPassengers().isEmpty()) {
                vacantPassengerSeats.add(seat);
            }
        }

        int totalVacant = (driverFree ? 1 : 0) + vacantPassengerSeats.size();

        // 1. Sneaking (Shift + Right-Click): Mount Driver Seat only
        if (player.isSneaking()) {
            if (driverFree) {
                autoDetectDriverSeatYaw(instance, player);
                Entity seat = instance.getDriverSeat() != null ? instance.getDriverSeat() : root;
                seat.addPassenger(player);
                inputTracker.inject(player);
                player.sendMessage("§aMounted vehicle as driver! Use WASD to steer, Shift to dismount.");
            } else {
                player.sendMessage("§cThe driver seat for " + instance.getModel().getProjectId() + " is taken by " + currentDriver.getName() + ".");
            }
            return;
        }

        // 2. Normal click:
        if (totalVacant == 0) {
            player.sendMessage("§cThis vehicle is full!");
            return;
        }

        if (totalVacant == 1) {
            // Auto sit in the only vacant seat
            if (driverFree) {
                autoDetectDriverSeatYaw(instance, player);
                Entity seat = instance.getDriverSeat() != null ? instance.getDriverSeat() : root;
                seat.addPassenger(player);
                inputTracker.inject(player);
                player.sendMessage("§aMounted vehicle as driver! Use WASD to steer, Shift to dismount.");
            } else {
                org.bukkit.entity.ArmorStand seat = vacantPassengerSeats.get(0);
                seat.addPassenger(player);
                player.sendMessage("§aMounted vehicle as co-passenger! Shift to dismount.");
            }
            return;
        }

        // totalVacant > 1: open the Seat Selection GUI!
        plugin.getBdeGuiManager().openSeatSelectionMenu(player, instance);
    }

    private void autoDetectDriverSeatYaw(ModelInstance instance, Player player) {
        Entity root = instance.getVehicleRoot();
        if (root == null) return;
        BdeModel.VehicleConfig cfg = instance.getModel().getVehicle();
        if (cfg != null && (cfg.getDriverSeatYaw() == null || cfg.getDriverSeatYaw() == 0.0)) {
            double relativeYaw = player.getLocation().getYaw() - root.getLocation().getYaw();
            relativeYaw = (relativeYaw % 360.0 + 360.0) % 360.0;
            
            double snapped = Math.round(relativeYaw / 90.0) * 90.0;
            snapped = (snapped % 360.0 + 360.0) % 360.0;
            
            double finalYaw;
            if (Math.abs(relativeYaw - snapped) <= 15.0 || Math.abs(relativeYaw - snapped) >= 345.0) {
                finalYaw = snapped;
            } else {
                finalYaw = relativeYaw;
            }
            
            cfg.setDriverSeatYaw(finalYaw);
            if (instance.getModel().getLocalFilePath() != null) {
                saveModelConfig(instance.getModel());
            }
        }
    }

    public void handleDismount(Player player, Entity dismounted) {
        for (ModelInstance instance : activeInstances.values()) {
            if ((instance.getVehicleRoot() != null && instance.getVehicleRoot().equals(dismounted)) ||
                (instance.getDriverSeat() != null && instance.getDriverSeat().equals(dismounted))) {
                inputTracker.uninject(player);
                player.sendMessage("§eDismounted vehicle.");
                return;
            }
        }

        if (dismounted instanceof org.bukkit.entity.ArmorStand && dismounted.getScoreboardTags().contains("bde_seat")) {
            // Do NOT remove the seat stand since it is now persistent!
            player.sendMessage("§eDismounted vehicle.");
        }
    }

    public void saveModelConfig(BdeModel model) {
        model.prepareForSave();
        String path = model.getLocalFilePath();
        if (path == null) {
            path = model.getProjectId();
        }
        if (path == null || path.isEmpty()) {
            plugin.getLogger().warning("Failed to save model config: no projectId or localFilePath found.");
            return;
        }
        if (!path.endsWith(".json")) {
            path = path + ".json";
        }
        
        File folder;
        if (model.getVehicleStats() != null || model.isVehicleLibrary()) {
            folder = new File(plugin.getDataFolder(), "vehicles");
        } else {
            folder = new File(plugin.getDataFolder(), "models");
        }
        
        File file = new File(folder, path);
        file.getParentFile().mkdirs();
        try (FileWriter writer = new FileWriter(file)) {
            gson.toJson(model, writer);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save model config for " + path + ": " + e.getMessage());
        }
    }

    private void verifyAndRemountPassengers(ModelInstance instance) {
        Entity root = instance.getRootEntity();
        if (root == null) return;

        List<Entity> currentPassengers = root.getPassengers();
        int remountedCount = 0;
        for (Display display : instance.getPassengers()) {
            if (display.isValid() && !currentPassengers.contains(display)) {
                root.addPassenger(display);
                remountedCount++;
            }
        }
        if (DEBUG_VEHICLES && remountedCount > 0) {
            plugin.getLogger().info("[BDE Debug] Remounted " + remountedCount + " displays onto root " + root.getUniqueId());
        }
    }

    private double getBlockTraction(Location loc, BdeModel.VehicleStats stats) {
        org.bukkit.Material blockType = loc.clone().subtract(0, 0.1, 0).getBlock().getType();
        String matName = blockType.name();

        // 1. Check vehicle overrides
        if (stats.getBlockOverrides() != null && stats.getBlockOverrides().containsKey(matName)) {
            return stats.getBlockOverrides().get(matName);
        }

        // 2. Check config
        String configPath = "block-traction." + matName;
        if (plugin.getConfig().contains(configPath)) {
            return plugin.getConfig().getDouble(configPath);
        }

        // 3. Fallback to hardcoded defaults
        switch (blockType) {
            case BLUE_ICE:
                return 0.02;
            case PACKED_ICE:
                return 0.05;
            case ICE:
                return 0.1;
            case SLIME_BLOCK:
                return 0.2;
            case SOUL_SAND:
                return 0.4;
            default:
                return 1.0;
        }
    }

    private void startVehicleTick() {
        new org.bukkit.scheduler.BukkitRunnable() {
            @Override
            public void run() {
                for (ModelInstance instance : activeInstances.values()) {
                    if (instance.getVehicleRoot() == null || !instance.getVehicleRoot().isValid()) continue;
                    
                    Entity vehicleRoot = instance.getVehicleRoot();
                    Entity hitbox = instance.getRootEntity();
                    
                    List<Entity> passengers = vehicleRoot.getPassengers();
                    Player driver = null;
                    
                    for (Entity passenger : passengers) {
                        if (passenger instanceof Player) {
                            driver = (Player) passenger;
                            break;
                        }
                    }
                    if (driver == null && instance.getDriverSeat() != null) {
                        for (Entity passenger : instance.getDriverSeat().getPassengers()) {
                            if (passenger instanceof Player) {
                                driver = (Player) passenger;
                                break;
                            }
                        }
                    }

                    if (DEBUG_VEHICLES && Bukkit.getCurrentTick() % 20 == 0) {
                        plugin.getLogger().info(String.format(
                            "[BDE Debug] Vehicle Tick - ID: %s | Driver: %s | vehicleRoot Passengers: %d | driverSeat: %s | driverSeat Passengers: %d",
                            instance.getId(),
                            (driver != null ? driver.getName() : "NONE"),
                            passengers.size(),
                            (instance.getDriverSeat() != null ? instance.getDriverSeat().getType().name() : "null"),
                            (instance.getDriverSeat() != null ? instance.getDriverSeat().getPassengers().size() : 0)
                        ));
                    }
                    BdeModel.VehicleStats stats = instance.getModel().getVehicleStats();
                    if (stats == null) continue;

                    Location rootLoc = vehicleRoot.getLocation();
                    double vehicleTraction = stats.getTraction();
                    double blockTraction = getBlockTraction(rootLoc, stats);
                    double effectiveTraction = Math.max(0.0, Math.min(1.0, vehicleTraction * blockTraction));

                    if (driver != null) {
                        PlayerInputTracker.PlayerInputData input = inputTracker.getInput(driver.getUniqueId());
                        
                        double topSpeed = stats.getTopSpeed();
                        double accel = stats.getAcceleration();
                        double decel = stats.getDeceleration();
                        double turnSpeed = stats.getTurnSpeed();
                        
                        float yaw = instance.getCurrentYaw();
                        
                        if (input.left) {
                            yaw = (yaw - (float) turnSpeed) % 360;
                        }
                        if (input.right) {
                            yaw = (yaw + (float) turnSpeed) % 360;
                        }
                        instance.setCurrentYaw(yaw);
                        
                        double speed = instance.getCurrentSpeed();
                        if (input.forward) {
                            speed = Math.min(topSpeed, speed + accel);
                        } else if (input.backward) {
                            speed = Math.max(-stats.getReverseSpeed(), speed - accel);
                        } else {
                            if (speed > 0) {
                                speed = Math.max(0.0, speed - decel);
                            } else if (speed < 0) {
                                speed = Math.min(0.0, speed + decel);
                            }
                        }
                        instance.setCurrentSpeed(speed);
                        
                        // Compute target horizontal velocities
                        double yawRad = Math.toRadians(yaw);
                        double tx = -Math.sin(yawRad) * speed;
                        double tz = Math.cos(yawRad) * speed;
                        
                        // Linearly interpolate actual velocities
                        double vx = instance.getVelocityX() + (tx - instance.getVelocityX()) * effectiveTraction;
                        double vz = instance.getVelocityZ() + (tz - instance.getVelocityZ()) * effectiveTraction;
                        instance.setVelocityX(vx);
                        instance.setVelocityZ(vz);
                        
                        Location targetLoc = rootLoc.clone().add(vx, 0, vz);
                        targetLoc.setYaw(yaw);
                        
                        double newY = targetLoc.getY();
                        boolean hasGround = false;
                        
                        for (double yOffset = 1.5; yOffset >= -2.0; yOffset -= 0.5) {
                            Location scanLoc = targetLoc.clone().add(0, yOffset, 0);
                            if (scanLoc.getBlock().getType().isSolid()) {
                                newY = scanLoc.getBlock().getY() + 1.0;
                                hasGround = true;
                                break;
                            }
                        }
                        
                        if (hasGround) {
                            double yDiff = newY - rootLoc.getY();
                            if (yDiff <= 1.2) {
                                targetLoc.setY(newY);
                            } else {
                                targetLoc.setX(rootLoc.getX());
                                targetLoc.setZ(rootLoc.getZ());
                                instance.setCurrentSpeed(0.0);
                                instance.setVelocityX(0.0);
                                instance.setVelocityZ(0.0);
                            }
                        } else {
                            targetLoc.setY(rootLoc.getY() - 0.2);
                        }
                        
                        if (DEBUG_VEHICLES && Bukkit.getCurrentTick() % 20 == 0) {
                            plugin.getLogger().info(String.format(
                                "[BDE Debug] Steering - Vehicle: %s | Driver: %s | Loc: %.2f, %.2f, %.2f | Speed: %.3f | Inputs: W=%b S=%b A=%b D=%b",
                                instance.getId(), driver.getName(), targetLoc.getX(), targetLoc.getY(), targetLoc.getZ(),
                                speed, input.forward, input.backward, input.left, input.right
                            ));
                        }

                        // Apply physical velocity for smooth client interpolation
                        org.bukkit.util.Vector vel = new org.bukkit.util.Vector(vx, targetLoc.getY() - rootLoc.getY(), vz);
                        vehicleRoot.setVelocity(vel);
                        teleportKeepPassengers(vehicleRoot, targetLoc);
                        
                        // Sync hitbox positions
                        updateHitboxLocations(instance, targetLoc);
                    } else {
                        double speed = instance.getCurrentSpeed();
                        if (speed != 0.0 || Math.abs(instance.getVelocityX()) > 0.001 || Math.abs(instance.getVelocityZ()) > 0.001) {
                            double decel = stats.getDeceleration();
                            if (speed > 0) {
                                speed = Math.max(0.0, speed - decel);
                            } else if (speed < 0) {
                                speed = Math.min(0.0, speed + decel);
                            }
                            instance.setCurrentSpeed(speed);
                            
                            float yaw = instance.getCurrentYaw();
                            double yawRad = Math.toRadians(yaw);
                            double tx = -Math.sin(yawRad) * speed;
                            double tz = Math.cos(yawRad) * speed;
                            
                            double vx = instance.getVelocityX() + (tx - instance.getVelocityX()) * effectiveTraction;
                            double vz = instance.getVelocityZ() + (tz - instance.getVelocityZ()) * effectiveTraction;
                            instance.setVelocityX(vx);
                            instance.setVelocityZ(vz);
                            
                            Location targetLoc = rootLoc.clone().add(vx, 0, vz);
                            targetLoc.setYaw(yaw);
                            
                            double newY = targetLoc.getY();
                            boolean hasGround = false;
                            for (double yOffset = 1.5; yOffset >= -2.0; yOffset -= 0.5) {
                                Location scanLoc = targetLoc.clone().add(0, yOffset, 0);
                                if (scanLoc.getBlock().getType().isSolid()) {
                                    newY = scanLoc.getBlock().getY() + 1.0;
                                    hasGround = true;
                                    break;
                                }
                            }
                            if (hasGround) {
                                double yDiff = newY - rootLoc.getY();
                                if (yDiff <= 1.2) {
                                    targetLoc.setY(newY);
                                } else {
                                    targetLoc.setX(rootLoc.getX());
                                    targetLoc.setZ(rootLoc.getZ());
                                    instance.setCurrentSpeed(0.0);
                                    instance.setVelocityX(0.0);
                                    instance.setVelocityZ(0.0);
                                }
                            } else {
                                targetLoc.setY(rootLoc.getY() - 0.2);
                            }
                            
                            org.bukkit.util.Vector vel = new org.bukkit.util.Vector(vx, targetLoc.getY() - rootLoc.getY(), vz);
                            vehicleRoot.setVelocity(vel);
                            teleportKeepPassengers(vehicleRoot, targetLoc);
                            
                            // Sync hitbox positions
                            updateHitboxLocations(instance, targetLoc);
                        }
                    }

                    // Always update subsystems and seats every tick!
                    updateSubsystems(instance);
                    updateSeatLocations(instance);
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    public static class BoundingBox {
        private float minX, maxX;
        private float minY, maxY;
        private float minZ, maxZ;

        public float getWidth() { return maxX - minX; }
        public float getHeight() { return maxY - minY; }
        public float getDepth() { return maxZ - minZ; }
        public float getMinX() { return minX; }
        public float getMaxX() { return maxX; }
        public float getMinY() { return minY; }
        public float getMaxY() { return maxY; }
        public float getMinZ() { return minZ; }
        public float getMaxZ() { return maxZ; }
    }

    public static BoundingBox calculateBounds(ModelInstance instance) {
        return calculateModelBounds(instance.getModel(), instance.getScale());
    }

    public static BoundingBox calculateModelBounds(BdeModel model, double scale) {
        BoundingBox box = new BoundingBox();
        box.minX = box.minY = box.minZ = Float.MAX_VALUE;
        box.maxX = box.maxY = box.maxZ = -Float.MAX_VALUE;

        if (model == null || model.getPassengers() == null) {
            box.minX = -0.5f; box.maxX = 0.5f;
            box.minY = 0f; box.maxY = 1.0f;
            box.minZ = -0.5f; box.maxZ = 0.5f;
            return box;
        }

        List<String> flatPassengers = new ArrayList<>();
        for (String passengerSnbt : model.getPassengers()) {
            List<String> split = splitObjects(passengerSnbt);
            for (String p : split) {
                collectAllPassengers(p, flatPassengers);
            }
        }

        boolean hasPassengers = false;
        for (String snbt : flatPassengers) {
            // Decompose transformation matrix from SNBT
            float[] m = new float[16];
            int transIdx = snbt.indexOf("transformation:[");
            if (transIdx != -1) {
                int endTrans = snbt.indexOf("]", transIdx);
                String matrixStr = snbt.substring(transIdx + 16, endTrans);
                String[] parts = matrixStr.split(",");
                if (parts.length == 16) {
                    for (int i = 0; i < 16; i++) {
                        String p = parts[i].trim().toLowerCase();
                        if (p.endsWith("f")) p = p.substring(0, p.length() - 1);
                        m[i] = Float.parseFloat(p);
                    }
                }
            } else {
                // Default identity matrix
                m[0] = 1f; m[5] = 1f; m[10] = 1f; m[15] = 1f;
            }

            Matrix4f matrix = new Matrix4f();
            matrix.setTransposed(m);

            Vector3f translation = new Vector3f();
            matrix.getTranslation(translation);

            Vector3f scaleVec = new Vector3f();
            matrix.getScale(scaleVec);

            translation.mul((float) scale);
            scaleVec.mul((float) scale);

            boolean isItemDisplay = isItemDisplaySnbt(snbt);
            boolean isTextDisplay = isTextDisplaySnbt(snbt);

            float minPx, maxPx, minPy, maxPy, minPz, maxPz;
            if (!isItemDisplay && !isTextDisplay) { // BlockDisplay
                minPx = translation.x;
                maxPx = translation.x + scaleVec.x;
                minPy = translation.y;
                maxPy = translation.y + scaleVec.y;
                minPz = translation.z;
                maxPz = translation.z + scaleVec.z;
            } else {
                minPx = translation.x - scaleVec.x / 2f;
                maxPx = translation.x + scaleVec.x / 2f;
                minPy = translation.y - scaleVec.y / 2f;
                maxPy = translation.y + scaleVec.y / 2f;
                minPz = translation.z - scaleVec.z / 2f;
                maxPz = translation.z + scaleVec.z / 2f;
            }

            if (model.getFrontYawOffset() != 0.0) {
                float rad = (float) Math.toRadians(model.getFrontYawOffset());
                float cos = (float) Math.cos(rad);
                float sin = (float) Math.sin(rad);

                float[][] corners = {
                    {minPx, minPz},
                    {minPx, maxPz},
                    {maxPx, minPz},
                    {maxPx, maxPz}
                };

                float rMinX = Float.MAX_VALUE;
                float rMaxX = -Float.MAX_VALUE;
                float rMinZ = Float.MAX_VALUE;
                float rMaxZ = -Float.MAX_VALUE;

                for (float[] corner : corners) {
                    float rx = corner[0] * cos - corner[1] * sin;
                    float rz = corner[0] * sin + corner[1] * cos;
                    rMinX = Math.min(rMinX, rx);
                    rMaxX = Math.max(rMaxX, rx);
                    rMinZ = Math.min(rMinZ, rz);
                    rMaxZ = Math.max(rMaxZ, rz);
                }

                minPx = rMinX;
                maxPx = rMaxX;
                minPz = rMinZ;
                maxPz = rMaxZ;
            }

            box.minX = Math.min(box.minX, minPx);
            box.maxX = Math.max(box.maxX, maxPx);
            box.minY = Math.min(box.minY, minPy);
            box.maxY = Math.max(box.maxY, maxPy);
            box.minZ = Math.min(box.minZ, minPz);
            box.maxZ = Math.max(box.maxZ, maxPz);
            hasPassengers = true;
        }

        if (!hasPassengers) {
            box.minX = -0.5f; box.maxX = 0.5f;
            box.minY = 0f; box.maxY = 1.0f;
            box.minZ = -0.5f; box.maxZ = 0.5f;
        }
        return box;
    }

    private static String extractValueProperty(String snbt) {
        int valIdx = snbt.indexOf("\"Value\"");
        if (valIdx == -1) {
            valIdx = snbt.indexOf("'Value'");
        }
        if (valIdx == -1) {
            valIdx = snbt.indexOf("Value");
        }
        if (valIdx == -1) {
            valIdx = snbt.indexOf("\"value\"");
        }
        if (valIdx == -1) {
            valIdx = snbt.indexOf("'value'");
        }
        if (valIdx == -1) {
            valIdx = snbt.indexOf("value");
        }
        if (valIdx == -1) return null;

        int colonIdx = snbt.indexOf(":", valIdx);
        if (colonIdx == -1) return null;

        int startQuoteIdx = -1;
        char quoteChar = 0;
        for (int i = colonIdx + 1; i < snbt.length(); i++) {
            char c = snbt.charAt(i);
            if (c == '"' || c == '\'') {
                startQuoteIdx = i;
                quoteChar = c;
                break;
            }
            if (c == '}' || c == ']' || c == ',') break;
        }

        if (startQuoteIdx == -1) return null;

        int endQuoteIdx = snbt.indexOf(quoteChar, startQuoteIdx + 1);
        if (endQuoteIdx == -1) return null;

        return snbt.substring(startQuoteIdx + 1, endQuoteIdx);
    }

    public static class LockSession {
        public Entity target;
        public long startTime;
        public String lastStatus = ""; // "none", "soft", "full"
    }

    public static class CustomProjectile {
        private final Entity baseEntity;
        private final Display displayEntity;
        private final Player shooter;
        private final BdeModel.ProjectileConfig config;
        private final Entity target;
        private final boolean fullLock;
        private final Location targetCoord;
        private org.bukkit.util.Vector velocity;
        private int ticksLived = 0;

        public CustomProjectile(Entity baseEntity, Display displayEntity, Player shooter, BdeModel.ProjectileConfig config, org.bukkit.util.Vector velocity, Entity target, boolean fullLock, Location targetCoord) {
            this.baseEntity = baseEntity;
            this.displayEntity = displayEntity;
            this.shooter = shooter;
            this.config = config;
            this.velocity = velocity;
            this.target = target;
            this.fullLock = fullLock;
            this.targetCoord = targetCoord;
        }

        public Entity getBaseEntity() { return baseEntity; }
        public Display getDisplayEntity() { return displayEntity; }
        public Player getShooter() { return shooter; }
        public BdeModel.ProjectileConfig getConfig() { return config; }
        public org.bukkit.util.Vector getVelocity() { return velocity; }
        public void setVelocity(org.bukkit.util.Vector velocity) { this.velocity = velocity; }
        public int getTicksLived() { return ticksLived; }
        public void incrementTicks() { this.ticksLived++; }
        public Entity getTarget() { return target; }
        public boolean isFullLock() { return fullLock; }
        public Location getTargetCoord() { return targetCoord; }
    }

    private void startProjectileTick() {
        new org.bukkit.scheduler.BukkitRunnable() {
            @Override
            public void run() {
                Iterator<CustomProjectile> it = activeProjectiles.iterator();
                while (it.hasNext()) {
                    CustomProjectile proj = it.next();
                    Entity base = proj.getBaseEntity();
                    if (base == null || !base.isValid()) {
                        if (proj.getDisplayEntity() != null && proj.getDisplayEntity().isValid()) {
                            proj.getDisplayEntity().remove();
                        }
                        it.remove();
                        continue;
                    }

                    proj.incrementTicks();
                    if (proj.getTicksLived() > 200) {
                        base.remove();
                        if (proj.getDisplayEntity() != null && proj.getDisplayEntity().isValid()) {
                            proj.getDisplayEntity().remove();
                        }
                        it.remove();
                        continue;
                    }

                    BdeModel.ProjectileConfig config = proj.getConfig();
                    Location loc = base.getLocation();
                    org.bukkit.util.Vector vel = proj.getVelocity();

                    if (config.isHasGravity()) {
                        vel.add(new org.bukkit.util.Vector(0, -0.04, 0));
                    }

                    if (config.isLockOn()) {
                        if (proj.isFullLock() && proj.getTarget() != null && proj.getTarget().isValid()) {
                            Location targetLoc = proj.getTarget().getLocation();
                            targetLoc.add(0, proj.getTarget().getHeight() / 2, 0);
                            org.bukkit.util.Vector toTarget = targetLoc.toVector().subtract(loc.toVector()).normalize();
                            vel = vel.add(toTarget.multiply(0.2)).normalize().multiply(config.getSpeed());
                            proj.setVelocity(vel);
                        } else if (!proj.isFullLock() && proj.getTargetCoord() != null) {
                            org.bukkit.util.Vector toTarget = proj.getTargetCoord().toVector().subtract(loc.toVector()).normalize();
                            vel = vel.add(toTarget.multiply(0.15)).normalize().multiply(config.getSpeed());
                            proj.setVelocity(vel);
                        }
                    }

                    Location nextLoc = loc.clone().add(vel);
                    nextLoc.setDirection(vel);
                    base.teleport(nextLoc);

                    if (proj.getDisplayEntity() != null && proj.getDisplayEntity().isValid()) {
                        proj.getDisplayEntity().teleport(nextLoc);
                    }

                    if (config.getFlyParticle() != null && !config.getFlyParticle().isEmpty()) {
                        try {
                            org.bukkit.Particle p = org.bukkit.Particle.valueOf(config.getFlyParticle().toUpperCase());
                            loc.getWorld().spawnParticle(p, loc, config.getFlyParticleCount(), 0.05, 0.05, 0.05, 0.01);
                        } catch (IllegalArgumentException ignored) {}
                    }

                    org.bukkit.block.Block block = nextLoc.getBlock();
                    boolean collided = block.getType().isSolid();
                    org.bukkit.entity.Entity hitEntity = null;

                    if (!collided) {
                        java.util.Collection<Entity> nearby = loc.getWorld().getNearbyEntities(nextLoc, 0.6, 0.6, 0.6);
                        for (Entity e : nearby) {
                            if (e.equals(base) || e.equals(proj.getShooter()) || (proj.getDisplayEntity() != null && e.equals(proj.getDisplayEntity()))) {
                                continue;
                            }
                            ModelInstance shooterInst = null;
                            for (ModelInstance inst : activeInstances.values()) {
                                if (inst.getVehicleRoot() != null && inst.getVehicleRoot().getPassengers().contains(proj.getShooter())) {
                                    shooterInst = inst;
                                    break;
                                }
                                if (inst.getDriverSeat() != null && inst.getDriverSeat().getPassengers().contains(proj.getShooter())) {
                                    shooterInst = inst;
                                    break;
                                }
                            }
                            if (shooterInst != null) {
                                if (e.equals(shooterInst.getVehicleRoot()) || shooterInst.getHitboxes().contains(e) || shooterInst.getPassengerSeats().contains(e) || e.equals(shooterInst.getDriverSeat())) {
                                    continue;
                                }
                            }

                            if (e instanceof org.bukkit.entity.LivingEntity || (e instanceof Interaction && e.getScoreboardTags().contains("bde_root"))) {
                                hitEntity = e;
                                collided = true;
                                break;
                            }
                        }
                    }

                    if (collided) {
                        Location impactLoc = hitEntity != null ? hitEntity.getLocation() : nextLoc;
                        
                        if (config.getImpactParticle() != null && !config.getImpactParticle().isEmpty()) {
                            try {
                                org.bukkit.Particle p = org.bukkit.Particle.valueOf(config.getImpactParticle().toUpperCase());
                                impactLoc.getWorld().spawnParticle(p, impactLoc, config.getImpactParticleCount(), 0.2, 0.2, 0.2, 0.1);
                            } catch (IllegalArgumentException ignored) {}
                        }

                        if (hitEntity != null) {
                            if (hitEntity instanceof org.bukkit.entity.LivingEntity) {
                                ((org.bukkit.entity.LivingEntity) hitEntity).damage(config.getDamage(), proj.getShooter());
                            } else if (hitEntity instanceof Interaction) {
                                ModelInstance hitInstance = getInstanceByRoot(hitEntity);
                                if (hitInstance != null) {
                                    double hp = hitInstance.getCurrentHp();
                                    hp = Math.max(0.0, hp - config.getDamage());
                                    hitInstance.setCurrentHp(hp);
                                    
                                    Location hitLoc = hitEntity.getLocation();
                                    hitLoc.getWorld().playSound(hitLoc, org.bukkit.Sound.ENTITY_ITEM_BREAK, 1.0f, 0.8f);
                                    try {
                                        hitLoc.getWorld().spawnParticle(org.bukkit.Particle.valueOf("DAMAGE_INDICATOR"), hitLoc, 5, 0.2, 0.2, 0.2, 0.1);
                                    } catch (Exception ignored) {}
                                    
                                    proj.getShooter().sendMessage("§cVehicle HP: §l" + String.format("%.1f", hp) + "§7/§f" + String.format("%.1f", hitInstance.getModel().getVehicleStats().getMaxHp()));

                                    if (hp <= 0.0) {
                                        hitLoc.getWorld().createExplosion(hitLoc, 2.0f, false, false);
                                        removeInstance(hitInstance.getId());
                                        proj.getShooter().sendMessage("§cVehicle destroyed!");
                                    }
                                }
                            }
                        }

                        if ("explode".equalsIgnoreCase(config.getOnHit())) {
                            Location explLoc = nextLoc;
                            if (config.isVanillaExplosionDamage()) {
                                explLoc.getWorld().createExplosion(explLoc, (float) config.getExplosionPower(), config.isDestroyBlocks(), config.isDestroyBlocks(), base);
                            } else {
                                explLoc.getWorld().createExplosion(explLoc, (float) config.getExplosionPower(), false, false, base);
                                
                                double rad = config.getExplosionPower() * 2.0;
                                for (Entity e : explLoc.getWorld().getNearbyEntities(explLoc, rad, rad, rad)) {
                                    if (e.equals(proj.getShooter())) continue;
                                    
                                    if (e instanceof org.bukkit.entity.LivingEntity) {
                                        double dist = e.getLocation().distance(explLoc);
                                        if (dist <= rad) {
                                            double factor = 1.0 - (dist / rad);
                                            double finalDmg = config.getDamage() * factor;
                                            ((org.bukkit.entity.LivingEntity) e).damage(finalDmg, proj.getShooter());
                                        }
                                    } else if (e instanceof Interaction && e.getScoreboardTags().contains("bde_root")) {
                                        ModelInstance hitInstance = getInstanceByRoot(e);
                                        if (hitInstance != null) {
                                            double dist = e.getLocation().distance(explLoc);
                                            if (dist <= rad) {
                                                double factor = 1.0 - (dist / rad);
                                                double finalDmg = config.getDamage() * factor;
                                                
                                                double hp = hitInstance.getCurrentHp();
                                                hp = Math.max(0.0, hp - finalDmg);
                                                hitInstance.setCurrentHp(hp);
                                                
                                                Location hitLoc = e.getLocation();
                                                hitLoc.getWorld().playSound(hitLoc, org.bukkit.Sound.ENTITY_ITEM_BREAK, 1.0f, 0.8f);
                                                
                                                proj.getShooter().sendMessage("§cVehicle HP: §l" + String.format("%.1f", hp) + "§7/§f" + String.format("%.1f", hitInstance.getModel().getVehicleStats().getMaxHp()));

                                                if (hp <= 0.0) {
                                                    hitLoc.getWorld().createExplosion(hitLoc, 2.0f, false, false);
                                                    removeInstance(hitInstance.getId());
                                                    proj.getShooter().sendMessage("§cVehicle destroyed!");
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        base.remove();
                        if (proj.getDisplayEntity() != null && proj.getDisplayEntity().isValid()) {
                            proj.getDisplayEntity().remove();
                        }
                        it.remove();
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    public void updateSubsystems(ModelInstance instance) {
        BdeModel model = instance.getModel();
        if (model.getVehicle() == null || model.getVehicle().getSubsystems() == null || model.getVehicle().getSubsystems().isEmpty()) {
            return;
        }

        Entity vehicleRoot = instance.getVehicleRoot();
        if (vehicleRoot == null || !vehicleRoot.isValid()) return;

        Location rootLoc = vehicleRoot.getLocation();
        double scale = instance.getScale();
        BoundingBox box = calculateModelBounds(model, scale);
        float interactionHeight = Math.max(0.1f, box.maxY);
        float mountHeight = getMountHeight(vehicleRoot, interactionHeight);

        for (BdeModel.SubsystemConfig sub : model.getVehicle().getSubsystems()) {
            Player controller = null;
            if (sub.getControllerSeatIndex() == -1) {
                for (Entity passenger : vehicleRoot.getPassengers()) {
                    if (passenger instanceof Player) {
                        controller = (Player) passenger;
                        break;
                    }
                }
                if (controller == null && instance.getDriverSeat() != null) {
                    for (Entity passenger : instance.getDriverSeat().getPassengers()) {
                        if (passenger instanceof Player) {
                            controller = (Player) passenger;
                            break;
                        }
                    }
                }
            } else {
                int seatIdx = sub.getControllerSeatIndex();
                List<org.bukkit.entity.ArmorStand> seats = instance.getPassengerSeats();
                if (seatIdx >= 0 && seatIdx < seats.size()) {
                    org.bukkit.entity.ArmorStand seat = seats.get(seatIdx);
                    if (seat != null && seat.isValid()) {
                        for (Entity passenger : seat.getPassengers()) {
                            if (passenger instanceof Player) {
                                controller = (Player) passenger;
                                break;
                            }
                        }
                    }
                }
            }

            List<Display> subDisplays = new ArrayList<>();
            int subIdx = model.getVehicle().getSubsystems().indexOf(sub);
            for (Display display : instance.getPassengers()) {
                if (sub.getDisplayTag(this) != null && !sub.getDisplayTag(this).isEmpty() && display.getScoreboardTags().contains(sub.getDisplayTag(this))) {
                    subDisplays.add(display);
                } else if (display.hasMetadata("bde_subsystem_parent_index") &&
                           display.getMetadata("bde_subsystem_parent_index").get(0).asInt() == subIdx) {
                    subDisplays.add(display);
                }
            }

            if (subDisplays.isEmpty()) continue;

            if (controller != null && controller.isOnline()) {
                double subYaw;
                double subPitch;

                if (instance.isSubsystemWasdAiming(sub.getName())) {
                    PlayerInputTracker.PlayerInputData input = inputTracker.getInput(controller.getUniqueId());
                    
                    double yawVal = instance.getSubsystemAimYaw(sub.getName(), rootLoc.getYaw());
                    double pitchVal = instance.getSubsystemAimPitch(sub.getName(), 0.0);

                    double rateYaw = 2.5;
                    if (input.left) {
                        yawVal -= rateYaw;
                    }
                    if (input.right) {
                        yawVal += rateYaw;
                    }
                    
                    double ratePitch = 1.5;
                    if (input.forward) {
                        pitchVal -= ratePitch;
                    }
                    if (input.backward) {
                        pitchVal += ratePitch;
                    }
                    pitchVal = Math.max(-90.0, Math.min(90.0, pitchVal));

                    instance.setSubsystemAimYaw(sub.getName(), yawVal);
                    instance.setSubsystemAimPitch(sub.getName(), pitchVal);

                    subYaw = yawVal;
                    subPitch = pitchVal;

                    if (instance.isWeaponCamActive(controller.getUniqueId())) {
                        Location current = controller.getLocation();
                        if (Math.abs(current.getYaw() - (float)subYaw) > 0.1 || Math.abs(current.getPitch() - (float)subPitch) > 0.1) {
                            Location newLook = current.clone();
                            newLook.setYaw((float) subYaw);
                            newLook.setPitch((float) subPitch);
                            controller.teleport(newLook);
                        }
                    }
                } else {
                    subYaw = controller.getLocation().getYaw();
                    subPitch = controller.getLocation().getPitch();

                    instance.setSubsystemAimYaw(sub.getName(), subYaw);
                    instance.setSubsystemAimPitch(sub.getName(), subPitch);
                }

                for (Display display : subDisplays) {
                    updateSubsystemTransform(display, scale, mountHeight, model, (float) rootLoc.getYaw(), (float) rootLoc.getPitch(), subYaw, subPitch, sub);
                }

                List<BdeModel.ProjectileConfig> modes = sub.getWeaponModes(this);
                if (!modes.isEmpty()) {
                    int modeIdx = instance.getSubsystemMode(controller.getUniqueId(), sub.getName());
                    BdeModel.ProjectileConfig mode = modes.get(modeIdx);
                    if (mode.isLockOn()) {
                        scanAndLockTarget(controller, instance, mode);
                    } else {
                        clearLockTarget(controller);
                    }
                }
            } else {
                for (Display display : subDisplays) {
                    updateSubsystemTransform(display, scale, mountHeight, model, (float) rootLoc.getYaw(), (float) rootLoc.getPitch(), rootLoc.getYaw(), 0.0, sub);
                }
                if (controller != null) {
                    clearLockTarget(controller);
                }
            }
        }
    }

    private void scanAndLockTarget(Player player, ModelInstance instance, BdeModel.ProjectileConfig config) {
        Location eye = player.getEyeLocation();
        org.bukkit.util.Vector dir = player.getLocation().getDirection();
        
        Entity bestTarget = null;
        double bestScore = -1.0;
        
        double range = config.getLockRange();
        double maxAngleRad = Math.toRadians(config.getLockAngle());
        double minDot = Math.cos(maxAngleRad);
        
        for (Entity entity : player.getWorld().getNearbyEntities(eye, range, range, range)) {
            if (entity.equals(player) || entity.equals(instance.getVehicleRoot()) || instance.getHitboxes().contains(entity) || entity.equals(instance.getDriverSeat())) {
                continue;
            }
            if (!(entity instanceof org.bukkit.entity.LivingEntity) && !(entity instanceof Interaction && entity.getScoreboardTags().contains("bde_root"))) {
                continue;
            }
            
            Location entityLoc = entity.getLocation().add(0, entity.getHeight() / 2, 0);
            org.bukkit.util.Vector toEntity = entityLoc.toVector().subtract(eye.toVector());
            double dist = toEntity.length();
            if (dist > range) continue;
            
            toEntity.normalize();
            double dot = dir.dot(toEntity);
            if (dot >= minDot) {
                if (player.hasLineOfSight(entity) || entity instanceof Interaction) {
                    if (dot > bestScore) {
                        bestScore = dot;
                        bestTarget = entity;
                    }
                }
            }
        }

        LockSession session = activeLockSessions.get(player.getUniqueId());
        if (bestTarget == null) {
            if (session != null) {
                setLockTeam(player, session.target, "clear");
                activeLockSessions.remove(player.getUniqueId());
            }
            return;
        }

        if (session == null || !bestTarget.equals(session.target)) {
            if (session != null) {
                setLockTeam(player, session.target, "clear");
            }
            session = new LockSession();
            session.target = bestTarget;
            session.startTime = System.currentTimeMillis();
            activeLockSessions.put(player.getUniqueId(), session);
        }

        long elapsed = System.currentTimeMillis() - session.startTime;
        double reqLockTimeMs = config.getLockTime() * 1000.0;
        if (elapsed < reqLockTimeMs) {
            if (!"soft".equals(session.lastStatus)) {
                setLockTeam(player, bestTarget, "soft");
                session.lastStatus = "soft";
            }
        } else {
            if (!"full".equals(session.lastStatus)) {
                setLockTeam(player, bestTarget, "full");
                session.lastStatus = "full";
            }
        }
    }

    private void clearLockTarget(Player player) {
        LockSession session = activeLockSessions.remove(player.getUniqueId());
        if (session != null && session.target != null && session.target.isValid()) {
            setLockTeam(player, session.target, "clear");
        }
    }

    public static void sendGlowPacket(Player player, Entity entity, boolean glowing) {
        try {
            Object craftPlayer = player.getClass().getMethod("getHandle").invoke(player);
            Object connection = craftPlayer.getClass().getField("connection").get(craftPlayer);
            
            int entityId = entity.getEntityId();
            Class<?> dataValueClass = Class.forName("net.minecraft.network.syncher.SynchedEntityData$DataValue");
            Class<?> serializersClass = Class.forName("net.minecraft.network.syncher.EntityDataSerializers");
            Object byteSerializer = serializersClass.getField("BYTE").get(null);
            
            java.lang.reflect.Constructor<?> dataValueConstructor = dataValueClass.getConstructor(int.class, Class.forName("net.minecraft.network.syncher.EntityDataSerializer"), Object.class);
            
            byte flagValue = (byte) (glowing ? 0x40 : 0x00);
            Object dataValue = dataValueConstructor.newInstance(0, byteSerializer, flagValue);
            
            List<Object> list = new ArrayList<>();
            list.add(dataValue);
            
            Class<?> packetClass = Class.forName("net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket");
            java.lang.reflect.Constructor<?> packetConstructor = packetClass.getConstructor(int.class, List.class);
            Object packet = packetConstructor.newInstance(entityId, list);
            
            java.lang.reflect.Method sendMethod = connection.getClass().getMethod("send", Class.forName("net.minecraft.network.protocol.Packet"));
            sendMethod.invoke(connection, packet);
        } catch (Exception ignored) {}
    }

    public static void setLockTeam(Player player, Entity target, String lockStatus) {
        org.bukkit.scoreboard.Scoreboard sb = player.getScoreboard();
        if (sb == Bukkit.getScoreboardManager().getMainScoreboard()) {
            sb = Bukkit.getScoreboardManager().getNewScoreboard();
            player.setScoreboard(sb);
        }
        
        for (org.bukkit.scoreboard.Team team : sb.getTeams()) {
            if (team.getName().startsWith("bde_lock_")) {
                team.removeEntry(target.getUniqueId().toString());
                team.removeEntry(target.getName());
            }
        }
        
        if ("clear".equals(lockStatus)) {
            sendGlowPacket(player, target, false);
            return;
        }
        
        String teamName = "bde_lock_" + lockStatus;
        org.bukkit.scoreboard.Team team = sb.getTeam(teamName);
        if (team == null) {
            team = sb.registerNewTeam(teamName);
            if ("soft".equals(lockStatus)) {
                team.color(net.kyori.adventure.text.format.NamedTextColor.YELLOW);
            } else if ("full".equals(lockStatus)) {
                team.color(net.kyori.adventure.text.format.NamedTextColor.GREEN);
            } else {
                team.color(net.kyori.adventure.text.format.NamedTextColor.RED);
            }
        }
        
        team.addEntry(target.getUniqueId().toString());
        team.addEntry(target.getName());
        
        sendGlowPacket(player, target, true);
    }

    private void updateSubsystemTransform(Display display, double scale, float mountHeight, BdeModel model, float yaw, float pitch, double subYaw, double subPitch, BdeModel.SubsystemConfig sub) {
        BdeModel targetModel = model;
        List<String> snbts = model.getPassengers();
        int index = getPassengerIndexFromTags(display);

        if (display.hasMetadata("bde_subsystem_model_id")) {
            String subModelId = display.getMetadata("bde_subsystem_model_id").get(0).asString();
            try {
                targetModel = loadModelSync(subModelId);
                snbts = targetModel.getPassengers();
                index = display.getMetadata("bde_subsystem_index").get(0).asInt();
            } catch (Exception e) {
                return;
            }
        }

        if (index < 0) return;
        if (snbts == null) return;
        List<String> flatSnbts = new ArrayList<>();
        for (String s : snbts) {
            List<String> split = splitObjects(s);
            for (String p : split) {
                collectAllPassengers(p, flatSnbts);
            }
        }
        if (index >= flatSnbts.size()) return;
        String snbt = flatSnbts.get(index);

        int transIdx = snbt.indexOf("transformation:[");
        if (transIdx != -1) {
            int endTrans = snbt.indexOf("]", transIdx);
            if (endTrans != -1) {
                String matrixStr = snbt.substring(transIdx + 16, endTrans);
                String[] parts = matrixStr.split(",");
                if (parts.length == 16) {
                    float[] m = new float[16];
                    for (int i = 0; i < 16; i++) {
                        String p = parts[i].trim().toLowerCase();
                        if (p.endsWith("f")) p = p.substring(0, p.length() - 1);
                        m[i] = Float.parseFloat(p);
                    }

                    Matrix4f matrix = new Matrix4f();
                    matrix.setTransposed(m);

                    Matrix4f scaledLocal = ModelTransformEngine.scaleMatrix(matrix, (float) scale);

                    Matrix4f mPass = new Matrix4f();
                    mPass.translation(0, -mountHeight, 0);

                    if (!isVersion1_20_5_OrHigher()) {
                        mPass.rotateY((float) Math.toRadians(-yaw));
                    }
                    mPass.rotateX((float) Math.toRadians(pitch));

                    if (model.getFrontYawOffset() != 0.0) {
                        mPass.rotateY((float) Math.toRadians(model.getFrontYawOffset()));
                    }

                    // Clamp relative yaw/pitch by FOV limits
                    double relativeYaw = ((subYaw - yaw) % 360.0 + 540.0) % 360.0 - 180.0;
                    if (sub.getFovMinYaw(this) != null) relativeYaw = Math.max(sub.getFovMinYaw(this), relativeYaw);
                    if (sub.getFovMaxYaw(this) != null) relativeYaw = Math.min(sub.getFovMaxYaw(this), relativeYaw);
                    double relativePitch = subPitch;
                    if (sub.getFovMinPitch(this) != null) relativePitch = Math.max(sub.getFovMinPitch(this), relativePitch);
                    if (sub.getFovMaxPitch(this) != null) relativePitch = Math.min(sub.getFovMaxPitch(this), relativePitch);

                    // Compute translation: seatOffset back to vehicle origin, then mountOffset
                    float dx = 0.0f;
                    float dy = 0.0f;
                    float dz = 0.0f;

                    if (display.hasMetadata("bde_subsystem_model_id")) {
                        float mx = 0.0f;
                        float my = 0.0f;
                        float mz = 0.0f;
                        if (sub.getMountOffset() != null && sub.getMountOffset().size() == 3) {
                            mx = (float) (sub.getMountOffset().get(0) * scale);
                            my = (float) (sub.getMountOffset().get(1) * scale);
                            mz = (float) (sub.getMountOffset().get(2) * scale);
                        }
                        float sx = 0.0f;
                        float sy = 0.0f;
                        float sz = 0.0f;
                        if (model.getSeatOffset() != null && model.getSeatOffset().size() == 3) {
                            sx = (float) (model.getSeatOffset().get(0) * scale);
                            sy = (float) (model.getSeatOffset().get(1) * scale);
                            sz = (float) (model.getSeatOffset().get(2) * scale);
                        }
                        dx = mx - sx;
                        dy = my - sy;
                        dz = mz - sz;
                    } else {
                        // Standard subsystem: translate back by seatOffset to rotate around vehicle origin (or seat)
                        if (model.getSeatOffset() != null && model.getSeatOffset().size() == 3) {
                            dx = (float) (-model.getSeatOffset().get(0) * scale);
                            dy = (float) (-model.getSeatOffset().get(1) * scale);
                            dz = (float) (-model.getSeatOffset().get(2) * scale);
                        }
                    }

                    mPass.translate(dx, dy, dz);

                    float px = 0.0f;
                    float py = 0.0f;
                    float pz = 0.0f;
                    List<Double> pivot = sub.getPivotOffset(this);
                    boolean hasPivot = pivot != null && pivot.size() == 3;
                    if (hasPivot) {
                        px = (float) (pivot.get(0) * scale);
                        py = (float) (pivot.get(1) * scale);
                        pz = (float) (pivot.get(2) * scale);
                        mPass.translate(px, py, pz);
                    }

                    mPass.rotateY((float) Math.toRadians(-relativeYaw));
                    mPass.rotateX((float) Math.toRadians(-relativePitch));

                    if (hasPivot) {
                        mPass.translate(-px, -py, -pz);
                    }

                    mPass.mul(scaledLocal);

                    Transformation transformation = ModelTransformEngine.decomposeToTransformation(mPass);
                    display.setTransformation(transformation);
                }
            }
        }
    }

    public void triggerSubsystemAction(ModelInstance instance, BdeModel.SubsystemConfig sub, Player player) {
        List<BdeModel.ProjectileConfig> modes = sub.getWeaponModes(this);
        if (modes.isEmpty()) return;

        int modeIdx = instance.getSubsystemMode(player.getUniqueId(), sub.getName());
        BdeModel.ProjectileConfig config = modes.get(modeIdx);

        UUID pId = player.getUniqueId();
        Map<String, Long> playerCooldowns = subsystemCooldowns.computeIfAbsent(pId, k -> new HashMap<>());
        long now = System.currentTimeMillis();
        long lastUse = playerCooldowns.getOrDefault(sub.getName() + "_" + config.getName(), 0L);
        if (now - lastUse < (long) (config.getCooldown() * 1000.0)) {
            return;
        }
        playerCooldowns.put(sub.getName() + "_" + config.getName(), now);

        try {
            org.bukkit.Sound sound = org.bukkit.Sound.valueOf(config.getLaunchSound().toUpperCase());
            player.getWorld().playSound(player.getLocation(), sound, config.getLaunchVolume(), config.getLaunchPitch());
        } catch (IllegalArgumentException ignored) {}

        Location launchLoc = player.getEyeLocation();
        Entity vehicleRoot = instance.getVehicleRoot();
        double scale = instance.getScale();
        
        org.bukkit.util.Vector velocity;
        List<Double> launchOffset = sub.getLaunchOffset(this);
        if (launchOffset != null && launchOffset.size() == 3 && vehicleRoot != null) {
            double subYaw;
            double subPitch;
            if (instance.isSubsystemWasdAiming(sub.getName())) {
                subYaw = instance.getSubsystemAimYaw(sub.getName(), vehicleRoot.getLocation().getYaw());
                subPitch = instance.getSubsystemAimPitch(sub.getName(), 0.0);
            } else {
                subYaw = player.getLocation().getYaw();
                subPitch = player.getLocation().getPitch();
            }
            double relativeYaw = ((subYaw - vehicleRoot.getLocation().getYaw()) % 360.0 + 540.0) % 360.0 - 180.0;
            if (sub.getFovMinYaw(this) != null) relativeYaw = Math.max(sub.getFovMinYaw(this), relativeYaw);
            if (sub.getFovMaxYaw(this) != null) relativeYaw = Math.min(sub.getFovMaxYaw(this), relativeYaw);
            double relativePitch = subPitch;
            if (sub.getFovMinPitch(this) != null) relativePitch = Math.max(sub.getFovMinPitch(this), relativePitch);
            if (sub.getFovMaxPitch(this) != null) relativePitch = Math.min(sub.getFovMaxPitch(this), relativePitch);

            List<Double> mountOffset = sub.getMountOffset();
            if (mountOffset == null || mountOffset.isEmpty()) {
                mountOffset = getSubsystemOffset(instance.getModel(), sub.getDisplayTag(this));
            }

            launchLoc = ModelTransformEngine.getSubsystemComponentPosition(
                vehicleRoot.getLocation(),
                mountOffset,
                launchOffset,
                instance.getModel().getSeatOffset(),
                scale,
                instance.getModel().getFrontYawOffset(),
                vehicleRoot.getLocation().getYaw(),
                vehicleRoot.getLocation().getPitch(),
                relativeYaw,
                relativePitch,
                sub.getPivotOffset(this)
            );

            double finalYaw = vehicleRoot.getLocation().getYaw() + relativeYaw;
            double finalPitch = relativePitch;
            launchLoc.setYaw((float) finalYaw);
            launchLoc.setPitch((float) finalPitch);
            velocity = launchLoc.getDirection().multiply(config.getSpeed());
        } else {
            List<Display> subDisplays = new ArrayList<>();
            int subIdx = instance.getModel().getVehicle().getSubsystems().indexOf(sub);
            for (Display display : instance.getPassengers()) {
                String subDisplayTag = sub.getDisplayTag(this);
                if (subDisplayTag != null && !subDisplayTag.isEmpty() && display.getScoreboardTags().contains(subDisplayTag)) {
                    subDisplays.add(display);
                } else if (display.hasMetadata("bde_subsystem_parent_index") &&
                           display.getMetadata("bde_subsystem_parent_index").get(0).asInt() == subIdx) {
                    subDisplays.add(display);
                }
            }
            if (!subDisplays.isEmpty()) {
                launchLoc = subDisplays.get(0).getLocation().clone();
                org.bukkit.util.Vector dir = player.getLocation().getDirection();
                launchLoc.add(dir.clone().multiply(0.8));
            } else {
                org.bukkit.util.Vector dir = player.getLocation().getDirection();
                launchLoc.add(dir.clone().multiply(0.8));
            }
            velocity = player.getLocation().getDirection().multiply(config.getSpeed());
            launchLoc.setDirection(velocity);
        }

        Entity target = null;
        boolean fullLock = false;
        Location targetCoord = null;
        if (config.isLockOn()) {
            LockSession session = activeLockSessions.get(player.getUniqueId());
            if (session != null && session.target != null && session.target.isValid()) {
                target = session.target;
                long elapsed = System.currentTimeMillis() - session.startTime;
                if (elapsed >= config.getLockTime() * 1000.0) {
                    fullLock = true;
                } else {
                    targetCoord = target.getLocation().add(0, target.getHeight() / 2, 0);
                }
            }
        }

        // Ray style weapon (laser)
        if ("laser".equalsIgnoreCase(config.getOnHit())) {
            // Draw a laser beam line and damage entities
            org.bukkit.World world = launchLoc.getWorld();
            org.bukkit.util.Vector dir = launchLoc.getDirection();
            double maxDist = config.getLockRange() > 0 ? config.getLockRange() : 30.0;
            
            for (double d = 0.5; d < maxDist; d += 0.5) {
                Location particleLoc = launchLoc.clone().add(dir.clone().multiply(d));
                if (config.getFlyParticle() != null && !config.getFlyParticle().isEmpty()) {
                    try {
                        org.bukkit.Particle p = org.bukkit.Particle.valueOf(config.getFlyParticle().toUpperCase());
                        world.spawnParticle(p, particleLoc, config.getFlyParticleCount(), 0, 0, 0, 0);
                    } catch (IllegalArgumentException ignored) {}
                }

                java.util.Collection<org.bukkit.entity.Entity> entities = world.getNearbyEntities(particleLoc, 0.4, 0.4, 0.4);
                boolean hit = false;
                for (org.bukkit.entity.Entity entity : entities) {
                    if (entity.equals(player) || entity.equals(instance.getVehicleRoot()) || instance.getPassengerSeats().contains(entity) || instance.getHitboxes().contains(entity) || entity.equals(instance.getDriverSeat())) {
                        continue;
                    }
                    
                    if (entity instanceof org.bukkit.entity.LivingEntity) {
                        ((org.bukkit.entity.LivingEntity) entity).damage(config.getDamage(), player);
                        hit = true;
                    } else if (entity instanceof Interaction && entity.getScoreboardTags().contains("bde_root")) {
                        ModelInstance hitInstance = getInstanceByRoot(entity);
                        if (hitInstance != null) {
                            double hp = hitInstance.getCurrentHp();
                            hp = Math.max(0.0, hp - config.getDamage());
                            hitInstance.setCurrentHp(hp);
                            Location hitLoc = entity.getLocation();
                            hitLoc.getWorld().playSound(hitLoc, org.bukkit.Sound.ENTITY_ITEM_BREAK, 1.0f, 0.8f);
                            try {
                                hitLoc.getWorld().spawnParticle(org.bukkit.Particle.valueOf("DAMAGE_INDICATOR"), hitLoc, 5, 0.2, 0.2, 0.2, 0.1);
                            } catch (Exception ignored) {}
                            if (hp <= 0.0) {
                                hitLoc.getWorld().createExplosion(hitLoc, 2.0f, false, false);
                                removeInstance(hitInstance.getId());
                            }
                            hit = true;
                        }
                    }
                }
                if (hit) break;
            }
            return;
        }

        if (config.getBdeModelId() != null && !config.getBdeModelId().isEmpty()) {
            org.bukkit.entity.Snowball base = launchLoc.getWorld().spawn(launchLoc, org.bukkit.entity.Snowball.class);
            base.setGravity(false);
            base.setSilent(true);
            base.setShooter(player);
            base.setVelocity(velocity);
            base.setMetadata("bde_projectile", new FixedMetadataValue(plugin, true));

            org.bukkit.entity.BlockDisplay display = launchLoc.getWorld().spawn(launchLoc, org.bukkit.entity.BlockDisplay.class);
            display.setBlock(Bukkit.createBlockData("minecraft:stone"));
            display.setTeleportDuration(1);
            display.setInterpolationDuration(1);
            display.setInterpolationDelay(0);

            try {
                BdeModel projModel = loadModelSync(config.getBdeModelId());
                if (projModel != null && projModel.getPassengers() != null && !projModel.getPassengers().isEmpty()) {
                    String firstPassenger = splitObjects(projModel.getPassengers().get(0)).get(0);
                    applyBlockDisplayData(display, firstPassenger, 1.0);
                    applyTransformation(display, firstPassenger, 1.0, 0f, projModel, false, (float) launchLoc.getYaw(), (float) launchLoc.getPitch(), false, config.getBasePoint(), config.getDirectionVector());
                }
            } catch (Exception ignored) {}

            CustomProjectile customProj = new CustomProjectile(base, display, player, config, velocity, target, fullLock, targetCoord);
            activeProjectiles.add(customProj);
        } else {
            org.bukkit.entity.Snowball base = launchLoc.getWorld().spawn(launchLoc, org.bukkit.entity.Snowball.class);
            base.setGravity(config.isHasGravity());
            base.setShooter(player);
            base.setVelocity(velocity);
            base.setMetadata("bde_projectile", new FixedMetadataValue(plugin, true));

            CustomProjectile customProj = new CustomProjectile(base, null, player, config, velocity, target, fullLock, targetCoord);
            activeProjectiles.add(customProj);
        }
    }

    public void giveSubsystemControllerItem(Player player, ModelInstance instance, int passengerIndex) {
        BdeModel.VehicleConfig vehicle = instance.getModel().getVehicle();
        if (vehicle == null || vehicle.getSubsystems() == null) return;
        
        BdeModel.SubsystemConfig sub = null;
        for (BdeModel.SubsystemConfig s : vehicle.getSubsystems()) {
            if (s.getControllerSeatIndex() == passengerIndex) {
                sub = s;
                break;
            }
        }
        if (sub == null) return;
        
        // Save current item
        int slot = player.getInventory().getHeldItemSlot();
        org.bukkit.inventory.ItemStack current = player.getInventory().getItem(slot);
        if (current != null && current.getType() != org.bukkit.Material.AIR) {
            originalHotbarItems.put(player.getUniqueId(), current.clone());
        } else {
            originalHotbarItems.put(player.getUniqueId(), new org.bukkit.inventory.ItemStack(org.bukkit.Material.AIR));
        }
        
        // Give control item
        org.bukkit.inventory.ItemStack controllerItem = createControllerItem(sub, instance, player.getUniqueId());
        player.getInventory().setItem(slot, controllerItem);
    }
    
    public org.bukkit.inventory.ItemStack createControllerItem(BdeModel.SubsystemConfig sub, ModelInstance instance, UUID playerId) {
        org.bukkit.inventory.ItemStack item = new org.bukkit.inventory.ItemStack(org.bukkit.Material.BLAZE_ROD);
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§e§lSubsystem Operator Controls");
            List<String> lore = new ArrayList<>();
            String weaponName = "None";
            List<BdeModel.ProjectileConfig> weaponModes = sub.getWeaponModes(this);
            if (weaponModes != null && !weaponModes.isEmpty()) {
                int modeIdx = instance.getSubsystemMode(playerId, sub.getName());
                weaponName = weaponModes.get(modeIdx).getName();
            }
            lore.add("§7Current Weapon: §f" + weaponName);
            lore.add("§7------------------------");
            lore.add("§7Left Click: Fire main gun");
            lore.add("§7Scroll Wheel: Cycle weapons");
            lore.add("§7Q Key: Toggle Camera/WASD Aiming");
            lore.add("§7F Key: Toggle Weapon-Cam View");
            lore.add("§7Shift: Dismount/Exit seat");
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }
    
    public void restorePlayerItem(Player player) {
        org.bukkit.inventory.ItemStack original = originalHotbarItems.remove(player.getUniqueId());
        if (original != null) {
            int slot = player.getInventory().getHeldItemSlot();
            player.getInventory().setItem(slot, original.getType() == org.bukkit.Material.AIR ? null : original);
        }
    }

    public org.bukkit.inventory.ItemStack getOriginalHotbarItem(UUID playerId) {
        return originalHotbarItems.get(playerId);
    }
    
    public void clearOriginalHotbarItem(UUID playerId) {
        originalHotbarItems.remove(playerId);
    }

    public void startPlacementSession(Player player, UUID instanceId, String vehicleModelId, int subIdx) {
        BdeModel model;
        try {
            model = loadModelSync(vehicleModelId);
        } catch (Exception e) {
            player.sendMessage("§cFailed to load vehicle model config: " + e.getMessage());
            return;
        }

        BdeModel.SubsystemConfig sub = model.getVehicle().getSubsystems().get(subIdx);
        if (sub.getTurretId() == null || sub.getTurretId().isEmpty()) {
            player.sendMessage("§cYou must link a turret template to this subsystem slot before entering placement mode.");
            return;
        }

        PlacementSession session = new PlacementSession(instanceId, subIdx);
        session.vehicleModelId = vehicleModelId;

        ModelInstance instance = instanceId != null ? activeInstances.get(instanceId) : null;
        if (instance != null) {
            session.vehicleOriginalLoc = instance.getLocation().clone();
            session.vehicleOriginalScale = instance.getScale();
            // Temporarily despawn vehicle
            removeInstance(instanceId);
        } else {
            Location loc = player.getLocation().clone();
            loc.setPitch(0.0f);
            session.vehicleOriginalLoc = loc;
            session.vehicleOriginalScale = 1.0;
        }

        // Subsystem floating in air setup: 4 blocks in front of the player, aligned to world grid (yaw=0, pitch=0)
        Location origin = player.getEyeLocation().add(player.getLocation().getDirection().multiply(4.0));
        origin.setYaw(0.0f);
        origin.setPitch(0.0f);
        session.subsystemOriginWorldLoc = origin;

        // Spawn subsystem BDE model floating in the air for Stage 1 & 2
        String subModelId = sub.getBdeModelId(this);
        if (subModelId != null && !subModelId.isEmpty()) {
            try {
                BdeModel subModel = loadModelSync(subModelId);
                ModelInstance subInst = spawnModel(subModel, origin, session.vehicleOriginalScale);
                session.tempSubsystemInstanceId = subInst.getId();
            } catch (Exception e) {
                player.sendMessage("§cFailed to spawn subsystem preview model: " + subModelId + ": " + e.getMessage());
            }
        }

        placementSessions.put(player.getUniqueId(), session);
        player.closeInventory();
        
        player.sendMessage("§eInteractive Placement Mode started!");
        player.sendMessage("§fStage 1: §b§lPivot Point Offset §7- Move crosshair/scroll to set the rotation center on the floating subsystem.");
        player.sendMessage("§7Scroll mouse wheel to adjust distance from your eyes.");
        player.sendMessage("§7Left Click: Save Pivot | Shift+Left Click: Cancel");
    }

    public void startTurretPlacementSession(Player player, String turretId) {
        TurretConfig turret = getTurretTemplate(turretId);
        if (turret == null) {
            player.sendMessage("§cTurret template not found: " + turretId);
            return;
        }

        PlacementSession session = new PlacementSession(null, -1);
        session.standaloneTurret = true;
        session.turretId = turretId;

        // Subsystem floating in air setup: 4 blocks in front of the player, aligned to world grid (yaw=0, pitch=0)
        Location origin = player.getEyeLocation().add(player.getLocation().getDirection().multiply(4.0));
        origin.setYaw(0.0f);
        origin.setPitch(0.0f);
        session.subsystemOriginWorldLoc = origin;

        // Spawn subsystem BDE model floating in the air for Stage 1 & 2
        String subModelId = turret.getBdeModelId();
        if (subModelId != null && !subModelId.isEmpty()) {
            try {
                BdeModel subModel = loadModelSync(subModelId);
                ModelInstance subInst = spawnModel(subModel, origin, 1.0);
                session.tempSubsystemInstanceId = subInst.getId();
            } catch (Exception e) {
                player.sendMessage("§cFailed to spawn turret preview model: " + subModelId + ": " + e.getMessage());
            }
        }

        placementSessions.put(player.getUniqueId(), session);
        player.closeInventory();
        
        player.sendMessage("§eInteractive Turret Placement Mode started!");
        player.sendMessage("§fStage 1: §b§lPivot Point Offset §7- Move crosshair/scroll to set the rotation center on the floating turret.");
        player.sendMessage("§7Scroll mouse wheel to adjust distance from your eyes.");
        player.sendMessage("§7Left Click: Save Pivot | Shift+Left Click: Cancel");
    }

    public boolean isEditingPlacement(Player player) {
        return placementSessions.containsKey(player.getUniqueId());
    }

    public void cancelPlacementSession(Player player) {
        PlacementSession session = placementSessions.remove(player.getUniqueId());
        if (session != null) {
            if (session.standaloneTurret) {
                if (session.tempSubsystemInstanceId != null) {
                    removeInstance(session.tempSubsystemInstanceId);
                }
                Bukkit.getScheduler().runTask(plugin, () -> {
                    plugin.getBdeGuiManager().openTurretEditor(player, session.turretId);
                });
                player.sendMessage("§cPlacement mode cancelled.");
                return;
            }
            if (session.tempSubsystemInstanceId != null) {
                removeInstance(session.tempSubsystemInstanceId);
            }
            if (session.vehicleOriginalLoc != null && session.vehicleModelId != null) {
                try {
                    BdeModel m = loadModelSync(session.vehicleModelId);
                    ModelInstance restored = spawnModel(m, session.vehicleOriginalLoc, session.vehicleOriginalScale);
                    plugin.getBdeGuiManager().selectModel(player, restored.getId());
                    plugin.getBdeGuiManager().openSubsystemDetailMenu(player, restored, m, m.getProjectId(), session.subsystemIndex);
                } catch (Exception ignored) {}
            } else {
                ModelInstance inst = activeInstances.get(session.instanceId);
                if (inst != null) {
                    try {
                        BdeModel m = loadModelSync(inst.getModel().getProjectId());
                        plugin.getBdeGuiManager().openSubsystemDetailMenu(player, inst, m, m.getProjectId(), session.subsystemIndex);
                    } catch (Exception ignored) {}
                }
            }
            player.sendMessage("§cPlacement mode cancelled.");
        }
    }

    public void advancePlacementStep(Player player) {
        PlacementSession session = placementSessions.get(player.getUniqueId());
        if (session == null) return;

        if (session.standaloneTurret) {
            TurretConfig turret = getTurretTemplate(session.turretId);
            if (turret == null) {
                placementSessions.remove(player.getUniqueId());
                player.sendMessage("§cFailed to load turret config.");
                return;
            }

            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.0f);

            if (session.step == PlacementStep.PIVOT_OFFSET) {
                session.step = PlacementStep.LAUNCH_POINT;
                player.sendMessage("§fStage 2: §6§lMuzzle Point §7- Position the projectile firing origin on the floating turret.");
                player.sendMessage("§7Left Click: Save Muzzle | Shift+Left Click: Cancel");
            } else if (session.step == PlacementStep.LAUNCH_POINT) {
                session.step = PlacementStep.CAMERA_OFFSET;
                player.sendMessage("§fStage 3: §a§lSpectator Camera §7- Position the first-person spectator view position.");
                player.sendMessage("§7Left Click: Save Camera | Shift+Left Click: Cancel");
            } else if (session.step == PlacementStep.CAMERA_OFFSET) {
                placementSessions.remove(player.getUniqueId());
                if (session.tempSubsystemInstanceId != null) {
                    removeInstance(session.tempSubsystemInstanceId);
                }
                saveTurretConfig(turret);
                player.sendMessage("§a§lPlacement Complete! Turret pivot, launch, and camera offsets configured successfully.");
                player.playSound(player.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
                
                Bukkit.getScheduler().runTask(plugin, () -> {
                    plugin.getBdeGuiManager().openTurretEditor(player, session.turretId);
                });
            }
            return;
        }

        BdeModel model;
        try {
            model = loadModelSync(session.vehicleModelId);
        } catch (Exception e) {
            placementSessions.remove(player.getUniqueId());
            player.sendMessage("§cFailed to load vehicle model: " + e.getMessage());
            return;
        }

        BdeModel.SubsystemConfig sub = model.getVehicle().getSubsystems().get(session.subsystemIndex);

        player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.0f);

        if (session.step == PlacementStep.PIVOT_OFFSET) {
            // Stage 1 (Pivot Offset) complete ➡️ Stage 2 (Muzzle/Launch Point)
            session.step = PlacementStep.LAUNCH_POINT;
            player.sendMessage("§fStage 2: §6§lMuzzle Point §7- Position the projectile firing origin on the floating subsystem.");
            player.sendMessage("§7Left Click: Save Muzzle | Shift+Left Click: Cancel");

        } else if (session.step == PlacementStep.LAUNCH_POINT) {
            // Stage 2 (Muzzle Point) complete ➡️ Stage 3 (Mount Point)
            if (session.tempSubsystemInstanceId != null) {
                removeInstance(session.tempSubsystemInstanceId);
                session.tempSubsystemInstanceId = null;
            }

            // Spawn the vehicle back
            try {
                ModelInstance vehicleInst = spawnModel(model, session.vehicleOriginalLoc, session.vehicleOriginalScale);
                session.instanceId = vehicleInst.getId();
                plugin.getBdeGuiManager().selectModel(player, vehicleInst.getId());
            } catch (Exception e) {
                player.sendMessage("§cFailed to spawn vehicle model: " + e.getMessage());
                placementSessions.remove(player.getUniqueId());
                return;
            }

            session.step = PlacementStep.MOUNT_POINT;
            player.sendMessage("§fStage 3: §a§lMount Point §7- Look at the vehicle chassis to place the turret base (snaps at pivot point).");
            player.sendMessage("§7Left Click: Save Mount | Shift+Left Click: Cancel");

        } else if (session.step == PlacementStep.MOUNT_POINT) {
            // Stage 3 (Mount Point) complete ➡️ Stage 4 (Spectator Camera)
            session.step = PlacementStep.CAMERA_OFFSET;
            player.sendMessage("§fStage 4: §a§lSpectator Camera §7- Position the first-person spectator view position.");
            player.sendMessage("§7Left Click: Save Camera | Shift+Left Click: Cancel");

        } else if (session.step == PlacementStep.CAMERA_OFFSET) {
            // Placement completed successfully!
            placementSessions.remove(player.getUniqueId());

            if (sub.getTurretId() != null) {
                TurretConfig turret = getTurretTemplate(sub.getTurretId());
                if (turret != null) {
                    saveTurretConfig(turret);
                }
            }

            if (model.getLocalFilePath() != null && model.isVehicleLibrary()) {
                saveModelConfig(model);
            }

            ModelInstance vehicleInstance = activeInstances.get(session.instanceId);
            Location currentLoc = vehicleInstance != null ? vehicleInstance.getLocation() : session.vehicleOriginalLoc;
            double currentScale = vehicleInstance != null ? vehicleInstance.getScale() : session.vehicleOriginalScale;

            if (vehicleInstance != null) {
                removeInstance(vehicleInstance.getId());
            }

            ModelInstance newInstance = spawnModel(model, currentLoc, currentScale);
            plugin.getBdeGuiManager().selectModel(player, newInstance.getId());

            player.sendMessage("§a§lPlacement Complete! Subsystem pivot, mount, launch, and camera offsets configured successfully.");
            player.playSound(player.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);

            try {
                BdeModel updated = loadModelSync(model.getProjectId());
                plugin.getBdeGuiManager().openSubsystemDetailMenu(player, newInstance, updated, model.getProjectId(), session.subsystemIndex);
            } catch (Exception ignored) {}
        }
    }

    private void runPlacementTickTask() {
        new org.bukkit.scheduler.BukkitRunnable() {
            @Override
            public void run() {
                for (Map.Entry<UUID, PlacementSession> entry : placementSessions.entrySet()) {
                    Player player = Bukkit.getPlayer(entry.getKey());
                    if (player == null || !player.isOnline()) {
                        placementSessions.remove(entry.getKey());
                        continue;
                    }

                    PlacementSession session = entry.getValue();
                    BdeModel model = null;
                    BdeModel.SubsystemConfig sub = null;
                    TurretConfig turret = null;

                    if (!session.standaloneTurret) {
                        try {
                            model = loadModelSync(session.vehicleModelId);
                            sub = model.getVehicle().getSubsystems().get(session.subsystemIndex);
                            turret = sub.getTurretId() != null ? getTurretTemplate(sub.getTurretId()) : null;
                        } catch (Exception e) {
                            placementSessions.remove(entry.getKey());
                            continue;
                        }
                    } else {
                        turret = getTurretTemplate(session.turretId);
                        if (turret == null) {
                            placementSessions.remove(entry.getKey());
                            continue;
                        }
                    }

                    Location rootLoc = null;
                    double scale = session.vehicleOriginalScale;

                    if (!session.standaloneTurret && (session.step == PlacementStep.MOUNT_POINT || session.step == PlacementStep.CAMERA_OFFSET)) {
                        ModelInstance instance = activeInstances.get(session.instanceId);
                        if (instance == null || instance.getVehicleRoot() == null) {
                            placementSessions.remove(entry.getKey());
                            player.sendMessage("§cVehicle instance lost. Placement mode cancelled.");
                            continue;
                        }
                        rootLoc = instance.getVehicleRoot().getLocation();
                        scale = instance.getScale();
                    } else {
                        ModelInstance tempSub = session.tempSubsystemInstanceId != null ? activeInstances.get(session.tempSubsystemInstanceId) : null;
                        if (tempSub != null && tempSub.getVehicleRoot() != null) {
                            BdeModel subModel = tempSub.getModel();
                            BoundingBox subBox = calculateModelBounds(subModel, scale);
                            float subMountHeight = getMountHeight(tempSub.getVehicleRoot(), Math.max(0.1f, subBox.maxY));
                            rootLoc = session.subsystemOriginWorldLoc.clone().add(0, subMountHeight, 0);
                        } else {
                            rootLoc = session.subsystemOriginWorldLoc;
                        }
                    }

                    if (rootLoc == null) continue;

                    Location eye = player.getEyeLocation();
                    org.bukkit.util.Vector dir = player.getLocation().getDirection();
                    Location hitLoc = eye.clone().add(dir.clone().multiply(session.distance));

                    double dx = hitLoc.getX() - rootLoc.getX();
                    double dy = hitLoc.getY() - rootLoc.getY();
                    double dz = hitLoc.getZ() - rootLoc.getZ();

                    double rx = dx;
                    double ry = dy;
                    double rz = dz;

                    if (!session.standaloneTurret && (session.step == PlacementStep.MOUNT_POINT || session.step == PlacementStep.CAMERA_OFFSET)) {
                        double revYawRad = Math.toRadians(-rootLoc.getYaw());
                        double cos = Math.cos(revYawRad);
                        double sin = Math.sin(revYawRad);
                        rx = dx * cos - dz * sin;
                        ry = dy;
                        rz = dx * sin + dz * cos;

                        double frontYawOffset = model.getFrontYawOffset();
                        if (frontYawOffset != 0.0) {
                            double radFront = Math.toRadians(-frontYawOffset);
                            double cosF = Math.cos(radFront);
                            double sinF = Math.sin(radFront);
                            double tempX = rx * cosF - rz * sinF;
                            rz = rx * sinF + rz * cosF;
                            rx = tempX;
                        }
                    }

                    rx /= scale;
                    ry /= scale;
                    rz /= scale;

                    double snap = 0.05;
                    rx = Math.round(rx / snap) * snap;
                    ry = Math.round(ry / snap) * snap;
                    rz = Math.round(rz / snap) * snap;

                    // Store un-locked snapped values for lock command capture
                    session.lastRx = rx;
                    session.lastRy = ry;
                    session.lastRz = rz;

                    // Apply axis locking
                    if (session.lockedAxes.contains("x")) {
                        rx = session.lockedValues.getOrDefault("x", rx);
                    }
                    if (session.lockedAxes.contains("y")) {
                        ry = session.lockedValues.getOrDefault("y", ry);
                    }
                    if (session.lockedAxes.contains("z")) {
                        rz = session.lockedValues.getOrDefault("z", rz);
                    }

                    String lockedStr = session.lockedAxes.isEmpty() ? "" : " §7[Locked: " + session.lockedAxes + "]";

                    if (session.step == PlacementStep.PIVOT_OFFSET) {
                        double px = rx;
                        double py = ry;
                        double pz = rz;

                        if (!session.standaloneTurret) {
                            List<Double> mount = sub.getMountOffset();
                            if (mount == null || mount.size() != 3) {
                                mount = java.util.Arrays.asList(0.0, 0.0, 0.0);
                                sub.setMountOffset(mount);
                            }
                            double mx = mount.get(0);
                            double my = mount.get(1);
                            double mz = mount.get(2);

                            px = rx - mx;
                            py = ry - my;
                            pz = rz - mz;
                        }

                        px = Math.round(px / snap) * snap;
                        py = Math.round(py / snap) * snap;
                        pz = Math.round(pz / snap) * snap;

                        List<Double> pivot = java.util.Arrays.asList(px, py, pz);
                        if (turret != null) {
                            turret.setPivotOffset(pivot);
                        }

                        player.sendActionBar(Component.text("§b§lPivot Offset: §f" + String.format("%.2f, %.2f, %.2f", px, py, pz) + lockedStr + " §7| §aLeft Click: Save §7| §cShift+Left Click: Cancel"));

                        Location pMount = rootLoc.clone();
                        if (!session.standaloneTurret) {
                            pMount = ModelTransformEngine.getSubsystemComponentPosition(
                                rootLoc,
                                sub.getMountOffset(),
                                java.util.Arrays.asList(0.0, 0.0, 0.0),
                                null,
                                scale,
                                0.0,
                                0.0,
                                0.0,
                                0.0,
                                0.0,
                                null
                            );
                        }
                        Location pPivot = ModelTransformEngine.getSubsystemComponentPosition(
                            rootLoc,
                            session.standaloneTurret ? java.util.Arrays.asList(0.0, 0.0, 0.0) : sub.getMountOffset(),
                            pivot,
                            null,
                            scale,
                            0.0,
                            0.0,
                            0.0,
                            0.0,
                            0.0,
                            null
                        );

                        try {
                            drawParticleLine(player, pMount, pPivot, org.bukkit.Particle.valueOf("HAPPY_VILLAGER"));
                        } catch (Exception ignored) {}

                    } else if (session.step == PlacementStep.LAUNCH_POINT) {
                        List<Double> pivot = turret != null ? turret.getPivotOffset() : java.util.Arrays.asList(0.0, 0.0, 0.0);
                        double lx = rx;
                        double ly = ry;
                        double lz = rz;

                        if (!session.standaloneTurret) {
                            List<Double> mount = sub.getMountOffset();
                            double mx = mount != null && mount.size() == 3 ? mount.get(0) : 0.0;
                            double my = mount != null && mount.size() == 3 ? mount.get(1) : 0.0;
                            double mz = mount != null && mount.size() == 3 ? mount.get(2) : 0.0;

                            lx = rx - mx;
                            ly = ry - my;
                            lz = rz - mz;
                        }

                        lx = Math.round(lx / snap) * snap;
                        ly = Math.round(ly / snap) * snap;
                        lz = Math.round(lz / snap) * snap;

                        List<Double> launch = java.util.Arrays.asList(lx, ly, lz);
                        if (turret != null) {
                            turret.setLaunchOffset(launch);
                        }

                        player.sendActionBar(Component.text("§6§lMuzzle Point: §f" + String.format("%.2f, %.2f, %.2f", lx, ly, lz) + lockedStr + " §7| §aLeft Click: Save §7| §cShift+Left Click: Cancel"));

                        Location pPivot = ModelTransformEngine.getSubsystemComponentPosition(
                            rootLoc,
                            session.standaloneTurret ? java.util.Arrays.asList(0.0, 0.0, 0.0) : sub.getMountOffset(),
                            pivot != null ? pivot : java.util.Arrays.asList(0.0, 0.0, 0.0),
                            null,
                            scale,
                            0.0,
                            0.0,
                            0.0,
                            0.0,
                            0.0,
                            pivot
                        );
                        Location pMuzzle = ModelTransformEngine.getSubsystemComponentPosition(
                            rootLoc,
                            session.standaloneTurret ? java.util.Arrays.asList(0.0, 0.0, 0.0) : sub.getMountOffset(),
                            launch,
                            null,
                            scale,
                            0.0,
                            0.0,
                            0.0,
                            0.0,
                            0.0,
                            pivot
                        );

                        try {
                            drawParticleLine(player, pPivot, pMuzzle, org.bukkit.Particle.valueOf("FLAME"));
                        } catch (Exception ignored) {}

                    } else if (!session.standaloneTurret && session.step == PlacementStep.MOUNT_POINT) {
                        List<Double> pivot = sub.getPivotOffset(ModelManager.this);
                        double px = pivot != null && pivot.size() == 3 ? pivot.get(0) : 0.0;
                        double py = pivot != null && pivot.size() == 3 ? pivot.get(1) : 0.0;
                        double pz = pivot != null && pivot.size() == 3 ? pivot.get(2) : 0.0;

                        double mx = rx - px;
                        double my = ry - py;
                        double mz = rz - pz;

                        mx = Math.round(mx / snap) * snap;
                        my = Math.round(my / snap) * snap;
                        mz = Math.round(mz / snap) * snap;

                        List<Double> mount = java.util.Arrays.asList(mx, my, mz);
                        sub.setMountOffset(mount);

                        player.sendActionBar(Component.text("§e§lMount Point: §f" + String.format("%.2f, %.2f, %.2f", mx, my, mz) + lockedStr + " §7| §aLeft Click: Save §7| §cShift+Left Click: Cancel"));

                        Location pPivot = ModelTransformEngine.getSubsystemComponentPosition(
                            rootLoc,
                            mount,
                            pivot,
                            model.getSeatOffset(),
                            scale,
                            model.getFrontYawOffset(),
                            rootLoc.getYaw(),
                            rootLoc.getPitch(),
                            0.0,
                            0.0,
                            pivot
                        );
                        try {
                            player.spawnParticle(org.bukkit.Particle.valueOf("HAPPY_VILLAGER"), pPivot, 2, 0.0, 0.0, 0.0, 0.0);
                        } catch (Exception ignored) {}

                    } else if (session.step == PlacementStep.CAMERA_OFFSET) {
                        List<Double> mount = session.standaloneTurret ? java.util.Arrays.asList(0.0, 0.0, 0.0) : sub.getMountOffset();
                        List<Double> pivot = turret != null ? turret.getPivotOffset() : java.util.Arrays.asList(0.0, 0.0, 0.0);
                        double mx = mount != null && mount.size() == 3 ? mount.get(0) : 0.0;
                        double my = mount != null && mount.size() == 3 ? mount.get(1) : 0.0;
                        double mz = mount != null && mount.size() == 3 ? mount.get(2) : 0.0;

                        double cx = rx - mx;
                        double cy = ry - my;
                        double cz = rz - mz;

                        cx = Math.round(cx / snap) * snap;
                        cy = Math.round(cy / snap) * snap;
                        cz = Math.round(cz / snap) * snap;

                        List<Double> camera = java.util.Arrays.asList(cx, cy, cz);
                        if (turret != null) {
                            turret.setCameraOffset(camera);
                        }

                        player.sendActionBar(Component.text("§a§lCamera View: §f" + String.format("%.2f, %.2f, %.2f", cx, cy, cz) + lockedStr + " §7| §aLeft Click: Save §7| §cShift+Left Click: Cancel"));

                        Location pMount = rootLoc.clone();
                        if (!session.standaloneTurret) {
                            pMount = ModelTransformEngine.getSubsystemComponentPosition(
                                rootLoc,
                                mount,
                                java.util.Arrays.asList(0.0, 0.0, 0.0),
                                model.getSeatOffset(),
                                scale,
                                model.getFrontYawOffset(),
                                rootLoc.getYaw(),
                                rootLoc.getPitch(),
                                0.0,
                                0.0,
                                pivot
                            );
                        }
                        Location pCam = ModelTransformEngine.getSubsystemComponentPosition(
                            rootLoc,
                            mount,
                            camera,
                            session.standaloneTurret ? null : model.getSeatOffset(),
                            scale,
                            session.standaloneTurret ? 0.0 : model.getFrontYawOffset(),
                            rootLoc.getYaw(),
                            session.standaloneTurret ? 0.0 : rootLoc.getPitch(),
                            0.0,
                            0.0,
                            pivot
                        );

                        try {
                            drawParticleLine(player, pMount, pCam, org.bukkit.Particle.valueOf("HAPPY_VILLAGER"));
                        } catch (Exception ignored) {}
                    }

                    if (!session.standaloneTurret && (session.step == PlacementStep.MOUNT_POINT || session.step == PlacementStep.CAMERA_OFFSET)) {
                        ModelInstance instance = activeInstances.get(session.instanceId);
                        if (instance != null) {
                            List<Display> subDisplays = new ArrayList<>();
                            int subIdx = model.getVehicle().getSubsystems().indexOf(sub);
                            for (Display display : instance.getPassengers()) {
                                String subDisplayTag = sub.getDisplayTag(ModelManager.this);
                                if (subDisplayTag != null && !subDisplayTag.isEmpty() && display.getScoreboardTags().contains(subDisplayTag)) {
                                    subDisplays.add(display);
                                } else if (display.hasMetadata("bde_subsystem_parent_index") &&
                                           display.getMetadata("bde_subsystem_parent_index").get(0).asInt() == subIdx) {
                                    subDisplays.add(display);
                                }
                            }
                            float mHeight = getMountHeight(instance.getVehicleRoot(), Math.max(0.1f, calculateModelBounds(model, scale).maxY));
                            for (Display display : subDisplays) {
                                updateSubsystemTransform(display, scale, mHeight, model, (float) rootLoc.getYaw(), (float) rootLoc.getPitch(), rootLoc.getYaw(), 0.0, sub);
                            }
                        }
                    } else {
                        ModelInstance tempSub = session.tempSubsystemInstanceId != null ? activeInstances.get(session.tempSubsystemInstanceId) : null;
                        if (tempSub != null && tempSub.getVehicleRoot() != null) {
                            tempSub.getVehicleRoot().teleport(session.subsystemOriginWorldLoc);
                            updateModelTransforms(tempSub);
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }
    
    private void drawParticleLine(Player player, Location start, Location end, org.bukkit.Particle particle) {
        double distance = start.distance(end);
        org.bukkit.util.Vector vector = end.toVector().subtract(start.toVector()).normalize();
        for (double d = 0; d < distance; d += 0.2) {
            Location point = start.clone().add(vector.clone().multiply(d));
            player.spawnParticle(particle, point, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }
}
