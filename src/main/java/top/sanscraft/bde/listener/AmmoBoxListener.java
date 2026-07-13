package top.sanscraft.bde.listener;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import top.sanscraft.bde.SansCraftBDEPlugin;
import top.sanscraft.bde.manager.AmmoBoxPlacementManager;
import top.sanscraft.bde.manager.BdeAmmoConfig;

/**
 * Handles placing ammo boxes as blocks and returning the box item (with its ammo) when broken.
 */
public class AmmoBoxListener implements Listener {
    private final SansCraftBDEPlugin plugin;

    public AmmoBoxListener(SansCraftBDEPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlaceAmmoBox(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getClickedBlock() == null) return;

        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        if (!plugin.getAmmoBoxItems().isAmmoBox(item)) return;

        String boxId = plugin.getAmmoBoxItems().getBoxId(item);
        BdeAmmoConfig.AmmoBoxConfig cfg = plugin.getBdeAmmoConfig().getBox(boxId);
        if (cfg == null || !cfg.placeable || cfg.placementMode == BdeAmmoConfig.PlacementMode.NONE) {
            return; // not a placeable box - leave normal interaction alone
        }

        // We're going to place; stop the item's default interaction.
        event.setCancelled(true);

        Block target = event.getClickedBlock().getRelative(event.getBlockFace());
        if (!target.getType().isAir() && !target.isReplaceable()) {
            player.sendMessage("§cNot enough space to place the ammo box there.");
            return;
        }

        if (cfg.placementMode == BdeAmmoConfig.PlacementMode.BDE_BLOCK) {
            if (cfg.placementBlockId == null
                    || plugin.getCustomBlockManager().getConfig(cfg.placementBlockId) == null) {
                player.sendMessage("§cThis ammo box's BDE placement block is not configured.");
                return;
            }
            target.setType(Material.BARRIER);
            plugin.getCustomBlockManager().placeBlock(target, cfg.placementBlockId);
        } else { // VANILLA_BLOCK
            target.setType(cfg.placementMaterial);
        }

        plugin.getAmmoBoxPlacementManager().place(target, cfg.placementMode, item);

        // Consume one box from the player's hand.
        if (item.getAmount() > 1) {
            item.setAmount(item.getAmount() - 1);
        } else {
            player.getInventory().setItemInMainHand(null);
        }

        player.playSound(target.getLocation(), org.bukkit.Sound.BLOCK_STONE_PLACE, 1.0f, 1.0f);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onBreakAmmoBox(BlockBreakEvent event) {
        Block block = event.getBlock();
        AmmoBoxPlacementManager mgr = plugin.getAmmoBoxPlacementManager();
        AmmoBoxPlacementManager.Placed placed = mgr.get(block);
        if (placed == null) return;

        // Don't drop the raw block; return the ammo box item instead.
        event.setDropItems(false);

        // Clean up the custom-block display (also removes it from CustomBlockManager tracking so its
        // own break listener won't double-handle this block).
        if (placed.mode == BdeAmmoConfig.PlacementMode.BDE_BLOCK) {
            plugin.getCustomBlockManager().breakBlock(block);
        }

        ItemStack box = mgr.remove(block);
        if (box != null) {
            block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), box);
        }
    }
}
