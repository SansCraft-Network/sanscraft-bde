package top.sanscraft.bde.listener;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.ItemStack;
import top.sanscraft.bde.SansCraftBDEPlugin;
import top.sanscraft.bde.manager.CustomBlockManager;

public class CustomBlockListener implements Listener {
    private final SansCraftBDEPlugin plugin;
    private final CustomBlockManager customBlockManager;

    public CustomBlockListener(SansCraftBDEPlugin plugin) {
        this.plugin = plugin;
        this.customBlockManager = plugin.getCustomBlockManager();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();
        String customBlockId = customBlockManager.getCustomBlockIdFromItem(item);
        if (customBlockId == null) return;

        Block block = event.getBlockPlaced();
        customBlockManager.placeBlock(block, customBlockId);
        
        Player player = event.getPlayer();
        player.sendMessage("§aYou placed a §f" + customBlockManager.getConfig(customBlockId).displayName + "§a!");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!customBlockManager.isCustomBlock(block)) return;

        String blockId = customBlockManager.getPlacedBlockId(block);
        if (blockId == null) return;

        CustomBlockManager.CustomBlockConfig config = customBlockManager.getConfig(blockId);
        if (config == null) return;

        Player player = event.getPlayer();

        // Cancel default drops (we're breaking a barrier block)
        event.setDropItems(false);

        // Break block in manager (removes display model)
        customBlockManager.breakBlock(block);

        // Drop custom block item if player is not in Creative mode
        if (player.getGameMode() != GameMode.CREATIVE) {
            ItemStack dropItem = customBlockManager.createCustomBlockItem(blockId, 1, block.getType());
            block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), dropItem);
        }

        // Play version-safe block break effects
        Location loc = block.getLocation().add(0.5, 0.5, 0.5);
        loc.getWorld().playSound(loc, Sound.BLOCK_STONE_BREAK, 1.0f, 1.0f);
        loc.getWorld().spawnParticle(Particle.CLOUD, loc, 10, 0.2, 0.2, 0.2, 0.05);

        player.sendMessage("§cYou broke a §f" + config.displayName + "§c!");
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        customBlockManager.handleChunkLoad(
                event.getWorld().getName(), 
                event.getChunk().getX(), 
                event.getChunk().getZ()
        );
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        customBlockManager.handleChunkUnload(
                event.getWorld().getName(), 
                event.getChunk().getX(), 
                event.getChunk().getZ()
        );
    }
}
