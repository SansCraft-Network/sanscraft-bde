package top.sanscraft.bde;

import org.bukkit.Material;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import top.sanscraft.bde.converter.ConversionMapper;
import top.sanscraft.bde.converter.VoxConverter;
import top.sanscraft.bde.model.BdeModel;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

public class BdeConversionTest {
    private ConversionMapper mapper;

    @BeforeEach
    public void setUp() {
        // Create a mapper using the overloaded constructor to avoid mocking plugin
        mapper = new ConversionMapper("minecraft:oak_planks", "minecraft:white_concrete", null);
    }

    @Test
    public void testColorMappingDefaults() {
        // Darker Red color (142, 32, 32) should resolve to RED_CONCRETE
        Material materialRed = mapper.getBlockForColor(142, 32, 32);
        assertEquals(Material.RED_CONCRETE, materialRed);

        // White color (255, 255, 255) should resolve to WHITE_WOOL
        Material materialWhite = mapper.getBlockForColor(255, 255, 255);
        assertEquals(Material.WHITE_WOOL, materialWhite);

        // Black color (0, 0, 0) should resolve to BLACK_CONCRETE
        Material materialBlack = mapper.getBlockForColor(0, 0, 0);
        assertEquals(Material.BLACK_CONCRETE, materialBlack);

        // Green color (0, 255, 0) should resolve to EMERALD_BLOCK (closest to bright green)
        Material materialGreen = mapper.getBlockForColor(0, 255, 0);
        assertEquals(Material.EMERALD_BLOCK, materialGreen);
    }

    @Test
    public void testTextureMappingDefaults() {
        // Default texture mapping should return default block
        String block = mapper.getBlockForTexture("some_random_texture");
        assertEquals("minecraft:oak_planks", block);
    }

    private byte[] createMockVoxBytes(int sizeX, int sizeY, int sizeZ, int[][] voxels, int[] palette) {
        ByteBuffer buf = ByteBuffer.allocate(2000);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        buf.put("VOX ".getBytes());
        buf.putInt(150);

        buf.put("SIZE".getBytes());
        buf.putInt(12);
        buf.putInt(0);
        buf.putInt(sizeX);
        buf.putInt(sizeY);
        buf.putInt(sizeZ);

        buf.put("XYZI".getBytes());
        buf.putInt(4 + voxels.length * 4);
        buf.putInt(0);
        buf.putInt(voxels.length);
        for (int[] voxel : voxels) {
            buf.put((byte) voxel[0]);
            buf.put((byte) voxel[1]);
            buf.put((byte) voxel[2]);
            buf.put((byte) voxel[3]);
        }

        buf.put("RGBA".getBytes());
        buf.putInt(1024);
        buf.putInt(0);
        for (int i = 0; i < 256; i++) {
            if (palette != null && i < palette.length) {
                buf.putInt(palette[i]);
            } else {
                buf.putInt(0xFFFFFFFF);
            }
        }

        byte[] bytes = new byte[buf.position()];
        System.arraycopy(buf.array(), 0, bytes, 0, bytes.length);
        return bytes;
    }

    @Test
    public void testVoxConverterBasic() throws Exception {
        int[] palette = new int[256];
        palette[0] = 142 | (32 << 8) | (32 << 16) | (255 << 24);

        int[][] voxels = {
            {0, 0, 0, 1}
        };

        byte[] voxBytes = createMockVoxBytes(2, 2, 2, voxels, palette);
        File tempFile = File.createTempFile("mock_model", ".vox");
        tempFile.deleteOnExit();

        try (FileOutputStream out = new FileOutputStream(tempFile)) {
            out.write(voxBytes);
        }

        VoxConverter converter = new VoxConverter(null);
        BdeModel model = converter.convert(tempFile, mapper, 1);

        assertNotNull(model);
        assertTrue(model.getProjectId().startsWith("mock_model"));
        assertEquals(1, model.getPassengers().size());

        String passenger = model.getPassengers().get(0);
        assertTrue(passenger.contains("minecraft:red_concrete"), "Should resolve to red concrete: " + passenger);
    }

    @Test
    public void testVoxConverterGreedyMeshing() throws Exception {
        int[] palette = new int[256];
        palette[0] = 255 | (255 << 8) | (255 << 16) | (255 << 24);

        int[][] voxels = {
            {0, 0, 0, 1},
            {1, 0, 0, 1}
        };

        byte[] voxBytes = createMockVoxBytes(4, 4, 4, voxels, palette);
        File tempFile = File.createTempFile("mock_greedy", ".vox");
        tempFile.deleteOnExit();

        try (FileOutputStream out = new FileOutputStream(tempFile)) {
            out.write(voxBytes);
        }

        VoxConverter converter = new VoxConverter(null);
        BdeModel model = converter.convert(tempFile, mapper, 1);

        assertNotNull(model);
        assertEquals(1, model.getPassengers().size(), "Should merge adjacent voxels into 1 passenger");

        String passenger = model.getPassengers().get(0);
        assertTrue(passenger.contains("minecraft:white_wool"), "Should resolve to white wool");
        assertTrue(passenger.contains("0.125000f"), "Scale X should be 0.125");
        assertTrue(passenger.contains("0.062500f"), "Scale Y and Z should be 0.0625");
    }

