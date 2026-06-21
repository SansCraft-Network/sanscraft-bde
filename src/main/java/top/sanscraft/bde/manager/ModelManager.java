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

    public ModelManager(SansCraftBDEPlugin plugin) {
        this.plugin = plugin;
        this.inputTracker = new PlayerInputTracker(plugin);
        startVehicleTick();
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
            String apiEndpoint = plugin.getConfig().getString("api-endpoint", "https://block-display.com/server-api/");
            String url = apiEndpoint + "?id=" + id;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
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

    /**
     * Loads a model definition synchronously by ID or filename.
     */
    public BdeModel loadModelSync(String id) throws Exception {
        if (id.matches("\\d{5,10}")) {
            File cacheFile = new File(plugin.getDataFolder(), "cache/" + id + ".json");
            if (cacheFile.exists()) {
                try (FileReader reader = new FileReader(cacheFile)) {
                    return gson.fromJson(reader, BdeModel.class);
                }
            }
            throw new Exception("Model not cached: " + id);
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

            Location hitboxLoc = ModelTransformEngine.getHitboxPosition(location, model, scale);
            hitboxLoc.setYaw(location.getYaw());
            root = location.getWorld().spawn(hitboxLoc, Interaction.class);
            root.setInteractionWidth(radius * 2f);
            root.setInteractionHeight(interactionHeight);
            root.setGravity(false);
            root.setInvulnerable(true);
            root.addScoreboardTag("bde_root");
            root.addScoreboardTag("bde_model_" + uuidStr);
            root.setMetadata("bde_instance_id", new FixedMetadataValue(plugin, uuidStr));
            instance.setRootEntity(root);

            // Spawn co-passenger seat ArmorStands immediately
            List<List<Double>> pOffsets = model.getPassengerOffsets();
            if (pOffsets != null) {
                for (int i = 0; i < pOffsets.size(); i++) {
                    Location seatLoc = ModelTransformEngine.getSeatPosition(location, pOffsets.get(i), model.getSeatOffset(), scale, model.getFrontYawOffset());
                    double psYaw = 0.0;
                    if (model.getVehicle() != null && i < model.getVehicle().getPassengerSeats().size()) {
                        psYaw = model.getVehicle().getPassengerSeats().get(i).getYaw();
                    }
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
        // Check for id:"minecraft:item_display" or id:item_display
        return snbt.contains("id:\"minecraft:item_display\"") ||
               snbt.contains("id:item_display") ||
               snbt.contains("id:'minecraft:item_display'") ||
               snbt.contains("id:\"item_display\"");
    }

    private static boolean isTextDisplaySnbt(String snbt) {
        return snbt.contains("id:\"minecraft:text_display\"") ||
               snbt.contains("id:text_display") ||
               snbt.contains("id:'minecraft:text_display'") ||
               snbt.contains("id:\"text_display\"");
    }

    private void applyTextDisplayData(TextDisplay display, String snbt) {
        // Extract and set text
        String textJson = extractSnbtString(snbt, "text:'");
        if (textJson == null) {
            textJson = extractSnbtString(snbt, "text:\"");
        }

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
        String alignment = extractSnbtString(snbt, "alignment:\"");
        if (alignment == null) {
            alignment = extractSnbtString(snbt, "alignment:'");
        }
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
        String itemId = extractSnbtString(snbt, "item:{id:\"");
        if (itemId == null) {
            // Try alternate format: item:{id:"..."
            itemId = extractSnbtString(snbt, "item:{id:\"");
        }

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
        String transformMode = extractSnbtString(snbt, "item_display:\"");
        if (transformMode != null) {
            try {
                display.setItemDisplayTransform(
                    ItemDisplay.ItemDisplayTransform.valueOf(transformMode.toUpperCase()));
            } catch (IllegalArgumentException ignored) {}
        }
    }

    /**
     * Extracts a quoted string value after the given prefix in SNBT.
     * e.g., extractSnbtString(snbt, "id:\"") returns "minecraft:paper" from id:"minecraft:paper"
     */
    private String extractSnbtString(String snbt, String prefix) {
        int idx = snbt.indexOf(prefix);
        if (idx == -1) return null;
        int start = idx + prefix.length();
        int end = snbt.indexOf("\"", start);
        if (end == -1) return null;
        return snbt.substring(start, end);
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

        updateModelTransforms(instance);
    }

    public void deactivateHitbox(ModelInstance instance) {
        if (instance.getModel().getVehicleStats() != null) return;
        if (instance.getRootEntity() == null) return;

        Entity root = instance.getRootEntity();
        Location loc = instance.getLocation();

        instance.setCurrentLocation(loc);

        root.remove();
        instance.setRootEntity(null);

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

    public void updateHitboxLocation(ModelInstance instance) {
        Entity hitbox = instance.getRootEntity();
        Entity vehicleRoot = instance.getVehicleRoot();
        if (hitbox != null && hitbox.isValid() && vehicleRoot != null && vehicleRoot.isValid()) {
            Location hitboxLoc = ModelTransformEngine.getHitboxPosition(vehicleRoot.getLocation(), instance.getModel(), instance.getScale());
            hitboxLoc.setYaw(vehicleRoot.getLocation().getYaw());
            hitbox.teleport(hitboxLoc);
        }
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
                        Location seatLoc = ModelTransformEngine.getSeatPosition(vehicleRoot.getLocation(), pOffsets.get(i), instance.getModel().getSeatOffset(), scale, instance.getModel().getFrontYawOffset());
                        double psYaw = 0.0;
                        if (instance.getModel().getVehicle() != null && i < instance.getModel().getVehicle().getPassengerSeats().size()) {
                            psYaw = instance.getModel().getVehicle().getPassengerSeats().get(i).getYaw();
                        }
                        seatLoc.setYaw((float) ((vehicleRoot.getLocation().getYaw() + psYaw) % 360.0));
                        teleportKeepPassengers(seat, seatLoc);
                        break;
                    }
                }
            }
            Entity driverSeat = instance.getDriverSeat();
            if (driverSeat != null && driverSeat.isValid()) {
                double dsYaw = 0.0;
                if (instance.getModel().getVehicle() != null) {
                    dsYaw = instance.getModel().getVehicle().getDriverSeatYaw();
                }
                float finalDsYaw = (float) ((vehicleRoot.getLocation().getYaw() + dsYaw) % 360.0);
                driverSeat.setRotation(finalDsYaw, driverSeat.getLocation().getPitch());
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
        if (instance.getVehicleRoot() != null) {
            teleportKeepPassengers(instance.getVehicleRoot(), newLoc);
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
                    
                    if (driver != null) {
                        PlayerInputTracker.PlayerInputData input = inputTracker.getInput(driver.getUniqueId());
                        BdeModel.VehicleStats stats = instance.getModel().getVehicleStats();
                        if (stats == null) continue;
                        
                        double topSpeed = stats.getTopSpeed();
                        double accel = stats.getAcceleration();
                        double decel = stats.getDeceleration();
                        double revSpeed = stats.getReverseSpeed();
                        double turnSpeed = stats.getTurnSpeed();
                        
                        Location rootLoc = vehicleRoot.getLocation();
                        float yaw = rootLoc.getYaw();
                        
                        if (input.left) {
                            yaw = (yaw - (float) turnSpeed) % 360;
                        }
                        if (input.right) {
                            yaw = (yaw + (float) turnSpeed) % 360;
                        }
                        
                        double speed = instance.getCurrentSpeed();
                        if (input.forward) {
                            speed = Math.min(topSpeed, speed + accel);
                        } else if (input.backward) {
                            speed = Math.max(-revSpeed, speed - accel);
                        } else {
                            if (speed > 0) {
                                speed = Math.max(0.0, speed - decel);
                            } else if (speed < 0) {
                                speed = Math.min(0.0, speed + decel);
                            }
                        }
                        instance.setCurrentSpeed(speed);
                        
                        double yawRad = Math.toRadians(yaw);
                        double dx = -Math.sin(yawRad) * speed;
                        double dz = Math.cos(yawRad) * speed;
                        
                        Location targetLoc = rootLoc.clone().add(dx, 0, dz);
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
                        org.bukkit.util.Vector vel = new org.bukkit.util.Vector(dx, targetLoc.getY() - rootLoc.getY(), dz);
                        vehicleRoot.setVelocity(vel);
                        teleportKeepPassengers(vehicleRoot, targetLoc);
                        
                        // Sync hitbox position
                        if (hitbox != null && hitbox.isValid()) {
                            Location hitboxLoc = ModelTransformEngine.getHitboxPosition(targetLoc, instance.getModel(), instance.getScale());
                            hitboxLoc.setYaw(targetLoc.getYaw());
                            hitbox.teleport(hitboxLoc);
                        }

                        // Update display entity visual transformations (applies yaw rotation)
                        updateModelTransforms(instance);
                        
                        // Sync seats
                        List<List<Double>> pOffsets = instance.getModel().getPassengerOffsets();
                        List<org.bukkit.entity.ArmorStand> activeSeats = instance.getPassengerSeats();
                        double scale = instance.getScale();
                        
                        for (org.bukkit.entity.ArmorStand seat : activeSeats) {
                            for (int i = 0; i < pOffsets.size(); i++) {
                                if (seat.getScoreboardTags().contains("bde_seat_" + i)) {
                                    Location seatLoc = ModelTransformEngine.getSeatPosition(targetLoc, pOffsets.get(i), instance.getModel().getSeatOffset(), scale, instance.getModel().getFrontYawOffset());
                                    double psYaw = 0.0;
                                    if (instance.getModel().getVehicle() != null && i < instance.getModel().getVehicle().getPassengerSeats().size()) {
                                        psYaw = instance.getModel().getVehicle().getPassengerSeats().get(i).getYaw();
                                    }
                                    seatLoc.setYaw((float) ((targetLoc.getYaw() + psYaw) % 360.0));
                                    teleportKeepPassengers(seat, seatLoc);
                                    break;
                                }
                            }
                        }
                        Entity driverSeat = instance.getDriverSeat();
                        if (driverSeat != null && driverSeat.isValid()) {
                            double dsYaw = 0.0;
                            if (instance.getModel().getVehicle() != null) {
                                dsYaw = instance.getModel().getVehicle().getDriverSeatYaw();
                            }
                            float finalDsYaw = (float) ((targetLoc.getYaw() + dsYaw) % 360.0);
                            driverSeat.setRotation(finalDsYaw, driverSeat.getLocation().getPitch());
                        }
                    } else {
                        double speed = instance.getCurrentSpeed();
                        if (speed != 0.0) {
                            BdeModel.VehicleStats stats = instance.getModel().getVehicleStats();
                            double decel = stats != null ? stats.getDeceleration() : 0.03;
                            if (speed > 0) {
                                speed = Math.max(0.0, speed - decel);
                            } else if (speed < 0) {
                                speed = Math.min(0.0, speed + decel);
                            }
                            instance.setCurrentSpeed(speed);
                            
                            Location rootLoc = vehicleRoot.getLocation();
                            double yawRad = Math.toRadians(rootLoc.getYaw());
                            double dx = -Math.sin(yawRad) * speed;
                            double dz = Math.cos(yawRad) * speed;
                            
                            Location targetLoc = rootLoc.clone().add(dx, 0, dz);
                            
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
                                }
                            } else {
                                targetLoc.setY(rootLoc.getY() - 0.2);
                            }
                            
                            org.bukkit.util.Vector vel = new org.bukkit.util.Vector(dx, targetLoc.getY() - rootLoc.getY(), dz);
                            vehicleRoot.setVelocity(vel);
                            teleportKeepPassengers(vehicleRoot, targetLoc);
                            
                            if (hitbox != null && hitbox.isValid()) {
                                Location hitboxLoc = ModelTransformEngine.getHitboxPosition(targetLoc, instance.getModel(), instance.getScale());
                                hitboxLoc.setYaw(targetLoc.getYaw());
                                hitbox.teleport(hitboxLoc);
                            }

                            List<List<Double>> pOffsets = instance.getModel().getPassengerOffsets();
                            List<org.bukkit.entity.ArmorStand> activeSeats = instance.getPassengerSeats();
                            double scale = instance.getScale();
                            for (org.bukkit.entity.ArmorStand seat : activeSeats) {
                                for (int i = 0; i < pOffsets.size(); i++) {
                                    if (seat.getScoreboardTags().contains("bde_seat_" + i)) {
                                        Location seatLoc = ModelTransformEngine.getSeatPosition(targetLoc, pOffsets.get(i), instance.getModel().getSeatOffset(), scale, instance.getModel().getFrontYawOffset());
                                        double psYaw = 0.0;
                                        if (instance.getModel().getVehicle() != null && i < instance.getModel().getVehicle().getPassengerSeats().size()) {
                                            psYaw = instance.getModel().getVehicle().getPassengerSeats().get(i).getYaw();
                                        }
                                        seatLoc.setYaw((float) ((targetLoc.getYaw() + psYaw) % 360.0));
                                        teleportKeepPassengers(seat, seatLoc);
                                        break;
                                    }
                                }
                            }
                            Entity driverSeat = instance.getDriverSeat();
                            if (driverSeat != null && driverSeat.isValid()) {
                                double dsYaw = 0.0;
                                if (instance.getModel().getVehicle() != null) {
                                    dsYaw = instance.getModel().getVehicle().getDriverSeatYaw();
                                }
                                float finalDsYaw = (float) ((targetLoc.getYaw() + dsYaw) % 360.0);
                                driverSeat.setRotation(finalDsYaw, driverSeat.getLocation().getPitch());
                            }
                        }
                    }
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
}
