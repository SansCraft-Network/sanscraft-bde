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
    private Integer subsystemIndex;
    private Integer weaponModeIndex;

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

    public BdeGuiHolder(GuiType guiType, UUID selectedModelId, String modelProjectId, Integer seatIndex, Integer subsystemIndex, Integer weaponModeIndex) {
        this.guiType = guiType;
        this.selectedModelId = selectedModelId;
        this.modelProjectId = modelProjectId;
        this.seatIndex = seatIndex;
        this.subsystemIndex = subsystemIndex;
        this.weaponModeIndex = weaponModeIndex;
    }

    public Integer getSeatIndex() {
        return seatIndex;
    }

    public void setSeatIndex(Integer seatIndex) {
        this.seatIndex = seatIndex;
    }

    public Integer getSubsystemIndex() {
        return subsystemIndex;
    }

    public void setSubsystemIndex(Integer subsystemIndex) {
        this.subsystemIndex = subsystemIndex;
    }

    public Integer getWeaponModeIndex() {
        return weaponModeIndex;
    }

    public void setWeaponModeIndex(Integer weaponModeIndex) {
        this.weaponModeIndex = weaponModeIndex;
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

    private String turretId;
    private String projectileId;

    public String getTurretId() {
        return turretId;
    }

    public void setTurretId(String turretId) {
        this.turretId = turretId;
    }

    public String getProjectileId() {
        return projectileId;
    }

    public void setProjectileId(String projectileId) {
        this.projectileId = projectileId;
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
        SEAT_DETAIL,
        GENERAL_BLOCK_TRACTION,
        VEHICLE_BLOCK_OVERRIDES,
        SUBSYSTEM_LIST,
        SUBSYSTEM_DETAIL,
        WEAPON_MODE_LIST,
        WEAPON_MODE_DETAIL,
        TURRET_LINK_MENU,
        TURRET_CATALOG,
        TURRET_EDITOR,
        TURRET_PROJECTILE_LINK,
        PROJECTILE_CATALOG,
        PROJECTILE_EDITOR,
        SUBSYSTEM_PROJECTILE_OVERRIDE
    }
}
