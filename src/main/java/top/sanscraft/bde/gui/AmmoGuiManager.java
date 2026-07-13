package top.sanscraft.bde.gui;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import top.sanscraft.bde.SansCraftBDEPlugin;
import top.sanscraft.bde.manager.BdeAmmoConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * GUI for creating and configuring ammo BOXES (/bde ammo gui). Every box property is editable here.
 */
public class AmmoGuiManager {
    private final SansCraftBDEPlugin plugin;
    private final NamespacedKey catalogKey;

    public AmmoGuiManager(SansCraftBDEPlugin plugin) {
        this.plugin = plugin;
        this.catalogKey = new NamespacedKey(plugin, "ammo_catalog_id");
    }

    // ---------------------------------------------------------------- Catalog

    public void openAmmoCatalog(Player player) {
        BdeGuiHolder holder = new BdeGuiHolder(BdeGuiHolder.GuiType.AMMO_EDITOR, null);
        Inventory inv = Bukkit.createInventory(holder, 54, "§8BDE Ammo Boxes");

        ItemStack pane = createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 54; i++) {
            if (i < 9 || i >= 45 || i % 9 == 0 || i % 9 == 8) inv.setItem(i, pane);
        }

        int slot = 9;
        for (BdeAmmoConfig.AmmoBoxConfig box : plugin.getBdeAmmoConfig().getBoxes().values()) {
            if (slot >= 45) break;
            List<String> lore = new ArrayList<>();
            lore.add("§7Supplies type: §b" + box.suppliedType);
            lore.add("§7Capacity: §e" + box.defaultFill + "§7/§6" + box.maxCapacity);
            lore.add("§7Placeable: " + (box.placeable ? "§aYes §7(" + box.placementMode + ")" : "§cNo"));
            lore.add(" ");
            lore.add("§eLeft/Right-Click: §7Edit");
            lore.add("§eShift-Right-Click: §7Give box");
            lore.add("§eShift-Left-Click: §7Delete");
            ItemStack entry = appearanceIcon(box, "§6" + box.name, lore.toArray(new String[0]));
            stampCatalogId(entry, box.id);
            inv.setItem(slot++, entry);
        }

