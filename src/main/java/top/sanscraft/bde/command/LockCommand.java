package top.sanscraft.bde.command;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import top.sanscraft.bde.SansCraftBDEPlugin;
import top.sanscraft.bde.manager.ModelManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class LockCommand implements CommandExecutor, TabCompleter {
    private final SansCraftBDEPlugin plugin;

    public LockCommand(SansCraftBDEPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }

        Player player = (Player) sender;
        if (!player.hasPermission("sanscraft.bde.lock")) {
            player.sendMessage(ChatColor.RED + "You do not have permission to execute this command.");
            return true;
        }

        ModelManager.PlacementSession session = plugin.getModelManager().getPlacementSession(player.getUniqueId());
        if (session == null) {
            player.sendMessage(ChatColor.RED + "You must be in Interactive Placement Mode to use this command.");
            return true;
        }

        if (args.length == 0) {
            player.sendMessage(ChatColor.GOLD + "=== Axis Lock Status ===");
            player.sendMessage(ChatColor.YELLOW + "Locked Axes: " + ChatColor.WHITE + (session.lockedAxes.isEmpty() ? "None" : session.lockedAxes));
            player.sendMessage(ChatColor.YELLOW + "Usage: /lock <x|y|z> [on|off]");
            return true;
        }

        String axis = args[0].toLowerCase();
        if (!axis.equals("x") && !axis.equals("y") && !axis.equals("z")) {
            player.sendMessage(ChatColor.RED + "Invalid axis. Choose x, y, or z.");
            return true;
        }

        boolean on = true;
        if (args.length >= 2) {
            String val = args[1].toLowerCase();
            if (val.equals("off") || val.equals("false")) {
                on = false;
            }
        } else {
            // Toggle
            on = !session.lockedAxes.contains(axis);
        }

        if (on) {
            // Store current relative coordinate as the locked value
            double value = 0.0;
            if (axis.equals("x")) {
                value = session.lastRx;
            } else if (axis.equals("y")) {
                value = session.lastRy;
            } else {
                value = session.lastRz;
            }
            session.lockedValues.put(axis, value);
            session.lockedAxes.add(axis);
            player.sendMessage(ChatColor.GREEN + "Locked " + axis.toUpperCase() + " coordinate at " + String.format("%.2f", value));
        } else {
            session.lockedAxes.remove(axis);
            session.lockedValues.remove(axis);
            player.sendMessage(ChatColor.YELLOW + "Unlocked " + axis.toUpperCase() + " coordinate.");
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("x", "y", "z");
        }
        if (args.length == 2) {
            return Arrays.asList("on", "off");
        }
        return Collections.emptyList();
    }
}