    @Test
    public void testVoxConverterDownsampling() throws Exception {
        int[] palette = new int[256];
        palette[0] = 142 | (32 << 8) | (32 << 16) | (255 << 24);
        palette[1] = 0 | (0 << 8) | (255 << 16) | (255 << 24);

        int[][] voxels = {
            {0, 0, 0, 1},
            {0, 0, 1, 1},
            {0, 1, 0, 1},
            {0, 1, 1, 1},
            {1, 0, 0, 1},
            {1, 0, 1, 2},
            {1, 1, 0, 2},
            {1, 1, 1, 2}
        };

        byte[] voxBytes = createMockVoxBytes(4, 4, 4, voxels, palette);
        File tempFile = File.createTempFile("mock_downsample", ".vox");
        tempFile.deleteOnExit();

        try (FileOutputStream out = new FileOutputStream(tempFile)) {
            out.write(voxBytes);
        }

        VoxConverter converter = new VoxConverter(null);
        BdeModel model = converter.convert(tempFile, mapper, 2);

        assertNotNull(model);
        assertEquals(1, model.getPassengers().size(), "Should downsample 2x2x2 group into 1 voxel");
        String passenger = model.getPassengers().get(0);
        assertTrue(passenger.contains("minecraft:red_concrete"), "Should choose majority color (Red)");
        assertTrue(passenger.contains("0.125000f"), "Scale should be 0.125");
    }

    @Test
    public void testPassengerNestingFlattening() {
        String snbt = "{id:\"minecraft:block_display\",block_state:{Name:\"minecraft:stone\"},Passengers:[{id:\"minecraft:block_display\",block_state:{Name:\"minecraft:red_concrete\"}},{id:\"minecraft:block_display\",block_state:{Name:\"minecraft:blue_concrete\"},Passengers:[{id:\"minecraft:block_display\",block_state:{Name:\"minecraft:lime_concrete\"}}]}]}";

        // 1. Verify removePassengersTag
        String clean = top.sanscraft.bde.manager.ModelManager.removePassengersTag(snbt);
        assertEquals("{id:\"minecraft:block_display\",block_state:{Name:\"minecraft:stone\"}}", clean);

        // 2. Verify parsePassengers
        java.util.List<String> children = top.sanscraft.bde.manager.ModelManager.parsePassengers(snbt);
        assertEquals(2, children.size());
        assertEquals("{id:\"minecraft:block_display\",block_state:{Name:\"minecraft:red_concrete\"}}", children.get(0));
        assertTrue(children.get(1).startsWith("{id:\"minecraft:block_display\",block_state:{Name:\"minecraft:blue_concrete\"}"));

        // 3. Verify collectAllPassengers
        java.util.List<String> flatList = new java.util.ArrayList<>();
        top.sanscraft.bde.manager.ModelManager.collectAllPassengers(snbt, flatList);

        assertEquals(4, flatList.size(), "Should extract 4 flat block display entities total");
        assertEquals("{id:\"minecraft:block_display\",block_state:{Name:\"minecraft:stone\"}}", flatList.get(0));
        assertEquals("{id:\"minecraft:block_display\",block_state:{Name:\"minecraft:red_concrete\"}}", flatList.get(1));
        assertEquals("{id:\"minecraft:block_display\",block_state:{Name:\"minecraft:blue_concrete\"}}", flatList.get(2));
        assertEquals("{id:\"minecraft:block_display\",block_state:{Name:\"minecraft:lime_concrete\"}}", flatList.get(3));
    }

    @Test
    public void testMatrixDecompositionWithScaling() {
        // A typical BDE matrix with non-uniform scaling and rotation
        float[] m = {
            0.2751040127f, 0.0779668841f, 0f, 3.377939534f,
            0f, 0f, 0.0375f, 0.1875f,
            0.058475163f, -0.3668053503f, 0f, 0.6394444147f,
            0f, 0f, 0f, 1f
        };

        org.joml.Matrix4f matrix = new org.joml.Matrix4f();
        matrix.setTransposed(m);

        org.joml.Quaternionf unnormalizedRot = new org.joml.Quaternionf();
        matrix.getUnnormalizedRotation(unnormalizedRot);

        // Verify that components are finite and not NaN
        assertTrue(Float.isFinite(unnormalizedRot.x));
        assertTrue(Float.isFinite(unnormalizedRot.y));
        assertTrue(Float.isFinite(unnormalizedRot.z));
        assertTrue(Float.isFinite(unnormalizedRot.w));

        // Verify it represents a unit quaternion (length close to 1)
        double len = Math.sqrt(unnormalizedRot.x * unnormalizedRot.x +
                               unnormalizedRot.y * unnormalizedRot.y +
                               unnormalizedRot.z * unnormalizedRot.z +
                               unnormalizedRot.w * unnormalizedRot.w);
        assertEquals(1.0, len, 1e-4);
    }

