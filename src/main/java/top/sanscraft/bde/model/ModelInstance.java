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
    private final List<org.bukkit.entity.ArmorStand> passengerSeats = new ArrayList<>();

    public ModelInstance(BdeModel model, Location spawnLocation, double scale) {
        this.id = UUID.randomUUID();
        this.model = model;
        this.spawnLocation = spawnLocation;
        this.scale = scale;
        this.currentLocation = spawnLocation.clone();
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
                seat.remove();
            }
        }
        passengerSeats.clear();

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
}
