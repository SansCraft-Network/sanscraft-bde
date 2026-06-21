package top.sanscraft.bde.converter;

import org.bukkit.Material;
import top.sanscraft.bde.SansCraftBDEPlugin;
import top.sanscraft.bde.model.BdeModel;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class VoxConverter {
    private final SansCraftBDEPlugin plugin;

    // Default MagicaVoxel palette
    private static final int[] DEFAULT_PALETTE = new int[256];

    static {
        // Simple fallback palette if RGBA chunk is missing
        for (int i = 0; i < 256; i++) {
            DEFAULT_PALETTE[i] = 0xFFFFFFFF; // Fallback to white
        }
    }

    public VoxConverter(SansCraftBDEPlugin plugin) {
        this.plugin = plugin;
    }

    public BdeModel convert(File file, ConversionMapper mapper, int resolutionFactor) throws Exception {
        if (!file.exists()) {
            throw new IOException("File not found: " + file.getPath());
        }

        byte[] bytes = Files.readAllBytes(file.toPath());
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        // Verify header
        byte[] magic = new byte[4];
        buffer.get(magic);
        if (!new String(magic).equals("VOX ")) {
            throw new Exception("Invalid .vox file: missing VOX header.");
        }

        int version = buffer.getInt();

        int sizeX = 0, sizeY = 0, sizeZ = 0;
        int[] palette = DEFAULT_PALETTE.clone();
        List<Voxel> rawVoxels = new ArrayList<>();

        while (buffer.hasRemaining()) {
            byte[] chunkIdBytes = new byte[4];
            buffer.get(chunkIdBytes);
            String chunkId = new String(chunkIdBytes);

            int contentSize = buffer.getInt();
            int childrenSize = buffer.getInt();

            if (chunkId.equals("SIZE")) {
                sizeX = buffer.getInt();
                sizeY = buffer.getInt();
                sizeZ = buffer.getInt();
            } else if (chunkId.equals("XYZI")) {
                int numVoxels = buffer.getInt();
                for (int i = 0; i < numVoxels; i++) {
                    int x = buffer.get() & 0xFF;
                    int y = buffer.get() & 0xFF;
                    int z = buffer.get() & 0xFF;
                    int colorIdx = buffer.get() & 0xFF;
                    rawVoxels.add(new Voxel(x, y, z, colorIdx));
                }
            } else if (chunkId.equals("RGBA")) {
                // Read 256 colors (RGBA)
                for (int i = 0; i < 256; i++) {
                    palette[i] = buffer.getInt(); // RGBA in little endian (A B G R?)
                }
            } else {
                // Skip content
                buffer.position(buffer.position() + contentSize);
            }
        }

        if (sizeX == 0 || sizeY == 0 || sizeZ == 0) {
            throw new Exception("Invalid .vox file: missing SIZE chunk.");
        }

        // Apply Resolution Downsampling
        int[][][] grid;
        if (resolutionFactor > 1) {
            int newX = (sizeX + resolutionFactor - 1) / resolutionFactor;
            int newY = (sizeY + resolutionFactor - 1) / resolutionFactor;
            int newZ = (sizeZ + resolutionFactor - 1) / resolutionFactor;

            grid = new int[newX][newY][newZ];
            
            // Temporary structures to count colors inside voxel groups
            Map<String, Map<Integer, Integer>> groupColors = new HashMap<>();

            for (Voxel v : rawVoxels) {
                int gx = v.x / resolutionFactor;
                int gy = v.y / resolutionFactor;
                int gz = v.z / resolutionFactor;

                String key = gx + "," + gy + "," + gz;
                groupColors.computeIfAbsent(key, k -> new HashMap<>());
                groupColors.get(key).merge(v.colorIndex, 1, Integer::sum);
            }

            // Set voxels based on majority vote inside group
            for (String key : groupColors.keySet()) {
                String[] coords = key.split(",");
                int gx = Integer.parseInt(coords[0]);
                int gy = Integer.parseInt(coords[1]);
                int gz = Integer.parseInt(coords[2]);

                Map<Integer, Integer> colors = groupColors.get(key);
                int bestColor = 0;
                int maxCount = 0;
                for (Map.Entry<Integer, Integer> entry : colors.entrySet()) {
                    if (entry.getValue() > maxCount) {
                        maxCount = entry.getValue();
                        bestColor = entry.getKey();
                    }
                }

                grid[gx][gy][gz] = bestColor;
            }

            sizeX = newX;
            sizeY = newY;
            sizeZ = newZ;
        } else {
            grid = new int[sizeX][sizeY][sizeZ];
            for (Voxel v : rawVoxels) {
                grid[v.x][v.y][v.z] = v.colorIndex;
            }
        }

        // Run Greedy Meshing Optimization
        List<Box> boxes = new ArrayList<>();
        boolean[][][] visited = new boolean[sizeX][sizeY][sizeZ];

        for (int y = 0; y < sizeY; y++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int x = 0; x < sizeX; x++) {
                    if (grid[x][y][z] != 0 && !visited[x][y][z]) {
                        int colorIdx = grid[x][y][z];

                        // Expand X
                        int dx = 1;
                        while (x + dx < sizeX && grid[x + dx][y][z] == colorIdx && !visited[x + dx][y][z]) {
                            dx++;
                        }

                        // Expand Z
                        int dz = 1;
                        boolean zCanExpand = true;
                        while (z + dz < sizeZ) {
                            for (int ix = 0; ix < dx; ix++) {
                                if (grid[x + ix][y][z + dz] != colorIdx || visited[x + ix][y][z + dz]) {
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
                        while (y + dy < sizeY) {
                            for (int ix = 0; ix < dx; ix++) {
                                for (int iz = 0; iz < dz; iz++) {
                                    if (grid[x + ix][y + dy][z + iz] != colorIdx || visited[x + ix][y + dy][z + iz]) {
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

                        boxes.add(new Box(x, y, z, dx, dy, dz, colorIdx));
                    }
                }
            }
        }

        // Map palette color indexes to Minecraft block states
        String[] blockStates = new String[256];
        for (int i = 0; i < 256; i++) {
            int rgba = palette[i];
            // RGBA structure in little endian:
            // byte 0 = R, byte 1 = G, byte 2 = B, byte 3 = A
            int r = (rgba) & 0xFF;
            int g = (rgba >> 8) & 0xFF;
            int b = (rgba >> 16) & 0xFF;

            Material material = mapper.getBlockForColor(r, g, b);
            blockStates[i] = "minecraft:" + material.name().toLowerCase();
        }

        // Construct BDE model
        BdeModel bdeModel = new BdeModel();
        bdeModel.setVersion("26.1");
        bdeModel.setType("full");
        bdeModel.setProjectId(file.getName().replace(".vox", ""));

        List<String> passengers = new ArrayList<>();
        int index = 0;

        float voxelScale = 1.0f / 16.0f * resolutionFactor;

        for (Box box : boxes) {
            String blockState = blockStates[box.colorIdx - 1];

            // Translation offsets (aligned to voxel center relative to grid minimum)
            float tx = box.x * voxelScale;
            float ty = box.y * voxelScale;
            float tz = box.z * voxelScale;

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
            
            // Format matrix
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

    private static class Voxel {
        public final int x;
        public final int y;
        public final int z;
        public final int colorIndex;

        public Voxel(int x, int y, int z, int colorIndex) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.colorIndex = colorIndex;
        }
    }

    private static class Box {
        public final int x;
        public final int y;
        public final int z;
        public final int dx;
        public final int dy;
        public final int dz;
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
