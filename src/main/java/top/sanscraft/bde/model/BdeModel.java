package top.sanscraft.bde.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BdeModel {
    private String version;
    private String type;
    private String project_id;
    private List<String> passengers;
    private BdeDatapack datapack;
    private List<String> hitbox;
    
    // Vehicle Config
    private VehicleConfig vehicle;

    private transient String localFilePath;
    private transient boolean isVehicleLibrary;

    public String getLocalFilePath() {
        return localFilePath;
    }

    public void setLocalFilePath(String localFilePath) {
        this.localFilePath = localFilePath;
    }

    public boolean isVehicleLibrary() {
        return isVehicleLibrary;
    }

    public void setIsVehicleLibrary(boolean isVehicleLibrary) {
        this.isVehicleLibrary = isVehicleLibrary;
    }

    // Keep old fields to support legacy loading
    private List<Double> seat_offset;
    private Double front_yaw_offset;
    private List<List<Double>> passenger_offsets;
    private VehicleStats vehicle_stats;

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getType() {
        VehicleConfig cfg = getVehicle();
        if (cfg != null) {
            return cfg.getType();
        }
        return type;
    }

    public void setType(String type) {
        VehicleConfig cfg = getVehicle();
        if (cfg != null) {
            cfg.setType(type);
        } else {
            this.type = type;
        }
    }

    public String getProjectId() {
        return project_id;
    }

    public void setProjectId(String projectId) {
        this.project_id = projectId;
    }

    public List<String> getPassengers() {
        return passengers;
    }

    public void setPassengers(List<String> passengers) {
        this.passengers = passengers;
    }

    public BdeDatapack getDatapack() {
        return datapack;
    }

    public void setDatapack(BdeDatapack datapack) {
        this.datapack = datapack;
    }

    public List<String> getHitbox() {
        return hitbox;
    }

    public void setHitbox(List<String> hitbox) {
        this.hitbox = hitbox;
    }

    // Nested BdeResponse class for root JSON object
    public static class BdeResponse {
        private BdeModel content;

        public BdeModel getContent() {
            return content;
        }

        public void setContent(BdeModel content) {
            this.content = content;
        }
    }

    // Nested BdeDatapack class for datapack content
    public static class BdeDatapack {
        private Map<String, Map<String, List<String>>> anim_keyframes;
        private Map<String, Map<String, List<String>>> sound_keyframes;

        public Map<String, Map<String, List<String>>> getAnimKeyframes() {
            return anim_keyframes;
        }

        public void setAnimKeyframes(Map<String, Map<String, List<String>>> anim_keyframes) {
            this.anim_keyframes = anim_keyframes;
        }

        public Map<String, Map<String, List<String>>> getSoundKeyframes() {
            return sound_keyframes;
        }

        public void setSoundKeyframes(Map<String, Map<String, List<String>>> sound_keyframes) {
            this.sound_keyframes = sound_keyframes;
        }
    }

    public VehicleConfig getVehicle() {
        if (vehicle == null) {
            if (seat_offset != null || front_yaw_offset != null || passenger_offsets != null || vehicle_stats != null || "vehicle".equalsIgnoreCase(type)) {
                vehicle = new VehicleConfig();
                vehicle.setSeatOffset(seat_offset);
                vehicle.setFrontYawOffset(front_yaw_offset);
                vehicle.setStats(vehicle_stats);
                if (type != null) {
                    vehicle.setType(type);
                }
                
                List<PassengerSeatConfig> seats = new ArrayList<>();
                if (passenger_offsets != null) {
                    for (int i = 0; i < passenger_offsets.size(); i++) {
                        PassengerSeatConfig seatCfg = new PassengerSeatConfig();
                        seatCfg.setOffset(passenger_offsets.get(i));
                        seatCfg.setName("Passenger Seat " + (i + 1));
                        seatCfg.setIcon("MINECART");
                        seats.add(seatCfg);
                    }
                }
                vehicle.setPassengerSeats(seats);
            }
        }
        return vehicle;
    }

    public void ensureVehicleConfig() {
        if (vehicle == null) {
            vehicle = new VehicleConfig();
            if (seat_offset != null) vehicle.setSeatOffset(seat_offset);
            if (front_yaw_offset != null) vehicle.setFrontYawOffset(front_yaw_offset);
            if (vehicle_stats != null) vehicle.setStats(vehicle_stats);
            if (type != null) vehicle.setType(type);
            
            List<PassengerSeatConfig> seats = new ArrayList<>();
            if (passenger_offsets != null) {
                for (int i = 0; i < passenger_offsets.size(); i++) {
                    PassengerSeatConfig seatCfg = new PassengerSeatConfig();
                    seatCfg.setOffset(passenger_offsets.get(i));
                    seatCfg.setName("Passenger Seat " + (i + 1));
                    seatCfg.setIcon("MINECART");
                    seats.add(seatCfg);
                }
            }
            vehicle.setPassengerSeats(seats);
        }
    }

    public void prepareForSave() {
        if (vehicle != null) {
            seat_offset = null;
            front_yaw_offset = null;
            passenger_offsets = null;
            vehicle_stats = null;
            type = vehicle.getType();
        }
    }

    public List<Double> getSeatOffset() {
        VehicleConfig cfg = getVehicle();
        return cfg != null ? cfg.getSeatOffset() : seat_offset;
    }

    public void setSeatOffset(List<Double> seatOffset) {
        ensureVehicleConfig();
        vehicle.setSeatOffset(seatOffset);
    }

    public Double getFrontYawOffset() {
        VehicleConfig cfg = getVehicle();
        return cfg != null ? cfg.getFrontYawOffset() : (front_yaw_offset != null ? front_yaw_offset : 0.0);
    }

    public void setFrontYawOffset(Double frontYawOffset) {
        ensureVehicleConfig();
        vehicle.setFrontYawOffset(frontYawOffset);
    }

    public List<List<Double>> getPassengerOffsets() {
        VehicleConfig cfg = getVehicle();
        if (cfg != null) {
            List<List<Double>> list = new ArrayList<>();
            for (PassengerSeatConfig seat : cfg.getPassengerSeats()) {
                list.add(seat.getOffset());
            }
            return list;
        }
        return passenger_offsets != null ? passenger_offsets : new ArrayList<>();
    }

    public void setPassengerOffsets(List<List<Double>> passengerOffsets) {
        ensureVehicleConfig();
        List<PassengerSeatConfig> seats = new ArrayList<>();
        if (passengerOffsets != null) {
            for (int i = 0; i < passengerOffsets.size(); i++) {
                PassengerSeatConfig seatCfg = new PassengerSeatConfig();
                seatCfg.setOffset(passengerOffsets.get(i));
                seatCfg.setName("Passenger Seat " + (i + 1));
                seatCfg.setIcon("MINECART");
                seats.add(seatCfg);
            }
        }
        vehicle.setPassengerSeats(seats);
    }

    public VehicleStats getVehicleStats() {
        VehicleConfig cfg = getVehicle();
        return cfg != null ? cfg.getStats() : vehicle_stats;
    }

    public void setVehicleStats(VehicleStats vehicleStats) {
        if (vehicleStats == null) {
            vehicle = null;
        } else {
            ensureVehicleConfig();
            vehicle.setStats(vehicleStats);
        }
    }

    public static class PassengerSeatConfig {
        private List<Double> offset;
        private String name;
        private String icon;
        private Double yaw;

        public List<Double> getOffset() {
            return offset;
        }

        public void setOffset(List<Double> offset) {
            this.offset = offset;
        }

        public String getName() {
            return name != null ? name : "Passenger Seat";
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getIcon() {
            return icon != null ? icon : "MINECART";
        }

        public void setIcon(String icon) {
            this.icon = icon;
        }

        public Double getYaw() {
            return yaw != null ? yaw : 0.0;
        }

        public void setYaw(Double yaw) {
            this.yaw = yaw;
        }
    }

    public static class VehicleConfig {
        private String type; // minecart, boat, armor_stand
        private String name; // Custom catalog name
        private String icon; // Custom catalog icon (material name)
        private List<Double> seat_offset;
        private String driver_seat_name;
        private String driver_seat_icon;
        private List<PassengerSeatConfig> passenger_seats = new ArrayList<>();
        private Double front_yaw_offset;
        private Double driver_seat_yaw;
        private VehicleStats stats;
        private List<HitboxConfig> hitboxes = new ArrayList<>();
        private List<SubsystemConfig> subsystems = new ArrayList<>();
        private String anim_idle;
        private String anim_forward;
        private String anim_reverse;
        private String anim_turn_left;
        private String anim_turn_right;
        private String sound_idle;
        private Integer sound_idle_cooldown;
        private String sound_moving;
        private Integer sound_moving_cooldown;

        public List<HitboxConfig> getHitboxes() {
            if (hitboxes == null) {
                hitboxes = new ArrayList<>();
            }
            return hitboxes;
        }

        public void setHitboxes(List<HitboxConfig> hitboxes) {
            this.hitboxes = hitboxes;
        }

        public List<SubsystemConfig> getSubsystems() {
            if (subsystems == null) {
                subsystems = new ArrayList<>();
            }
            return subsystems;
        }

        public void setSubsystems(List<SubsystemConfig> subsystems) {
            this.subsystems = subsystems;
        }

        public String getType() {
            return type != null ? type : "armor_stand";
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getName() {
            return name != null ? name : "";
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getIcon() {
            return icon != null ? icon : "MINECART";
        }

        public void setIcon(String icon) {
            this.icon = icon;
        }

        public List<Double> getSeatOffset() {
            return seat_offset;
        }

        public void setSeatOffset(List<Double> seatOffset) {
            this.seat_offset = seatOffset;
        }

        public String getDriverSeatName() {
            return driver_seat_name != null ? driver_seat_name : "Driver Seat";
        }

        public void setDriverSeatName(String driverSeatName) {
            this.driver_seat_name = driverSeatName;
        }

        public String getDriverSeatIcon() {
            return driver_seat_icon != null ? driver_seat_icon : "SADDLE";
        }

        public void setDriverSeatIcon(String driverSeatIcon) {
            this.driver_seat_icon = driverSeatIcon;
        }

        public List<PassengerSeatConfig> getPassengerSeats() {
            return passenger_seats != null ? passenger_seats : new ArrayList<>();
        }

        public void setPassengerSeats(List<PassengerSeatConfig> passengerSeats) {
            this.passenger_seats = passengerSeats;
        }

        public Double getFrontYawOffset() {
            return front_yaw_offset != null ? front_yaw_offset : 0.0;
        }

        public void setFrontYawOffset(Double frontYawOffset) {
            this.front_yaw_offset = frontYawOffset;
        }

        public Double getDriverSeatYaw() {
            return driver_seat_yaw != null ? driver_seat_yaw : 0.0;
        }

        public void setDriverSeatYaw(Double driverSeatYaw) {
            this.driver_seat_yaw = driverSeatYaw;
        }

        public VehicleStats getStats() {
            return stats;
        }

        public void setStats(VehicleStats stats) {
            this.stats = stats;
        }

        public String getAnimIdle() { return anim_idle; }
        public void setAnimIdle(String animIdle) { this.anim_idle = animIdle; }

        public String getAnimForward() { return anim_forward; }
        public void setAnimForward(String animForward) { this.anim_forward = animForward; }

        public String getAnimReverse() { return anim_reverse; }
        public void setAnimReverse(String animReverse) { this.anim_reverse = animReverse; }

        public String getAnimTurnLeft() { return anim_turn_left; }
        public void setAnimTurnLeft(String animTurnLeft) { this.anim_turn_left = animTurnLeft; }

        public String getAnimTurnRight() { return anim_turn_right; }
        public void setAnimTurnRight(String animTurnRight) { this.anim_turn_right = animTurnRight; }

        public String getSoundIdle() { return sound_idle; }
        public void setSoundIdle(String soundIdle) { this.sound_idle = soundIdle; }

        public int getSoundIdleCooldown() { return sound_idle_cooldown != null ? sound_idle_cooldown : 20; }
        public void setSoundIdleCooldown(int soundIdleCooldown) { this.sound_idle_cooldown = soundIdleCooldown; }

        public String getSoundMoving() { return sound_moving; }
        public void setSoundMoving(String soundMoving) { this.sound_moving = soundMoving; }

        public int getSoundMovingCooldown() { return sound_moving_cooldown != null ? sound_moving_cooldown : 20; }
        public void setSoundMovingCooldown(int soundMovingCooldown) { this.sound_moving_cooldown = soundMovingCooldown; }
    }

    public static class VehicleStats {
        private double top_speed = 0.3;
        private double acceleration = 0.02;
        private double deceleration = 0.03;
        private double reverse_speed = 0.1;
        private double turn_speed = 4.0;
        private double traction = 1.0;
        private Map<String, Double> block_overrides = new HashMap<>();
        private Map<String, Object> additional_properties = new HashMap<>();

        public double getTopSpeed() { return top_speed; }
        public void setTopSpeed(double topSpeed) { this.top_speed = topSpeed; }

        public double getAcceleration() { return acceleration; }
        public void setAcceleration(double acceleration) { this.acceleration = acceleration; }

        public double getDeceleration() { return deceleration; }
        public void setDeceleration(double deceleration) { this.deceleration = deceleration; }

        public double getReverseSpeed() { return reverse_speed; }
        public void setReverseSpeed(double reverseSpeed) { this.reverse_speed = reverseSpeed; }

        public double getTurnSpeed() { return turn_speed; }
        public void setTurnSpeed(double turnSpeed) { this.turn_speed = turnSpeed; }

        public double getTraction() { return traction; }
        public void setTraction(double traction) { this.traction = traction; }

        private double max_hp = 100.0;
        public double getMaxHp() { return max_hp; }
        public void setMaxHp(double maxHp) { this.max_hp = maxHp; }

        public Map<String, Double> getBlockOverrides() {
            if (block_overrides == null) {
                block_overrides = new HashMap<>();
            }
            return block_overrides;
        }
        public void setBlockOverrides(Map<String, Double> blockOverrides) { this.block_overrides = blockOverrides; }

        public Map<String, Object> getAdditionalProperties() { return additional_properties; }
        public void setAdditionalProperties(Map<String, Object> additionalProperties) { this.additional_properties = additionalProperties; }
    }

    public static class HitboxConfig {
        private List<Double> offset = new ArrayList<>(java.util.Arrays.asList(0.0, 0.0, 0.0));
        private double width = 1.0;
        private double height = 1.0;
        private String name = "default";

        public List<Double> getOffset() { return offset; }
        public void setOffset(List<Double> offset) { this.offset = offset; }
        public double getWidth() { return width; }
        public void setWidth(double width) { this.width = width; }
        public double getHeight() { return height; }
        public void setHeight(double height) { this.height = height; }
        public String getName() { return name != null ? name : "default"; }
        public void setName(String name) { this.name = name; }
    }

    public static class SubsystemConfig {
        private String name = "Subsystem";
        private int controllerSeatIndex = -1; // -1 for driver, 0+ for passenger seats
        private String displayTag; // Scoreboard tag of the display entity representing the weapon
        private List<ProjectileConfig> weaponModes = new ArrayList<>();
        private String bdeModelId;
        private List<Double> mountOffset;
        private List<Double> launchOffset;
        private Double fovMinYaw;
        private Double fovMaxYaw;
        private Double fovMinPitch;
        private Double fovMaxPitch;
        private List<Double> cameraOffset;
        private List<Double> pivotOffset;

        public String getName() { return name != null ? name : "Subsystem"; }
        public void setName(String name) { this.name = name; }
        public int getControllerSeatIndex() { return controllerSeatIndex; }
        public void setControllerSeatIndex(int controllerSeatIndex) { this.controllerSeatIndex = controllerSeatIndex; }
        public String getDisplayTag() { return displayTag; }
        public void setDisplayTag(String displayTag) { this.displayTag = displayTag; }
        public List<ProjectileConfig> getWeaponModes() {
            if (weaponModes == null) {
                weaponModes = new ArrayList<>();
            }
            return weaponModes;
        }
        public void setWeaponModes(List<ProjectileConfig> weaponModes) { this.weaponModes = weaponModes; }

        public String getBdeModelId() { return bdeModelId; }
        public void setBdeModelId(String bdeModelId) { this.bdeModelId = bdeModelId; }

        public List<Double> getMountOffset() { return mountOffset; }
        public void setMountOffset(List<Double> mountOffset) { this.mountOffset = mountOffset; }

        public List<Double> getLaunchOffset() { return launchOffset; }
        public void setLaunchOffset(List<Double> launchOffset) { this.launchOffset = launchOffset; }

        public Double getFovMinYaw() { return fovMinYaw; }
        public void setFovMinYaw(Double fovMinYaw) { this.fovMinYaw = fovMinYaw; }

        public Double getFovMaxYaw() { return fovMaxYaw; }
        public void setFovMaxYaw(Double fovMaxYaw) { this.fovMaxYaw = fovMaxYaw; }

        public Double getFovMinPitch() { return fovMinPitch; }
        public void setFovMinPitch(Double fovMinPitch) { this.fovMinPitch = fovMinPitch; }

        public Double getFovMaxPitch() { return fovMaxPitch; }
        public void setFovMaxPitch(Double fovMaxPitch) { this.fovMaxPitch = fovMaxPitch; }

        public List<Double> getCameraOffset() { return cameraOffset; }
        public void setCameraOffset(List<Double> cameraOffset) { this.cameraOffset = cameraOffset; }

        public List<Double> getPivotOffset() { return pivotOffset; }
        public void setPivotOffset(List<Double> pivotOffset) { this.pivotOffset = pivotOffset; }
    }

    public static class ProjectileConfig {
        private String name = "Dumb Fire";
        private String bdeModelId; // Optional model ID for projectile model display
        private String launchSound = "ENTITY_WIND_CHARGE_WIND_BURST";
        private float launchVolume = 1.0f;
        private float launchPitch = 1.2f;
        private String flyParticle = "DUST";
        private int flyParticleCount = 1;
        private String impactParticle = "EXPLOSION";
        private int impactParticleCount = 10;

        private boolean hasGravity = true;
        private double speed = 1.5;
        private double damage = 10.0;
        private double cooldown = 1.0;
        private String onHit = "explode"; // "despawn", "explode"
        private double explosionPower = 2.0;
        private boolean destroyBlocks = false;
        private boolean vanillaExplosionDamage = true;

        // Lock-on settings
        private boolean lockOn = false;
        private double lockRange = 50.0;
        private double lockAngle = 30.0;
        private double lockTime = 2.0;

        public String getName() { return name != null ? name : "Projectile"; }
        public void setName(String name) { this.name = name; }
        public String getBdeModelId() { return bdeModelId; }
        public void setBdeModelId(String bdeModelId) { this.bdeModelId = bdeModelId; }
        public String getLaunchSound() { return launchSound != null ? launchSound : "ENTITY_WIND_CHARGE_WIND_BURST"; }
        public void setLaunchSound(String launchSound) { this.launchSound = launchSound; }
        public float getLaunchVolume() { return launchVolume; }
        public void setLaunchVolume(float launchVolume) { this.launchVolume = launchVolume; }
        public float getLaunchPitch() { return launchPitch; }
        public void setLaunchPitch(float launchPitch) { this.launchPitch = launchPitch; }
        public String getFlyParticle() { return flyParticle != null ? flyParticle : "DUST"; }
        public void setFlyParticle(String flyParticle) { this.flyParticle = flyParticle; }
        public int getFlyParticleCount() { return flyParticleCount; }
        public void setFlyParticleCount(int flyParticleCount) { this.flyParticleCount = flyParticleCount; }
        public String getImpactParticle() { return impactParticle != null ? impactParticle : "EXPLOSION"; }
        public void setImpactParticle(String impactParticle) { this.impactParticle = impactParticle; }
        public int getImpactParticleCount() { return impactParticleCount; }
        public void setImpactParticleCount(int impactParticleCount) { this.impactParticleCount = impactParticleCount; }
        public boolean isHasGravity() { return hasGravity; }
        public void setHasGravity(boolean hasGravity) { this.hasGravity = hasGravity; }
        public double getSpeed() { return speed; }
        public void setSpeed(double speed) { this.speed = speed; }
        public double getDamage() { return damage; }
        public void setDamage(double damage) { this.damage = damage; }
        public double getCooldown() { return cooldown; }
        public void setCooldown(double cooldown) { this.cooldown = cooldown; }
        public String getOnHit() { return onHit != null ? onHit : "explode"; }
        public void setOnHit(String onHit) { this.onHit = onHit; }
        public double getExplosionPower() { return explosionPower; }
        public void setExplosionPower(double explosionPower) { this.explosionPower = explosionPower; }
        public boolean isDestroyBlocks() { return destroyBlocks; }
        public void setDestroyBlocks(boolean destroyBlocks) { this.destroyBlocks = destroyBlocks; }
        public boolean isVanillaExplosionDamage() { return vanillaExplosionDamage; }
        public void setVanillaExplosionDamage(boolean vanillaExplosionDamage) { this.vanillaExplosionDamage = vanillaExplosionDamage; }
        public boolean isLockOn() { return lockOn; }
        public void setLockOn(boolean lockOn) { this.lockOn = lockOn; }
        public double getLockRange() { return lockRange; }
        public void setLockRange(double lockRange) { this.lockRange = lockRange; }
        public double getLockAngle() { return lockAngle; }
        public void setLockAngle(double lockAngle) { this.lockAngle = lockAngle; }
        public double getLockTime() { return lockTime; }
        public void setLockTime(double lockTime) { this.lockTime = lockTime; }
    }
}
