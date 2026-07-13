package top.sanscraft.bde;

import org.junit.jupiter.api.Test;
import top.sanscraft.bde.manager.BdeAmmoInventoryManager;
import top.sanscraft.bde.model.BdeModel;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the ammo feature (issue #6) covering the pure-logic pieces that don't require a
 * running Bukkit server: the lore-placeholder substitution and the ProjectileConfig ammo accessors.
 */
public class AmmoFeatureTest {

    @Test
    public void testPlaceholderSubstitutionReplacesBothTokens() {
        List<String> template = Arrays.asList(
                "§7In this storage: §e%bde_ammo_current%",
                "§7Vehicle total: §e%bde_ammo_total%"
        );
        List<String> result = BdeAmmoInventoryManager.substitutePlaceholders(template, 12, 40);

        assertEquals(2, result.size());
        assertEquals("§7In this storage: §e12", result.get(0));
        assertEquals("§7Vehicle total: §e40", result.get(1));
    }

    @Test
    public void testPlaceholderSubstitutionMultipleTokensPerLine() {
        List<String> template = Collections.singletonList("%bde_ammo_current%/%bde_ammo_total%");
        List<String> result = BdeAmmoInventoryManager.substitutePlaceholders(template, 3, 9);
        assertEquals("3/9", result.get(0));
    }

    @Test
    public void testPlaceholderSubstitutionHandlesNulls() {
        assertTrue(BdeAmmoInventoryManager.substitutePlaceholders(null, 1, 2).isEmpty());

        List<String> template = Arrays.asList("no tokens here", null);
        List<String> result = BdeAmmoInventoryManager.substitutePlaceholders(template, 5, 5);
        assertEquals("no tokens here", result.get(0));
        assertEquals("", result.get(1));
    }

    @Test
    public void testProjectileAmmoTypeNormalizesEmptyToNull() {
        BdeModel.ProjectileConfig pc = new BdeModel.ProjectileConfig();
        // Default: no ammo requirement -> fires freely
        assertNull(pc.getAmmoType());

        pc.setAmmoType("");
        assertNull(pc.getAmmoType(), "empty string should be treated as no requirement");

        pc.setAmmoType("cannonball");
        assertEquals("cannonball", pc.getAmmoType());
    }

    @Test
    public void testProjectileAmmoPerShotClampedToAtLeastOne() {
        BdeModel.ProjectileConfig pc = new BdeModel.ProjectileConfig();
        assertEquals(1, pc.getAmmoPerShot(), "default per-shot should be 1");

        pc.setAmmoPerShot(0);
        assertEquals(1, pc.getAmmoPerShot());

        pc.setAmmoPerShot(-5);
        assertEquals(1, pc.getAmmoPerShot());

        pc.setAmmoPerShot(4);
        assertEquals(4, pc.getAmmoPerShot());
    }
}
