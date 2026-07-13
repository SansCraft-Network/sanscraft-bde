package top.sanscraft.bde.gui;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import top.sanscraft.bde.SansCraftBDEPlugin;
import top.sanscraft.bde.manager.BdeRepairConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class RepairGuiManager {
    private final SansCraftBDEPlugin plugin;

    public RepairGuiManager(SansCraftBDEPlugin plugin) {
        this.plugin = plugin;
    }

    public void openRepairCatalog(Player player) {
        BdeGuiHolder holder = new BdeGuiHolder(BdeGuiHolder.GuiType.REPAIR_EDITOR, null);
        Inventory inv = Bukkit.createInventory(holder, 54, "§8BDE Repair Tools Config");

        ItemStack pane = createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 54; i++) {
            if (i < 9 || i >= 45 || i % 9 == 0 || i % 9 == 8) {
                inv.setItem(i, pane);
            }
        }

        int slot = 9;
        for (BdeRepairConfig.RepairToolConfig tool : plugin.getBdeRepairConfig().getRegisteredTools().values()) {
            if (slot >= 45) break;
            
            List<String> lore = new ArrayList<>();
            lore.add("§7Material: §f" + tool.material.name());
            lore.add("§7Amount: §e+" + tool.repairAmount + " HP");
            lore.add("§7Cooldown: §b" + tool.cooldown + "s");
            lore.add("§7Delay: §d" + tool.repairDelay + "s");
            lore.add("§7Visual Cooldown: §6" + (tool.visualCooldown ? "Yes" : "No"));
            if (!tool.repairCost.isEmpty()) {
                lore.add("§7Costs:");
                for (var costEntry : tool.repairCost.entrySet()) {
                    lore.add("  §7- §f" + costEntry.getValue() + "x " + costEntry.getKey().name());
                }
            } else {
                lore.add("§7Cost: §aConsumes itself");
            }
            lore.add(" ");
            lore.add("§eClick to edit settings.");

            inv.setItem(slot++, createGuiItem(tool.material, "§6Tool: " + tool.name, lore.toArray(new String[0])));
        }

        inv.setItem(45, createGuiItem(Material.BARRIER, "§cClose Menu"));
        inv.setItem(49, createGuiItem(Material.NETHER_STAR, "§aAdd New Repair Tool", 
                "§7Right-click with an item on your cursor",
                "§7over this star to register it as a tool."
        ));

        player.openInventory(inv);
    }

    public void openRepairEditor(Player player, String toolId) {
        BdeRepairConfig.RepairToolConfig tool = plugin.getBdeRepairConfig().getRegisteredTools().get(toolId);
        if (tool == null) {
            openRepairCatalog(player);
            return;
        }

        BdeGuiHolder holder = new BdeGuiHolder(BdeGuiHolder.GuiType.REPAIR_EDITOR, null);
        holder.setExtraData(toolId);
        Inventory inv = Bukkit.createInventory(holder, 54, "§8Edit: " + tool.name);

        ItemStack pane = createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 54; i++) {
            if (i < 9 || i >= 45 || i % 9 == 0 || i % 9 == 8) {
                inv.setItem(i, pane);
            }
        }

        inv.setItem(13, createGuiItem(tool.material, "§6" + tool.name, 
                "§7ID: §f" + tool.id,
                "§7Material: §f" + tool.material.name()
        ));

        inv.setItem(20, createGuiItem(Material.GOLDEN_APPLE, "§eRepair Amount: §6" + tool.repairAmount + " HP",
                "§7Amount of health restored.",
                " ",
                "§aLeft-Click to increase by 5",
                "§cRight-Click to decrease by 5"
        ));

        inv.setItem(22, createGuiItem(Material.CLOCK, "§bCooldown: §e" + tool.cooldown + "s",
                "§7Seconds between repair attempts.",
                " ",
                "§aLeft-Click to increase by 0.5s",
                "§cRight-Click to decrease by 0.5s"
        ));

        inv.setItem(24, createGuiItem(Material.REPEATER, "§dRepair Delay: §e" + tool.repairDelay + "s",
                "§7Time player must stand still to repair.",
                "§7Set to 0s for instant repairs.",
                " ",
                "§aLeft-Click to increase by 0.5s",
                "§cRight-Click to decrease by 0.5s"
        ));

        List<String> costLore = new ArrayList<>();
        costLore.add("§7Required materials to execute a repair:");
        if (!tool.repairCost.isEmpty()) {
            for (var entry : tool.repairCost.entrySet()) {
                costLore.add("  §7- §f" + entry.getValue() + "x " + entry.getKey().name());
            }
        } else {
            costLore.add("  §aConsumes itself");
        }
        costLore.add(" ");
        costLore.add("§aLeft-Click with an item on cursor to ADD/INCREASE cost");
        costLore.add("§cRight-Click with an item on cursor to DECREASE/REMOVE cost");
        inv.setItem(30, createGuiItem(Material.CHEST, "§6Configure Cost", costLore.toArray(new String[0])));

        inv.setItem(32, createGuiItem(Material.REDSTONE_LAMP, "§6Visual Cooldown: " + (tool.visualCooldown ? "§aENABLED" : "§cDISABLED"),
                "§7Toggle Minecraft cooldown overlay on item.",
                " ",
                "§eClick to toggle."
        ));

        inv.setItem(40, createGuiItem(Material.LAVA_BUCKET, "§cDelete Tool"));
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
