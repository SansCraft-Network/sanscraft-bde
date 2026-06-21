package top.sanscraft.bde.converter;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import top.sanscraft.bde.SansCraftBDEPlugin;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public class ConversionMapper {
    private final SansCraftBDEPlugin plugin;
    private final Map<String, String> textureToBlock = new HashMap<>();
    private final Map<String, Material> colorToBlock = new HashMap<>();
    private final String defaultBlockbenchBlock;
    private final String defaultVoxBlock;

    // Static map of standard Minecraft materials and their average RGB values
    private static final Map<Material, int[]> BLOCK_COLORS = new HashMap<>();

    static {
        // Concretes
        BLOCK_COLORS.put(Material.WHITE_CONCRETE, new int[]{207, 213, 214});
        BLOCK_COLORS.put(Material.ORANGE_CONCRETE, new int[]{224, 97, 0});
        BLOCK_COLORS.put(Material.MAGENTA_CONCRETE, new int[]{169, 48, 159});
        BLOCK_COLORS.put(Material.LIGHT_BLUE_CONCRETE, new int[]{36, 137, 199});
        BLOCK_COLORS.put(Material.YELLOW_CONCRETE, new int[]{240, 175, 21});
        BLOCK_COLORS.put(Material.LIME_CONCRETE, new int[]{94, 168, 24});
        BLOCK_COLORS.put(Material.PINK_CONCRETE, new int[]{213, 101, 142});
        BLOCK_COLORS.put(Material.GRAY_CONCRETE, new int[]{54, 57, 61});
        BLOCK_COLORS.put(Material.LIGHT_GRAY_CONCRETE, new int[]{125, 125, 115});
        BLOCK_COLORS.put(Material.CYAN_CONCRETE, new int[]{21, 119, 136});
        BLOCK_COLORS.put(Material.PURPLE_CONCRETE, new int[]{100, 31, 156});
        BLOCK_COLORS.put(Material.BLUE_CONCRETE, new int[]{44, 46, 143});
        BLOCK_COLORS.put(Material.BROWN_CONCRETE, new int[]{96, 59, 31});
        BLOCK_COLORS.put(Material.GREEN_CONCRETE, new int[]{73, 91, 36});
        BLOCK_COLORS.put(Material.RED_CONCRETE, new int[]{142, 32, 32});
        BLOCK_COLORS.put(Material.BLACK_CONCRETE, new int[]{8, 10, 15});

        // Wools
        BLOCK_COLORS.put(Material.WHITE_WOOL, new int[]{233, 236, 236});
        BLOCK_COLORS.put(Material.ORANGE_WOOL, new int[]{240, 118, 19});
        BLOCK_COLORS.put(Material.MAGENTA_WOOL, new int[]{189, 68, 179});
        BLOCK_COLORS.put(Material.LIGHT_BLUE_WOOL, new int[]{58, 175, 217});
        BLOCK_COLORS.put(Material.YELLOW_WOOL, new int[]{248, 197, 39});
        BLOCK_COLORS.put(Material.LIME_WOOL, new int[]{112, 185, 25});
        BLOCK_COLORS.put(Material.PINK_WOOL, new int[]{237, 141, 172});
        BLOCK_COLORS.put(Material.GRAY_WOOL, new int[]{62, 68, 71});
        BLOCK_COLORS.put(Material.LIGHT_GRAY_WOOL, new int[]{142, 142, 134});
        BLOCK_COLORS.put(Material.CYAN_WOOL, new int[]{21, 137, 145});
        BLOCK_COLORS.put(Material.PURPLE_WOOL, new int[]{121, 42, 172});
        BLOCK_COLORS.put(Material.BLUE_WOOL, new int[]{53, 57, 157});
        BLOCK_COLORS.put(Material.BROWN_WOOL, new int[]{114, 71, 40});
        BLOCK_COLORS.put(Material.GREEN_WOOL, new int[]{84, 109, 27});
        BLOCK_COLORS.put(Material.RED_WOOL, new int[]{160, 39, 34});
        BLOCK_COLORS.put(Material.BLACK_WOOL, new int[]{20, 21, 25});

        // Terracottas
        BLOCK_COLORS.put(Material.TERRACOTTA, new int[]{152, 94, 67});
        BLOCK_COLORS.put(Material.WHITE_TERRACOTTA, new int[]{209, 178, 161});
        BLOCK_COLORS.put(Material.ORANGE_TERRACOTTA, new int[]{161, 83, 37});
        BLOCK_COLORS.put(Material.MAGENTA_TERRACOTTA, new int[]{149, 88, 108});
        BLOCK_COLORS.put(Material.LIGHT_BLUE_TERRACOTTA, new int[]{113, 108, 137});
        BLOCK_COLORS.put(Material.YELLOW_TERRACOTTA, new int[]{186, 133, 35});
        BLOCK_COLORS.put(Material.LIME_TERRACOTTA, new int[]{103, 117, 52});
        BLOCK_COLORS.put(Material.PINK_TERRACOTTA, new int[]{161, 78, 78});
        BLOCK_COLORS.put(Material.GRAY_TERRACOTTA, new int[]{57, 42, 35});
        BLOCK_COLORS.put(Material.LIGHT_GRAY_TERRACOTTA, new int[]{135, 106, 97});
        BLOCK_COLORS.put(Material.CYAN_TERRACOTTA, new int[]{86, 91, 91});
        BLOCK_COLORS.put(Material.PURPLE_TERRACOTTA, new int[]{118, 70, 86});
        BLOCK_COLORS.put(Material.BLUE_TERRACOTTA, new int[]{74, 59, 91});
        BLOCK_COLORS.put(Material.BROWN_TERRACOTTA, new int[]{77, 51, 35});
        BLOCK_COLORS.put(Material.GREEN_TERRACOTTA, new int[]{76, 83, 42});
        BLOCK_COLORS.put(Material.RED_TERRACOTTA, new int[]{142, 60, 46});
        BLOCK_COLORS.put(Material.BLACK_TERRACOTTA, new int[]{37, 22, 16});

        // Wood Planks
        BLOCK_COLORS.put(Material.OAK_PLANKS, new int[]{162, 130, 84});
        BLOCK_COLORS.put(Material.SPRUCE_PLANKS, new int[]{114, 85, 48});
        BLOCK_COLORS.put(Material.BIRCH_PLANKS, new int[]{196, 179, 131});
        BLOCK_COLORS.put(Material.JUNGLE_PLANKS, new int[]{186, 139, 102});
        BLOCK_COLORS.put(Material.ACACIA_PLANKS, new int[]{168, 93, 50});
        BLOCK_COLORS.put(Material.DARK_OAK_PLANKS, new int[]{66, 43, 22});
        BLOCK_COLORS.put(Material.MANGROVE_PLANKS, new int[]{119, 53, 50});
        BLOCK_COLORS.put(Material.CHERRY_PLANKS, new int[]{226, 169, 160});
        BLOCK_COLORS.put(Material.BAMBOO_PLANKS, new int[]{165, 149, 83});
        BLOCK_COLORS.put(Material.CRIMSON_PLANKS, new int[]{148, 59, 87});
        BLOCK_COLORS.put(Material.WARPED_PLANKS, new int[]{59, 114, 114});

        // Stone types
        BLOCK_COLORS.put(Material.STONE, new int[]{125, 125, 125});
        BLOCK_COLORS.put(Material.COBBLESTONE, new int[]{105, 105, 105});
        BLOCK_COLORS.put(Material.SMOOTH_STONE, new int[]{150, 150, 150});
        BLOCK_COLORS.put(Material.STONE_BRICKS, new int[]{122, 122, 122});
        BLOCK_COLORS.put(Material.MOSSY_COBBLESTONE, new int[]{110, 119, 90});
        BLOCK_COLORS.put(Material.GRANITE, new int[]{152, 107, 91});
        BLOCK_COLORS.put(Material.DIORITE, new int[]{188, 188, 188});
        BLOCK_COLORS.put(Material.ANDESITE, new int[]{134, 134, 134});
        BLOCK_COLORS.put(Material.DEEPSLATE, new int[]{73, 73, 73});
        BLOCK_COLORS.put(Material.COBBLED_DEEPSLATE, new int[]{69, 69, 69});
        BLOCK_COLORS.put(Material.OBSIDIAN, new int[]{20, 15, 26});

        // Minerals
        BLOCK_COLORS.put(Material.GOLD_BLOCK, new int[]{249, 219, 74});
        BLOCK_COLORS.put(Material.IRON_BLOCK, new int[]{216, 216, 216});
        BLOCK_COLORS.put(Material.DIAMOND_BLOCK, new int[]{98, 220, 220});
        BLOCK_COLORS.put(Material.EMERALD_BLOCK, new int[]{36, 206, 103});
        BLOCK_COLORS.put(Material.REDSTONE_BLOCK, new int[]{189, 21, 0});
        BLOCK_COLORS.put(Material.LAPIS_BLOCK, new int[]{30, 67, 140});
        BLOCK_COLORS.put(Material.COAL_BLOCK, new int[]{16, 16, 16});
        BLOCK_COLORS.put(Material.COPPER_BLOCK, new int[]{195, 107, 79});

        // Dirt/Sands/Gravel/Clay
        BLOCK_COLORS.put(Material.DIRT, new int[]{134, 96, 67});
        BLOCK_COLORS.put(Material.COARSE_DIRT, new int[]{119, 85, 59});
        BLOCK_COLORS.put(Material.MUD, new int[]{60, 57, 60});
        BLOCK_COLORS.put(Material.CLAY, new int[]{160, 166, 179});
        BLOCK_COLORS.put(Material.SAND, new int[]{219, 207, 161});
        BLOCK_COLORS.put(Material.RED_SAND, new int[]{191, 103, 33});
        BLOCK_COLORS.put(Material.GRAVEL, new int[]{126, 124, 122});
        BLOCK_COLORS.put(Material.NETHERRACK, new int[]{111, 41, 41});
        BLOCK_COLORS.put(Material.END_STONE, new int[]{221, 229, 163});
        BLOCK_COLORS.put(Material.GLOWSTONE, new int[]{191, 137, 72});
    }

    public ConversionMapper(SansCraftBDEPlugin plugin, File mappingFile) {
        this.plugin = plugin;
        this.defaultBlockbenchBlock = plugin.getConfig().getString("blockbench.default-block", "minecraft:oak_planks");
        this.defaultVoxBlock = plugin.getConfig().getString("voxels.default-block", "minecraft:white_concrete");

        if (mappingFile != null && mappingFile.exists()) {
            loadMappings(mappingFile);
        }
    }

    public ConversionMapper(String defaultBlockbenchBlock, String defaultVoxBlock, File mappingFile) {
        this.plugin = null;
        this.defaultBlockbenchBlock = defaultBlockbenchBlock;
        this.defaultVoxBlock = defaultVoxBlock;

        if (mappingFile != null && mappingFile.exists()) {
            loadMappings(mappingFile);
        }
    }

    private void loadMappings(File mappingFile) {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(mappingFile);
        
        // Texture mapping load
        if (config.isConfigurationSection("texture_mappings")) {
            for (String key : config.getConfigurationSection("texture_mappings").getKeys(false)) {
                textureToBlock.put(key, config.getString("texture_mappings." + key));
            }
        }

        // Color mapping load (e.g. hex #FF0000 -> material)
        if (config.isConfigurationSection("color_mappings")) {
            for (String key : config.getConfigurationSection("color_mappings").getKeys(false)) {
                String matStr = config.getString("color_mappings." + key);
                Material mat = Material.matchMaterial(matStr);
                if (mat != null) {
                    colorToBlock.put(key.toLowerCase(), mat);
                }
            }
        }
    }

    /**
     * Maps a Blockbench texture name to a block state name.
     */
    public String getBlockForTexture(String textureKey) {
        if (textureKey != null && textureKey.startsWith("#")) {
            textureKey = textureKey.substring(1);
        }
        return textureToBlock.getOrDefault(textureKey, defaultBlockbenchBlock);
    }

    /**
     * Maps an RGB value to a Minecraft Material.
     */
    public Material getBlockForColor(int r, int g, int b) {
        String hex = String.format("#%02x%02x%02x", r, g, b);
        if (colorToBlock.containsKey(hex)) {
            return colorToBlock.get(hex);
        }

        // Find closest block color using perceptually-weighted Redmean distance
        Material closest = null;
        double minDistance = Double.MAX_VALUE;

        for (Map.Entry<Material, int[]> entry : BLOCK_COLORS.entrySet()) {
            int[] rgb = entry.getValue();
            
            // Redmean color distance calculation
            long rMean = ((long) r + (long) rgb[0]) / 2;
            long rDiff = (long) r - (long) rgb[0];
            long gDiff = (long) g - (long) rgb[1];
            long bDiff = (long) b - (long) rgb[2];
            double distance = Math.sqrt((((512 + rMean) * rDiff * rDiff) >> 8) + 4 * gDiff * gDiff + (((767 - rMean) * bDiff * bDiff) >> 8));

            if (distance < minDistance) {
                minDistance = distance;
                closest = entry.getKey();
            }
        }

        return closest != null ? closest : Material.matchMaterial(defaultVoxBlock);
    }
}
