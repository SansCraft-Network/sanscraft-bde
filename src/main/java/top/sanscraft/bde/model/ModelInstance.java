package top.sanscraft.bde.model;

import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ModelInstance {
    private final UUID id;
    private final BdeModel model;
    private final Location spawnLocation;
    private double scale;
    
    private Entity rootEntity;
    private Entity vehicleRoot;
    private Entity driverSeat;
    private Location currentLocation;
    private final List<Display> passengers = new ArrayList<>();
    private final Map<String, Display> taggedPassengers = new HashMap<>();
    
    // Vehicle runtime states
    private double currentSpeed = 0.0;
    private float currentYaw = 0.0f;
    private double velocityX = 0.0;
    private double velocityZ = 0.0;
    private final List<org.bukkit.entity.ArmorStand> passengerSeats = new ArrayList<>();
    private double currentHp = 100.0;
    private final List<org.bukkit.entity.Interaction> hitboxes = new ArrayList<>();
    private final Map<String, Integer> activeSubsystemModes = new HashMap<>(); // key: playerId_subsystemName -> mode index
    private final Map<UUID, Boolean> weaponCamActive = new HashMap<>(); // key: playerId -> active
    private final Map<String, Boolean> subsystemWasdAiming = new HashMap<>(); // key: subsystemName -> true/false
    private final Map<String, Double> subsystemAimYaw = new HashMap<>(); // key: subsystemName -> relative aim yaw
    private final Map<String, Double> subsystemAimPitch = new HashMap<>(); // key: subsystemName -> relative aim pitch
    private final Map<UUID, Boolean> lastJumpStates = new HashMap<>();
    private final Map<String, Double> subsystemRelativeYaw = new HashMap<>();
    private final Map<String, Double> subsystemRelativePitch = new HashMap<>();
    private int engineSoundTimer = 0;

    public ModelInstance(BdeModel model, Location spawnLocation, double scale) {
        this.id = UUID.randomUUID();
        this.model = model;
        this.spawnLocation = spawnLocation;
        this.scale = scale;
        this.currentLocation = spawnLocation.clone();
        this.currentYaw = (float) spawnLocation.getYaw();
        if (model.getVehicleStats() != null) {
            this.currentHp = model.getVehicleStats().getMaxHp();
        }
    }

    public UUID getId() {
        return id;
    }

    public BdeModel getModel() {
        return model;
    }

    public Location getSpawnLocation() {
        return spawnLocation;
    }

    public double getScale() {
        return scale;
    }

    public void setScale(double scale) {
        this.scale = scale;
    }

    public Entity getRootEntity() {
        return rootEntity;
    }

    public void setRootEntity(Entity rootEntity) {
        this.rootEntity = rootEntity;
    }

    public Entity getVehicleRoot() {
        return vehicleRoot;
    }

    public void setVehicleRoot(Entity vehicleRoot) {
        this.vehicleRoot = vehicleRoot;
    }

    public Entity getDriverSeat() {
        return driverSeat;
    }

    public void setDriverSeat(Entity driverSeat) {
        this.driverSeat = driverSeat;
    }

    public Location getCurrentLocation() {
        return currentLocation;
    }

    public void setCurrentLocation(Location currentLocation) {
        this.currentLocation = currentLocation;
    }

    public Location getLocation() {
        if (vehicleRoot != null && vehicleRoot.isValid()) {
            return vehicleRoot.getLocation();
        }
        if (rootEntity != null && rootEntity.isValid()) {
            return rootEntity.getLocation();
        }
        if (currentLocation != null) {
            return currentLocation;
        }
        return spawnLocation;
    }

    public List<Display> getPassengers() {
        return passengers;
    }

    public void addPassenger(Display display) {
        this.passengers.add(display);
        for (String tag : display.getScoreboardTags()) {
            if (tag.startsWith("bde_")) {
                this.taggedPassengers.put(tag, display);
            }
        }
    }

    public Display getPassengerByTag(String tag) {
        return taggedPassengers.get(tag);
    }

    public Map<String, Display> getTaggedPassengers() {
        return taggedPassengers;
    }

    public void cleanup() {
        // Explicitly remove all passenger display entities to prevent orphans
        for (Display display : passengers) {
            if (display != null && display.isValid()) {
                display.remove();
            }
        }
        passengers.clear();
        taggedPassengers.clear();

        // Remove passenger seats
        for (org.bukkit.entity.ArmorStand seat : passengerSeats) {
            if (seat != null && seat.isValid()) {
                Entity vehicle = seat.getVehicle();
                if (vehicle != null && vehicle.isValid()) {
                    vehicle.remove();
                }
                seat.remove();
            }
        }
        passengerSeats.clear();

        // Remove all hitboxes
        for (org.bukkit.entity.Interaction hitbox : hitboxes) {
            if (hitbox != null && hitbox.isValid()) {
                hitbox.remove();
            }
        }
        hitboxes.clear();

        // Remove vehicle root
        if (vehicleRoot != null && vehicleRoot.isValid()) {
            vehicleRoot.remove();
        }
        vehicleRoot = null;

        // Remove driver seat
        if (driverSeat != null && driverSeat.isValid()) {
            driverSeat.remove();
        }
        driverSeat = null;

        // Remove the root entity
        if (rootEntity != null && rootEntity.isValid()) {
            rootEntity.remove();
        }
        rootEntity = null;

        activeSubsystemModes.clear();
        weaponCamActive.clear();
    }

    public double getCurrentSpeed() {
        return currentSpeed;
    }

    public void setCurrentSpeed(double currentSpeed) {
        this.currentSpeed = currentSpeed;
    }

    public List<org.bukkit.entity.ArmorStand> getPassengerSeats() {
        return passengerSeats;
    }

    public float getCurrentYaw() {
        return currentYaw;
    }

    public void setCurrentYaw(float currentYaw) {
        this.currentYaw = currentYaw;
    }

    public double getVelocityX() {
        return velocityX;
    }

    public void setVelocityX(double velocityX) {
        this.velocityX = velocityX;
    }

    public double getVelocityZ() {
        return velocityZ;
    }

    public void setVelocityZ(double velocityZ) {
        this.velocityZ = velocityZ;
    }

    public double getCurrentHp() {
        return currentHp;
    }

    public void setCurrentHp(double currentHp) {
        this.currentHp = currentHp;
    }

    public List<org.bukkit.entity.Interaction> getHitboxes() {
        return hitboxes;
    }

    public int getSubsystemMode(UUID playerId, String subName) {
        return activeSubsystemModes.getOrDefault(playerId.toString() + "_" + subName, 0);
    }

    public void cycleSubsystemMode(UUID playerId, String subName, int delta, List<String> modes) {
        if (modes == null || modes.isEmpty()) return;
        String key = playerId.toString() + "_" + subName;
        int current = activeSubsystemModes.getOrDefault(key, 0);
        int next = (current + delta) % modes.size();
        if (next < 0) next += modes.size();
        activeSubsystemModes.put(key, next);
    }

    public boolean isWeaponCamActive(UUID playerId) {
        return weaponCamActive.getOrDefault(playerId, false);
    }

    public void toggleWeaponCam(UUID playerId) {
        weaponCamActive.put(playerId, !isWeaponCamActive(playerId));
    }

    public boolean isSubsystemWasdAiming(String subName) {
        return subsystemWasdAiming.getOrDefault(subName, false);
    }

    public void setSubsystemWasdAiming(String subName, boolean value) {
        subsystemWasdAiming.put(subName, value);
    }

    public double getSubsystemAimYaw(String subName, double defaultValue) {
        return subsystemAimYaw.getOrDefault(subName, defaultValue);
    }

    public void setSubsystemAimYaw(String subName, double value) {
        subsystemAimYaw.put(subName, value);
    }

    public double getSubsystemAimPitch(String subName, double defaultValue) {
        return subsystemAimPitch.getOrDefault(subName, defaultValue);
    }

    public void setSubsystemAimPitch(String subName, double value) {
        subsystemAimPitch.put(subName, value);
    }

    public boolean getLastJumpState(UUID id) {
        return lastJumpStates.getOrDefault(id, false);
    }

    public void setLastJumpState(UUID id, boolean val) {
        lastJumpStates.put(id, val);
    }

    public double getSubsystemRelativeYaw(String name) {
        return subsystemRelativeYaw.getOrDefault(name, 0.0);
    }

    public void setSubsystemRelativeYaw(String name, double val) {
        subsystemRelativeYaw.put(name, val);
    }

    public double getSubsystemRelativePitch(String name) {
        return subsystemRelativePitch.getOrDefault(name, 0.0);
    }

    public void setSubsystemRelativePitch(String name, double val) {
        subsystemRelativePitch.put(name, val);
    }

    public int getEngineSoundTimer() {
        return engineSoundTimer;
    }

    public void setEngineSoundTimer(int timer) {
        this.engineSoundTimer = timer;
    }
}
