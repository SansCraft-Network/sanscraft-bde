package top.sanscraft.bde.manager;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import top.sanscraft.bde.SansCraftBDEPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class BdeAmmoConfig {
    private final SansCraftBDEPlugin plugin;
    private final Map<String, AmmoConfig> registeredAmmo = new HashMap<>();

    public BdeAmmoConfig(SansCraftBDEPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadAmmo() {
        registeredAmmo.clear();
        File file = new File(plugin.getDataFolder(), "ammo.yml");
        if (!file.exists()) {
            try {
                file.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().severe("Failed to create ammo.yml: " + e.getMessage());
            }
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        if (!config.isConfigurationSection("ammo")) return;

        for (String key : config.getConfigurationSection("ammo").getKeys(false)) {
            String name = config.getString("ammo." + key + ".name", key);
            String materialStr = config.getString("ammo." + key + ".material", "CLAY_BALL");
            Material material = Material.matchMaterial(materialStr);
            if (material == null) material = Material.CLAY_BALL;

            int customModelData = config.getInt("ammo." + key + ".custom_model_data", -1);
            String customBlockId = config.getString("ammo." + key + ".custom_block_id", null);
            List<String> lore = config.getStringList("ammo." + key + ".lore");

            AmmoConfig ammo = new AmmoConfig(key, name, material, customModelData, customBlockId, lore);
            registeredAmmo.put(key, ammo);
        }
    }

    public void saveAmmo() {
        File file = new File(plugin.getDataFolder(), "ammo.yml");
        YamlConfiguration config = new YamlConfiguration();

        for (Map.Entry<String, AmmoConfig> entry : registeredAmmo.entrySet()) {
            String key = entry.getKey();
            AmmoConfig ammo = entry.getValue();

            config.set("ammo." + key + ".name", ammo.name);
            config.set("ammo." + key + ".material", ammo.material.name());
            if (ammo.customModelData != -1) {
                config.set("ammo." + key + ".custom_model_data", ammo.customModelData);
            }
            if (ammo.customBlockId != null && !ammo.customBlockId.isEmpty()) {
                config.set("ammo." + key + ".custom_block_id", ammo.customBlockId);
            }
            if (ammo.lore != null && !ammo.lore.isEmpty()) {
                config.set("ammo." + key + ".lore", ammo.lore);
            }
        }

        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save ammo.yml: " + e.getMessage());
        }
    }

    public Map<String, AmmoConfig> getRegisteredAmmo() {
        return registeredAmmo;
    }

    public void addAmmo(AmmoConfig ammo) {
        registeredAmmo.put(ammo.id, ammo);
        saveAmmo();
    }

    public void removeAmmo(String id) {
        registeredAmmo.remove(id);
        saveAmmo();
    }

    public AmmoConfig findMatchingAmmo(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return null;

        // First check custom block ID
        if (item.hasItemMeta()) {
            NamespacedKey cbKey = new NamespacedKey(plugin, "custom_block_id");
            String cbId = item.getItemMeta().getPersistentDataContainer().get(cbKey, PersistentDataType.STRING);
            if (cbId != null && !cbId.isEmpty()) {
                for (AmmoConfig config : registeredAmmo.values()) {
                    if (cbId.equalsIgnoreCase(config.customBlockId)) {
                        return config;
                    }
                }
            }
        }

        // Then check material & custom model data
        for (AmmoConfig config : registeredAmmo.values()) {
            // If the registered ammo config is designated as a custom block, we should only match by custom block id
            if (config.customBlockId != null && !config.customBlockId.isEmpty()) {
                continue;
            }
            if (item.getType() == config.material) {
                if (config.customModelData == -1) {
                    if (!item.hasItemMeta() || !item.getItemMeta().hasCustomModelData()) {
                        return config;
                    }
                } else {
                    if (item.hasItemMeta() && item.getItemMeta().hasCustomModelData() && item.getItemMeta().getCustomModelData() == config.customModelData) {
                        return config;
                    }
                }
            }
        }
        return null;
    }

    public static class AmmoConfig {
        public final String id;
        public final String name;
        public final Material material;
        public final int customModelData;
        public final String customBlockId;
        /** Optional lore template. Supports placeholders %bde_ammo_current% and %bde_ammo_total%
         *  which are substituted with live counts when the ammo item is shown inside a vehicle/turret storage. */
        public final List<String> lore;

        public AmmoConfig(String id, String name, Material material, int customModelData, String customBlockId) {
            this(id, name, material, customModelData, customBlockId, new ArrayList<>());
        }

        public AmmoConfig(String id, String name, Material material, int customModelData, String customBlockId, List<String> lore) {
            this.id = id;
            this.name = name;
            this.material = material;
            this.customModelData = customModelData;
            this.customBlockId = customBlockId;
            this.lore = lore != null ? lore : new ArrayList<>();
        }
    }
}
