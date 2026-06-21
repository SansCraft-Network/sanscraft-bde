package top.sanscraft.bde.converter;

import top.sanscraft.bde.SansCraftBDEPlugin;
import top.sanscraft.bde.model.BdeModel;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ObjConverter {
    private final SansCraftBDEPlugin plugin;

    public static class Material {
        public String name;
        public float[] baseColor = new float[]{1.0f, 1.0f, 1.0f, 1.0f};
        public BufferedImage texture;
    }

    public ObjConverter(SansCraftBDEPlugin plugin) {
        this.plugin = plugin;
    }

    public BdeModel convert(File file, ConversionMapper mapper, int density) throws Exception {
        return convert(file, mapper, density, -1.0, -1);
    }

    public BdeModel convert(File file, ConversionMapper mapper, int density, double targetSizeBlocks) throws Exception {
        return convert(file, mapper, density, targetSizeBlocks, -1);
    }

    public BdeModel convert(File file, ConversionMapper mapper, int density, double targetSizeBlocks, int maxDisplaysCap) throws Exception {
        if (!file.exists()) {
            throw new IOException("File not found: " + file.getPath());
        }

        List<float[]> vertices = new ArrayList<>();
        List<float[]> texCoords = new ArrayList<>();
        Map<String, Material> materials = new HashMap<>();
        List<ModelVoxelizer.Triangle> triangles = new ArrayList<>();

        Material currentMaterial = null;

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                String[] tokens = line.split("\\s+");
                if (tokens.length == 0) continue;

                String type = tokens[0].toLowerCase();
                switch (type) {
                    case "mtllib":
                        if (tokens.length > 1) {
                            String mtlFilename = tokens[1];
                            File mtlFile = new File(file.getParentFile(), mtlFilename);
                            if (mtlFile.exists()) {
                                parseMtl(mtlFile, materials);
                            }
                        }
                        break;
                    case "usemtl":
                        if (tokens.length > 1) {
                            currentMaterial = materials.get(tokens[1]);
                        }
                        break;
                    case "v":
                        if (tokens.length >= 4) {
                            vertices.add(new float[]{
                                    Float.parseFloat(tokens[1]),
                                    Float.parseFloat(tokens[2]),
                                    Float.parseFloat(tokens[3])
                            });
                        }
                        break;
                    case "vt":
                        if (tokens.length >= 3) {
                            texCoords.add(new float[]{
                                    Float.parseFloat(tokens[1]),
                                    Float.parseFloat(tokens[2])
                            });
                        }
                        break;
                    case "f":
                        if (tokens.length >= 4) {
                            // Parse vertices specs: v/vt/vn
                            List<int[]> faceSpecs = new ArrayList<>();
                            for (int i = 1; i < tokens.length; i++) {
                                String[] parts = tokens[i].split("/", -1);
                                int vIdx = resolveIndex(parts[0], vertices.size());
                                int vtIdx = parts.length > 1 ? resolveIndex(parts[1], texCoords.size()) : -1;
                                faceSpecs.add(new int[]{vIdx, vtIdx});
                            }

                            // Fan triangulation
                            for (int i = 1; i < faceSpecs.size() - 1; i++) {
                                int[] spec1 = faceSpecs.get(0);
                                int[] spec2 = faceSpecs.get(i);
                                int[] spec3 = faceSpecs.get(i + 1);

                                if (spec1[0] == -1 || spec2[0] == -1 || spec3[0] == -1) continue;

                                ModelVoxelizer.Triangle triangle = new ModelVoxelizer.Triangle();
                                triangle.p1 = vertices.get(spec1[0]);
                                triangle.p2 = vertices.get(spec2[0]);
                                triangle.p3 = vertices.get(spec3[0]);

                                if (spec1[1] != -1 && spec2[1] != -1 && spec3[1] != -1) {
                                    triangle.uv1 = texCoords.get(spec1[1]);
                                    triangle.uv2 = texCoords.get(spec2[1]);
                                    triangle.uv3 = texCoords.get(spec3[1]);
                                }

                                if (currentMaterial != null) {
                                    triangle.baseColor = currentMaterial.baseColor;
                                    triangle.texture = currentMaterial.texture;
                                }

                                triangles.add(triangle);
                            }
                        }
                        break;
                    default:
                        break;
                }
            }
        }

        String projectId = file.getName().replaceAll("\\.[^.]+$", "");
        return ModelVoxelizer.voxelize(projectId, triangles, density, mapper, targetSizeBlocks, maxDisplaysCap);
    }

    private int resolveIndex(String token, int listSize) {
        if (token == null || token.trim().isEmpty()) return -1;
        try {
            int val = Integer.parseInt(token);
            if (val > 0) return val - 1;
            return listSize + val;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void parseMtl(File file, Map<String, Material> materials) {
        Material currentMat = null;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                String[] tokens = line.split("\\s+");
                if (tokens.length == 0) continue;

                String type = tokens[0].toLowerCase();
                switch (type) {
                    case "newmtl":
                        if (tokens.length > 1) {
                            currentMat = new Material();
                            currentMat.name = tokens[1];
                            materials.put(currentMat.name, currentMat);
                        }
                        break;
                    case "kd":
                        if (currentMat != null && tokens.length >= 4) {
                            currentMat.baseColor = new float[]{
                                    Float.parseFloat(tokens[1]),
                                    Float.parseFloat(tokens[2]),
                                    Float.parseFloat(tokens[3]),
                                    1.0f
                            };
                        }
                        break;
                    case "map_kd":
                        if (currentMat != null && tokens.length > 1) {
                            // The rest of the line might contain spaces in the filename, join them
                            StringBuilder sb = new StringBuilder();
                            for (int i = 1; i < tokens.length; i++) {
                                sb.append(tokens[i]);
                                if (i < tokens.length - 1) sb.append(" ");
                            }
                            String filename = sb.toString();
                            File texFile = new File(file.getParentFile(), filename);
                            if (texFile.exists()) {
                                try {
                                    currentMat.texture = ImageIO.read(texFile);
                                } catch (IOException e) {
                                    // ignore texture load error
                                }
                            }
                        }
                        break;
                    default:
                        break;
                }
            }
        } catch (Exception e) {
            // ignore mtl parsing errors
        }
    }
}
