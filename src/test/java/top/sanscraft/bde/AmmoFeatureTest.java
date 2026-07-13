package top.sanscraft.bde;

import org.junit.jupiter.api.Test;
import top.sanscraft.bde.manager.AmmoBoxItems;
import top.sanscraft.bde.model.BdeModel;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the ammo-box feature (issue #6) covering pure-logic pieces that need no running
 * server: the lore placeholder rendering and the ProjectileConfig ammo accessors.
 */
public class AmmoFeatureTest {

    @Test
    public void testRenderReplacesCurrentAndMax() {
        List<String> template = Arrays.asList(
                "§7Ammo: §e%bde_ammo_current%§7/§6%bde_ammo_max%",
                "plain line"
        );
        List<String> result = AmmoBoxItems.render(template, 12, 50);
        assertEquals("§7Ammo: §e12§7/§650", result.get(0));
        assertEquals("plain line", result.get(1));
    }

    @Test
    public void testRenderMultipleTokensPerLine() {
        List<String> result = AmmoBoxItems.render(Collections.singletonList("%bde_ammo_current%/%bde_ammo_max%"), 3, 9);
        assertEquals("3/9", result.get(0));
    }

    @Test
    public void testRenderHandlesNulls() {
        assertTrue(AmmoBoxItems.render(null, 1, 2).isEmpty());
        List<String> result = AmmoBoxItems.render(Arrays.asList("no tokens", null), 5, 5);
        assertEquals("no tokens", result.get(0));
        assertEquals("", result.get(1));
    }

    @Test
    public void testProjectileAmmoTypeNormalizesEmptyToNull() {
        BdeModel.ProjectileConfig pc = new BdeModel.ProjectileConfig();
        assertNull(pc.getAmmoType());
        pc.setAmmoType("");
        assertNull(pc.getAmmoType(), "empty string should be treated as no requirement");
        pc.setAmmoType("shell");
        assertEquals("shell", pc.getAmmoType());
    }

    @Test
    public void testProjectileAmmoPerShotClampedToAtLeastOne() {
        BdeModel.ProjectileConfig pc = new BdeModel.ProjectileConfig();
        assertEquals(1, pc.getAmmoPerShot());
        pc.setAmmoPerShot(0);
        assertEquals(1, pc.getAmmoPerShot());
        pc.setAmmoPerShot(-5);
        assertEquals(1, pc.getAmmoPerShot());
        pc.setAmmoPerShot(4);
        assertEquals(4, pc.getAmmoPerShot());
    }
}
