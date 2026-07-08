package top.sanscraft.bde.manager;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.Input;
import top.sanscraft.bde.SansCraftBDEPlugin;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerInputTracker implements Listener {
    private final SansCraftBDEPlugin plugin;
    private final Map<UUID, PlayerInputData> inputs = new ConcurrentHashMap<>();
    private final Set<UUID> activeDrivers = ConcurrentHashMap.newKeySet();

    public static class PlayerInputData {
        public boolean forward;
        public boolean backward;
        public boolean left;
        public boolean right;
        public boolean jump;
        public boolean shift;
        public boolean sprint;
    }

    public PlayerInputTracker(SansCraftBDEPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    public void onPlayerInput(PlayerInputEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (!activeDrivers.contains(uuid)) return;

        Input input = event.getInput();
        PlayerInputData data = new PlayerInputData();
        data.forward = input.isForward();
        data.backward = input.isBackward();
        data.left = input.isLeft();
        data.right = input.isRight();
        data.jump = input.isJump();
        data.shift = input.isSneak();
        data.sprint = input.isSprint();

        inputs.put(uuid, data);

        if (ModelManager.DEBUG_VEHICLES) {
            plugin.getLogger().info("[BDE Debug] PlayerInputEvent for " + event.getPlayer().getName() +
                    ": W=" + data.forward + ", S=" + data.backward +
                    ", A=" + data.left + ", D=" + data.right);
        }
    }

    public PlayerInputData getInput(UUID uuid) {
        return inputs.getOrDefault(uuid, new PlayerInputData());
    }

    public void inject(Player player) {
        UUID uuid = player.getUniqueId();
        activeDrivers.add(uuid);
        if (ModelManager.DEBUG_VEHICLES) {
            plugin.getLogger().info("[BDE Debug] Injecting input tracker for " + player.getName());
        }
    }

    public void uninject(Player player) {
        UUID uuid = player.getUniqueId();
        activeDrivers.remove(uuid);
        inputs.remove(uuid);
        if (ModelManager.DEBUG_VEHICLES) {
            plugin.getLogger().info("[BDE Debug] Uninjecting input tracker for driver: " + player.getName());
        }
    }
}
