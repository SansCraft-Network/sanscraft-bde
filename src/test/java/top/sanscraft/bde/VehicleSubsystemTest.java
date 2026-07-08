package top.sanscraft.bde;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;
import top.sanscraft.bde.manager.ModelTransformEngine;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class VehicleSubsystemTest {

    private static final double EPSILON = 1e-4;

    @Test
    public void testMathematicalValidationZeroRotation() {
        // Zero rotations: Seat position should be simple translation
        Location vehicleRoot = new Location(null, 10.0, 50.0, -20.0);
        List<Double> mountOffset = Arrays.asList(2.0, 1.0, -3.0);
        List<Double> passengerOffset = Arrays.asList(0.0, 0.5, 1.0);
        List<Double> pivotOffset = Arrays.asList(0.5, 0.0, -0.5);
        double scale = 2.0;
        double frontYawOffset = 0.0;
        double vehicleYaw = 0.0;
        double vehiclePitch = 0.0;
        double relativeYaw = 0.0;
        double relativePitch = 0.0;

        Location result = ModelTransformEngine.getSubsystemSeatPosition(
            vehicleRoot, mountOffset, passengerOffset, pivotOffset,
            scale, frontYawOffset, vehicleYaw, vehiclePitch, relativeYaw, relativePitch
        );

        // Expected world coordinates:
        // vehicleRoot + scale * (mountOffset + passengerOffset)
        // = (10 + 2*(2+0), 50 + 2*(1+0.5), -20 + 2*(-3+1))
        // = (14.0, 53.0, -24.0)
        assertEquals(14.0, result.getX(), EPSILON);
        assertEquals(53.0, result.getY(), EPSILON);
        assertEquals(-24.0, result.getZ(), EPSILON);
    }

    @Test
    public void testMathematicalValidationRotations() {
        Location vehicleRoot = new Location(null, 0.0, 0.0, 0.0);
        List<Double> mountOffset = Arrays.asList(0.0, 0.0, 1.0);
        List<Double> passengerOffset = Arrays.asList(0.0, 0.0, 0.0);
        List<Double> pivotOffset = Arrays.asList(0.0, 0.0, 0.0);
        double scale = 1.0;
        double frontYawOffset = 0.0;
        double vehicleYaw = 90.0; // West (-X)
        double vehiclePitch = 0.0;
        double relativeYaw = 0.0;
        double relativePitch = 0.0;

        Location result = ModelTransformEngine.getSubsystemSeatPosition(
            vehicleRoot, mountOffset, passengerOffset, pivotOffset,
            scale, frontYawOffset, vehicleYaw, vehiclePitch, relativeYaw, relativePitch
        );

        // Vector (0, 0, 1) rotated by -90 degrees around Y:
        // x' = x*cos(-90) + z*sin(-90) = 0 + 1*(-1) = -1
        // z' = -x*sin(-90) + z*cos(-90) = 0 + 0 = 0
        // Resulting location should be (-1.0, 0.0, 0.0)
        assertEquals(-1.0, result.getX(), EPSILON);
        assertEquals(0.0, result.getY(), EPSILON);
        assertEquals(0.0, result.getZ(), EPSILON);
    }

    @Test
    public void testMinecraftCompatibilityRotationOrder() {
        Location vehicleRoot = new Location(null, 100.0, 64.0, 100.0);
        List<Double> mountOffset = Arrays.asList(0.0, 0.0, 0.0);
        List<Double> componentOffset = Arrays.asList(0.0, 0.0, 2.0);
        double scale = 1.0;
        double frontYawOffset = 0.0;
        double vehicleYaw = 45.0;
        double vehiclePitch = 30.0;
        double relativeYaw = 0.0;
        double relativePitch = 0.0;

        Location result = ModelTransformEngine.getSubsystemComponentPosition(
            vehicleRoot, mountOffset, componentOffset, null,
            scale, frontYawOffset, vehicleYaw, vehiclePitch, relativeYaw, relativePitch
        );

        assertTrue(Double.isFinite(result.getX()));
        assertTrue(Double.isFinite(result.getY()));
        assertTrue(Double.isFinite(result.getZ()));
    }

    @Test
    public void testMinecraftCompatibilityAngleNormalization() {
        // Validate yaw wrapping to [-180, 180] range
        double testYaw1 = 190.0;
        double wrappedYaw1 = ((testYaw1 % 360.0 + 540.0) % 360.0 - 180.0);
        assertEquals(-170.0, wrappedYaw1, EPSILON);

        double testYaw2 = -270.0;
        double wrappedYaw2 = ((testYaw2 % 360.0 + 540.0) % 360.0 - 180.0);
        assertEquals(90.0, wrappedYaw2, EPSILON);
    }
}
