package top.sanscraft.bde.manager;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import top.sanscraft.bde.SansCraftBDEPlugin;
import top.sanscraft.bde.model.ModelInstance;
import top.sanscraft.bde.model.BdeModel;

import java.util.*;

public class BdeRepairTaskManager {
    private final SansCraftBDEPlugin plugin;
    private final Map<UUID, RepairSession> activeSessions = new HashMap<>();
    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();

    public BdeRepairTaskManager(SansCraftBDEPlugin plugin) {
        this.plugin = plugin;
        
        // Cooldown and action bar update ticker (runs every 10 ticks = 0.5s)
        new BukkitRunnable() {
            @Override
            public void run() {
                updateCooldownsAndActionBars();
            }
        }.runTaskTimer(plugin, 10L, 10L);
    }

    public synchronized boolean startRepair(Player player, ModelInstance vehicle, BdeRepairConfig.RepairToolConfig tool, ItemStack toolItem) {
        if (activeSessions.containsKey(player.getUniqueId())) {
            player.sendMessage("§cYou are already repairing a vehicle!");
            return false;
        }

        // Check Cooldown
        long now = System.currentTimeMillis();
        Map<String, Long> playerCooldowns = cooldowns.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>());
        if (playerCooldowns.containsKey(tool.id)) {
            long remaining = playerCooldowns.get(tool.id) - now;
            if (remaining > 0) {
                player.sendMessage("§cThis repair tool is on cooldown for " + String.format("%.1f", remaining / 1000.0) + "s!");
                return false;
            }
        }

        // Check Cost Requirements
        if (!hasRequiredCosts(player, tool)) {
            player.sendMessage("§cYou do not have the required materials to repair!");
            return false;
        }

        // Check if vehicle needs repair
        double maxHp = vehicle.getModel().getVehicleStats() != null ? vehicle.getModel().getVehicleStats().getMaxHp() : 100.0;
        boolean needsSubHp = false;
        if (vehicle.getModel().getVehicle() != null) {
            for (BdeModel.SubsystemConfig sub : vehicle.getModel().getVehicle().getSubsystems()) {
                if (sub.getMaxHp() != null) {
                    double subHp = vehicle.getSubsystemHp(sub.getName()) != null ? vehicle.getSubsystemHp(sub.getName()) : sub.getMaxHp();
                    if (subHp < sub.getMaxHp()) {
                        needsSubHp = true;
                        break;
                    }
                }
            }
        }
        if (vehicle.getCurrentHp() >= maxHp && !needsSubHp) {
            player.sendMessage("§cThis vehicle and all its subsystems are already at full health!");
            return false;
        }

