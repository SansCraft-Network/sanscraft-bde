package top.sanscraft.bde.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public class BdeGuiHolder implements InventoryHolder {
    private final GuiType guiType;
    private final UUID selectedModelId;
    private final String modelProjectId;
    private int page = 0;

    private Integer seatIndex;

    public BdeGuiHolder(GuiType guiType, UUID selectedModelId) {
        this(guiType, selectedModelId, null);
    }

    public BdeGuiHolder(GuiType guiType, UUID selectedModelId, String modelProjectId) {
        this.guiType = guiType;
        this.selectedModelId = selectedModelId;
        this.modelProjectId = modelProjectId;
    }

    public BdeGuiHolder(GuiType guiType, UUID selectedModelId, String modelProjectId, Integer seatIndex) {
        this.guiType = guiType;
        this.selectedModelId = selectedModelId;
        this.modelProjectId = modelProjectId;
        this.seatIndex = seatIndex;
    }

    public Integer getSeatIndex() {
        return seatIndex;
    }

    public void setSeatIndex(Integer seatIndex) {
        this.seatIndex = seatIndex;
    }

    public GuiType getGuiType() {
        return guiType;
    }

    public UUID getSelectedModelId() {
        return selectedModelId;
    }

    public String getModelProjectId() {
        return modelProjectId;
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return null; // Will be built dynamically by BdeGuiManager
    }

    public enum GuiType {
        MAIN_MENU,
        ANIMATIONS,
        CONVERTER,
        MOVEMENT,
        VEHICLE,
        BLOCKS,
        VEHICLES_CATALOG,
        SEAT_SELECTION,
        SEAT_CONFIGURATION,
        SEAT_DETAIL
    }
}
