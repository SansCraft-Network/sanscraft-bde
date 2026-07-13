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

/**
 * Manages ammo-box storage on vehicles/turrets and the draining of boxes when firing.
 *
 * Storage is a small inventory (vehicle shared 9x3, turret 9x1 by default, configurable) that may
 * only hold ammo-box items. Contents are serialised into the vehicle-root entity's PDC per storage
 * key ("shared" or "subsystem_<index>"). Firing drains matching boxes: the firing turret's own
 * storage first, then the vehicle-shared storage.
 */
public class BdeAmmoInventoryManager {
    private final SansCraftBDEPlugin plugin;

    public BdeAmmoInventoryManager(SansCraftBDEPlugin plugin) {
        this.plugin = plugin;
    }

    // ---------------------------------------------------------------- GUIs

    public void openVehicleAmmoSelector(Player player, ModelInstance instance) {
        BdeModel.VehicleConfig cfg = instance.getModel().getVehicle();
        List<BdeModel.SubsystemConfig> subsystems = cfg != null ? cfg.getSubsystems() : null;
        if (subsystems == null || subsystems.isEmpty()) {
            openAmmoInventory(player, instance, "shared");
            return;
        }

        BdeGuiHolder holder = new BdeGuiHolder(BdeGuiHolder.GuiType.AMMO_EDITOR, instance.getId());
        holder.setExtraData("selector");
        Inventory inv = Bukkit.createInventory(holder, 27, "§8Select Ammo Storage");

        ItemStack pane = createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 27; i++) inv.setItem(i, pane);

        List<String> sharedLore = new ArrayList<>();
        sharedLore.add("§7Shared storage - boxes here feed all turrets.");
        sharedLore.add(" ");
        sharedLore.add("§eClick to open storage.");
        inv.setItem(11, createGuiItem(Material.CHEST, "§6Vehicle Shared Ammo", sharedLore.toArray(new String[0])));

        int slot = 13;
        for (int i = 0; i < subsystems.size(); i++) {
            if (slot >= 16) break;
            BdeModel.SubsystemConfig sub = subsystems.get(i);
            List<String> subLore = new ArrayList<>();
            subLore.add("§7Private storage - boxes here feed only this turret.");
            subLore.add(" ");
            subLore.add("§eClick to open storage.");
            inv.setItem(slot++, createGuiItem(Material.CROSSBOW, "§bTurret: " + sub.getName() + " Ammo", subLore.toArray(new String[0])));
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

        int size = storageSizeFor(storageKey);
        String title;
        if (storageKey.startsWith("subsystem_")) {
            int subIdx = parseIndex(storageKey);
            BdeModel.VehicleConfig cfg = instance.getModel().getVehicle();
            if (cfg != null && subIdx >= 0 && subIdx < cfg.getSubsystems().size()) {
                title = "§8Turret: " + cfg.getSubsystems().get(subIdx).getName() + " Ammo";
            } else {
                title = "§8Turret Ammo";
            }
        } else {
            title = "§8Vehicle Shared Ammo";
        }

        BdeAmmoStorageHolder holder = new BdeAmmoStorageHolder(instance.getId(), storageKey, size);
        Inventory inv = Bukkit.createInventory(holder, size, title);
        inv.setContents(loadAmmoInventoryItems(rootEntity, storageKey, size));
        player.openInventory(inv);
    }

    public void saveAmmoInventory(ModelInstance instance, String storageKey, Inventory inventory) {
        Entity rootEntity = instance.getVehicleRoot();
        if (rootEntity == null) return;
        NamespacedKey key = new NamespacedKey(plugin, "bde_ammo_" + storageKey);
        rootEntity.getPersistentDataContainer().set(key, PersistentDataType.STRING, serializeInventory(inventory.getContents()));
    }

    public ItemStack[] loadAmmoInventoryItems(Entity rootEntity, String storageKey, int size) {
        NamespacedKey key = new NamespacedKey(plugin, "bde_ammo_" + storageKey);
        String data = rootEntity.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (data == null || data.isEmpty()) return new ItemStack[size];
        ItemStack[] items = deserializeInventory(data);
        if (items.length != size) {
            ItemStack[] resized = new ItemStack[size];
            System.arraycopy(items, 0, resized, 0, Math.min(items.length, size));
            return resized;
        }
        return items;
    }

