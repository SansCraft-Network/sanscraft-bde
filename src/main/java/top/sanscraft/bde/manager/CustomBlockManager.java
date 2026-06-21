package top.sanscraft.bde.manager;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import top.sanscraft.bde.SansCraftBDEPlugin;
import top.sanscraft.bde.model.BdeModel;
import top.sanscraft.bde.model.ModelInstance;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.*;

public class CustomBlockManager {
    private final SansCraftBDEPlugin plugin;
    private final Map<String, CustomBlockConfig> registeredBlocks = new HashMap<>();
    private final Map<BlockLocation, String> placedBlocks = new HashMap<>();
    private final Map<BlockLocation, ModelInstance> activeDisplays = new HashMap<>();
    private final NamespacedKey blockKey;
    private final Gson gson = new Gson();

    public CustomBlockManager(SansCraftBDEPlugin plugin) {
        this.plugin = plugin;
        this.blockKey = new NamespacedKey(plugin, "custom_block_id");
    }

    /**
     * Loads custom block definitions from custom_blocks.yml.
     */
    public void loadCustomBlocks() {
        registeredBlocks.clear();
        File file = new File(plugin.getDataFolder(), "custom_blocks.yml");
        if (!file.exists()) {
            plugin.saveResource("custom_blocks.yml", false);
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        if (!config.isConfigurationSection("custom_blocks")) return;

        for (String key : config.getConfigurationSection("custom_blocks").getKeys(false)) {
            String model = config.getString("custom_blocks." + key + ".model");
            double scale = config.getDouble("custom_blocks." + key + ".scale", 1.0);
            List<Double> offsetList = config.getDoubleList("custom_blocks." + key + ".offset");
            double[] offset = new double[]{0.5, 0.0, 0.5}; // default centered
            if (offsetList.size() == 3) {
                offset[0] = offsetList.get(0);
                offset[1] = offsetList.get(1);
                offset[2] = offsetList.get(2);
            }

            String materialStr = config.getString("custom_blocks." + key + ".item.material", "BARRIER");
            Material material = Material.matchMaterial(materialStr);
            if (material == null) material = Material.BARRIER;

            String name = config.getString("custom_blocks." + key + ".item.name", key);
            List<String> lore = config.getStringList("custom_blocks." + key + ".item.lore");

            CustomBlockConfig blockConfig = new CustomBlockConfig(key, model, scale, offset, material, name, lore);
            registeredBlocks.put(key, blockConfig);
        }
        plugin.getLogger().info("Registered " + registeredBlocks.size() + " custom blocks from custom_blocks.yml");
    }

    /**
     * Loads placed custom blocks from placed_blocks.json.
     */
    public void loadPlacedBlocks() {
        placedBlocks.clear();
        File file = new File(plugin.getDataFolder(), "placed_blocks.json");
        if (!file.exists()) return;

        try (FileReader reader = new FileReader(file)) {
            Type listType = new TypeToken<List<PlacedBlockRecord>>() {}.getType();
            List<PlacedBlockRecord> list = gson.fromJson(reader, listType);
            if (list != null) {
                for (PlacedBlockRecord record : list) {
                    BlockLocation loc = new BlockLocation(record.world, record.x, record.y, record.z);
                    placedBlocks.put(loc, record.blockId);
                }
            }
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to load placed blocks: " + e.getMessage());
        }
    }

    /**
     * Saves placed custom blocks to placed_blocks.json.
     */
    public void savePlacedBlocks() {
        File file = new File(plugin.getDataFolder(), "placed_blocks.json");
        List<PlacedBlockRecord> list = new ArrayList<>();
        for (Map.Entry<BlockLocation, String> entry : placedBlocks.entrySet()) {
            BlockLocation loc = entry.getKey();
            list.add(new PlacedBlockRecord(loc.world, loc.x, loc.y, loc.z, entry.getValue()));
        }

        try (FileWriter writer = new FileWriter(file)) {
            gson.toJson(list, writer);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save placed blocks: " + e.getMessage());
        }
    }

    /**
     * Spawns display models in chunks that are loaded.
     */
    public void handleChunkLoad(String worldName, int chunkX, int chunkZ) {
        for (Map.Entry<BlockLocation, String> entry : placedBlocks.entrySet()) {
            BlockLocation loc = entry.getKey();
            if (!loc.world.equals(worldName)) continue;
            
            // Check if block coordinates lie inside chunk
            if ((loc.x >> 4) == chunkX && (loc.z >> 4) == chunkZ) {
                spawnBlockDisplay(loc, entry.getValue());
            }
        }
    }

    /**
     * Cleans up display models in chunks that are unloaded.
     */
    public void handleChunkUnload(String worldName, int chunkX, int chunkZ) {
        List<BlockLocation> toRemove = new ArrayList<>();
        for (BlockLocation loc : activeDisplays.keySet()) {
            if (!loc.world.equals(worldName)) continue;

            if ((loc.x >> 4) == chunkX && (loc.z >> 4) == chunkZ) {
                ModelInstance instance = activeDisplays.get(loc);
                if (instance != null) {
                    plugin.getModelManager().removeInstance(instance.getId());
                }
                toRemove.add(loc);
            }
        }
        for (BlockLocation loc : toRemove) {
            activeDisplays.remove(loc);
        }
    }

    /**
     * Registers a newly placed block.
     */
    public void placeBlock(Block block, String customBlockId) {
        BlockLocation loc = new BlockLocation(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        placedBlocks.put(loc, customBlockId);
        savePlacedBlocks();

        // Spawn display model immediately
        spawnBlockDisplay(loc, customBlockId);
    }

    /**
     * Removes a placed block.
     */
    public void breakBlock(Block block) {
        BlockLocation loc = new BlockLocation(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        placedBlocks.remove(loc);
        savePlacedBlocks();

        // Despawn display model immediately
        ModelInstance instance = activeDisplays.remove(loc);
        if (instance != null) {
            plugin.getModelManager().removeInstance(instance.getId());
        }
    }

    /**
     * Spawns the display model for a placed custom block.
     */
    private void spawnBlockDisplay(BlockLocation loc, String blockId) {
        if (activeDisplays.containsKey(loc)) return; // Already spawned

        CustomBlockConfig config = registeredBlocks.get(blockId);
        if (config == null) return;

        Location spawnLoc = loc.toLocation().add(config.offset[0], config.offset[1], config.offset[2]);

        // Load model asynchronously
        plugin.getModelManager().loadModel(config.model)
                .thenAccept(model -> {
                    // Check if chunk is still loaded before spawning
                    if (spawnLoc.getChunk().isLoaded()) {
                        // Run on primary thread
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            // Ensure it hasn't been spawned in the meantime or broken
                            if (!placedBlocks.containsKey(loc) || activeDisplays.containsKey(loc)) return;

                            ModelInstance instance = plugin.getModelManager().spawnModel(model, spawnLoc, config.scale);
                            activeDisplays.put(loc, instance);
                        });
                    }
                })
                .exceptionally(ex -> {
                    plugin.getLogger().warning("Failed to load display model for custom block " + blockId + ": " + ex.getMessage());
                    return null;
                });
    }

    public ItemStack createCustomBlockItem(String blockId, int amount) {
        CustomBlockConfig config = registeredBlocks.get(blockId);
        if (config == null) return null;
        return createCustomBlockItem(blockId, amount, config.material);
    }

    public ItemStack createCustomBlockItem(String blockId, int amount, Material customMaterial) {
        CustomBlockConfig config = registeredBlocks.get(blockId);
        if (config == null) return null;

        ItemStack item = new ItemStack(customMaterial, amount);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', config.displayName));
            List<String> lore = new ArrayList<>();
            for (String line : config.lore) {
                lore.add(ChatColor.translateAlternateColorCodes('&', line));
            }
            meta.setLore(lore);

            // Store custom block ID in persistent data container
            meta.getPersistentDataContainer().set(blockKey, PersistentDataType.STRING, blockId);
            item.setItemMeta(meta);
        }
        return item;
    }

