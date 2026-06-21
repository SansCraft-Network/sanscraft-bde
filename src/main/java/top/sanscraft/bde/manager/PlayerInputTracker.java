package top.sanscraft.bde.manager;

import io.netty.channel.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import top.sanscraft.bde.SansCraftBDEPlugin;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerInputTracker {
    private final SansCraftBDEPlugin plugin;
    private final Map<UUID, PlayerInputData> inputs = new ConcurrentHashMap<>();
    private final Set<UUID> activeDrivers = ConcurrentHashMap.newKeySet();
    private boolean useModernEvent = false;

    public static class PlayerInputData {
        public boolean forward;
        public boolean backward;
        public boolean left;
        public boolean right;
        public boolean jump;
        public boolean shift;
        public boolean sprint;
    }

    public PlayerInputTracker(SansCraftBDEPlugin plugin) {
        this.plugin = plugin;
        setupInputListener();
    }

    @SuppressWarnings("unchecked")
    private void setupInputListener() {
        try {
            // Check if modern Paper input event is available
            Class<? extends org.bukkit.event.Event> eventClass = (Class<? extends org.bukkit.event.Event>) 
                    Class.forName("org.bukkit.event.player.PlayerInputEvent");

            Bukkit.getPluginManager().registerEvent(
                    eventClass,
                    new org.bukkit.event.Listener() {},
                    org.bukkit.event.EventPriority.NORMAL,
                    (listener, event) -> {
                        if (eventClass.isInstance(event)) {
                            try {
                                Player player = (Player) event.getClass().getMethod("getPlayer").invoke(event);
                                UUID uuid = player.getUniqueId();
                                if (!activeDrivers.contains(uuid)) return;

                                Object inputObj = event.getClass().getMethod("getInput").invoke(event);
                                PlayerInputData data = new PlayerInputData();
                                data.forward = getBooleanInput(inputObj, "isForward", "forward");
                                data.backward = getBooleanInput(inputObj, "isBackward", "backward");
                                data.left = getBooleanInput(inputObj, "isLeft", "left");
                                data.right = getBooleanInput(inputObj, "isRight", "right");
                                data.jump = getBooleanInput(inputObj, "isJump", "jump", "jumping");
                                data.shift = getBooleanInput(inputObj, "isSneak", "sneak", "isShift", "shift");
                                data.sprint = getBooleanInput(inputObj, "isSprint", "sprint");

                                inputs.put(uuid, data);

                                if (ModelManager.DEBUG_VEHICLES) {
                                    plugin.getLogger().info("[BDE Debug] PlayerInputEvent for " + player.getName() +
                                            ": W=" + data.forward + ", S=" + data.backward +
                                            ", A=" + data.left + ", D=" + data.right);
                                }
                            } catch (Exception e) {
                                plugin.getLogger().warning("Failed to decode PlayerInputEvent: " + e.getMessage());
                            }
                        }
                    },
                    plugin
            );
            plugin.getLogger().info("[BDE] Successfully registered modern PlayerInputEvent listener dynamically.");
            useModernEvent = true;
        } catch (ClassNotFoundException e) {
            plugin.getLogger().info("[BDE] Modern PlayerInputEvent not found. Falling back to Netty packet injection for WASD inputs.");
            useModernEvent = false;
        }
    }

    public PlayerInputData getInput(UUID uuid) {
        return inputs.getOrDefault(uuid, new PlayerInputData());
    }

    public void inject(Player player) {
        UUID uuid = player.getUniqueId();
        activeDrivers.add(uuid);
        if (ModelManager.DEBUG_VEHICLES) {
            plugin.getLogger().info("[BDE Debug] Injecting input tracker for " + player.getName() + " (useModernEvent=" + useModernEvent + ")");
        }

        if (useModernEvent) return;

        try {
            Channel channel = getChannel(player);
            if (channel == null) {
                if (ModelManager.DEBUG_VEHICLES) {
                    plugin.getLogger().warning("[BDE Debug] Netty channel is null for " + player.getName());
                }
                return;
            }

            String handlerName = "bde_input_" + uuid;
            if (channel.pipeline().get(handlerName) != null) {
                channel.pipeline().remove(handlerName);
            }

            channel.pipeline().addBefore("packet_handler", handlerName, new ChannelDuplexHandler() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                    String className = msg.getClass().getName();
                    if (className.equals("net.minecraft.network.protocol.game.ServerboundPlayerInputPacket") || 
                        className.contains("PlayerInput") || 
                        className.contains("SteerVehicle") || 
                        className.endsWith("PacketPlayInSteerVehicle")) {
                        PlayerInputData data = parseInputPacket(msg);
                        if (data != null) {
                            inputs.put(uuid, data);
                            if (ModelManager.DEBUG_VEHICLES) {
                                plugin.getLogger().info("[BDE Debug] Netty Parsed Input for " + player.getName() + 
                                    ": W=" + data.forward + ", S=" + data.backward + 
                                    ", A=" + data.left + ", D=" + data.right);
                            }
                        } else {
                            if (ModelManager.DEBUG_VEHICLES) {
                                plugin.getLogger().warning("[BDE Debug] Netty packet parsed as NULL for " + player.getName());
                            }
                        }
                    }
                    super.channelRead(ctx, msg);
                }
            });
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to inject packet listener for player " + player.getName() + ": " + e.getMessage());
        }
    }

    public void uninject(Player player) {
        UUID uuid = player.getUniqueId();
        activeDrivers.remove(uuid);
        inputs.remove(uuid);
        if (ModelManager.DEBUG_VEHICLES) {
            plugin.getLogger().info("[BDE Debug] Uninjecting input tracker for driver: " + player.getName());
        }

        if (useModernEvent) return;

        try {
            Channel channel = getChannel(player);
            if (channel == null) return;

            String handlerName = "bde_input_" + uuid;
            if (channel.pipeline().get(handlerName) != null) {
                channel.pipeline().remove(handlerName);
            }
        } catch (Exception ignored) {}
    }

    private Channel getChannel(Player player) {
        try {
            Object serverPlayer = player.getClass().getMethod("getHandle").invoke(player);
            if (serverPlayer == null) return null;
            
            if (ModelManager.DEBUG_VEHICLES) {
                plugin.getLogger().info("[BDE Debug] getChannel - serverPlayer class: " + serverPlayer.getClass().getName());
            }
            
            Set<Object> visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            Channel channel = findChannel(serverPlayer, 0, visited);
            if (channel == null && ModelManager.DEBUG_VEHICLES) {
                plugin.getLogger().warning("[BDE Debug] findChannel deep search returned null");
            }
            return channel;
        } catch (Exception e) {
            if (ModelManager.DEBUG_VEHICLES) {
                plugin.getLogger().warning("[BDE Debug] Exception in getChannel: " + e.getMessage());
                e.printStackTrace();
            }
            return null;
        }
    }

    private Channel findChannel(Object obj, int depth, Set<Object> visited) {
        if (obj == null || depth > 5 || visited.contains(obj)) {
            return null;
        }
        visited.add(obj);

        if (obj instanceof Channel) {
            return (Channel) obj;
        }

        // Avoid scanning common system, bukkit, or java classes to stay on path and be fast
        String className = obj.getClass().getName();
        if (className.startsWith("java.") || 
            className.startsWith("javax.") || 
            className.startsWith("org.bukkit.") || 
            className.startsWith("org.spigotmc.") ||
            className.startsWith("com.google.") ||
            className.startsWith("com.mojang.") ||
            className.startsWith("io.netty.util.") ||
            className.startsWith("io.netty.buffer.")) {
            return null;
        }

        // Scan all fields including superclasses
        Class<?> current = obj.getClass();
        while (current != null && current != Object.class) {
            for (java.lang.reflect.Field f : current.getDeclaredFields()) {
                // Skip primitive fields and static fields
                if (f.getType().isPrimitive() || java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object fieldValue = f.get(obj);
                    if (fieldValue != null) {
                        Channel ch = findChannel(fieldValue, depth + 1, visited);
                        if (ch != null) {
                            if (ModelManager.DEBUG_VEHICLES) {
                                plugin.getLogger().info("[BDE Debug] Found Netty Channel via field '" + f.getName() + "' (" + f.getType().getName() + ") at depth " + depth);
                            }
                            return ch;
                        }
                    }
                } catch (Throwable ignored) {}
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private PlayerInputData parseInputPacket(Object packet) {
        try {
            Object inputObj = null;
            // 1. Try finding accessor method or field for Input object (1.20.5+)
            try {
                inputObj = packet.getClass().getMethod("input").invoke(packet);
            } catch (Exception e) {
                // Scan fields for non-primitive fields with "input" or record types
                for (java.lang.reflect.Field f : packet.getClass().getDeclaredFields()) {
                    if (!f.getType().isPrimitive() && (f.getType().getSimpleName().contains("Input") || f.getType().isRecord())) {
                        f.setAccessible(true);
                        inputObj = f.get(packet);
                        break;
                    }
                }
            }

            if (inputObj != null) {
                PlayerInputData data = new PlayerInputData();
                boolean parsed = false;
                
                // Try record-like methods first
                java.lang.reflect.Method[] methods = inputObj.getClass().getDeclaredMethods();
                for (java.lang.reflect.Method m : methods) {
                    if (m.getParameterCount() == 0 && m.getReturnType() == boolean.class) {
                        m.setAccessible(true);
                        String name = m.getName().toLowerCase();
                        boolean val = (boolean) m.invoke(inputObj);
                        if (name.equals("forward") || name.equals("isforward")) { data.forward = val; parsed = true; }
                        else if (name.equals("backward") || name.equals("isbackward")) { data.backward = val; parsed = true; }
                        else if (name.equals("left") || name.equals("isleft") || name.equals("pressingleft")) { data.left = val; parsed = true; }
                        else if (name.equals("right") || name.equals("isright") || name.equals("pressingright")) { data.right = val; parsed = true; }
                        else if (name.equals("jump") || name.equals("isjump") || name.equals("jumping")) { data.jump = val; parsed = true; }
                        else if (name.equals("shift") || name.equals("sneak") || name.equals("issneak") || name.equals("isshift") || name.equals("shiftkeydown")) { data.shift = val; parsed = true; }
                        else if (name.equals("sprint") || name.equals("issprint")) { data.sprint = val; parsed = true; }
                    }
                }
                
                // Also scan fields in case getters are obfuscated or named uniquely
                java.lang.reflect.Field[] fields = inputObj.getClass().getDeclaredFields();
                int boolFieldIdx = 0;
                for (java.lang.reflect.Field f : fields) {
                    if (f.getType() == boolean.class) {
                        f.setAccessible(true);
                        String name = f.getName().toLowerCase();
                        boolean val = f.getBoolean(inputObj);
                        
                        // Named fields
                        if (name.equals("forward") || name.equals("zza") || name.equals("forwardkey")) { data.forward = val; parsed = true; }
                        else if (name.equals("backward") || name.equals("backkey")) { data.backward = val; parsed = true; }
                        else if (name.equals("left") || name.equals("xxa") || name.equals("leftkey") || name.equals("pressingleft")) { data.left = val; parsed = true; }
                        else if (name.equals("right") || name.equals("rightkey") || name.equals("pressingright")) { data.right = val; parsed = true; }
                        else if (name.equals("jump") || name.equals("jumping") || name.equals("jumpkey")) { data.jump = val; parsed = true; }
                        else if (name.equals("shift") || name.equals("sneak") || name.equals("shiftkeydown") || name.equals("sneakkey")) { data.shift = val; parsed = true; }
                        else if (name.equals("sprint") || name.equals("sprintkey")) { data.sprint = val; parsed = true; }
                        else {
                            // If fields are obfuscated (a, b, c, d, e, f, g), map them by index
                            // Typical order in Input record: forward, backward, left, right, jump, shift, sprint
                            if (boolFieldIdx == 0) data.forward = val;
                            else if (boolFieldIdx == 1) data.backward = val;
                            else if (boolFieldIdx == 2) data.left = val;
                            else if (boolFieldIdx == 3) data.right = val;
                            else if (boolFieldIdx == 4) data.jump = val;
                            else if (boolFieldIdx == 5) data.shift = val;
                            else if (boolFieldIdx == 6) data.sprint = val;
                        }
                        boolFieldIdx++;
                    }
                }
                
                if (parsed) {
                    return data;
                }
            }
        } catch (Exception ignored) {}

        // Fallback for older flat fields in ServerboundPlayerInputPacket (1.20.4 and below)
        try {
            PlayerInputData data = new PlayerInputData();
            java.lang.reflect.Field xxaField = null;
            java.lang.reflect.Field zzaField = null;
            java.lang.reflect.Field jumpingField = null;
            java.lang.reflect.Field shiftField = null;

            // First try by name (Mojang or Spigot mappings)
            for (java.lang.reflect.Field f : packet.getClass().getDeclaredFields()) {
                String name = f.getName().toLowerCase();
                if (name.equals("xxa")) xxaField = f;
                else if (name.equals("zza")) zzaField = f;
                else if (name.equals("jumping") || name.equals("isjumping")) jumpingField = f;
                else if (name.equals("shiftkeydown") || name.equals("isshiftkeydown") || name.equals("shift")) shiftField = f;
            }

            // If not found by name, try finding by type and order (obfuscated fallback)
            if (xxaField == null || zzaField == null) {
                java.util.List<java.lang.reflect.Field> floats = new java.util.ArrayList<>();
                java.util.List<java.lang.reflect.Field> booleans = new java.util.ArrayList<>();
                for (java.lang.reflect.Field f : packet.getClass().getDeclaredFields()) {
                    if (f.getType() == float.class) {
                        floats.add(f);
                    } else if (f.getType() == boolean.class) {
                        booleans.add(f);
                    }
                }
                if (floats.size() >= 2) {
                    xxaField = floats.get(0);
                    zzaField = floats.get(1);
                }
                if (booleans.size() >= 2) {
                    jumpingField = booleans.get(0);
                    shiftField = booleans.get(1);
                }
            }

            boolean parsed = false;
            if (xxaField != null && zzaField != null) {
                xxaField.setAccessible(true);
                zzaField.setAccessible(true);
                float xxa = xxaField.getFloat(packet);
                float zza = zzaField.getFloat(packet);
                data.left = xxa > 0;
                data.right = xxa < 0;
                data.forward = zza > 0;
                data.backward = zza < 0;
                parsed = true;
            }
            if (jumpingField != null) {
                jumpingField.setAccessible(true);
                data.jump = jumpingField.getBoolean(packet);
                parsed = true;
            }
            if (shiftField != null) {
                shiftField.setAccessible(true);
                data.shift = shiftField.getBoolean(packet);
                parsed = true;
            }
            if (parsed) {
                return data;
            }
        } catch (Exception ignored) {}

        if (ModelManager.DEBUG_VEHICLES) {
            // Print diagnostic details about the packet to help troubleshoot
            Bukkit.getLogger().warning("[BDE Debug] Failed to decode packet: " + packet.getClass().getName());
            Bukkit.getLogger().warning("[BDE Debug] Packet Fields:");
            for (java.lang.reflect.Field f : packet.getClass().getDeclaredFields()) {
                Bukkit.getLogger().warning("  - " + f.getType().getName() + " " + f.getName());
            }
            Bukkit.getLogger().warning("[BDE Debug] Packet Methods:");
            for (java.lang.reflect.Method m : packet.getClass().getDeclaredMethods()) {
                if (m.getParameterCount() == 0) {
                    Bukkit.getLogger().warning("  - " + m.getReturnType().getName() + " " + m.getName() + "()");
                }
            }
        }
        return null;
    }

    private static boolean getBooleanInput(Object inputObj, String... methodNames) {
        for (String name : methodNames) {
            try {
                java.lang.reflect.Method m = inputObj.getClass().getMethod(name);
                m.setAccessible(true);
                return (boolean) m.invoke(inputObj);
            } catch (NoSuchMethodException e) {
                // Try next
            } catch (Exception e) {
                // Ignore other exceptions
            }
        }
        return false;
    }
}
