package top.sanscraft.bde.manager;

import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import top.sanscraft.bde.SansCraftBDEPlugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Tracks ammo boxes that have been placed in the world as blocks, so that breaking them returns the
 * exact ammo-box item (with its preserved ammo) instead of the underlying block's normal drop.
 *
 * The placed box is stored as a serialised {@link ItemStack} keyed by block location and persisted to
 * placed_ammo_boxes.yml so it survives restarts.
 */
public class AmmoBoxPlacementManager {

    public static class Placed {
        public final BdeAmmoConfig.PlacementMode mode;
        public final String serializedItem;

        public Placed(BdeAmmoConfig.PlacementMode mode, String serializedItem) {
            this.mode = mode;
            this.serializedItem = serializedItem;
        }
    }

    private final SansCraftBDEPlugin plugin;
    private final Map<String, Placed> placed = new HashMap<>();

    public AmmoBoxPlacementManager(SansCraftBDEPlugin plugin) {
        this.plugin = plugin;
    }

    private static String key(Block block) {
        return block.getWorld().getName() + "," + block.getX() + "," + block.getY() + "," + block.getZ();
    }

    public boolean isPlaced(Block block) {
        return placed.containsKey(key(block));
    }

    public Placed get(Block block) {
        return placed.get(key(block));
    }

    /** Record a placed ammo box (one box item, amount 1). */
    public void place(Block block, BdeAmmoConfig.PlacementMode mode, ItemStack boxItem) {
        ItemStack single = boxItem.clone();
        single.setAmount(1);
        placed.put(key(block), new Placed(mode, BdeAmmoInventoryManager.serializeInventory(new ItemStack[]{single})));
        save();
    }

    /** Remove tracking for a block and return the stored box item (or null). */
    public ItemStack remove(Block block) {
        Placed p = placed.remove(key(block));
        if (p == null) return null;
        save();
        ItemStack[] arr = BdeAmmoInventoryManager.deserializeInventory(p.serializedItem);
        return (arr.length > 0) ? arr[0] : null;
    }

    // ---------------------------------------------------------------- Persistence

    private File file() {
        return new File(plugin.getDataFolder(), "placed_ammo_boxes.yml");
    }

    public void load() {
        placed.clear();
        File f = file();
        if (!f.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
        if (!cfg.isConfigurationSection("placed")) return;
        for (String k : cfg.getConfigurationSection("placed").getKeys(false)) {
            String modeStr = cfg.getString("placed." + k + ".mode", "VANILLA_BLOCK");
            String item = cfg.getString("placed." + k + ".item", "");
            BdeAmmoConfig.PlacementMode mode;
            try {
                mode = BdeAmmoConfig.PlacementMode.valueOf(modeStr);
            } catch (IllegalArgumentException e) {
                mode = BdeAmmoConfig.PlacementMode.VANILLA_BLOCK;
            }
            // Keys are stored with '|' separators in yaml (',' is fine too, but avoid yaml path issues).
            placed.put(k.replace('|', ','), new Placed(mode, item));
        }
    }

    public void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (Map.Entry<String, Placed> e : placed.entrySet()) {
            String yk = e.getKey().replace(',', '|');
            cfg.set("placed." + yk + ".mode", e.getValue().mode.name());
            cfg.set("placed." + yk + ".item", e.getValue().serializedItem);
        }
        try {
            cfg.save(file());
        } catch (IOException ex) {
            plugin.getLogger().severe("Failed to save placed_ammo_boxes.yml: " + ex.getMessage());
        }
    }
}
