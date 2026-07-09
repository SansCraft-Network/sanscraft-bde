package top.sanscraft.bde.model;

import java.util.ArrayList;
import java.util.List;

public class TurretConfig {
    private String id;
    private String name = "Turret";
    private String bdeModelId;
    private List<Double> pivotOffset = new ArrayList<>();
    private List<Double> launchOffset = new ArrayList<>();
    private List<Double> cameraOffset = new ArrayList<>();
    private Double fovMinYaw;
    private Double fovMaxYaw;
    private Double fovMinPitch;
    private Double fovMaxPitch;
    private String displayTag;
    private List<String> projectileIds = new ArrayList<>();

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name != null ? name : "Turret";
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getBdeModelId() {
        return bdeModelId;
    }

    public void setBdeModelId(String bdeModelId) {
        this.bdeModelId = bdeModelId;
    }

    public List<Double> getPivotOffset() {
        if (pivotOffset == null) {
            pivotOffset = new ArrayList<>();
        }
        return pivotOffset;
    }

    public void setPivotOffset(List<Double> pivotOffset) {
        this.pivotOffset = pivotOffset;
    }

    public List<Double> getLaunchOffset() {
        if (launchOffset == null) {
            launchOffset = new ArrayList<>();
        }
        return launchOffset;
    }

    public void setLaunchOffset(List<Double> launchOffset) {
        this.launchOffset = launchOffset;
    }

    public List<Double> getCameraOffset() {
        if (cameraOffset == null) {
            cameraOffset = new ArrayList<>();
        }
        return cameraOffset;
    }

    public void setCameraOffset(List<Double> cameraOffset) {
        this.cameraOffset = cameraOffset;
    }

    public Double getFovMinYaw() {
        return fovMinYaw;
    }

    public void setFovMinYaw(Double fovMinYaw) {
        this.fovMinYaw = fovMinYaw;
    }

    public Double getFovMaxYaw() {
        return fovMaxYaw;
    }

    public void setFovMaxYaw(Double fovMaxYaw) {
        this.fovMaxYaw = fovMaxYaw;
    }

    public Double getFovMinPitch() {
        return fovMinPitch;
    }

    public void setFovMinPitch(Double fovMinPitch) {
        this.fovMinPitch = fovMinPitch;
    }

    public Double getFovMaxPitch() {
        return fovMaxPitch;
    }

    public void setFovMaxPitch(Double fovMaxPitch) {
        this.fovMaxPitch = fovMaxPitch;
    }

    public String getDisplayTag() {
        return displayTag;
    }

    public void setDisplayTag(String displayTag) {
        this.displayTag = displayTag;
    }

    public List<String> getProjectileIds() {
        if (projectileIds == null) {
            projectileIds = new ArrayList<>();
        }
        return projectileIds;
    }

    public void setProjectileIds(List<String> projectileIds) {
        this.projectileIds = projectileIds;
    }
}
