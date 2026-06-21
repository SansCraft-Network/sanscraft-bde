package top.sanscraft.bde.manager;

import org.bukkit.Location;
import org.bukkit.util.Transformation;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import top.sanscraft.bde.model.BdeModel;

import java.util.List;

public class ModelTransformEngine {

    /**
     * Computes the world transformation matrix of the vehicle root or model base.
     */
    public static Matrix4f getWorldMatrix(Location location, double scale, float yaw) {
        Matrix4f matrix = new Matrix4f();
        matrix.translation((float) location.getX(), (float) location.getY(), (float) location.getZ());
        matrix.rotateY((float) Math.toRadians(-yaw));
        matrix.scale((float) scale);
        return matrix;
    }

    /**
     * Scales both the translation and the linear part of a 4x4 matrix by the model scale.
     */
    public static Matrix4f scaleMatrix(Matrix4f matrix, float scale) {
        Matrix4f scaled = new Matrix4f(matrix);
        Vector3f translation = new Vector3f();
        scaled.getTranslation(translation);
        translation.mul(scale);
        scaled.setTranslation(translation);
        scaled.scale(scale);
        return scaled;
    }

    /**
     * Decomposes a 4x4 matrix into a Bukkit Transformation object.
     */
    public static Transformation decomposeToTransformation(Matrix4f matrix) {
        Vector3f translation = new Vector3f();
        matrix.getTranslation(translation);

        Vector3f scale = new Vector3f();
        matrix.getScale(scale);

        Quaternionf rotation = new Quaternionf();
        matrix.getUnnormalizedRotation(rotation);

        return new Transformation(translation, rotation, scale, new Quaternionf());
    }

    /**
     * Computes the passenger display's relative matrix.
     * Order of operations: Scale -> Rotate -> Translate -> SeatOffset -> MountOffset
     */
    public static Matrix4f getDisplayPassengerMatrix(Matrix4f localMatrix, double modelScale, float interactionHeight, BdeModel model, float yaw, float pitch, boolean hasVehicleRoot) {
        Matrix4f scaledLocal = scaleMatrix(localMatrix, (float) modelScale);

        Matrix4f mPass = new Matrix4f();
        // 1. MountOffset
        mPass.translation(0, -interactionHeight, 0);

        // 2. Yaw & Pitch rotations of the model instance
        mPass.rotateY((float) Math.toRadians(-yaw));
        mPass.rotateX((float) Math.toRadians(pitch));

        // 3. FrontYawOffset
        if (model != null && model.getFrontYawOffset() != 0.0) {
            mPass.rotateY((float) Math.toRadians(model.getFrontYawOffset()));
        }

        // 4. SeatOffset (displacement by -seat_offset)
        if (model != null && model.getSeatOffset() != null && model.getSeatOffset().size() == 3) {
            float sx = (float) (model.getSeatOffset().get(0) * modelScale);
            float sy = (float) (model.getSeatOffset().get(1) * modelScale);
            float sz = (float) (model.getSeatOffset().get(2) * modelScale);
            mPass.translate(-sx, -sy, -sz);
        }

        // 5. Local matrix (contains Scale, Rotate, Translate, Pivot)
        mPass.mul(scaledLocal);

        return mPass;
    }

    /**
     * Computes the absolute display matrix relative to the model's base location/yaw (no seat/mount offsets).
     */
    public static Matrix4f getDisplayWorldLocalMatrix(Matrix4f localMatrix, double modelScale, BdeModel model, float yaw, float pitch) {
        Matrix4f scaledLocal = scaleMatrix(localMatrix, (float) modelScale);

        Matrix4f mWorldLocal = new Matrix4f();
        // Yaw & Pitch rotations of the model instance
        mWorldLocal.rotateY((float) Math.toRadians(-yaw));
        mWorldLocal.rotateX((float) Math.toRadians(pitch));

        // FrontYawOffset
        if (model != null && model.getFrontYawOffset() != 0.0) {
            mWorldLocal.rotateY((float) Math.toRadians(model.getFrontYawOffset()));
        }
        mWorldLocal.mul(scaledLocal);

        return mWorldLocal;
    }

    /**
     * Calculates the world location for the hitbox (Interaction entity).
     * Formula: hitboxPos = vehicleRootPos + R(seatOffset) + hitboxOffset
     */
    public static Location getHitboxPosition(Location vehicleRootLoc, BdeModel model, double scale) {
        double sx = 0, sy = 0, sz = 0;
        if (model != null && model.getSeatOffset() != null && model.getSeatOffset().size() == 3) {
            sx = model.getSeatOffset().get(0) * scale;
            sy = model.getSeatOffset().get(1) * scale;
            sz = model.getSeatOffset().get(2) * scale;
        }

        double frontOffset = 0.0;
        if (model != null) {
            frontOffset = model.getFrontYawOffset();
        }
        if (frontOffset != 0.0) {
            double radFront = Math.toRadians(frontOffset);
            double cosF = Math.cos(radFront);
            double sinF = Math.sin(radFront);
            double tempX = sx * cosF - sz * sinF;
            sz = sx * sinF + sz * cosF;
            sx = tempX;
        }

        // Rotate seatOffset by vehicleRootLoc yaw (Minecraft yaw: clockwise, so rotate by -yaw)
        double yawRad = Math.toRadians(-vehicleRootLoc.getYaw());
        double cos = Math.cos(yawRad);
        double sin = Math.sin(yawRad);

        double rx = sx * cos - sz * sin;
        double rz = sx * sin + sz * cos;

        // hitboxOffset is 0,0,0
        return vehicleRootLoc.clone().add(rx, sy, rz);
    }

    /**
     * Calculates the world location for co-passenger seats.
     * Formula: seatPos = vehicleRootLoc + R(passengerOffset - seatOffset)
     */
    public static Location getSeatPosition(Location vehicleRootLoc, List<Double> passengerOffset, List<Double> driverSeatOffset, double scale, double frontYawOffset) {
        double px = passengerOffset.get(0) * scale;
        double py = passengerOffset.get(1) * scale;
        double pz = passengerOffset.get(2) * scale;

        double dx = 0, dy = 0, dz = 0;
        if (driverSeatOffset != null && driverSeatOffset.size() == 3) {
            dx = driverSeatOffset.get(0) * scale;
            dy = driverSeatOffset.get(1) * scale;
            dz = driverSeatOffset.get(2) * scale;
        }

        double rx = px - dx;
        double ry = py - dy;
        double rz = pz - dz;

        if (frontYawOffset != 0.0) {
            double radFront = Math.toRadians(frontYawOffset);
            double cosF = Math.cos(radFront);
            double sinF = Math.sin(radFront);
            double tempX = rx * cosF - rz * sinF;
            rz = rx * sinF + rz * cosF;
            rx = tempX;
        }

        double yawRad = Math.toRadians(-vehicleRootLoc.getYaw());
        double cos = Math.cos(yawRad);
        double sin = Math.sin(yawRad);

        double rotX = rx * cos - rz * sin;
        double rotZ = rx * sin + rz * cos;

        return vehicleRootLoc.clone().add(rotX, ry, rotZ);
    }
}