    public String getCustomBlockIdFromItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        return meta.getPersistentDataContainer().get(blockKey, PersistentDataType.STRING);
    }

    public boolean isCustomBlock(Block block) {
        BlockLocation loc = new BlockLocation(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        return placedBlocks.containsKey(loc);
    }

    public String getPlacedBlockId(Block block) {
        BlockLocation loc = new BlockLocation(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        return placedBlocks.get(loc);
    }

    public CustomBlockConfig getConfig(String blockId) {
        return registeredBlocks.get(blockId);
    }

    public Set<String> getRegisteredBlockIds() {
        return registeredBlocks.keySet();
    }

    public void despawnAllLoadedBlocks() {
        for (ModelInstance instance : activeDisplays.values()) {
            plugin.getModelManager().removeInstance(instance.getId());
        }
        activeDisplays.clear();
    }

    public void setBlockDefaultLink(String modelId, Material material) {
        File file = new File(plugin.getDataFolder(), "custom_blocks.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        String path = "custom_blocks." + modelId;
        config.set(path + ".model", modelId);
        config.set(path + ".item.material", material.name());
        if (!config.contains(path + ".scale")) config.set(path + ".scale", 1.0);
        if (!config.contains(path + ".offset")) config.set(path + ".offset", Arrays.asList(0.5, 0.0, 0.5));
        if (!config.contains(path + ".item.name")) config.set(path + ".item.name", "Custom " + modelId);
        if (!config.contains(path + ".item.lore")) config.set(path + ".item.lore", Arrays.asList("A custom " + modelId + " block."));

        try {
            config.save(file);
            loadCustomBlocks(); // Reload definitions
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save custom_blocks.yml: " + e.getMessage());
        }
    }

    public void unlinkBlockDefault(String modelId) {
        File file = new File(plugin.getDataFolder(), "custom_blocks.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        config.set("custom_blocks." + modelId, null);

        try {
            config.save(file);
            loadCustomBlocks(); // Reload definitions
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save custom_blocks.yml: " + e.getMessage());
        }
    }

    public void initialize() {
        loadCustomBlocks();
        loadPlacedBlocks();
        
        // Spawn active blocks in currently loaded chunks
        for (org.bukkit.World world : Bukkit.getWorlds()) {
            for (org.bukkit.Chunk chunk : world.getLoadedChunks()) {
                handleChunkLoad(world.getName(), chunk.getX(), chunk.getZ());
            }
        }
    }

    // Helper classes
    public static class CustomBlockConfig {
        public final String id;
        public final String model;
        public final double scale;
        public final double[] offset;
        public final Material material;
        public final String displayName;
        public final List<String> lore;

        public CustomBlockConfig(String id, String model, double scale, double[] offset, Material material, String displayName, List<String> lore) {
            this.id = id;
            this.model = model;
            this.scale = scale;
            this.offset = offset;
            this.material = material;
            this.displayName = displayName;
            this.lore = lore;
        }
    }

    public static class BlockLocation {
        public final String world;
        public final int x;
        public final int y;
        public final int z;

        public BlockLocation(String world, int x, int y, int z) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public Location toLocation() {
            return new Location(Bukkit.getWorld(world), x, y, z);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            BlockLocation that = (BlockLocation) o;
            return x == that.x && y == that.y && z == that.z && Objects.equals(world, that.world);
        }

        @Override
        public int hashCode() {
            return Objects.hash(world, x, y, z);
        }
    }

    private static class PlacedBlockRecord {
        public String world;
        public int x;
        public int y;
        public int z;
        public String blockId;

        public PlacedBlockRecord(String world, int x, int y, int z, String blockId) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.blockId = blockId;
        }
    }
}