        if (tool.repairDelay <= 0.0) {
            // Instant Repair
            executeRepair(player, vehicle, tool, toolItem);
            return true;
        } else {
            // Delayed Repair Session
            RepairSession session = new RepairSession(player, vehicle, tool, toolItem);
            activeSessions.put(player.getUniqueId(), session);
            session.runTaskTimer(plugin, 1L, 1L); // Tick every 20th of a second
            return true;
        }
    }

    private boolean hasRequiredCosts(Player player, BdeRepairConfig.RepairToolConfig tool) {
        for (Map.Entry<Material, Integer> cost : tool.repairCost.entrySet()) {
            if (!player.getInventory().containsAtLeast(new ItemStack(cost.getKey()), cost.getValue())) {
                return false;
            }
        }
        return true;
    }

    private void deductCosts(Player player, BdeRepairConfig.RepairToolConfig tool) {
        for (Map.Entry<Material, Integer> cost : tool.repairCost.entrySet()) {
            player.getInventory().removeItem(new ItemStack(cost.getKey(), cost.getValue()));
        }
    }

    private void executeRepair(Player player, ModelInstance vehicle, BdeRepairConfig.RepairToolConfig tool, ItemStack toolItem) {
        if (!vehicle.getRootEntity().isValid()) {
            player.sendMessage("§cVehicle no longer exists!");
            return;
        }

        // Final Cost Check
        if (!hasRequiredCosts(player, tool)) {
            player.sendMessage("§cYou do not have the required materials to finish the repair!");
            return;
        }

        deductCosts(player, tool);

        // Apply HP
        BdeModel.VehicleConfig vc = vehicle.getModel().getVehicle();
        double maxHp = vehicle.getModel().getVehicleStats() != null ? vehicle.getModel().getVehicleStats().getMaxHp() : 100.0;
        double currentHp = vehicle.getCurrentHp();
        double nextHp = Math.min(maxHp, currentHp + tool.repairAmount);
        vehicle.setCurrentHp(nextHp);

        // Repair subsystems
        if (vc != null) {
            for (BdeModel.SubsystemConfig sub : vc.getSubsystems()) {
                if (sub.getMaxHp() != null) {
                    double subHp = vehicle.getSubsystemHp(sub.getName()) != null ? vehicle.getSubsystemHp(sub.getName()) : sub.getMaxHp();
                    if (subHp < sub.getMaxHp()) {
                        double nextSubHp = Math.min(sub.getMaxHp(), subHp + tool.repairAmount);
                        vehicle.setSubsystemHp(sub.getName(), nextSubHp);
                        if (nextSubHp > 0.0 && vehicle.isSubsystemDisabled(sub.getName())) {
                            vehicle.setSubsystemDisabled(sub.getName(), false);
                            player.sendMessage("§aSubsystem " + sub.getName() + " has been repaired and reactivated!");
                        }
                    }
                }
            }
        }

        // Set Cooldown
        if (tool.cooldown > 0.0) {
            long end = System.currentTimeMillis() + (long) (tool.cooldown * 1000.0);
            cooldowns.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>()).put(tool.id, end);
            
            // Visual Cooldown
            if (tool.visualCooldown) {
                player.setCooldown(tool.material, (int) (tool.cooldown * 20.0));
            }
        }

        player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 0.8f, 1.2f);
        player.sendMessage("§aVehicle repaired successfully! HP: §e" + String.format("%.1f", nextHp) + "§7/§f" + String.format("%.1f", maxHp));
    }

    public synchronized void cancelRepair(Player player, String reason) {
        RepairSession session = activeSessions.remove(player.getUniqueId());
        if (session != null) {
            session.cancel();
            player.sendActionBar("§cRepair Cancelled: " + reason);
        }
    }

    private synchronized void updateCooldownsAndActionBars() {
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID pId = player.getUniqueId();
            
            // If in active session, action bar is handled by session tick
            if (activeSessions.containsKey(pId)) {
                continue;
            }

            // Check if holding a repair tool on cooldown
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (hand == null || hand.getType() == Material.AIR) continue;

            BdeRepairConfig.RepairToolConfig tool = findMatchingTool(hand);
            if (tool == null) continue;

            Map<String, Long> playerCooldowns = cooldowns.get(pId);
            if (playerCooldowns != null && playerCooldowns.containsKey(tool.id)) {
                long end = playerCooldowns.get(tool.id);
                long remaining = end - now;
                if (remaining > 0) {
                    player.sendActionBar("§cCooldown: " + String.format("%.1f", remaining / 1000.0) + "s");
                } else {
                    playerCooldowns.remove(tool.id);
                }
            }
        }
    }

    public BdeRepairConfig.RepairToolConfig findMatchingTool(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        for (BdeRepairConfig.RepairToolConfig tool : plugin.getBdeRepairConfig().getRegisteredTools().values()) {
            if (item.getType() == tool.material) {
                if (tool.customModelData == -1 || (item.getItemMeta().hasCustomModelData() && item.getItemMeta().getCustomModelData() == tool.customModelData)) {
                    return tool;
                }
            }
        }
        return null;
    }

    private class RepairSession extends BukkitRunnable {
        private final Player player;
        private final ModelInstance vehicle;
        private final BdeRepairConfig.RepairToolConfig tool;
        private final ItemStack toolItem;
        private final Location startLoc;
        private final double totalTicks;
        private double ticksElapsed = 0;

        public RepairSession(Player player, ModelInstance vehicle, BdeRepairConfig.RepairToolConfig tool, ItemStack toolItem) {
            this.player = player;
            this.vehicle = vehicle;
            this.tool = tool;
            this.toolItem = toolItem;
            this.startLoc = player.getLocation().clone();
            this.totalTicks = tool.repairDelay * 20.0;
        }

        @Override
        public void run() {
            // Cancel if vehicle is invalid or player offline
            if (!player.isOnline()) {
                cancelSession();
                return;
            }
            if (!vehicle.getRootEntity().isValid()) {
                cancelSession();
                player.sendMessage("§cVehicle no longer exists!");
                return;
            }

            // Check if player moved
            if (player.getLocation().distanceSquared(startLoc) > 0.01) {
                cancelSession();
                player.sendActionBar("§cRepair Cancelled: You moved!");
                return;
            }

            // Progress repair delay
            ticksElapsed++;
            double percent = (ticksElapsed / totalTicks) * 100.0;
            
            // Build progress bar
            StringBuilder bar = new StringBuilder("§eRepairing: [");
            int filled = (int) (percent / 10.0);
            for (int i = 0; i < 10; i++) {
                if (i < filled) {
                    bar.append("■");
                } else {
                    bar.append("□");
                }
            }
            bar.append("] ").append(String.format("%d", (int) percent)).append("%");
            player.sendActionBar(bar.toString());

            if (ticksElapsed >= totalTicks) {
                executeRepair(player, vehicle, tool, toolItem);
                cancelSession();
            }
        }

        private void cancelSession() {
            synchronized (BdeRepairTaskManager.this) {
                activeSessions.remove(player.getUniqueId());
            }
            cancel();
        }
    }
}
