package top.sanscraft.bde.manager;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import top.sanscraft.bde.SansCraftBDEPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class BdeRepairConfig {
    private final SansCraftBDEPlugin plugin;
    private final Map<String, RepairToolConfig> registeredTools = new HashMap<>();

    public BdeRepairConfig(SansCraftBDEPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadRepairs() {
        registeredTools.clear();
        File file = new File(plugin.getDataFolder(), "repairs.yml");
        if (!file.exists()) {
            try {
                file.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().severe("Failed to create repairs.yml: " + e.getMessage());
            }
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        if (!config.isConfigurationSection("repairs")) return;

        for (String key : config.getConfigurationSection("repairs").getKeys(false)) {
            String name = config.getString("repairs." + key + ".name", key);
            String materialStr = config.getString("repairs." + key + ".material", "IRON_INGOT");
            Material material = Material.matchMaterial(materialStr);
            if (material == null) material = Material.IRON_INGOT;

            int customModelData = config.getInt("repairs." + key + ".custom_model_data", -1);
            double repairAmount = config.getDouble("repairs." + key + ".repair_amount", 10.0);
            double cooldown = config.getDouble("repairs." + key + ".cooldown", 0.0);
            double repairDelay = config.getDouble("repairs." + key + ".repair_delay", 0.0);
            boolean visualCooldown = config.getBoolean("repairs." + key + ".visual_cooldown", false);

            List<String> costList = config.getStringList("repairs." + key + ".cost");
            Map<Material, Integer> repairCost = new HashMap<>();
            for (String costStr : costList) {
                String[] parts = costStr.trim().split("x");
                if (parts.length == 2) {
                    try {
                        int amount = Integer.parseInt(parts[0].trim());
                        Material costMat = Material.matchMaterial(parts[1].trim());
                        if (costMat != null) {
                            repairCost.put(costMat, amount);
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }

            RepairToolConfig tool = new RepairToolConfig(key, name, material, customModelData, repairAmount, cooldown, repairDelay, visualCooldown, repairCost);
            registeredTools.put(key, tool);
        }
    }

    public void saveRepairs() {
        File file = new File(plugin.getDataFolder(), "repairs.yml");
        YamlConfiguration config = new YamlConfiguration();

        for (Map.Entry<String, RepairToolConfig> entry : registeredTools.entrySet()) {
            String key = entry.getKey();
            RepairToolConfig tool = entry.getValue();

            config.set("repairs." + key + ".name", tool.name);
            config.set("repairs." + key + ".material", tool.material.name());
            if (tool.customModelData != -1) {
                config.set("repairs." + key + ".custom_model_data", tool.customModelData);
            }
            config.set("repairs." + key + ".repair_amount", tool.repairAmount);
            config.set("repairs." + key + ".cooldown", tool.cooldown);
            config.set("repairs." + key + ".repair_delay", tool.repairDelay);
            config.set("repairs." + key + ".visual_cooldown", tool.visualCooldown);

            List<String> costList = new ArrayList<>();
            for (Map.Entry<Material, Integer> costEntry : tool.repairCost.entrySet()) {
                costList.add(costEntry.getValue() + "x" + costEntry.getKey().name());
            }
            config.set("repairs." + key + ".cost", costList);
        }

        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save repairs.yml: " + e.getMessage());
        }
    }

    public Map<String, RepairToolConfig> getRegisteredTools() {
        return registeredTools;
    }

    public void addTool(RepairToolConfig tool) {
        registeredTools.put(tool.id, tool);
        saveRepairs();
    }

    public void removeTool(String id) {
        registeredTools.remove(id);
        saveRepairs();
    }

    public static class RepairToolConfig {
        public final String id;
        public final String name;
        public final Material material;
        public final int customModelData;
        public final double repairAmount;
        public final double cooldown;
        public final double repairDelay;
        public final boolean visualCooldown;
        public final Map<Material, Integer> repairCost;

        public RepairToolConfig(String id, String name, Material material, int customModelData, double repairAmount, double cooldown, double repairDelay, boolean visualCooldown, Map<Material, Integer> repairCost) {
            this.id = id;
            this.name = name;
            this.material = material;
            this.customModelData = customModelData;
            this.repairAmount = repairAmount;
            this.cooldown = cooldown;
            this.repairDelay = repairDelay;
            this.visualCooldown = visualCooldown;
            this.repairCost = repairCost != null ? repairCost : new HashMap<>();
        }
    }
}
