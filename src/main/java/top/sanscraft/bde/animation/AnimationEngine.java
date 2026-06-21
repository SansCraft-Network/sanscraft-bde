package top.sanscraft.bde.animation;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import top.sanscraft.bde.SansCraftBDEPlugin;
import top.sanscraft.bde.model.BdeModel;
import top.sanscraft.bde.model.ModelInstance;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AnimationEngine {
    private final SansCraftBDEPlugin plugin;
    private final Map<UUID, AnimationTask> activeAnimations = new HashMap<>();

    private static final Pattern TAG_PATTERN = Pattern.compile("tag=(bde_\\w+)");
    private static final Pattern TRANS_PATTERN = Pattern.compile("transformation:\\[([^\\]]+)\\]");
    private static final Pattern DURATION_PATTERN = Pattern.compile("interpolation_duration:(\\d+)");
    private static final Pattern DELAY_PATTERN = Pattern.compile("start_interpolation:(-?\\d+)");

    public AnimationEngine(SansCraftBDEPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Plays an animation on a spawned model instance.
     */
    public void playAnimation(ModelInstance instance, String animationName, boolean loop) {
        // Stop any running animation on this instance first
        stopAnimation(instance.getId());

        BdeModel.BdeDatapack datapack = instance.getModel().getDatapack();
        if (datapack == null) return;

        Map<String, Map<String, List<String>>> animKeyframes = datapack.getAnimKeyframes();
        Map<String, Map<String, List<String>>> soundKeyframes = datapack.getSoundKeyframes();

        Map<String, List<String>> animFrames = null;
        if (animKeyframes != null) {
            animFrames = animKeyframes.get(animationName);
            if (animFrames == null) {
                for (String key : animKeyframes.keySet()) {
                    if (key.equalsIgnoreCase(animationName)) {
                        animFrames = animKeyframes.get(key);
                        break;
                    }
                }
            }
            if (animFrames == null) {
                for (String key : animKeyframes.keySet()) {
                    if (key.toLowerCase().contains(animationName.toLowerCase()) || 
                        animationName.toLowerCase().contains(key.toLowerCase())) {
                        animFrames = animKeyframes.get(key);
                        plugin.getLogger().info("Fuzzy matched animation: " + key + " for " + animationName);
                        break;
                    }
                }
            }
        }

        Map<String, List<String>> soundFrames = null;
        if (soundKeyframes != null) {
            soundFrames = soundKeyframes.get(animationName);
            if (soundFrames == null) {
                for (String key : soundKeyframes.keySet()) {
                    if (key.equalsIgnoreCase(animationName)) {
                        soundFrames = soundKeyframes.get(key);
                        break;
                    }
                }
            }
            if (soundFrames == null) {
                for (String key : soundKeyframes.keySet()) {
                    if (key.toLowerCase().contains(animationName.toLowerCase()) || 
                        animationName.toLowerCase().contains(key.toLowerCase())) {
                        soundFrames = soundKeyframes.get(key);
                        plugin.getLogger().info("Fuzzy matched sound keyframes: " + key + " for " + animationName);
                        break;
                    }
                }
            }
        }

        if (animFrames == null && soundFrames == null) {
            plugin.getLogger().warning("Animation " + animationName + " not found for model.");
            return;
        }

        // Determine max frame index for animation and sound separately
        int maxAnimFrame = 0;
        if (animFrames != null) {
            for (String key : animFrames.keySet()) {
                maxAnimFrame = Math.max(maxAnimFrame, Integer.parseInt(key));
            }
        }
        int maxSoundFrame = 0;
        if (soundFrames != null) {
            for (String key : soundFrames.keySet()) {
                maxSoundFrame = Math.max(maxSoundFrame, Integer.parseInt(key));
            }
        }
        int maxFrame = Math.max(maxAnimFrame, maxSoundFrame);

        AnimationTask task = new AnimationTask(instance, animFrames, soundFrames, maxAnimFrame, maxSoundFrame, maxFrame, loop);
        activeAnimations.put(instance.getId(), task);
        task.runTaskTimer(plugin, 0L, 1L); // Run every tick (50ms)
    }

    /**
     * Stops any running animation on the given instance.
     */
    public void stopAnimation(UUID instanceId) {
        AnimationTask task = activeAnimations.remove(instanceId);
        if (task != null) {
            task.cancel();
        }
    }

    public boolean isPlaying(UUID instanceId) {
        return activeAnimations.containsKey(instanceId);
    }

    public void pauseAnimation(UUID instanceId) {
        AnimationTask task = activeAnimations.get(instanceId);
        if (task != null) {
            task.setPaused(true);
        }
    }

    public void resumeAnimation(UUID instanceId) {
        AnimationTask task = activeAnimations.get(instanceId);
        if (task != null) {
            task.setPaused(false);
        }
    }

    public boolean isPaused(UUID instanceId) {
        AnimationTask task = activeAnimations.get(instanceId);
        return task != null && task.isPaused();
    }

    public void setSpeed(UUID instanceId, double speed) {
        AnimationTask task = activeAnimations.get(instanceId);
        if (task != null) {
            task.setSpeed(speed);
        }
    }

    private class AnimationTask extends BukkitRunnable {
        private final ModelInstance instance;
        private final Map<String, List<String>> animFrames;
        private final Map<String, List<String>> soundFrames;
        private final int maxAnimFrame;
        private final int maxSoundFrame;
        private final int maxFrame;
        private final boolean loop;
        
        private double speed = 1.0;
        private boolean paused = false;
        private double framePointer = 0.0;
        private int lastExecutedFrame = -1;

        public AnimationTask(ModelInstance instance, 
                             Map<String, List<String>> animFrames, 
                             Map<String, List<String>> soundFrames, 
                             int maxAnimFrame,
                             int maxSoundFrame,
                             int maxFrame, 
                             boolean loop) {
            this.instance = instance;
            this.animFrames = animFrames;
            this.soundFrames = soundFrames;
            this.maxAnimFrame = maxAnimFrame;
            this.maxSoundFrame = maxSoundFrame;
            this.maxFrame = maxFrame;
            this.loop = loop;
        }

        public double getSpeed() {
            return speed;
        }

        public void setSpeed(double speed) {
            this.speed = speed;
        }

        public boolean isPaused() {
            return paused;
        }

        public void setPaused(boolean paused) {
            this.paused = paused;
        }

        @Override
        public void run() {
            boolean displaysValid = false;
            for (Display display : instance.getPassengers()) {
                if (display != null && display.isValid()) {
                    displaysValid = true;
                    break;
                }
            }

            if (!displaysValid) {
                cancel();
                activeAnimations.remove(instance.getId());
                return;
            }

            if (paused) return;

            int targetFrame = (int) Math.floor(framePointer);
            for (int f = lastExecutedFrame + 1; f <= targetFrame; f++) {
                int resolvedTick = f;
                if (resolvedTick > maxFrame) {
                    if (loop) {
                        resolvedTick = resolvedTick % (maxFrame + 1);
                    } else {
                        cancel();
                        activeAnimations.remove(instance.getId());
                        return;
                    }
                }

                int animFrame = resolvedTick;
                if (animFrame > maxAnimFrame) {
                    if (loop) {
                        animFrame = animFrame % (maxAnimFrame + 1);
                    } else {
                        animFrame = Math.min(animFrame, maxAnimFrame);
                    }
                }
                executeAnimFrame(animFrame);

                int soundFrame = resolvedTick;
                if (soundFrame > maxSoundFrame) {
                    if (loop) {
                        soundFrame = soundFrame % (maxSoundFrame + 1);
                    } else {
                        soundFrame = Math.min(soundFrame, maxSoundFrame);
                    }
                }
                executeSoundFrame(soundFrame);
            }
            lastExecutedFrame = targetFrame;
            framePointer += speed;

            if (framePointer > maxFrame) {
                if (loop) {
                    framePointer = framePointer % (maxFrame + 1);
                    lastExecutedFrame = -1;
                } else {
                    if (lastExecutedFrame < maxFrame) {
                        int resolvedTick = maxFrame;

                        int animFrame = resolvedTick;
                        if (animFrame > maxAnimFrame) {
                            animFrame = Math.min(animFrame, maxAnimFrame);
                        }
                        executeAnimFrame(animFrame);

                        int soundFrame = resolvedTick;
                        if (soundFrame > maxSoundFrame) {
                            soundFrame = Math.min(soundFrame, maxSoundFrame);
                        }
                        executeSoundFrame(soundFrame);
                    }
                    cancel();
                    activeAnimations.remove(instance.getId());
                }
            }
        }

        private void executeAnimFrame(int frame) {
            if (animFrames != null) {
                List<String> commands = animFrames.get(String.valueOf(frame));
                if (commands != null) {
                    for (String cmd : commands) {
                        try {
                            processAnimCommand(cmd);
                        } catch (Exception e) {
                            plugin.getLogger().warning("Error parsing anim command: " + cmd + " - " + e.getMessage());
                        }
                    }
                }
            }
        }

        private void executeSoundFrame(int frame) {
            if (soundFrames != null) {
                List<String> commands = soundFrames.get(String.valueOf(frame));
                if (commands != null) {
                    for (String cmd : commands) {
                        try {
                            processSoundCommand(cmd);
                        } catch (Exception e) {
                            plugin.getLogger().warning("Error parsing sound command: " + cmd + " - " + e.getMessage());
                        }
                    }
                }
            }
        }

        private void processAnimCommand(String cmd) {
            Matcher tagMatcher = TAG_PATTERN.matcher(cmd);
            if (!tagMatcher.find()) return;
            String tag = tagMatcher.group(1);

            Display display = instance.getPassengerByTag(tag);
            if (display == null) return;

            Matcher transMatcher = TRANS_PATTERN.matcher(cmd);
            if (!transMatcher.find()) return;
            String matrixStr = transMatcher.group(1);

            String[] parts = matrixStr.split(",");
            if (parts.length != 16) return;

            float[] m = new float[16];
            for (int i = 0; i < 16; i++) {
                String p = parts[i].trim().toLowerCase();
                if (p.endsWith("f")) p = p.substring(0, p.length() - 1);
                m[i] = Float.parseFloat(p);
            }

            // Parse parameters
            int duration = 0;
            Matcher durMatcher = DURATION_PATTERN.matcher(cmd);
            if (durMatcher.find()) {
                duration = Integer.parseInt(durMatcher.group(1));
            }

            int delay = 0;
            Matcher delayMatcher = DELAY_PATTERN.matcher(cmd);
            if (delayMatcher.find()) {
                delay = Integer.parseInt(delayMatcher.group(1));
            }

            // Decompose and scale row-major BDE matrix
            double scale = instance.getScale();
            Matrix4f matrix = new Matrix4f();
            matrix.setTransposed(m);

            org.bukkit.entity.Entity root = instance.getRootEntity();
            org.bukkit.entity.Entity vehicleRoot = instance.getVehicleRoot();
            boolean isPassenger = (vehicleRoot != null);

            float mountHeight = 0f;
            if (isPassenger) {
                top.sanscraft.bde.manager.ModelManager.BoundingBox box = plugin.getModelManager().calculateModelBounds(instance.getModel(), scale);
                float interactionHeight = Math.max(0.1f, box.getMaxY());
                mountHeight = plugin.getModelManager().getMountHeight(vehicleRoot, interactionHeight);
            }

            Location loc = instance.getLocation();
            float yaw = (float) loc.getYaw();
            float pitch = (float) loc.getPitch();
            if (isPassenger) {
                if (top.sanscraft.bde.manager.ModelManager.isVersion1_20_5_OrHigher()) {
                    yaw = 0.0f;
                } else {
                    float spawnYaw = (float) instance.getSpawnLocation().getYaw();
                    yaw = (float) ((yaw - spawnYaw) % 360.0);
                }
            }

            Matrix4f finalMatrix;
            if (isPassenger) {
                finalMatrix = top.sanscraft.bde.manager.ModelTransformEngine.getDisplayPassengerMatrix(matrix, scale, mountHeight, instance.getModel(), yaw, pitch, vehicleRoot != null);
            } else {
                finalMatrix = top.sanscraft.bde.manager.ModelTransformEngine.getDisplayWorldLocalMatrix(matrix, scale, instance.getModel(), yaw, pitch);
            }

            Transformation transformation = top.sanscraft.bde.manager.ModelTransformEngine.decomposeToTransformation(finalMatrix);

            // Scale duration and delay by current playback speed
            int durationScaled = Math.max(1, (int) Math.round(duration / speed));
            int delayScaled = Math.max(-1, (int) Math.round(delay / speed));

            // Set variables on entity
            display.setInterpolationDuration(durationScaled);
            display.setInterpolationDelay(delayScaled);
            display.setTransformation(transformation);
        }

        private void processSoundCommand(String cmd) {
            // e.g. playsound ambient.basalt_deltas.additions block @a ~ ~ ~ 1 1.125
            String[] parts = cmd.trim().split("\\s+");
            if (parts.length < 2) return;
            if (!parts[0].equalsIgnoreCase("playsound")) return;

            String soundName = parts[1];
            org.bukkit.SoundCategory category = org.bukkit.SoundCategory.MASTER;
            float volume = 1.0f;
            float pitch = 1.0f;
            Location playLoc = instance.getLocation().clone();

            if (parts.length > 2) {
                category = parseSoundCategory(parts[2]);
            }
            if (parts.length > 6) {
                try {
                    double x = parseCoordinate(parts[4], playLoc.getX());
                    double y = parseCoordinate(parts[5], playLoc.getY());
                    double z = parseCoordinate(parts[6], playLoc.getZ());
                    playLoc.setX(x);
                    playLoc.setY(y);
                    playLoc.setZ(z);
                } catch (NumberFormatException ignored) {}
            }
            if (parts.length > 7) {
                try {
                    volume = Float.parseFloat(parts[7]);
                } catch (NumberFormatException ignored) {}
            }
            if (parts.length > 8) {
                try {
                    pitch = Float.parseFloat(parts[8]);
                } catch (NumberFormatException ignored) {}
            }

            Sound vanillaSound = AnimationEngine.matchVanillaSound(soundName);
            if (vanillaSound != null) {
                playLoc.getWorld().playSound(playLoc, vanillaSound, category, volume, pitch);
            } else {
                playLoc.getWorld().playSound(playLoc, soundName, category, volume, pitch);
            }
        }


        private org.bukkit.SoundCategory parseSoundCategory(String categoryName) {
            String lower = categoryName.toLowerCase();
            switch (lower) {
                case "master": return org.bukkit.SoundCategory.MASTER;
                case "music": return org.bukkit.SoundCategory.MUSIC;
                case "record":
                case "records": return org.bukkit.SoundCategory.RECORDS;
                case "weather": return org.bukkit.SoundCategory.WEATHER;
                case "block":
                case "blocks": return org.bukkit.SoundCategory.BLOCKS;
                case "hostile": return org.bukkit.SoundCategory.HOSTILE;
                case "neutral": return org.bukkit.SoundCategory.NEUTRAL;
                case "player":
                case "players": return org.bukkit.SoundCategory.PLAYERS;
                case "ambient": return org.bukkit.SoundCategory.AMBIENT;
                case "voice": return org.bukkit.SoundCategory.VOICE;
                default: return org.bukkit.SoundCategory.MASTER;
            }
        }

        private double parseCoordinate(String arg, double relativeTo) {
            if (arg.startsWith("~")) {
                if (arg.length() == 1) {
                    return relativeTo;
                }
                return relativeTo + Double.parseDouble(arg.substring(1));
            } else if (arg.startsWith("^")) {
                if (arg.length() == 1) {
                    return relativeTo;
                }
                return relativeTo + Double.parseDouble(arg.substring(1));
            } else {
                return Double.parseDouble(arg);
            }
        }
    }

    public static Sound matchVanillaSound(String soundName) {
        if (soundName == null) return null;
        String clean = soundName.toLowerCase();
        if (clean.startsWith("minecraft:")) {
            clean = clean.substring(10);
        }
        String enumName = clean.replace('.', '_').replace(':', '_').toUpperCase();
        try {
            return Sound.valueOf(enumName);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
