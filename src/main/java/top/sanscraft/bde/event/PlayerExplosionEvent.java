package top.sanscraft.bde.event;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.Location;

import java.util.List;

public class PlayerExplosionEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    
    private final Player player;
    private final Location location;
    private final float power;
    private final List<Block> blocks;
    private boolean cancelled = false;

    public PlayerExplosionEvent(Player player, Location location, float power, List<Block> blocks) {
        this.player = player;
        this.location = location;
        this.power = power;
        this.blocks = blocks;
    }

    public Player getPlayer() {
        return player;
    }

    public Location getLocation() {
        return location;
    }

    public float getPower() {
        return power;
    }

    public List<Block> getBlocks() {
        return blocks;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
