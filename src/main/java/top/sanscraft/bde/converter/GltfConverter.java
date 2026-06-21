package top.sanscraft.bde.converter;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import top.sanscraft.bde.SansCraftBDEPlugin;
import top.sanscraft.bde.model.BdeModel;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public class GltfConverter {
    private final SansCraftBDEPlugin plugin;
    private final Gson gson = new Gson();

    public GltfConverter(SansCraftBDEPlugin plugin) {
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

        String jsonContent = null;
        byte[] binBuffer = null;
        boolean isGlb = file.getName().toLowerCase().endsWith(".glb");

        if (isGlb) {
            byte[] fileBytes = Files.readAllBytes(file.toPath());
            ByteBuffer buffer = ByteBuffer.wrap(fileBytes);
            buffer.order(ByteOrder.LITTLE_ENDIAN);

            int magic = buffer.getInt();
            if (magic != 0x46546C67) { // "glTF"
                throw new Exception("Invalid GLB magic: expected glTF.");
            }
            int version = buffer.getInt();
            int length = buffer.getInt();

            while (buffer.hasRemaining()) {
                int chunkLength = buffer.getInt();
                int chunkType = buffer.getInt();
                byte[] chunkData = new byte[chunkLength];
                buffer.get(chunkData);

                if (chunkType == 0x4E4F534A) { // "JSON"
                    jsonContent = new String(chunkData, StandardCharsets.UTF_8);
                } else if (chunkType == 0x004E4942) { // "BIN"
                    binBuffer = chunkData;
                }
            }
        } else {
            // standard .gltf JSON file
            try (FileReader reader = new FileReader(file)) {
                JsonObject gltfJson = gson.fromJson(reader, JsonObject.class);
                jsonContent = gltfJson.toString();
            }
        }

        if (jsonContent == null) {
            throw new Exception("Failed to load glTF JSON content.");
        }

        JsonObject gltf = gson.fromJson(jsonContent, JsonObject.class);
        JsonArray meshes = gltf.getAsJsonArray("meshes");
        JsonArray accessors = gltf.getAsJsonArray("accessors");
        JsonArray bufferViews = gltf.getAsJsonArray("bufferViews");
        JsonArray buffers = gltf.getAsJsonArray("buffers");
        JsonArray materials = gltf.getAsJsonArray("materials");
        JsonArray textures = gltf.getAsJsonArray("textures");
        JsonArray images = gltf.getAsJsonArray("images");

        // Load external/internal buffers if not GLB
        List<byte[]> loadedBuffers = new ArrayList<>();
        if (!isGlb && buffers != null) {
            for (JsonElement bufEl : buffers) {
                JsonObject bufObj = bufEl.getAsJsonObject();
                if (bufObj.has("uri")) {
                    String uri = bufObj.get("uri").getAsString();
                    if (uri.startsWith("data:")) {
                        String base64Data = uri.substring(uri.indexOf(",") + 1);
                        loadedBuffers.add(Base64.getDecoder().decode(base64Data));
                    } else {
                        File binFile = new File(file.getParentFile(), uri);
                        if (binFile.exists()) {
                            loadedBuffers.add(Files.readAllBytes(binFile.toPath()));
                        } else {
                            throw new IOException("Buffer file not found: " + binFile.getPath());
                        }
                    }
                } else {
                    loadedBuffers.add(new byte[0]);
                }
            }
        }

        // Helper to retrieve buffer bytes for a buffer index
        // If GLB, buffer 0 is the BIN chunk.
        // Otherwise, buffer is indexed in loadedBuffers.
        final byte[] finalBinBuffer = binBuffer;
        class BufferResolver {
            byte[] resolve(int bufferIdx) {
                if (isGlb) {
                    return finalBinBuffer;
                } else {
                    return loadedBuffers.get(bufferIdx);
                }
            }
        }
        BufferResolver resolver = new BufferResolver();

        // Parse images/textures
        List<BufferedImage> loadedImages = new ArrayList<>();
        if (images != null) {
            for (JsonElement imgEl : images) {
                JsonObject imgObj = imgEl.getAsJsonObject();
                BufferedImage img = null;
                if (imgObj.has("bufferView")) {
                    int bvIdx = imgObj.get("bufferView").getAsInt();
                    JsonObject bv = bufferViews.get(bvIdx).getAsJsonObject();
                    int offset = bv.has("byteOffset") ? bv.get("byteOffset").getAsInt() : 0;
                    int len = bv.get("byteLength").getAsInt();
                    int bufIdx = bv.has("buffer") ? bv.get("buffer").getAsInt() : 0;
                    byte[] srcBuf = resolver.resolve(bufIdx);
                    if (srcBuf != null) {
                        byte[] imgBytes = new byte[len];
                        System.arraycopy(srcBuf, offset, imgBytes, 0, len);
                        img = ImageIO.read(new ByteArrayInputStream(imgBytes));
                    }
                } else if (imgObj.has("uri")) {
                    String uri = imgObj.get("uri").getAsString();
                    if (uri.startsWith("data:")) {
                        String base64Data = uri.substring(uri.indexOf(",") + 1);
                        byte[] imgBytes = Base64.getDecoder().decode(base64Data);
                        img = ImageIO.read(new ByteArrayInputStream(imgBytes));
                    } else {
                        File imgFile = new File(file.getParentFile(), uri);
                        if (imgFile.exists()) {
                            img = ImageIO.read(imgFile);
                        }
                    }
                }
                loadedImages.add(img);
            }
        }

        List<ModelVoxelizer.Triangle> triangles = new ArrayList<>();

        if (meshes == null) {
            throw new Exception("No meshes found in glTF file.");
        }

        for (JsonElement meshEl : meshes) {
            JsonObject mesh = meshEl.getAsJsonObject();
            JsonArray primitives = mesh.getAsJsonArray("primitives");
            if (primitives == null) continue;

            for (JsonElement primEl : primitives) {
                JsonObject primitive = primEl.getAsJsonObject();
                JsonObject attributes = primitive.getAsJsonObject("attributes");
                if (attributes == null || !attributes.has("POSITION")) continue;

                int posAccessorIdx = attributes.get("POSITION").getAsInt();
                float[][] positions = getFloatAccessorData(posAccessorIdx, accessors, bufferViews, isGlb, finalBinBuffer, loadedBuffers);

                int colorAccessorIdx = attributes.has("COLOR_0") ? attributes.get("COLOR_0").getAsInt() : -1;
                float[][] colors = colorAccessorIdx != -1 ? getFloatAccessorData(colorAccessorIdx, accessors, bufferViews, isGlb, finalBinBuffer, loadedBuffers) : null;

                int texAccessorIdx = attributes.has("TEXCOORD_0") ? attributes.get("TEXCOORD_0").getAsInt() : -1;
                float[][] texcoords = texAccessorIdx != -1 ? getFloatAccessorData(texAccessorIdx, accessors, bufferViews, isGlb, finalBinBuffer, loadedBuffers) : null;

                int[] indices = null;
                if (primitive.has("indices")) {
                    int indAccessorIdx = primitive.get("indices").getAsInt();
                    indices = getIntAccessorData(indAccessorIdx, accessors, bufferViews, isGlb, finalBinBuffer, loadedBuffers);
                }

                // Material Base Color and Texture resolution
                float[] baseColor = new float[]{1.0f, 1.0f, 1.0f, 1.0f};
                BufferedImage texture = null;

                if (primitive.has("material") && materials != null) {
                    int matIdx = primitive.get("material").getAsInt();
                    if (matIdx >= 0 && matIdx < materials.size()) {
                        JsonObject mat = materials.get(matIdx).getAsJsonObject();
                        if (mat.has("pbrMetallicRoughness")) {
                            JsonObject pbr = mat.getAsJsonObject("pbrMetallicRoughness");
                            if (pbr.has("baseColorFactor")) {
                                JsonArray bcf = pbr.getAsJsonArray("baseColorFactor");
                                baseColor = new float[]{
                                        bcf.get(0).getAsFloat(),
                                        bcf.get(1).getAsFloat(),
                                        bcf.get(2).getAsFloat(),
                                        bcf.get(3).getAsFloat()
                                };
                            }
                            if (pbr.has("baseColorTexture") && textures != null && images != null) {
                                JsonObject bct = pbr.getAsJsonObject("baseColorTexture");
                                int texIdx = bct.get("index").getAsInt();
                                if (texIdx >= 0 && texIdx < textures.size()) {
                                    JsonObject tex = textures.get(texIdx).getAsJsonObject();
                                    if (tex.has("source")) {
                                        int imgIdx = tex.get("source").getAsInt();
                                        if (imgIdx >= 0 && imgIdx < loadedImages.size()) {
                                            texture = loadedImages.get(imgIdx);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Convert geometry primitives to triangles
                int numVertices = indices != null ? indices.length : positions.length;
                for (int i = 0; i < numVertices; i += 3) {
                    if (i + 2 >= numVertices) break;

                    int idx1 = indices != null ? indices[i] : i;
                    int idx2 = indices != null ? indices[i + 1] : i + 1;
                    int idx3 = indices != null ? indices[i + 2] : i + 2;

                    if (idx1 >= positions.length || idx2 >= positions.length || idx3 >= positions.length) {
                        continue;
                    }

                    ModelVoxelizer.Triangle triangle = new ModelVoxelizer.Triangle();
                    triangle.p1 = positions[idx1];
                    triangle.p2 = positions[idx2];
                    triangle.p3 = positions[idx3];

                    if (colors != null && idx1 < colors.length && idx2 < colors.length && idx3 < colors.length) {
                        triangle.c1 = colors[idx1];
                        triangle.c2 = colors[idx2];
                        triangle.c3 = colors[idx3];
                    }

                    if (texcoords != null && idx1 < texcoords.length && idx2 < texcoords.length && idx3 < texcoords.length) {
                        triangle.uv1 = texcoords[idx1];
                        triangle.uv2 = texcoords[idx2];
                        triangle.uv3 = texcoords[idx3];
                    }

                    triangle.baseColor = baseColor;
                    triangle.texture = texture;

                    triangles.add(triangle);
                }
            }
        }

        String projectId = file.getName().replaceAll("\\.[^.]+$", "");
        return ModelVoxelizer.voxelize(projectId, triangles, density, mapper, targetSizeBlocks, maxDisplaysCap);
    }

    private float[][] getFloatAccessorData(int accessorIdx, JsonArray accessors, JsonArray bufferViews, boolean isGlb, byte[] binBuffer, List<byte[]> loadedBuffers) {
        JsonObject accessor = accessors.get(accessorIdx).getAsJsonObject();
        int bufferViewIdx = accessor.get("bufferView").getAsInt();
        int byteOffset = accessor.has("byteOffset") ? accessor.get("byteOffset").getAsInt() : 0;
        int componentType = accessor.get("componentType").getAsInt();
        int count = accessor.get("count").getAsInt();
        String type = accessor.get("type").getAsString();

        JsonObject bufferView = bufferViews.get(bufferViewIdx).getAsJsonObject();
        int bvByteOffset = bufferView.has("byteOffset") ? bufferView.get("byteOffset").getAsInt() : 0;
        int stride = bufferView.has("byteStride") ? bufferView.get("byteStride").getAsInt() : 0;
        int bufferIdx = bufferView.has("buffer") ? bufferView.get("buffer").getAsInt() : 0;

        byte[] srcBuf = isGlb ? binBuffer : loadedBuffers.get(bufferIdx);

        int numComponents = 1;
        if (type.equals("VEC2")) numComponents = 2;
        else if (type.equals("VEC3")) numComponents = 3;
        else if (type.equals("VEC4")) numComponents = 4;

        int componentSize = 4;
        if (componentType == 5120 || componentType == 5121) componentSize = 1;
        else if (componentType == 5122 || componentType == 5123) componentSize = 2;
        else if (componentType == 5125 || componentType == 5126) componentSize = 4;

        if (stride == 0) {
            stride = numComponents * componentSize;
        }

        float[][] result = new float[count][numComponents];
        ByteBuffer wrap = ByteBuffer.wrap(srcBuf);
        wrap.order(ByteOrder.LITTLE_ENDIAN);

        int startOffset = bvByteOffset + byteOffset;

        for (int i = 0; i < count; i++) {
            int elementOffset = startOffset + i * stride;
            for (int c = 0; c < numComponents; c++) {
                int offset = elementOffset + c * componentSize;
                if (offset + componentSize > srcBuf.length) {
                    continue; // safety check
                }
                wrap.position(offset);
                if (componentType == 5126) {
                    result[i][c] = wrap.getFloat();
                } else if (componentType == 5123) { // UNSIGNED_SHORT
                    result[i][c] = (wrap.getShort() & 0xFFFF) / 65535.0f;
                } else if (componentType == 5122) { // SHORT
                    result[i][c] = wrap.getShort() / 32767.0f;
                } else if (componentType == 5121) { // UNSIGNED_BYTE
                    result[i][c] = (wrap.get() & 0xFF) / 255.0f;
                } else if (componentType == 5120) { // BYTE
                    result[i][c] = wrap.get() / 127.0f;
                }
            }
        }
        return result;
    }

    private int[] getIntAccessorData(int accessorIdx, JsonArray accessors, JsonArray bufferViews, boolean isGlb, byte[] binBuffer, List<byte[]> loadedBuffers) {
        JsonObject accessor = accessors.get(accessorIdx).getAsJsonObject();
        int bufferViewIdx = accessor.get("bufferView").getAsInt();
        int byteOffset = accessor.has("byteOffset") ? accessor.get("byteOffset").getAsInt() : 0;
        int componentType = accessor.get("componentType").getAsInt();
        int count = accessor.get("count").getAsInt();

        JsonObject bufferView = bufferViews.get(bufferViewIdx).getAsJsonObject();
        int bvByteOffset = bufferView.has("byteOffset") ? bufferView.get("byteOffset").getAsInt() : 0;
        int stride = bufferView.has("byteStride") ? bufferView.get("byteStride").getAsInt() : 0;
        int bufferIdx = bufferView.has("buffer") ? bufferView.get("buffer").getAsInt() : 0;

        byte[] srcBuf = isGlb ? binBuffer : loadedBuffers.get(bufferIdx);

        int componentSize = 4;
        if (componentType == 5120 || componentType == 5121) componentSize = 1;
        else if (componentType == 5122 || componentType == 5123) componentSize = 2;
        else if (componentType == 5125 || componentType == 5126) componentSize = 4;

        if (stride == 0) {
            stride = componentSize;
        }

        int[] result = new int[count];
        ByteBuffer wrap = ByteBuffer.wrap(srcBuf);
        wrap.order(ByteOrder.LITTLE_ENDIAN);

        int startOffset = bvByteOffset + byteOffset;

        for (int i = 0; i < count; i++) {
            int offset = startOffset + i * stride;
            if (offset + componentSize > srcBuf.length) {
                continue; // safety check
            }
            wrap.position(offset);
            if (componentType == 5125) { // UNSIGNED_INT
                result[i] = wrap.getInt();
            } else if (componentType == 5123) { // UNSIGNED_SHORT
                result[i] = (wrap.getShort() & 0xFFFF);
            } else if (componentType == 5122) { // SHORT
                result[i] = wrap.getShort();
            } else if (componentType == 5121) { // UNSIGNED_BYTE
                result[i] = (wrap.get() & 0xFF);
            } else if (componentType == 5120) { // BYTE
                result[i] = wrap.get();
            }
        }
        return result;
    }
}
