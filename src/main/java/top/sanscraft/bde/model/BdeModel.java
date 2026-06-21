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
    }

    public static class VehicleStats {
        private double top_speed = 0.3;
        private double acceleration = 0.02;
        private double deceleration = 0.03;
        private double reverse_speed = 0.1;
        private double turn_speed = 4.0;
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

        public Map<String, Object> getAdditionalProperties() { return additional_properties; }
        public void setAdditionalProperties(Map<String, Object> additionalProperties) { this.additional_properties = additionalProperties; }
    }
}
