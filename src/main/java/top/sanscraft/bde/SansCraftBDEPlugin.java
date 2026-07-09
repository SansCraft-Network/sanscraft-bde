package top.sanscraft.bde;

import org.bukkit.plugin.java.JavaPlugin;
import top.sanscraft.bde.command.BDECommand;
import top.sanscraft.bde.listener.CustomBlockListener;
import top.sanscraft.bde.manager.CustomBlockManager;
import top.sanscraft.bde.manager.ModelManager;
import top.sanscraft.bde.manager.BdeGuiManager;
import top.sanscraft.bde.animation.AnimationEngine;

import java.io.File;

public class SansCraftBDEPlugin extends JavaPlugin {
    private static SansCraftBDEPlugin instance;
    
    private ModelManager modelManager;
    private CustomBlockManager customBlockManager;
    private AnimationEngine animationEngine;
    private BdeGuiManager bdeGuiManager;

    @Override
    public void onEnable() {
        instance = this;

        // Initialize directories
        getDataFolder().mkdirs();
        new File(getDataFolder(), "cache").mkdirs();
        new File(getDataFolder(), "models").mkdirs();
        new File(getDataFolder(), "mappings").mkdirs();

        // Save default configs
        saveDefaultConfig();
        saveResource("custom_blocks.yml", false);

        // Initialize managers
        this.animationEngine = new AnimationEngine(this);
        this.modelManager = new ModelManager(this);
        this.customBlockManager = new CustomBlockManager(this);
        this.bdeGuiManager = new BdeGuiManager(this);

        // Load custom blocks
        this.customBlockManager.initialize();

        // Register commands & listeners
        BDECommand bdeCommand = new BDECommand(this);
        getCommand("bde").setExecutor(bdeCommand);
        getCommand("bde").setTabCompleter(bdeCommand);

        top.sanscraft.bde.command.LockCommand lockCommand = new top.sanscraft.bde.command.LockCommand(this);
        getCommand("lock").setExecutor(lockCommand);
        getCommand("lock").setTabCompleter(lockCommand);

        getServer().getPluginManager().registerEvents(new CustomBlockListener(this), this);
        getServer().getPluginManager().registerEvents(new top.sanscraft.bde.listener.BdeGuiListener(this), this);

        getLogger().info("SansCraftBDE has been enabled successfully!");
    }

    @Override
    public void onDisable() {
        // Despawn all active model instances to prevent orphan entities
        boolean cleanupModels = getConfig().getBoolean("cleanup-models-on-shutdown", true);

        if (cleanupModels && modelManager != null) {
            modelManager.cleanupAll();
            getLogger().info("Cleaned up all spawned BDE models.");
        } else {
            getLogger().info("Skipping BDE model cleanup. Models will persist as orphaned entities.");
        }
        if (customBlockManager != null) {
            customBlockManager.despawnAllLoadedBlocks();
        }
        if (bdeGuiManager != null) {
            bdeGuiManager.cleanupAll();
        }
        getLogger().info("SansCraftBDE has been disabled and cleaned up!");
    }

    public static SansCraftBDEPlugin getInstance() {
        return instance;
    }

    public ModelManager getModelManager() {
        return modelManager;
    }

    public CustomBlockManager getCustomBlockManager() {
        return customBlockManager;
    }

    public AnimationEngine getAnimationEngine() {
        return animationEngine;
    }

    public BdeGuiManager getBdeGuiManager() {
        return bdeGuiManager;
    }
}
