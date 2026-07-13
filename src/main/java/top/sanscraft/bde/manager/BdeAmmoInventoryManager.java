package top.sanscraft.bde.manager;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import top.sanscraft.bde.SansCraftBDEPlugin;
import top.sanscraft.bde.gui.BdeAmmoStorageHolder;
import top.sanscraft.bde.gui.BdeGuiHolder;
import top.sanscraft.bde.model.BdeModel;
import top.sanscraft.bde.model.ModelInstance;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public class BdeAmmoInventoryManager {
    private final SansCraftBDEPlugin plugin;

    public BdeAmmoInventoryManager(SansCraftBDEPlugin plugin) {
        this.plugin = plugin;
    }

    public void openVehicleAmmoSelector(Player player, ModelInstance instance) {
        BdeModel model = instance.getModel();
        BdeModel.VehicleConfig cfg = model.getVehicle();
        
        // Count subsystems with ammo or turrets
        List<BdeModel.SubsystemConfig> subsystems = cfg != null ? cfg.getSubsystems() : null;
        if (subsystems == null || subsystems.isEmpty()) {
            // No subsystems, directly open vehicle shared ammo inventory
            openAmmoInventory(player, instance, "shared");
            return;
        }

        BdeGuiHolder holder = new BdeGuiHolder(BdeGuiHolder.GuiType.AMMO_EDITOR, instance.getId());
        holder.setExtraData("selector");
        Inventory inv = Bukkit.createInventory(holder, 27, "§8Select Ammo Storage");

        ItemStack pane = createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 27; i++) {
            inv.setItem(i, pane);
        }

        // Slot 11: Vehicle Shared Ammo
        List<String> sharedLore = new ArrayList<>();
        sharedLore.add("§7Shared storage accessible by all turrets.");
        sharedLore.add(" ");
        sharedLore.add("§eClick to open storage.");
        inv.setItem(11, createGuiItem(Material.CHEST, "§6Vehicle Shared Ammo (9x3)", sharedLore.toArray(new String[0])));

        // Slot 13-15: Subsystems/Turrets specific ammo
        int slot = 13;
        for (int i = 0; i < subsystems.size(); i++) {
            if (slot >= 16) break;
            BdeModel.SubsystemConfig sub = subsystems.get(i);
            List<String> subLore = new ArrayList<>();
            subLore.add("§7Specific ammo only accessible by this turret.");
            subLore.add(" ");
            subLore.add("§eClick to open storage.");
            inv.setItem(slot++, createGuiItem(Material.CROSSBOW, "§bTurret: " + sub.getName() + " Ammo (9x1)", subLore.toArray(new String[0])));
        }

        inv.setItem(22, createGuiItem(Material.BARRIER, "§cClose Menu"));
        player.openInventory(inv);
    }

    public void openAmmoInventory(Player player, ModelInstance instance, String storageKey) {
        Entity rootEntity = instance.getVehicleRoot();
        if (rootEntity == null) {
            player.sendMessage("§cError: Vehicle has no physical root!");
            return;
        }

        int size = 27; // Default vehicle shared ammo size (9x3)
        String title = "§8Vehicle Shared Ammo";
        if (storageKey.startsWith("subsystem_")) {
            size = plugin.getConfig().getInt("ammo.storage-size.turret", 9);
            int subIdx = Integer.parseInt(storageKey.substring(10));
            BdeModel.VehicleConfig cfg = instance.getModel().getVehicle();
            if (cfg != null && subIdx >= 0 && subIdx < cfg.getSubsystems().size()) {
                title = "§8Turret: " + cfg.getSubsystems().get(subIdx).getName() + " Ammo";
            } else {
                title = "§8Turret Ammo";
            }
        } else {
            size = plugin.getConfig().getInt("ammo.storage-size.vehicle", 27);
        }

        // Ensure size is valid multiple of 9
        if (size <= 0 || size % 9 != 0) {
            size = storageKey.equals("shared") ? 27 : 9;
        }

        BdeAmmoStorageHolder holder = new BdeAmmoStorageHolder(instance.getId(), storageKey, size);
        Inventory inv = Bukkit.createInventory(holder, size, title);

        // Load items from entity PDC
        ItemStack[] items = loadAmmoInventoryItems(rootEntity, storageKey, size);
        inv.setContents(items);

        // Overlay live ammo-count lore placeholders for display
        applyDisplayLore(inv, instance, storageKey);

        player.openInventory(inv);
    }

    public void saveAmmoInventory(ModelInstance instance, String storageKey, Inventory inventory) {
        Entity rootEntity = instance.getVehicleRoot();
        if (rootEntity == null) return;

        NamespacedKey key = new NamespacedKey(plugin, "bde_ammo_" + storageKey);
        String data = serializeInventory(inventory.getContents());
        rootEntity.getPersistentDataContainer().set(key, PersistentDataType.STRING, data);
    }

    public ItemStack[] loadAmmoInventoryItems(Entity rootEntity, String storageKey, int size) {
        NamespacedKey key = new NamespacedKey(plugin, "bde_ammo_" + storageKey);
        String data = rootEntity.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (data == null || data.isEmpty()) {
            return new ItemStack[size];
        }
        ItemStack[] items = deserializeInventory(data);
        if (items.length != size) {
            ItemStack[] resized = new ItemStack[size];
            System.arraycopy(items, 0, resized, 0, Math.min(items.length, size));
            return resized;
        }
        return items;
    }

    /** Resolve the configured storage size (slots) for a storage key, clamped to a valid multiple of 9. */
    public int storageSizeFor(String storageKey) {
        int size;
        if (storageKey != null && storageKey.startsWith("subsystem_")) {
            size = plugin.getConfig().getInt("ammo.storage-size.turret", 9);
            if (size <= 0 || size % 9 != 0) size = 9;
        } else {
            size = plugin.getConfig().getInt("ammo.storage-size.vehicle", 27);
            if (size <= 0 || size % 9 != 0) size = 27;
        }
        return size;
    }

    /** Count how many units of the given ammo type are stored in a single storage (shared or subsystem_<idx>). */
    public int countAmmo(ModelInstance instance, String storageKey, BdeAmmoConfig.AmmoConfig ammo) {
        Entity root = instance.getVehicleRoot();
        if (root == null || ammo == null) return 0;
        ItemStack[] items = loadAmmoInventoryItems(root, storageKey, storageSizeFor(storageKey));
        int total = 0;
        for (ItemStack it : items) {
            if (it == null) continue;
            BdeAmmoConfig.AmmoConfig match = plugin.getBdeAmmoConfig().findMatchingAmmo(it);
            if (match != null && match.id.equals(ammo.id)) total += it.getAmount();
        }
        return total;
    }

    /** Total available ammo of a type accessible to a turret: its private storage + the vehicle-shared storage. */
    public int countAvailableAmmo(ModelInstance instance, int subsystemIndex, BdeAmmoConfig.AmmoConfig ammo) {
        return countAmmo(instance, "subsystem_" + subsystemIndex, ammo)
                + countAmmo(instance, "shared", ammo);
    }

    /** Remove up to {@code amount} units of the ammo type from a single storage, persisting the result.
     *  Returns true only if the full amount was consumed (atomic: nothing removed unless enough is present). */
    public boolean consumeFromStorage(ModelInstance instance, String storageKey, BdeAmmoConfig.AmmoConfig ammo, int amount) {
        Entity root = instance.getVehicleRoot();
        if (root == null || ammo == null || amount <= 0) return false;
        ItemStack[] items = loadAmmoInventoryItems(root, storageKey, storageSizeFor(storageKey));

        int total = 0;
        for (ItemStack it : items) {
            if (it == null) continue;
            BdeAmmoConfig.AmmoConfig match = plugin.getBdeAmmoConfig().findMatchingAmmo(it);
            if (match != null && match.id.equals(ammo.id)) total += it.getAmount();
        }
        if (total < amount) return false;

        int remaining = amount;
        for (int i = 0; i < items.length && remaining > 0; i++) {
            ItemStack it = items[i];
            if (it == null) continue;
            BdeAmmoConfig.AmmoConfig match = plugin.getBdeAmmoConfig().findMatchingAmmo(it);
            if (match == null || !match.id.equals(ammo.id)) continue;
            int take = Math.min(remaining, it.getAmount());
            it.setAmount(it.getAmount() - take);
            remaining -= take;
            if (it.getAmount() <= 0) items[i] = null;
        }

        NamespacedKey key = new NamespacedKey(plugin, "bde_ammo_" + storageKey);
        root.getPersistentDataContainer().set(key, PersistentDataType.STRING, serializeInventory(items));
        return true;
    }

    /** Consume ammo for a turret shot: draw from the turret's private storage first, then the vehicle-shared pool.
     *  Returns true if the shot's ammo was fully paid for. */
    public boolean tryConsumeAmmo(ModelInstance instance, int subsystemIndex, BdeAmmoConfig.AmmoConfig ammo, int amount) {
        if (ammo == null || amount <= 0) return true; // no ammo requirement
        if (consumeFromStorage(instance, "subsystem_" + subsystemIndex, ammo, amount)) return true;
        return consumeFromStorage(instance, "shared", ammo, amount);
    }

    /** Count units of an ammo type currently sitting in an open inventory (live, not PDC). */
    public int countInInventory(Inventory inv, String ammoId) {
        int total = 0;
        for (ItemStack it : inv.getContents()) {
            if (it == null) continue;
            BdeAmmoConfig.AmmoConfig match = plugin.getBdeAmmoConfig().findMatchingAmmo(it);
            if (match != null && match.id.equals(ammoId)) total += it.getAmount();
        }
        return total;
    }

    /** Count an ammo type across every ammo storage on this vehicle (shared + all turret storages), read from PDC. */
    public int countAmmoAcrossVehicle(ModelInstance instance, BdeAmmoConfig.AmmoConfig ammo) {
        int total = countAmmo(instance, "shared", ammo);
        BdeModel.VehicleConfig cfg = instance.getModel().getVehicle();
        if (cfg != null && cfg.getSubsystems() != null) {
            for (int i = 0; i < cfg.getSubsystems().size(); i++) {
                total += countAmmo(instance, "subsystem_" + i, ammo);
            }
        }
        return total;
    }

    /** Overlay each ammo item's configured lore template for display inside an open storage GUI,
     *  substituting %bde_ammo_current% (this storage) and %bde_ammo_total% (whole vehicle).
     *  Items whose ammo type has no lore template are left untouched. */
    public void applyDisplayLore(Inventory inv, ModelInstance instance, String storageKey) {
        for (ItemStack it : inv.getContents()) {
            if (it == null) continue;
            BdeAmmoConfig.AmmoConfig ammo = plugin.getBdeAmmoConfig().findMatchingAmmo(it);
            if (ammo == null || ammo.lore == null || ammo.lore.isEmpty()) continue;
            int current = countInInventory(inv, ammo.id);
            // total = every other storage's PDC count + this storage's live count
            int total = countAmmoAcrossVehicle(instance, ammo) - countAmmo(instance, storageKey, ammo) + current;
            setLore(it, substitutePlaceholders(ammo.lore, current, total));
        }
    }

    /** Reset stored ammo items' lore back to the raw template (placeholders intact) so the canonical
     *  stored form is stable and identical stacks merge. Called before persisting on storage close. */
    public void normalizeStorageLore(Inventory inv) {
        for (ItemStack it : inv.getContents()) {
            if (it == null) continue;
            BdeAmmoConfig.AmmoConfig ammo = plugin.getBdeAmmoConfig().findMatchingAmmo(it);
            if (ammo == null || ammo.lore == null || ammo.lore.isEmpty()) continue;
            setLore(it, new ArrayList<>(ammo.lore));
        }
    }

    /** Substitute the ammo-count placeholders in a lore template. Pure/static so it is unit-testable. */
    public static List<String> substitutePlaceholders(List<String> template, int current, int total) {
        List<String> out = new ArrayList<>();
        if (template == null) return out;
        for (String line : template) {
            if (line == null) { out.add(""); continue; }
            out.add(line.replace("%bde_ammo_current%", String.valueOf(current))
                        .replace("%bde_ammo_total%", String.valueOf(total)));
        }
        return out;
    }

    private void setLore(ItemStack item, List<String> lore) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.setLore(lore);
        item.setItemMeta(meta);
    }

    public static String serializeInventory(ItemStack[] items) {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream);
            dataOutput.writeInt(items.length);
            for (ItemStack item : items) {
                dataOutput.writeObject(item);
            }
            dataOutput.close();
            return Base64.getEncoder().encodeToString(outputStream.toByteArray());
        } catch (Exception e) {
            return "";
        }
    }

    public static ItemStack[] deserializeInventory(String data) {
        try {
            ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64.getDecoder().decode(data));
            BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream);
            int length = dataInput.readInt();
            ItemStack[] items = new ItemStack[length];
            for (int i = 0; i < length; i++) {
                items[i] = (ItemStack) dataInput.readObject();
            }
            dataInput.close();
            return items;
        } catch (Exception e) {
            return new ItemStack[0];
        }
    }

    private ItemStack createGuiItem(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            List<String> loreList = new ArrayList<>();
            for (String line : lore) {
                loreList.add(line);
            }
            meta.setLore(loreList);
            item.setItemMeta(meta);
        }
        return item;
    }
}
