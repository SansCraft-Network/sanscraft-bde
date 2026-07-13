package top.sanscraft.bde.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;
import java.util.UUID;

public class BdeAmmoStorageHolder implements InventoryHolder {
    private final UUID instanceId;
    private final String storageKey; // "shared" or "subsystem_<index>"
    private final int size;

    public BdeAmmoStorageHolder(UUID instanceId, String storageKey, int size) {
        this.instanceId = instanceId;
        this.storageKey = storageKey;
        this.size = size;
    }

    public UUID getInstanceId() {
        return instanceId;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public int getSize() {
        return size;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return null;
    }
}