        inv.setItem(45, createGuiItem(Material.BARRIER, "§cClose Menu"));
        inv.setItem(49, createGuiItem(Material.NETHER_STAR, "§aAdd New Ammo Box",
                "§7Place an item on your cursor, then click here.",
                "§7The box will take that item's appearance."
        ));
        player.openInventory(inv);
    }

    // ---------------------------------------------------------------- Editor

    public void openAmmoEditor(Player player, String boxId) {
        BdeAmmoConfig.AmmoBoxConfig box = plugin.getBdeAmmoConfig().getBox(boxId);
        if (box == null) {
            openAmmoCatalog(player);
            return;
        }

        BdeGuiHolder holder = new BdeGuiHolder(BdeGuiHolder.GuiType.AMMO_EDITOR, null);
        holder.setExtraData(boxId);
        Inventory inv = Bukkit.createInventory(holder, 54, "§8Edit Box: " + box.name);

        ItemStack pane = createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 54; i++) {
            if (i < 9 || i >= 45 || i % 9 == 0 || i % 9 == 8) inv.setItem(i, pane);
        }

        inv.setItem(4, appearanceIcon(box, "§6" + box.name, "§7ID: §f" + box.id, "§7Supplies: §b" + box.suppliedType));

        inv.setItem(10, createGuiItem(Material.NAME_TAG, "§eRename Box",
                "§7Current: §f" + box.name, " ", "§eClick to rename in chat."));

        inv.setItem(11, createGuiItem(Material.HOPPER, "§eSupplied Ammo Type",
                "§7Current: §b" + box.suppliedType,
                "§7A projectile's required ammo must match this.",
                " ", "§eClick to set in chat."));

        inv.setItem(12, createGuiItem(Material.ANVIL, "§bMax Capacity: §e" + box.maxCapacity,
                "§aLeft-Click: +1  §7| §aShift: +10",
                "§cRight-Click: -1  §7| §cShift: -10"));

        inv.setItem(13, createGuiItem(Material.BUCKET, "§bDefault Fill: §e" + box.defaultFill,
                "§7Ammo a freshly-created/given box starts with.",
                "§aLeft-Click: +1  §7| §aShift: +10",
                "§cRight-Click: -1  §7| §cShift: -10"));

        List<String> loreInfo = new ArrayList<>();
        loreInfo.add("§7Shown on the box item.");
        loreInfo.add("§7Placeholders: §b%bde_ammo_current% §7, §b%bde_ammo_max%");
        loreInfo.add(" ");
        if (box.lore == null || box.lore.isEmpty()) loreInfo.add("§8(default lore)");
        else { loreInfo.add("§7Current:"); for (String l : box.lore) loreInfo.add("  §f" + l); }
        loreInfo.add(" ");
        loreInfo.add("§eClick to edit in chat §7(| separates lines, 'clear' resets).");
        inv.setItem(14, createGuiItem(Material.WRITABLE_BOOK, "§eLore Template", loreInfo.toArray(new String[0])));

        inv.setItem(19, createGuiItem(Material.PAINTING, "§eCustom Model Data: §f" + (box.customModelData == -1 ? "None" : box.customModelData),
                "§7Applied to the box item without changing its material.",
                " ", "§eClick to set in chat (or 'clear')."));

        inv.setItem(20, appearanceIcon(box, "§eItem Appearance",
                "§7Material: §f" + box.material.name(),
                "§7Item Model: §f" + (box.itemModel == null ? "None" : box.itemModel),
                "§7CustomModelData: §f" + (box.customModelData == -1 ? "None" : box.customModelData),
                " ",
                "§7Place an item on your cursor and click",
                "§7to copy its material onto this box."));

        inv.setItem(21, createGuiItem(Material.ITEM_FRAME, "§eItem Model",
                "§7Current: §f" + (box.itemModel == null ? "None" : box.itemModel),
                "§7Overrides the visual, e.g. §fminecraft:iron_ingot",
                " ", "§eClick to set in chat (or 'clear')."));

        inv.setItem(22, createGuiItem(box.placeable ? Material.GRASS_BLOCK : Material.BARRIER,
                "§bPlaceable: " + (box.placeable ? "§a§lYES" : "§c§lNO"),
                "§7Whether this box can be placed as a block.",
                "§eClick to toggle."));

        inv.setItem(23, createGuiItem(Material.SCAFFOLDING, "§bPlacement Mode: §e" + box.placementMode,
                "§7NONE, BDE_BLOCK (custom block), or VANILLA_BLOCK.",
                "§eClick to cycle."));

        // Placement target icon reflects what will actually be placed.
        ItemStack targetIcon;
        List<String> targetLore = new ArrayList<>();
        if (box.placementMode == BdeAmmoConfig.PlacementMode.BDE_BLOCK) {
            ItemStack cb = (box.placementBlockId != null)
                    ? plugin.getCustomBlockManager().createCustomBlockItem(box.placementBlockId, 1) : null;
            targetIcon = (cb != null) ? cb : new ItemStack(Material.REPEATING_COMMAND_BLOCK);
            targetLore.add("§7BDE block: §f" + (box.placementBlockId == null ? "None" : box.placementBlockId));
            if (box.placementBlockId != null && cb == null) targetLore.add("§cNo item configured for that block.");
        } else if (box.placementMode == BdeAmmoConfig.PlacementMode.VANILLA_BLOCK) {
            if (box.placementMaterial != null && box.placementMaterial.isItem()) {
                targetIcon = new ItemStack(box.placementMaterial);
                targetLore.add("§7Vanilla block: §f" + box.placementMaterial.name());
            } else {
                targetIcon = new ItemStack(Material.BARRIER);
                targetLore.add("§cThis material doesn't exist.");
            }
        } else {
            targetIcon = new ItemStack(Material.STRUCTURE_VOID);
            targetLore.add("§8(placement disabled)");
        }
        targetLore.add(" ");
        targetLore.add("§eClick to set in chat (a bde block id, or a material name).");
        ItemMeta targetMeta = targetIcon.getItemMeta();
        if (targetMeta != null) {
            targetMeta.setDisplayName("§ePlacement Target");
            targetMeta.setLore(targetLore);
            targetIcon.setItemMeta(targetMeta);
        }
        inv.setItem(24, targetIcon);

        inv.setItem(30, createGuiItem(Material.CHEST_MINECART, "§aGive Me This Box",
                "§7Gives you one box filled to its default."));

        inv.setItem(40, createGuiItem(Material.LAVA_BUCKET, "§cDelete Box"));
        inv.setItem(45, createGuiItem(Material.ARROW, "§7Back to Catalog"));
        inv.setItem(49, createGuiItem(Material.BARRIER, "§cClose Menu"));
        player.openInventory(inv);
    }

    // ---------------------------------------------------------------- Delete confirm

    public void openAmmoDeleteConfirm(Player player, String boxId) {
        BdeAmmoConfig.AmmoBoxConfig box = plugin.getBdeAmmoConfig().getBox(boxId);
        if (box == null) { openAmmoCatalog(player); return; }

        BdeGuiHolder holder = new BdeGuiHolder(BdeGuiHolder.GuiType.AMMO_EDITOR, null);
        holder.setExtraData("confirmdelete:" + boxId);
        Inventory inv = Bukkit.createInventory(holder, 27, "§4Delete box: " + box.name + "?");

        ItemStack pane = createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 27; i++) inv.setItem(i, pane);
        inv.setItem(11, createGuiItem(Material.LIME_WOOL, "§aConfirm Delete", "§7Removes this box definition."));
        inv.setItem(13, createGuiItem(box.material, "§6" + box.name, "§7ID: §f" + box.id));
        inv.setItem(15, createGuiItem(Material.RED_WOOL, "§cCancel", "§7Keep this box."));
        player.openInventory(inv);
    }

    // ---------------------------------------------------------------- Give

    /** Give the player one ammo box filled to its default fill. */
    public void giveAmmoBox(Player player, BdeAmmoConfig.AmmoBoxConfig box) {
        ItemStack item = plugin.getAmmoBoxItems().create(box, box.defaultFill);
        java.util.Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
        for (ItemStack extra : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), extra);
        }
        player.sendMessage("§aGave you ammo box: §f" + box.name + " §7(" + box.defaultFill + "/" + box.maxCapacity + ")");
        player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.2f);
    }

    // ---------------------------------------------------------------- Helpers

    /** Read the box id stamped on a catalog display item (null if not a catalog entry). */
    public String getCatalogId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(catalogKey, PersistentDataType.STRING);
    }

    private void stampCatalogId(ItemStack item, String id) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(catalogKey, PersistentDataType.STRING, id);
            item.setItemMeta(meta);
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

    /** GUI icon that shows the box's true appearance (material swap + item_model + CMD) with a name/lore. */
    private ItemStack appearanceIcon(BdeAmmoConfig.AmmoBoxConfig box, String name, String... lore) {
        ItemStack item = plugin.getAmmoBoxItems().appearanceItem(box);
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
}
