package top.sanscraft.bde.manager;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import top.sanscraft.bde.SansCraftBDEPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Builds and reads ammo-box {@link ItemStack}s.
 *
 * <p>An ammo box carries its state on the item itself (PDC):
 * <ul>
 *   <li>{@code ammobox_id} — the registry id it was created from</li>
 *   <li>{@code ammobox_current} — current ammo in the box</li>
 *   <li>{@code ammobox_max} — max capacity (may be admin-overridden per item)</li>
 *   <li>{@code ammobox_type} — the ammo type this box supplies</li>
 *   <li>{@code ammobox_lore} — the lore template (lines joined by \n) used to re-render lore</li>
 * </ul>
 * The lore is re-rendered from the template on every state change so
 * {@code %bde_ammo_current%} / {@code %bde_ammo_max%} always reflect live values.
 */
public class AmmoBoxItems {

    private final SansCraftBDEPlugin plugin;
    private final NamespacedKey keyId;
    private final NamespacedKey keyCurrent;
    private final NamespacedKey keyMax;
    private final NamespacedKey keyType;
    private final NamespacedKey keyLore;

    public AmmoBoxItems(SansCraftBDEPlugin plugin) {
        this.plugin = plugin;
        this.keyId = new NamespacedKey(plugin, "ammobox_id");
        this.keyCurrent = new NamespacedKey(plugin, "ammobox_current");
        this.keyMax = new NamespacedKey(plugin, "ammobox_max");
        this.keyType = new NamespacedKey(plugin, "ammobox_type");
        this.keyLore = new NamespacedKey(plugin, "ammobox_lore");
    }

    /** Build a fresh ammo-box item for the given definition, filled to {@code current}. */
    public ItemStack create(BdeAmmoConfig.AmmoBoxConfig cfg, int current) {
        ItemStack item;
        if (cfg.itemCustomBlockId != null && !cfg.itemCustomBlockId.isEmpty()
                && plugin.getCustomBlockManager() != null) {
            ItemStack custom = plugin.getCustomBlockManager().createCustomBlockItem(cfg.itemCustomBlockId, 1);
            item = custom != null ? custom : new ItemStack(cfg.material);
        } else {
            item = new ItemStack(cfg.material);
        }

        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(cfg.name != null ? cfg.name : cfg.id);
            if (cfg.customModelData != -1) meta.setCustomModelData(cfg.customModelData);

            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(keyId, PersistentDataType.STRING, cfg.id);
            pdc.set(keyCurrent, PersistentDataType.INTEGER, Math.max(0, current));
            pdc.set(keyMax, PersistentDataType.INTEGER, Math.max(1, cfg.maxCapacity));
            pdc.set(keyType, PersistentDataType.STRING, cfg.suppliedType != null ? cfg.suppliedType : cfg.id);
            List<String> template = (cfg.lore != null && !cfg.lore.isEmpty()) ? cfg.lore : BdeAmmoConfig.defaultLore();
            pdc.set(keyLore, PersistentDataType.STRING, String.join("\n", template));

            meta.setLore(render(template, Math.max(0, current), Math.max(1, cfg.maxCapacity)));
            item.setItemMeta(meta);
        }
        return item;
    }

    public boolean isAmmoBox(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(keyId, PersistentDataType.STRING);
    }

    public String getBoxId(ItemStack item) {
        if (!isAmmoBox(item)) return null;
        return item.getItemMeta().getPersistentDataContainer().get(keyId, PersistentDataType.STRING);
    }

    public int getCurrent(ItemStack item) {
        if (!isAmmoBox(item)) return 0;
        Integer v = item.getItemMeta().getPersistentDataContainer().get(keyCurrent, PersistentDataType.INTEGER);
        return v != null ? v : 0;
    }

    public int getMax(ItemStack item) {
        if (!isAmmoBox(item)) return 0;
        Integer v = item.getItemMeta().getPersistentDataContainer().get(keyMax, PersistentDataType.INTEGER);
        return v != null ? v : 0;
    }

    public String getSuppliedType(ItemStack item) {
        if (!isAmmoBox(item)) return null;
        return item.getItemMeta().getPersistentDataContainer().get(keyType, PersistentDataType.STRING);
    }

    private List<String> getTemplate(ItemStack item) {
        String joined = item.getItemMeta().getPersistentDataContainer().get(keyLore, PersistentDataType.STRING);
        if (joined == null || joined.isEmpty()) return BdeAmmoConfig.defaultLore();
        return new ArrayList<>(Arrays.asList(joined.split("\n", -1)));
    }

    /** Set the current ammo (clamped to >= 0) and re-render lore. Mutates the item in place. */
    public void setCurrent(ItemStack item, int current) {
        if (!isAmmoBox(item)) return;
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        int newCurrent = Math.max(0, current);
        pdc.set(keyCurrent, PersistentDataType.INTEGER, newCurrent);
        Integer max = pdc.get(keyMax, PersistentDataType.INTEGER);
        meta.setLore(render(getTemplate(item), newCurrent, max != null ? max : newCurrent));
        item.setItemMeta(meta);
    }

    /** Set the max capacity (clamped to >= 1) and re-render lore. */
    public void setMax(ItemStack item, int max) {
        if (!isAmmoBox(item)) return;
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        int newMax = Math.max(1, max);
        pdc.set(keyMax, PersistentDataType.INTEGER, newMax);
        Integer cur = pdc.get(keyCurrent, PersistentDataType.INTEGER);
        meta.setLore(render(getTemplate(item), cur != null ? cur : 0, newMax));
        item.setItemMeta(meta);
    }

    /** Set the supplied ammo type. */
    public void setSuppliedType(ItemStack item, String type) {
        if (!isAmmoBox(item)) return;
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(keyType, PersistentDataType.STRING, type);
        item.setItemMeta(meta);
    }

    /** Substitute the ammo placeholders in a lore template. Static + pure for unit testing. */
    public static List<String> render(List<String> template, int current, int max) {
        List<String> out = new ArrayList<>();
        if (template == null) return out;
        for (String line : template) {
            if (line == null) { out.add(""); continue; }
            out.add(line.replace("%bde_ammo_current%", String.valueOf(current))
                        .replace("%bde_ammo_max%", String.valueOf(max)));
        }
        return out;
    }
}
