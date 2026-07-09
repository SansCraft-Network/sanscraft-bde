package top.sanscraft.bde.listener;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityExplodeEvent;
import top.sanscraft.bde.SansCraftBDEPlugin;
import top.sanscraft.bde.event.PlayerExplosionEvent;

public class PlayerExplosionListener implements Listener {
    private final SansCraftBDEPlugin plugin;

    public PlayerExplosionListener(SansCraftBDEPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (event.getEntity() != null && event.getEntity().hasMetadata("bde_projectile")) {
            if (event.getEntity() instanceof Projectile) {
                Projectile projectile = (Projectile) event.getEntity();
                if (projectile.getShooter() instanceof Player) {
                    Player shooter = (Player) projectile.getShooter();
                    
                    // Check if shooter is online and has permission
                    if (!shooter.isOnline() || !shooter.hasPermission("sanscraft.bde.projectiles.break")) {
                        event.blockList().clear();
                        return;
                    }
                    
                    // Call the custom PlayerExplosionEvent
                    PlayerExplosionEvent playerExplosionEvent = new PlayerExplosionEvent(
                            shooter,
                            event.getLocation(),
                            event.getYield(),
                            event.blockList()
                    );
                    Bukkit.getPluginManager().callEvent(playerExplosionEvent);
                    
                    if (playerExplosionEvent.isCancelled()) {
                        event.setCancelled(true);
                    } else {
                        // Keep only the blocks that are left in the event's list
                        event.blockList().retainAll(playerExplosionEvent.getBlocks());
                    }
                }
            }
        }
    }
}
