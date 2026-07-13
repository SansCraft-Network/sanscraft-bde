package top.sanscraft.bde.gui;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import top.sanscraft.bde.SansCraftBDEPlugin;
import top.sanscraft.bde.manager.BdeAmmoConfig;

import java.util.ArrayList;
import java.util.List;

public class AmmoGuiManager {
    private final SansCraftBDEPlugin plugin;

    public AmmoGuiManager(SansCraftBDEPlugin plugin) {
        this.plugin = plugin;
    }

    public void openAmmoCatalog(Player player) {
        BdeGuiHolder holder = new BdeGuiHolder(BdeGuiHolder.GuiType.AMMO_EDITOR, null);
        Inventory inv = Bukkit.createInventory(holder, 54, "§8BDE Ammo Registry");

        ItemStack pane = createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 54; i++) {
            if (i < 9 || i >= 45 || i % 9 == 0 || i % 9 == 8) {
                inv.setItem(i, pane);
            }
        }

        int slot = 9;
        for (BdeAmmoConfig.AmmoConfig ammo : plugin.getBdeAmmoConfig().getRegisteredAmmo().values()) {
            if (slot >= 45) break;

            List<String> lore = new ArrayList<>();
            lore.add("§7Material: §f" + ammo.material.name());
            if (ammo.customModelData != -1) {
                lore.add("§7CustomModelData: §e" + ammo.customModelData);
            }
            if (ammo.customBlockId != null && !ammo.customBlockId.isEmpty()) {
                lore.add("§7Custom Block ID: §b" + ammo.customBlockId);
            }
            lore.add(" ");
            lore.add("§eClick to edit settings.");

            inv.setItem(slot++, createGuiItem(ammo.material, "§6Ammo: " + ammo.name, lore.toArray(new String[0])));
        }

        inv.setItem(45, createGuiItem(Material.BARRIER, "§cClose Menu"));
        inv.setItem(49, createGuiItem(Material.NETHER_STAR, "§aAdd New Ammo Type", 
                "§7Right-click with an item on your cursor",
                "§7over this star to register it as ammo."
        ));

        player.openInventory(inv);
    }

    public void openAmmoEditor(Player player, String ammoId) {
        BdeAmmoConfig.AmmoConfig ammo = plugin.getBdeAmmoConfig().getRegisteredAmmo().get(ammoId);
        if (ammo == null) {
            openAmmoCatalog(player);
            return;
        }

        BdeGuiHolder holder = new BdeGuiHolder(BdeGuiHolder.GuiType.AMMO_EDITOR, null);
        holder.setExtraData(ammoId);
        Inventory inv = Bukkit.createInventory(holder, 54, "§8Edit Ammo: " + ammo.name);

        ItemStack pane = createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 54; i++) {
            if (i < 9 || i >= 45 || i % 9 == 0 || i % 9 == 8) {
                inv.setItem(i, pane);
            }
        }

        inv.setItem(13, createGuiItem(ammo.material, "§6" + ammo.name, 
                "§7ID: §f" + ammo.id,
                "§7Material: §f" + ammo.material.name()
        ));

        inv.setItem(20, createGuiItem(Material.PAPER, "§eAttributes Info",
                "§7Material: §f" + ammo.material.name(),
                "§7CustomModelData: §f" + (ammo.customModelData == -1 ? "None" : ammo.customModelData),
                "§7Custom Block ID: §f" + (ammo.customBlockId == null || ammo.customBlockId.isEmpty() ? "None" : ammo.customBlockId)
        ));

        // Lore template (issue #6). Placeholders resolve live inside vehicle/turret ammo storage.
        List<String> loreInfo = new ArrayList<>();
        loreInfo.add("§7Shown on the ammo item inside storage.");
        loreInfo.add("§7Placeholders: §b%bde_ammo_current% §7and §b%bde_ammo_total%");
        loreInfo.add(" ");
        if (ammo.lore == null || ammo.lore.isEmpty()) {
            loreInfo.add("§8(no custom lore set)");
        } else {
            loreInfo.add("§7Current template:");
            for (String line : ammo.lore) loreInfo.add("  §f" + line);
        }
        loreInfo.add(" ");
        loreInfo.add("§eClick to edit lore in chat.");
        inv.setItem(24, createGuiItem(Material.WRITABLE_BOOK, "§eLore Template", loreInfo.toArray(new String[0])));

        inv.setItem(31, createGuiItem(Material.SHEARS, "§cClear Lore Template",
                "§7Removes the custom lore for this ammo type."
        ));

        inv.setItem(40, createGuiItem(Material.LAVA_BUCKET, "§cDelete Ammo"));
        inv.setItem(45, createGuiItem(Material.ARROW, "§7Back to Catalog"));
        inv.setItem(49, createGuiItem(Material.BARRIER, "§cClose Menu"));

        player.openInventory(inv);
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
