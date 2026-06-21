package top.sanscraft.bde.converter;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import top.sanscraft.bde.SansCraftBDEPlugin;
import top.sanscraft.bde.model.BdeModel;

import java.io.File;
import java.io.FileReader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class BlockbenchConverter {
    private final SansCraftBDEPlugin plugin;
    private final Gson gson = new Gson();

    public BlockbenchConverter(SansCraftBDEPlugin plugin) {
        this.plugin = plugin;
    }

    public BdeModel convert(File file, ConversionMapper mapper) throws Exception {
        if (!file.exists()) {
            throw new Exception("File not found: " + file.getPath());
        }

        JsonObject json;
        try (FileReader reader = new FileReader(file)) {
            json = gson.fromJson(reader, JsonObject.class);
        }

        if (json == null || !json.has("elements")) {
            throw new Exception("Invalid Blockbench model: missing 'elements' array.");
        }

        // Parse texture definitions
        JsonObject texturesObj = json.getAsJsonObject("textures");
        BdeModel bdeModel = new BdeModel();
        bdeModel.setVersion("26.1");
        bdeModel.setType("full");
        bdeModel.setProjectId(file.getName().replace(".json", ""));

        List<String> passengers = new ArrayList<>();
        JsonArray elements = json.getAsJsonArray("elements");

        int elementIndex = 0;
        for (JsonElement el : elements) {
            JsonObject element = el.getAsJsonObject();
            JsonArray from = element.getAsJsonArray("from");
            JsonArray to = element.getAsJsonArray("to");

            if (from == null || to == null || from.size() != 3 || to.size() != 3) {
                continue;
            }

            // Calculate dimensions
            float fx = from.get(0).getAsFloat() / 16f;
            float fy = from.get(1).getAsFloat() / 16f;
            float fz = from.get(2).getAsFloat() / 16f;

            float tx = to.get(0).getAsFloat() / 16f;
            float ty = to.get(1).getAsFloat() / 16f;
            float tz = to.get(2).getAsFloat() / 16f;

            float width = tx - fx;
            float height = ty - fy;
            float depth = tz - fz;

            // Calculate transformation matrix using JOML Matrix4f
            Matrix4f matrix = new Matrix4f();

            // Handle rotation if present
            if (element.has("rotation")) {
                JsonObject rotObj = element.getAsJsonObject("rotation");
                JsonArray origin = rotObj.getAsJsonArray("origin");
                String axis = rotObj.get("axis").getAsString();
                float angle = rotObj.get("angle").getAsFloat();

                float ox = origin.get(0).getAsFloat() / 16f;
                float oy = origin.get(1).getAsFloat() / 16f;
                float oz = origin.get(2).getAsFloat() / 16f;

                // Rotate around the specified origin in 3D space:
                // Translation O -> Rotate -> Translation (P - O) -> Scale
                matrix.translate(ox, oy, oz);

                float angleRad = (float) Math.toRadians(angle);
                Vector3f axisVec = new Vector3f();
                if (axis.equalsIgnoreCase("x")) axisVec.set(1, 0, 0);
                else if (axis.equalsIgnoreCase("y")) axisVec.set(0, 1, 0);
                else axisVec.set(0, 0, 1);

                matrix.rotate(angleRad, axisVec);
                matrix.translate(fx - ox, fy - oy, fz - oz);
            } else {
                matrix.translate(fx, fy, fz);
            }

            // Scale to match element size
            matrix.scale(width, height, depth);

            // Export matrix as row-major float array
            float[] m = new float[16];
            matrix.get(m); // retrieve JOML's column-major layout
            float[] transposed = new float[16];
            transposed[0] = m[0];  transposed[1] = m[4];  transposed[2] = m[8];  transposed[3] = m[12];
            transposed[4] = m[1];  transposed[5] = m[5];  transposed[6] = m[9];  transposed[7] = m[13];
            transposed[8] = m[2];  transposed[9] = m[6];  transposed[10] = m[10]; transposed[11] = m[14];
            transposed[12] = m[3]; transposed[13] = m[7]; transposed[14] = m[11]; transposed[15] = m[15];
            m = transposed;

            // Determine block state from faces
            String blockState = "minecraft:oak_planks";
            if (element.has("faces")) {
                JsonObject faces = element.getAsJsonObject("faces");
                // Peek at first face to get texture key (e.g. #0 or #wood)
                for (String faceName : faces.keySet()) {
                    JsonObject face = faces.getAsJsonObject(faceName);
                    if (face.has("texture")) {
                        String texKey = face.get("texture").getAsString();
                        
                        // Resolve texture key to block
                        if (texturesObj != null && texKey.startsWith("#")) {
                            String id = texKey.substring(1);
                            if (texturesObj.has(id)) {
                                String texName = texturesObj.get(id).getAsString();
                                blockState = mapper.getBlockForTexture(texName);
                            } else {
                                blockState = mapper.getBlockForTexture(texKey);
                            }
                        } else {
                            blockState = mapper.getBlockForTexture(texKey);
                        }
                        break;
                    }
                }
            }

            // Construct SNBT string
            StringBuilder snbt = new StringBuilder();
            snbt.append("{id:\"minecraft:block_display\",");
            snbt.append("block_state:{Name:\"").append(blockState).append("\"},");
            
            // Format transformation matrix
            snbt.append("transformation:[");
            for (int i = 0; i < 16; i++) {
                snbt.append(String.format(Locale.US, "%.6ff", m[i]));
                if (i < 15) snbt.append(",");
            }
            snbt.append("],");
            
            // Scoreboard tag
            snbt.append("Tags:[\"bde_").append(elementIndex).append("\"]}");
            passengers.add(snbt.toString());
            elementIndex++;
        }

        bdeModel.setPassengers(passengers);
        return bdeModel;
    }
}