    // ---------------------------------------------------------------- Consumption

    /** Total ammo of the given supplied type available to a turret: its private storage + shared storage. */
    public int countAvailableAmmo(ModelInstance instance, int subsystemIndex, String requiredType) {
        return countStorage(instance, "subsystem_" + subsystemIndex, requiredType)
                + countStorage(instance, "shared", requiredType);
    }

    /** Consume {@code amount} ammo of the given type for a shot. Drains the turret's own storage first,
     *  then the vehicle-shared storage. Atomic: nothing is drained unless enough is available. */
    public boolean tryConsumeAmmo(ModelInstance instance, int subsystemIndex, String requiredType, int amount) {
        if (requiredType == null || requiredType.isEmpty() || amount <= 0) return true;
        if (countAvailableAmmo(instance, subsystemIndex, requiredType) < amount) return false;

        int remaining = drainStorage(instance, "subsystem_" + subsystemIndex, requiredType, amount);
        if (remaining > 0) remaining = drainStorage(instance, "shared", requiredType, remaining);
        return remaining <= 0;
    }

    private int countStorage(ModelInstance instance, String storageKey, String requiredType) {
        Entity root = instance.getVehicleRoot();
        if (root == null) return 0;
        AmmoBoxItems boxes = plugin.getAmmoBoxItems();
        int total = 0;
        for (ItemStack it : loadAmmoInventoryItems(root, storageKey, storageSizeFor(storageKey))) {
            if (it == null || !boxes.isAmmoBox(it)) continue;
            if (requiredType.equalsIgnoreCase(boxes.getSuppliedType(it))) total += boxes.getCurrent(it);
        }
        return total;
    }

    /** Drain up to {@code amount} of the type from one storage, persisting the change. Returns the leftover
     *  amount that could not be drained from this storage. */
    private int drainStorage(ModelInstance instance, String storageKey, String requiredType, int amount) {
        Entity root = instance.getVehicleRoot();
        if (root == null || amount <= 0) return amount;
        AmmoBoxItems boxes = plugin.getAmmoBoxItems();

        ItemStack[] items = loadAmmoInventoryItems(root, storageKey, storageSizeFor(storageKey));
        int remaining = amount;
        boolean changed = false;
        for (ItemStack it : items) {
            if (remaining <= 0) break;
            if (it == null || !boxes.isAmmoBox(it)) continue;
            if (!requiredType.equalsIgnoreCase(boxes.getSuppliedType(it))) continue;
            int cur = boxes.getCurrent(it);
            if (cur <= 0) continue;
            int take = Math.min(remaining, cur);
            boxes.setCurrent(it, cur - take);
            remaining -= take;
            changed = true;
        }
        if (changed) {
            NamespacedKey key = new NamespacedKey(plugin, "bde_ammo_" + storageKey);
            root.getPersistentDataContainer().set(key, PersistentDataType.STRING, serializeInventory(items));
        }
        return remaining;
    }

    // ---------------------------------------------------------------- Helpers

    /** Configured storage size (slots) for a key, clamped to a valid multiple of 9. */
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

    private int parseIndex(String storageKey) {
        try {
            return Integer.parseInt(storageKey.substring("subsystem_".length()));
        } catch (Exception e) {
            return -1;
        }
    }

    private ItemStack createGuiItem(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            List<String> loreList = new ArrayList<>();
            for (String line : lore) loreList.add(line);
            meta.setLore(loreList);
            item.setItemMeta(meta);
        }
        return item;
    }

    public static String serializeInventory(ItemStack[] items) {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream);
            dataOutput.writeInt(items.length);
            for (ItemStack item : items) dataOutput.writeObject(item);
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
            for (int i = 0; i < length; i++) items[i] = (ItemStack) dataInput.readObject();
            dataInput.close();
            return items;
        } catch (Exception e) {
            return new ItemStack[0];
        }
    }
}
