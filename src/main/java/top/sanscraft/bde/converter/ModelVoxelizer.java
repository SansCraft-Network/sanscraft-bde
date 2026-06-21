package top.sanscraft.bde.converter;

import org.bukkit.Material;
import top.sanscraft.bde.model.BdeModel;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ModelVoxelizer {

    public static class Triangle {
        public float[] p1, p2, p3;     // [x, y, z]
        public float[] uv1, uv2, uv3;  // [u, v] (optional)
        public float[] c1, c2, c3;     // [r, g, b, a] (optional vertex color)
        public float[] baseColor;      // [r, g, b, a] (fallback material color)
        public BufferedImage texture;  // material texture (optional)
    }

    public static BdeModel voxelize(String projectId, List<Triangle> triangles, int density, ConversionMapper mapper) throws Exception {
        return voxelize(projectId, triangles, density, mapper, -1.0, -1);
    }

    public static BdeModel voxelize(String projectId, List<Triangle> triangles, int density, ConversionMapper mapper, double targetSizeBlocks) throws Exception {
        return voxelize(projectId, triangles, density, mapper, targetSizeBlocks, -1);
    }

    public static BdeModel voxelize(String projectId, List<Triangle> triangles, int density, ConversionMapper mapper, double targetSizeBlocks, int maxDisplaysCap) throws Exception {
        if (triangles.isEmpty()) {
            BdeModel emptyModel = new BdeModel();
            emptyModel.setVersion("26.1");
            emptyModel.setType("full");
            emptyModel.setProjectId(projectId);
            emptyModel.setPassengers(new ArrayList<>());
            return emptyModel;
        }

        // Clone triangles to preserve vertex sharing identity while preventing mutation of caller data
        List<Triangle> workingTriangles = new ArrayList<>();
        java.util.Map<float[], float[]> vertexCopies = new java.util.IdentityHashMap<>();
        for (Triangle t : triangles) {
            Triangle ct = new Triangle();
            ct.uv1 = t.uv1; ct.uv2 = t.uv2; ct.uv3 = t.uv3;
            ct.c1 = t.c1; ct.c2 = t.c2; ct.c3 = t.c3;
            ct.baseColor = t.baseColor;
            ct.texture = t.texture;
            
            ct.p1 = vertexCopies.computeIfAbsent(t.p1, p -> new float[]{p[0], p[1], p[2]});
            ct.p2 = vertexCopies.computeIfAbsent(t.p2, p -> new float[]{p[0], p[1], p[2]});
            ct.p3 = vertexCopies.computeIfAbsent(t.p3, p -> new float[]{p[0], p[1], p[2]});
            workingTriangles.add(ct);
        }

        // 1. Calculate Bounding Box of all vertices
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        float maxZ = Float.NEGATIVE_INFINITY;

        for (Triangle t : workingTriangles) {
            for (float[] p : new float[][]{t.p1, t.p2, t.p3}) {
                if (p[0] < minX) minX = p[0];
                if (p[1] < minY) minY = p[1];
                if (p[2] < minZ) minZ = p[2];
                if (p[0] > maxX) maxX = p[0];
                if (p[1] > maxY) maxY = p[1];
                if (p[2] > maxZ) maxZ = p[2];
            }
        }

        float sizeX = Math.max(0.001f, maxX - minX);
        float sizeY = Math.max(0.001f, maxY - minY);
        float sizeZ = Math.max(0.001f, maxZ - minZ);
        float maxDim = Math.max(sizeX, Math.max(sizeY, sizeZ));

        // Apply target size scaling if specified
        if (targetSizeBlocks > 0) {
            float scaleFactor = (float) targetSizeBlocks / maxDim;
            java.util.Set<float[]> scaled = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            for (Triangle t : workingTriangles) {
                for (float[] p : new float[][]{t.p1, t.p2, t.p3}) {
                    if (scaled.add(p)) {
                        p[0] *= scaleFactor;
                        p[1] *= scaleFactor;
                        p[2] *= scaleFactor;
                    }
                }
            }
            // Recalculate bounding box
            minX = Float.POSITIVE_INFINITY; minY = Float.POSITIVE_INFINITY; minZ = Float.POSITIVE_INFINITY;
            maxX = Float.NEGATIVE_INFINITY; maxY = Float.NEGATIVE_INFINITY; maxZ = Float.NEGATIVE_INFINITY;
            for (Triangle t : workingTriangles) {
                for (float[] p : new float[][]{t.p1, t.p2, t.p3}) {
                    if (p[0] < minX) minX = p[0];
                    if (p[1] < minY) minY = p[1];
                    if (p[2] < minZ) minZ = p[2];
                    if (p[0] > maxX) maxX = p[0];
                    if (p[1] > maxY) maxY = p[1];
                    if (p[2] > maxZ) maxZ = p[2];
                }
            }
            sizeX = Math.max(0.001f, maxX - minX);
            sizeY = Math.max(0.001f, maxY - minY);
            sizeZ = Math.max(0.001f, maxZ - minZ);
            maxDim = Math.max(sizeX, Math.max(sizeY, sizeZ));
        }

        // Calculate grid dimensions based on voxel density
        int gridX = Math.max(1, Math.round(sizeX * density));
        int gridY = Math.max(1, Math.round(sizeY * density));
        int gridZ = Math.max(1, Math.round(sizeZ * density));
        int maxGridDim = Math.max(gridX, Math.max(gridY, gridZ));

        // Auto-scale to max dimension of 128 if safety limits are exceeded
        if (maxGridDim > 128) {
            float scaleFactor = 128f / maxGridDim;
            java.util.logging.Logger.getLogger("BDE").warning("[BDE] Model '" + projectId + "' voxel grid size (" + gridX + "x" + gridY + "x" + gridZ + ") exceeded safety limits. Auto-scaling down by factor " + String.format("%.4f", scaleFactor));

            java.util.Set<float[]> scaled = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            for (Triangle t : workingTriangles) {
                for (float[] p : new float[][]{t.p1, t.p2, t.p3}) {
                    if (scaled.add(p)) {
                        p[0] *= scaleFactor;
                        p[1] *= scaleFactor;
                        p[2] *= scaleFactor;
                    }
                }
            }
            // Recalculate bounding box
            minX = Float.POSITIVE_INFINITY; minY = Float.POSITIVE_INFINITY; minZ = Float.POSITIVE_INFINITY;
            maxX = Float.NEGATIVE_INFINITY; maxY = Float.NEGATIVE_INFINITY; maxZ = Float.NEGATIVE_INFINITY;
            for (Triangle t : workingTriangles) {
                for (float[] p : new float[][]{t.p1, t.p2, t.p3}) {
                    if (p[0] < minX) minX = p[0];
                    if (p[1] < minY) minY = p[1];
                    if (p[2] < minZ) minZ = p[2];
                    if (p[0] > maxX) maxX = p[0];
                    if (p[1] > maxY) maxY = p[1];
                    if (p[2] > maxZ) maxZ = p[2];
                }
            }
            sizeX = Math.max(0.001f, maxX - minX);
            sizeY = Math.max(0.001f, maxY - minY);
            sizeZ = Math.max(0.001f, maxZ - minZ);

            gridX = Math.max(1, Math.round(sizeX * density));
            gridY = Math.max(1, Math.round(sizeY * density));
            gridZ = Math.max(1, Math.round(sizeZ * density));
        }

        // 3D grid storing 32-bit ARGB colors (0 = transparent / empty)
        int[][][] grid = new int[gridX][gridY][gridZ];

        // 2. Voxelize/Rasterize Triangles onto the grid
        for (Triangle t : workingTriangles) {
            // Transform triangle vertices to grid coordinates relative to bounding box min
            float[] gp1 = new float[]{(t.p1[0] - minX) * density, (t.p1[1] - minY) * density, (t.p1[2] - minZ) * density};
            float[] gp2 = new float[]{(t.p2[0] - minX) * density, (t.p2[1] - minY) * density, (t.p2[2] - minZ) * density};
            float[] gp3 = new float[]{(t.p3[0] - minX) * density, (t.p3[1] - minY) * density, (t.p3[2] - minZ) * density};

            float[] v1 = new float[]{gp2[0] - gp1[0], gp2[1] - gp1[1], gp2[2] - gp1[2]};
            float[] v2 = new float[]{gp3[0] - gp1[0], gp3[1] - gp1[1], gp3[2] - gp1[2]};

            float len1 = (float) Math.sqrt(v1[0]*v1[0] + v1[1]*v1[1] + v1[2]*v1[2]);
            float len2 = (float) Math.sqrt(v2[0]*v2[0] + v2[1]*v2[1] + v2[2]*v2[2]);

            // Sample at least twice per voxel unit along each edge to avoid gaps
            int steps1 = Math.max(2, Math.round(len1 * 2));
            int steps2 = Math.max(2, Math.round(len2 * 2));

            for (int i = 0; i <= steps1; i++) {
                float u = (float) i / steps1;
                int maxJ = Math.round((1.0f - u) * steps2);
                for (int j = 0; j <= maxJ; j++) {
                    float v = (float) j / steps2;

                    float gx = gp1[0] + u * v1[0] + v * v2[0];
                    float gy = gp1[1] + u * v1[1] + v * v2[1];
                    float gz = gp1[2] + u * v1[2] + v * v2[2];

                    int ix = Math.max(0, Math.min(gridX - 1, Math.round(gx)));
                    int iy = Math.max(0, Math.min(gridY - 1, Math.round(gy)));
                    int iz = Math.max(0, Math.min(gridZ - 1, Math.round(gz)));

                    int argb = getSampledColor(t, u, v);
                    if (((argb >> 24) & 0xFF) > 0) {
                        grid[ix][iy][iz] = argb;
                    }
                }
            }
        }

        // 3. Resolve Colors to Block States
        // Map unique block states to integer IDs for greedy meshing
        List<String> blockStates = new ArrayList<>();
        Map<String, Integer> blockStateToId = new HashMap<>();
        
        int[][][] meshGrid = new int[gridX][gridY][gridZ];
        for (int x = 0; x < gridX; x++) {
            for (int y = 0; y < gridY; y++) {
                for (int z = 0; z < gridZ; z++) {
                    int argb = grid[x][y][z];
                    if (argb != 0) {
                        int r = (argb >> 16) & 0xFF;
                        int g = (argb >> 8) & 0xFF;
                        int b = argb & 0xFF;

                        Material mat = mapper.getBlockForColor(r, g, b);
                        String blockState = "minecraft:" + mat.name().toLowerCase();
                        
                        int id = blockStateToId.computeIfAbsent(blockState, k -> {
                            blockStates.add(k);
                            return blockStates.size();
                        });
                        meshGrid[x][y][z] = id;
                    }
                }
            }
        }

        // 4. Run Greedy Meshing Optimization on meshGrid
        List<Box> boxes = new ArrayList<>();
        boolean[][][] visited = new boolean[gridX][gridY][gridZ];

        for (int y = 0; y < gridY; y++) {
            for (int z = 0; z < gridZ; z++) {
                for (int x = 0; x < gridX; x++) {
                    if (meshGrid[x][y][z] != 0 && !visited[x][y][z]) {
                        int blockId = meshGrid[x][y][z];

                        // Expand X
                        int dx = 1;
                        while (x + dx < gridX && meshGrid[x + dx][y][z] == blockId && !visited[x + dx][y][z]) {
                            dx++;
                        }

                        // Expand Z
                        int dz = 1;
                        boolean zCanExpand = true;
                        while (z + dz < gridZ) {
                            for (int ix = 0; ix < dx; ix++) {
                                if (meshGrid[x + ix][y][z + dz] != blockId || visited[x + ix][y][z + dz]) {
                                    zCanExpand = false;
                                    break;
                                }
                            }
                            if (!zCanExpand) break;
                            dz++;
                        }

                        // Expand Y
                        int dy = 1;
                        boolean yCanExpand = true;
                        while (y + dy < gridY) {
                            for (int ix = 0; ix < dx; ix++) {
                                for (int iz = 0; iz < dz; iz++) {
                                    if (meshGrid[x + ix][y + dy][z + iz] != blockId || visited[x + ix][y + dy][z + iz]) {
                                        yCanExpand = false;
                                        break;
                                    }
                                }
                                if (!yCanExpand) break;
                            }
                            if (!yCanExpand) break;
                            dy++;
                        }

                        // Mark as visited
                        for (int iy = 0; iy < dy; iy++) {
                            for (int ix = 0; ix < dx; ix++) {
                                for (int iz = 0; iz < dz; iz++) {
                                    visited[x + ix][y + iy][z + iz] = true;
                                }
                            }
                        }

                        boxes.add(new Box(x, y, z, dx, dy, dz, blockId));
                    }
                }
            }
        }

        // Auto-reduce density if number of entities exceeds the cap
        if (maxDisplaysCap > 0 && boxes.size() > maxDisplaysCap && density > 1) {
            int newDensity = Math.max(1, (int) Math.round(density / 1.5));
            if (newDensity < density) {
                java.util.logging.Logger.getLogger("BDE").warning("[BDE] Converted model '" + projectId + "' generated " + boxes.size() + " elements, exceeding cap of " + maxDisplaysCap + ". Retrying voxelization at lower density: " + newDensity);
                return voxelize(projectId, triangles, newDensity, mapper, targetSizeBlocks, maxDisplaysCap);
            }
        }

        // 5. Construct BDE model
        BdeModel bdeModel = new BdeModel();
        bdeModel.setVersion("26.1");
        bdeModel.setType("full");
        bdeModel.setProjectId(projectId);

        List<String> passengers = new ArrayList<>();
        int index = 0;
        float voxelScale = 1.0f / density;

        for (Box box : boxes) {
            String blockState = blockStates.get(box.colorIdx - 1);

            // Translate relative to bounding box min
            float tx = minX + box.x * voxelScale;
            float ty = minY + box.y * voxelScale;
            float tz = minZ + box.z * voxelScale;

            float scaleX = box.dx * voxelScale;
            float scaleY = box.dy * voxelScale;
            float scaleZ = box.dz * voxelScale;

            // Form transformation matrix in row-major
            float[] m = new float[16];
            m[0] = scaleX; m[1] = 0f;     m[2] = 0f;     m[3] = tx;
            m[4] = 0f;     m[5] = scaleY; m[6] = 0f;     m[7] = ty;
            m[8] = 0f;     m[9] = 0f;     m[10] = scaleZ;m[11] = tz;
            m[12] = 0f;    m[13] = 0f;    m[14] = 0f;    m[15] = 1f;

            StringBuilder snbt = new StringBuilder();
            snbt.append("{id:\"minecraft:block_display\",");
            snbt.append("block_state:{Name:\"").append(blockState).append("\"},");

            snbt.append("transformation:[");
            for (int i = 0; i < 16; i++) {
                snbt.append(String.format(Locale.US, "%.6ff", m[i]));
                if (i < 15) snbt.append(",");
            }
            snbt.append("],");

            snbt.append("Tags:[\"bde_").append(index).append("\"]}");
            passengers.add(snbt.toString());
            index++;
        }

        bdeModel.setPassengers(passengers);
        return bdeModel;
    }

    private static int getSampledColor(Triangle t, float u, float v) {
        // 1. Texture mapping
        if (t.texture != null && t.uv1 != null && t.uv2 != null && t.uv3 != null) {
            float texU = (1 - u - v) * t.uv1[0] + u * t.uv2[0] + v * t.uv3[0];
            float texV = (1 - u - v) * t.uv1[1] + u * t.uv2[1] + v * t.uv3[1];

            // Normalize UV coordinates to [0, 1] range
            texU = texU - (float) Math.floor(texU);
            texV = texV - (float) Math.floor(texV);

            int width = t.texture.getWidth();
            int height = t.texture.getHeight();

            int x = Math.max(0, Math.min(width - 1, (int) (texU * width)));
            // glTF coordinates: Y is inverted relative to BufferedImage top-left origin
            int y = Math.max(0, Math.min(height - 1, (int) ((1.0f - texV) * height)));

            return t.texture.getRGB(x, y);
        }

        // 2. Vertex colors
        if (t.c1 != null && t.c2 != null && t.c3 != null) {
            float r = (1 - u - v) * t.c1[0] + u * t.c2[0] + v * t.c3[0];
            float g = (1 - u - v) * t.c1[1] + u * t.c2[1] + v * t.c3[1];
            float b = (1 - u - v) * t.c1[2] + u * t.c2[2] + v * t.c3[2];
            float a = 1.0f;
            if (t.c1.length > 3 && t.c2.length > 3 && t.c3.length > 3) {
                a = (1 - u - v) * t.c1[3] + u * t.c2[3] + v * t.c3[3];
            }

            int ir = Math.max(0, Math.min(255, Math.round(r * 255)));
            int ig = Math.max(0, Math.min(255, Math.round(g * 255)));
            int ib = Math.max(0, Math.min(255, Math.round(b * 255)));
            int ia = Math.max(0, Math.min(255, Math.round(a * 255)));

            return (ia << 24) | (ir << 16) | (ig << 8) | ib;
        }

        // 3. Fallback material baseColor
        if (t.baseColor != null) {
            int ir = Math.max(0, Math.min(255, Math.round(t.baseColor[0] * 255)));
            int ig = Math.max(0, Math.min(255, Math.round(t.baseColor[1] * 255)));
            int ib = Math.max(0, Math.min(255, Math.round(t.baseColor[2] * 255)));
            int ia = t.baseColor.length > 3 ? Math.max(0, Math.min(255, Math.round(t.baseColor[3] * 255))) : 255;

            return (ia << 24) | (ir << 16) | (ig << 8) | ib;
        }

        return 0xFFFFFFFF; // fallback to solid white
    }

    private static class Box {
        public final int x, y, z;
        public final int dx, dy, dz;
        public final int colorIdx;

        public Box(int x, int y, int z, int dx, int dy, int dz, int colorIdx) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
            this.colorIdx = colorIdx;
        }
    }
}