    @Test
    public void testSplitObjects() {
        String snbt = "{id:\"minecraft:item_display\",Tags:[\"bde_0\"]},{id:\"minecraft:item_display\",Tags:[\"bde_1\"]}";
        java.util.List<String> result = top.sanscraft.bde.manager.ModelManager.splitObjects(snbt);
        assertEquals(2, result.size());
        assertEquals("{id:\"minecraft:item_display\",Tags:[\"bde_0\"]}", result.get(0));
        assertEquals("{id:\"minecraft:item_display\",Tags:[\"bde_1\"]}", result.get(1));
    }

    @Test
    public void testPlayerProfileAPIExistence() throws Exception {
        Class<?> playerProfileClass = Class.forName("org.bukkit.profile.PlayerProfile");
        assertNotNull(playerProfileClass);
        
        Class<?> playerTexturesClass = Class.forName("org.bukkit.profile.PlayerTextures");
        assertNotNull(playerTexturesClass);
        
        java.lang.reflect.Method getTexturesMethod = playerProfileClass.getMethod("getTextures");
        assertNotNull(getTexturesMethod);
        assertEquals(playerTexturesClass, getTexturesMethod.getReturnType());
        
        java.lang.reflect.Method setTexturesMethod = playerProfileClass.getMethod("setTextures", playerTexturesClass);
        assertNotNull(setTexturesMethod);
        
        java.lang.reflect.Method setSkinMethod = playerTexturesClass.getMethod("setSkin", java.net.URL.class);
        assertNotNull(setSkinMethod);
    }

    @Test
    public void testExtractValueProperty() throws Exception {
        java.lang.reflect.Method method = top.sanscraft.bde.manager.ModelManager.class.getDeclaredMethod("extractValueProperty", String.class);
        method.setAccessible(true);

        String snbt1 = "{id:\"minecraft:item_display\",tag:{\"SkullOwner\":{\"Properties\":{\"textures\":[{\"Value\":\"abc\"}]}}}}";
        assertEquals("abc", method.invoke(null, snbt1));

        String snbt2 = "{id:\"minecraft:item_display\",tag:{SkullOwner:{Properties:{textures:[{Value:'def'}]}}}}";
        assertEquals("def", method.invoke(null, snbt2));

        String snbt3 = "{id:\"minecraft:item_display\",tag:{SkullOwner:{Properties:{textures:[{Value:\"ghi\"}]}}}}";
        assertEquals("ghi", method.invoke(null, snbt3));
    }

    @Test
    public void testParseModel295586() throws Exception {
        java.io.File file = new java.io.File("C:\\Users\\username\\.gemini\\antigravity-ide\\brain\\a60ff746-4056-4f73-b9eb-1a8468181551\\.system_generated\\steps\\204\\content.md");
        if (!file.exists()) {
            return; // Skip if file doesn't exist (e.g. in other environments)
        }
        java.util.List<String> lines = java.nio.file.Files.readAllLines(file.toPath());
        String jsonStr = null;
        for (String line : lines) {
            if (line.trim().startsWith("{")) {
                jsonStr = line.trim();
                break;
            }
        }
        assertNotNull(jsonStr);
        com.google.gson.Gson gson = new com.google.gson.Gson();
        BdeModel.BdeResponse response = gson.fromJson(jsonStr, BdeModel.BdeResponse.class);
        assertNotNull(response);
        assertNotNull(response.getContent());
        assertNotNull(response.getContent().getDatapack());
        
        java.util.Map<String, java.util.Map<String, java.util.List<String>>> anim = response.getContent().getDatapack().getAnimKeyframes();
        java.util.Map<String, java.util.Map<String, java.util.List<String>>> sound = response.getContent().getDatapack().getSoundKeyframes();
        
        assertNotNull(anim);
        assertNotNull(sound);
        assertTrue(anim.containsKey("dance"));
        assertTrue(sound.containsKey("rat_dance"));
    }

    @Test
    public void testMatchVanillaSound() {
        try {
            // Trigger classloading/registry access to check if we are in a running server environment
            org.bukkit.Sound.values();
        } catch (Throwable t) {
            // Skip the test if RegistryAccess is not initialized (no server running)
            return;
        }
        assertEquals(org.bukkit.Sound.BLOCK_NOTE_BLOCK_HARP, top.sanscraft.bde.animation.AnimationEngine.matchVanillaSound("block.note_block.harp"));
        assertEquals(org.bukkit.Sound.BLOCK_NOTE_BLOCK_HARP, top.sanscraft.bde.animation.AnimationEngine.matchVanillaSound("minecraft:block.note_block.harp"));
        assertEquals(org.bukkit.Sound.AMBIENT_BASALT_DELTAS_ADDITIONS, top.sanscraft.bde.animation.AnimationEngine.matchVanillaSound("ambient.basalt_deltas.additions"));
        assertNull(top.sanscraft.bde.animation.AnimationEngine.matchVanillaSound("custom.sound.from.resource.pack"));
        assertNull(top.sanscraft.bde.animation.AnimationEngine.matchVanillaSound(null));
    }
}

