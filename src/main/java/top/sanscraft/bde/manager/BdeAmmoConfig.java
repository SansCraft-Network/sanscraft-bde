package top.sanscraft.bde.manager;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import top.sanscraft.bde.SansCraftBDEPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Registry of ammo BOX definitions (configured via /bde ammo gui, persisted in ammo.yml).
 *
 * An ammo box is an item that internally holds a quantity of ammo (current/max) of a supplied
 * "type". Boxes are loaded into a vehicle/turret ammo storage and drained when firing. Their
 * remaining ammo is stored on the item (PDC) and reflected in the lore via placeholders.
 *
 * This replaces the previous "loose ammo item + count" model.
 */
public class BdeAmmoConfig {

    public enum PlacementMode { NONE, BDE_BLOCK, VANILLA_BLOCK }

    private final SansCraftBDEPlugin plugin;
    private final Map<String, AmmoBoxConfig> boxes = new LinkedHashMap<>();

    public BdeAmmoConfig(SansCraftBDEPlugin plugin) {
        this.plugin = plugin;
    }

    /** Default lore applied to newly-created boxes. Placeholders resolve live on the item. */
    public static List<String> defaultLore() {
        return new ArrayList<>(Arrays.asList(
                "§8» §7Ammo Box",
                "§7Ammo: §e%bde_ammo_current%§7/§6%bde_ammo_max%"
        ));
    }

    public void loadAmmo() {
        boxes.clear();
        File file = new File(plugin.getDataFolder(), "ammo.yml");
        if (!file.exists()) {
            try {
                file.getParentFile().mkdirs();
                file.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().severe("Failed to create ammo.yml: " + e.getMessage());
            }
        }

        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        if (!cfg.isConfigurationSection("ammo")) return;

        for (String key : cfg.getConfigurationSection("ammo").getKeys(false)) {
            String base = "ammo." + key + ".";
            AmmoBoxConfig box = new AmmoBoxConfig(key);
            box.name = cfg.getString(base + "name", key);

            Material mat = Material.matchMaterial(cfg.getString(base + "material", "BUNDLE"));
            box.material = mat != null ? mat : Material.BUNDLE;
            box.customModelData = cfg.getInt(base + "custom_model_data", -1);
            box.itemCustomBlockId = emptyToNull(cfg.getString(base + "item_custom_block_id", null));

            box.suppliedType = cfg.getString(base + "supplied_type", key);
            box.maxCapacity = Math.max(1, cfg.getInt(base + "max_capacity", 64));
            box.defaultFill = Math.max(0, cfg.getInt(base + "default_fill", box.maxCapacity));

            List<String> lore = cfg.getStringList(base + "lore");
            box.lore = (lore == null || lore.isEmpty()) ? defaultLore() : lore;

            box.placeable = cfg.getBoolean(base + "placeable", false);
            try {
                box.placementMode = PlacementMode.valueOf(cfg.getString(base + "placement_mode", "NONE").toUpperCase());
            } catch (IllegalArgumentException e) {
                box.placementMode = PlacementMode.NONE;
            }
            box.placementBlockId = emptyToNull(cfg.getString(base + "placement_block_id", null));
            Material pmat = Material.matchMaterial(cfg.getString(base + "placement_material", "IRON_BLOCK"));
            box.placementMaterial = pmat != null ? pmat : Material.IRON_BLOCK;

            boxes.put(key, box);
        }
    }

    public void saveAmmo() {
        File file = new File(plugin.getDataFolder(), "ammo.yml");
        YamlConfiguration cfg = new YamlConfiguration();

        for (Map.Entry<String, AmmoBoxConfig> entry : boxes.entrySet()) {
            String base = "ammo." + entry.getKey() + ".";
            AmmoBoxConfig b = entry.getValue();

            cfg.set(base + "name", b.name);
            cfg.set(base + "material", b.material.name());
            if (b.customModelData != -1) cfg.set(base + "custom_model_data", b.customModelData);
            if (b.itemCustomBlockId != null && !b.itemCustomBlockId.isEmpty()) {
                cfg.set(base + "item_custom_block_id", b.itemCustomBlockId);
            }
            cfg.set(base + "supplied_type", b.suppliedType);
            cfg.set(base + "max_capacity", b.maxCapacity);
            cfg.set(base + "default_fill", b.defaultFill);
            if (b.lore != null && !b.lore.isEmpty()) cfg.set(base + "lore", b.lore);
            cfg.set(base + "placeable", b.placeable);
            cfg.set(base + "placement_mode", b.placementMode.name());
            if (b.placementBlockId != null && !b.placementBlockId.isEmpty()) {
                cfg.set(base + "placement_block_id", b.placementBlockId);
            }
            cfg.set(base + "placement_material", b.placementMaterial.name());
        }

        try {
            cfg.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save ammo.yml: " + e.getMessage());
        }
    }

    public Map<String, AmmoBoxConfig> getBoxes() { return boxes; }

    public AmmoBoxConfig getBox(String id) { return id == null ? null : boxes.get(id); }

    public void addBox(AmmoBoxConfig box) {
        boxes.put(box.id, box);
        saveAmmo();
    }

    public void removeBox(String id) {
        boxes.remove(id);
        saveAmmo();
    }

    /** Distinct supplied types across all boxes (used for projectile ammo-type selection). */
    public Set<String> getSuppliedTypes() {
        TreeSet<String> set = new TreeSet<>();
        for (AmmoBoxConfig b : boxes.values()) {
            if (b.suppliedType != null && !b.suppliedType.isEmpty()) set.add(b.suppliedType);
        }
        return set;
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }

    public static class AmmoBoxConfig {
        public String id;
        public String name;
        public Material material = Material.BUNDLE;
        public int customModelData = -1;
        /** Optional bde custom block whose configured item is used for this box's appearance. */
        public String itemCustomBlockId;
        /** Ammo category this box supplies; a projectile's required ammoType must equal this. */
        public String suppliedType;
        public int maxCapacity = 64;
        public int defaultFill = 64;
        public List<String> lore = new ArrayList<>();
        public boolean placeable = false;
        public PlacementMode placementMode = PlacementMode.NONE;
        /** bde custom block id used when placementMode == BDE_BLOCK. */
        public String placementBlockId;
        /** vanilla block material used when placementMode == VANILLA_BLOCK. */
        public Material placementMaterial = Material.IRON_BLOCK;

        public AmmoBoxConfig(String id) {
            this.id = id;
            this.suppliedType = id;
        }
    }
}
