# SansCraftBDE (Block Display Engine)

SansCraftBDE is a high-performance Minecraft Paper/Spigot plugin that introduces custom 3D models made entirely of Minecraft Block, Item, and Text Display entities. It features a complete physical vehicle engine with WASD steering, configurable velocity/handling stats, a multi-seat mounting system, and real-time in-game configuration editors.

---

## Features

- **High-Performance 3D Models**: Uses native Minecraft Block, Item, and Text Display entities to render detailed 3D models without client-side mods or resource pack overhead.
- **Physical Vehicle Engine**: Fully steerable vehicles using WASD packet interception (Netty). Supports configurable top speed, acceleration, deceleration, turn speed, reverse speed, and automatic ground height alignment.
- **Dynamic Driving Front Offset**: Adjust visual model alignment relative to the driving direction in 90-degree increments to correct models modeled with non-standard front alignments.
- **Smart Seat Routing**:
  - **Sneak (Shift) + Right-Click**: Forces mounting in the driver seat (notifies the player if occupied).
  - **Normal Right-Click**: Opens a seat selection GUI (if multiple seats are vacant) or automatically seats the player if only one is vacant.
- **In-Game Seat Config Editor**:
  - Add or delete passenger seats.
  - Dynamically translate seat offsets (X/Y/Z) with real-time in-world visual feedback.
  - Rotate seats using a **Triple Seat Rotation System** (Perfect 0°, Cardinal 90/180/270°, or custom Double precision).
- **Vehicle Catalog (`/bde vehicles`)**:
  - Navigate vehicle project files stored under the `vehicles/` directory using an in-game folder interface.
  - Spawn vehicles, edit metadata, rename projects, or customize catalog icons directly from the GUI.
- **Simplified Spawning**: Snap orientations to cardinal directions and align bounding boxes to touch block floors automatically.

---

## Commands

### Command Reference

| Command | Description | Permission |
| --- | --- | --- |
| `/bde spawn <model_id> [scale] [x] [y] [z] [yaw] [flags]` | Spawns a BDE model at target coordinates. | `sanscraft.bde.admin` |
| `/bde vehicles` | Opens the vehicle catalog GUI. | `sanscraft.bde.admin` |
| `/bde gui` | Opens the main configuration GUI for the selected model. | `sanscraft.bde.admin` |
| `/bde select` | Selects the nearest spawned BDE model within 6 blocks. | `sanscraft.bde.admin` |
| `/bde clear` / `/bde deselect` | Deselects the currently selected BDE model. | `sanscraft.bde.admin` |
| `/bde remove` | Removes the currently selected BDE model from the world. | `sanscraft.bde.admin` |
| `/bde move <x_offset> <y_offset> <z_offset>` | Moves the selected model by a relative block offset. | `sanscraft.bde.admin` |
| `/bde rotate <yaw_offset>` | Rotates the selected model by a relative angle. | `sanscraft.bde.admin` |
| `/bde list` | Lists all active model instances spawned in the world. | `sanscraft.bde.admin` |
| `/bde anim <play|stop> <animation_name>` | Controls animation keyframes for the selected model. | `sanscraft.bde.admin` |
| `/bde debug <vehicles|steering>` | Toggles runtime debug logging for packets and vehicle ticks. | `sanscraft.bde.admin` |

### Spawning Flags
- **`simple`**: Snaps the spawned model's yaw rotation to the nearest 90-degree cardinal direction relative to the player's direction, and levels the pitch to `0.0f`.
- **`onground`**: Automatically adjusts the spawn Y-coordinate so the lowest boundary of the model's bounding box rests perfectly on the top of the solid block below.

---

## In-Game Configuration Editors

### Vehicle Config Editor (`/bde gui`)
Right-click a vehicle in the `/bde vehicles` catalog or use `/bde gui` on a selected spawned model:
- **Driving Front Offset (Slot 17 - Compass)**: Cycles the visual model heading offset (`0° -> 90° -> 180° -> 270°`) relative to the movement vector.
- **Seat Configuration (Slot 16 - Saddle)**: Opens the Seat Editor.
- **Save (Slot 52 - Green Dye)**: Overwrites the configuration file in `vehicles/` for registered configurations.
- **Save As (Slot 53 - Book)**: Saves the configuration to a new filepath (entered via chat prompt) under the `vehicles/` directory.

### Seat Config Editor
Manage driver and passenger positions:
- **Add Passenger Seat (Slot 46 - Slimeball)**: Spawns a new seat armor stand at the vehicle center.
- **Seat Details (Left-Click any seat item)**:
  - **X/Y/Z Offsets**: Left/Right click to translate by `0.1` blocks (Shift + Click for `0.01` blocks). The in-world ArmorStand shifts in real-time.
  - **Seat Yaw Rotation (Slot 26 - Redstone)**:
    - **Left-Click**: Cycles cardinally (`0° -> 90° -> 180° -> 270°`).
    - **Right-Click**: Prompt chat input to type custom double-precision angles (e.g. `45.5`).
  - **Delete Seat (Slot 25 - Barrier)**: Removes the passenger seat from the configuration.

---

## Development & Contribution

### Compiling Build Profiles
Ensure you have Maven installed and JDK 21 configured.

- **Modern Build Target** (Java 21, Paper 1.21.1+):
  ```bash
  mvn clean package
  ```
  Generates `target/sanscraft-bde-1.0.0-SNAPSHOT.jar`

- **Legacy Build Target** (Java 17, Paper 1.20.4+):
  ```bash
  mvn clean package -Plegacy
  ```
  Generates `target/sanscraft-bde-1.0.0-SNAPSHOT.jar` built targeting Java 17 bytecode.

---

## License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.
