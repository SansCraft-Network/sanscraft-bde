package top.sanscraft.bde;

import org.bukkit.Material;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import top.sanscraft.bde.converter.ConversionMapper;
import top.sanscraft.bde.converter.GltfConverter;
import top.sanscraft.bde.converter.ModelVoxelizer;
import top.sanscraft.bde.converter.ObjConverter;
import top.sanscraft.bde.model.BdeModel;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class GltfObjConversionTest {
    private ConversionMapper mapper;

    @BeforeEach
    public void setUp() {
        // Use default mapper constructors suitable for tests
        mapper = new ConversionMapper("minecraft:oak_planks", "minecraft:white_concrete", null);
    }

    @Test
    public void testRedmeanColorMatching() {
        // Red color (255, 0, 0) should match REDSTONE_BLOCK (perceptually closest)
        Material matRed = mapper.getBlockForColor(255, 0, 0);
        assertTrue(matRed == Material.REDSTONE_BLOCK || matRed == Material.RED_CONCRETE || matRed == Material.RED_WOOL || matRed == Material.RED_TERRACOTTA);

        // Brownish color (162, 130, 84) matches OAK_PLANKS exactly
        Material matOak = mapper.getBlockForColor(162, 130, 84);
        assertEquals(Material.OAK_PLANKS, matOak);

        // Dark color (16, 16, 16) matches COAL_BLOCK or BLACK_CONCRETE/WOOL
        Material matDark = mapper.getBlockForColor(16, 16, 16);
        assertTrue(matDark == Material.COAL_BLOCK || matDark == Material.BLACK_CONCRETE || matDark == Material.BLACK_WOOL);
    }

    @Test
    public void testModelVoxelizerBasic() throws Exception {
        List<ModelVoxelizer.Triangle> triangles = new ArrayList<>();

        // Create a single triangle along XY plane
        ModelVoxelizer.Triangle t = new ModelVoxelizer.Triangle();
        t.p1 = new float[]{0f, 0f, 0f};
        t.p2 = new float[]{1f, 0f, 0f};
        t.p3 = new float[]{0f, 1f, 0f};
        t.baseColor = new float[]{1f, 0f, 0f, 1f}; // Red

        triangles.add(t);

        // Voxelize with density 16 (voxels per block)
        BdeModel model = ModelVoxelizer.voxelize("test_voxel", triangles, 16, mapper);
        assertNotNull(model);
        assertEquals("test_voxel", model.getProjectId());
        assertFalse(model.getPassengers().isEmpty());

        // Check if passenger strings contain red material
        String firstPassenger = model.getPassengers().get(0);
        assertTrue(firstPassenger.contains("minecraft:redstone_block") || firstPassenger.contains("minecraft:red_concrete") || firstPassenger.contains("minecraft:red_wool") || firstPassenger.contains("minecraft:red_terracotta"));
    }

    @Test
    public void testObjConverter() throws Exception {
        // Construct a mock OBJ file
        File objFile = File.createTempFile("mock_model", ".obj");
        objFile.deleteOnExit();

        File mtlFile = new File(objFile.getParentFile(), objFile.getName().replace(".obj", ".mtl"));
        mtlFile.deleteOnExit();

        try (FileWriter mtlWriter = new FileWriter(mtlFile)) {
            mtlWriter.write("newmtl RedMaterial\n");
            mtlWriter.write("Kd 1.0 0.0 0.0\n");
        }

        try (FileWriter objWriter = new FileWriter(objFile)) {
            objWriter.write("mtllib " + mtlFile.getName() + "\n");
            objWriter.write("v 0.0 0.0 0.0\n");
            objWriter.write("v 1.0 0.0 0.0\n");
            objWriter.write("v 0.0 1.0 0.0\n");
            objWriter.write("usemtl RedMaterial\n");
            objWriter.write("f 1 2 3\n");
        }

        ObjConverter converter = new ObjConverter(null);
        BdeModel model = converter.convert(objFile, mapper, 16);

        assertNotNull(model);
        assertFalse(model.getPassengers().isEmpty());
        String passenger = model.getPassengers().get(0);
        assertTrue(passenger.contains("minecraft:redstone_block") || passenger.contains("minecraft:red_concrete") || passenger.contains("minecraft:red_wool") || passenger.contains("minecraft:red_terracotta"));
    }

    @Test
    public void testGltfConverter() throws Exception {
        // Construct the binary buffer bytes dynamically
        ByteBuffer buf = ByteBuffer.allocate(42);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        // Positions (12 bytes per vertex)
        buf.putFloat(0f); buf.putFloat(0f); buf.putFloat(0f);
        buf.putFloat(1f); buf.putFloat(0f); buf.putFloat(0f);
        buf.putFloat(0f); buf.putFloat(1f); buf.putFloat(0f);
        // Indices (2 bytes per vertex index)
        buf.putShort((short) 0); buf.putShort((short) 1); buf.putShort((short) 2);

        String base64Buffer = Base64.getEncoder().encodeToString(buf.array());

        // Construct a mock GLTF JSON
        String gltfJson = "{\n" +
                "  \"asset\": {\n" +
                "    \"version\": \"2.0\"\n" +
                "  },\n" +
                "  \"meshes\": [\n" +
                "    {\n" +
                "      \"primitives\": [\n" +
                "        {\n" +
                "          \"attributes\": {\n" +
                "            \"POSITION\": 0\n" +
                "          },\n" +
                "          \"indices\": 1,\n" +
                "          \"material\": 0\n" +
                "        }\n" +
                "      ]\n" +
                "    }\n" +
                "  ],\n" +
                "  \"materials\": [\n" +
                "    {\n" +
                "      \"pbrMetallicRoughness\": {\n" +
                "        \"baseColorFactor\": [1.0, 0.0, 0.0, 1.0]\n" +
                "      }\n" +
                "    }\n" +
                "  ],\n" +
                "  \"accessors\": [\n" +
                "    {\n" +
                "      \"bufferView\": 0,\n" +
                "      \"componentType\": 5126,\n" +
                "      \"count\": 3,\n" +
                "      \"type\": \"VEC3\"\n" +
                "    },\n" +
                "    {\n" +
                "      \"bufferView\": 1,\n" +
                "      \"componentType\": 5123,\n" +
                "      \"count\": 3,\n" +
                "      \"type\": \"SCALAR\"\n" +
                "    }\n" +
                "  ],\n" +
                "  \"bufferViews\": [\n" +
                "    {\n" +
                "      \"buffer\": 0,\n" +
                "      \"byteOffset\": 0,\n" +
                "      \"byteLength\": 36\n" +
                "    },\n" +
                "    {\n" +
                "      \"buffer\": 0,\n" +
                "      \"byteOffset\": 36,\n" +
                "      \"byteLength\": 6\n" +
                "    }\n" +
                "  ],\n" +
                "  \"buffers\": [\n" +
                "    {\n" +
                "      \"byteLength\": 42,\n" +
                "      \"uri\": \"data:application/octet-stream;base64," + base64Buffer + "\"\n" +
                "    }\n" +
                "  ]\n" +
                "}";

        File gltfFile = File.createTempFile("mock_model", ".gltf");
        gltfFile.deleteOnExit();

        try (FileWriter writer = new FileWriter(gltfFile)) {
            writer.write(gltfJson);
        }

        GltfConverter converter = new GltfConverter(null);
        BdeModel model = converter.convert(gltfFile, mapper, 16);

        assertNotNull(model);
        assertFalse(model.getPassengers().isEmpty());
        String passenger = model.getPassengers().get(0);
        assertTrue(passenger.contains("minecraft:redstone_block") || passenger.contains("minecraft:red_concrete") || passenger.contains("minecraft:red_wool") || passenger.contains("minecraft:red_terracotta"));
    }

    @Test
    public void testModelVoxelizerTargetSize() throws Exception {
        List<ModelVoxelizer.Triangle> triangles = new ArrayList<>();
        ModelVoxelizer.Triangle t = new ModelVoxelizer.Triangle();
        t.p1 = new float[]{0f, 0f, 0f};
        t.p2 = new float[]{100f, 0f, 0f};
        t.p3 = new float[]{0f, 100f, 0f};
        t.baseColor = new float[]{1f, 0f, 0f, 1f};
        triangles.add(t);

        BdeModel model = ModelVoxelizer.voxelize("test_target_size", triangles, 16, mapper, 2.0);
        assertNotNull(model);
        assertFalse(model.getPassengers().isEmpty());
    }

    @Test
    public void testModelVoxelizerAutoScale() throws Exception {
        List<ModelVoxelizer.Triangle> triangles = new ArrayList<>();
        ModelVoxelizer.Triangle t = new ModelVoxelizer.Triangle();
        t.p1 = new float[]{0f, 0f, 0f};
        t.p2 = new float[]{5000f, 0f, 0f};
        t.p3 = new float[]{0f, 5000f, 0f};
        t.baseColor = new float[]{1f, 0f, 0f, 1f};
        triangles.add(t);

        BdeModel model = ModelVoxelizer.voxelize("test_auto_scale", triangles, 16, mapper);
        assertNotNull(model);
        assertFalse(model.getPassengers().isEmpty());
    }
}
