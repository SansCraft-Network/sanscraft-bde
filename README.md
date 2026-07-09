# SansCraftBDE (Block Display Engine)

[![Build & Release](https://github.com/SansCraft-Network/sanscraft-bde/actions/workflows/build-and-release.yml/badge.svg)](https://github.com/SansCraft-Network/sanscraft-bde/actions/workflows/build-and-release.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Target: Paper 1.21.11+](https://img.shields.io/badge/Minecraft-Paper%201.21.11%2B-blue.svg)](https://papermc.io/)

SansCraftBDE is a high-performance Paper/Spigot plugin that introduces detailed 3D models made entirely of Minecraft Block, Item, and Text Display entities. Featuring a fully steerable physical vehicle engine, customizable multi-seat mounting mechanics, voxelizing model converters, and a custom block placement framework, SansCraftBDE allows server creators to build immersive 3D gameplay without client-side mods or resource pack overhead.

---

## Table of Contents
- [Core Features](#core-features)
- [3D Model Converters](#3d-model-converters)
  - [Supported Formats](#supported-formats)
  - [Voxelization & Downsampling](#voxelization--downsampling)
  - [Optimization (Greedy Meshing)](#optimization-greedy-meshing)
  - [Mapping Files (`mappings/*.yml`)](#mapping-files-mappingsyml)
- [Custom Blocks Framework](#custom-blocks-framework)
  - [Mechanics & Collision](#mechanics--collision)
  - [Custom Block Commands](#custom-block-commands)
- [Physical Vehicle Engine](#physical-vehicle-engine)
  - [Steering & Physics Stats](#steering--physics-stats)
  - [Seat Configurations & GUI Editor](#seat-configurations--gui-editor)
  - [Catalog GUI & Spawning Logic](#catalog-gui--spawning-logic)
- [Command Reference](#command-reference)
- [Configuration Templates](#configuration-templates)
  - [`config.yml`](#configyml)
  - [`custom_blocks.yml`](#custom_blocksyml)
  - [BDE Model JSON Structure](#bde-model-json-structure)
- [Developer & Contribution Guide](#developer--contribution-guide)
  - [Build Target Profiles](#build-target-profiles)
  - [Continuous Integration Workflow](#continuous-integration-workflow)
- [License](#license)

---

## Core Features

- **Mod-Free 3D Displays**: Renders complex structures using Minecraft's native display entities, minimizing network load and ensuring client compatibility.
- **Physical Vehicle Engine**: Integrates Netty-driven WASD packet steering. Supports variable top speeds, acceleration, deceleration rates, reverse, turning speed limits, and terrain height detection.
- **Voxelizer Conversion Utility**: Converts mesh/texture formats (`.gltf`, `.glb`, `.obj`, `.vox`) and Blockbench JSON into BDE-compliant JSON models.
- **Solid Custom Blocks**: Maps display models to physical solid `BARRIER` blocks, allowing custom-shaped functional structures with place, break, sound, and particle support.
- **In-Game Configuration Editor**: Modify seat coordinates (X/Y/Z offsets, 3-level rotation modes), set visual driving headings, and save vehicle parameters directly from a GUI interface.
- **Organized Catalog**: Sort and spawn structures from directories using a visual catalog GUI (`/bde vehicles`).

---

## 3D Model Converters

SansCraftBDE provides a built-in conversion command `/bde convert` to translate standard 3D file formats into BDE-compliant passenger JSON configurations. The converted outputs are saved under the `models/` directory.

### Supported Formats

| Format | Extension | Conversion Pipeline | Key Options |
| --- | --- | --- | --- |
| **Blockbench** | `.json` | Matrix translation of cubes and textures to Block Displays. | Texture-to-Block mappings. |
| **MagicaVoxel** | `.vox` | Imports voxel colors and structures directly. | Resolution downsampling factor. |
| **glTF/GLB** | `.gltf` / `.glb` | Rasterizes/Voxelizes mesh coordinates onto a 3D grid. | Density, Target Block Size, Entity Cap. |
| **Wavefront** | `.obj` | Rasterizes/Voxelizes mesh coordinates onto a 3D grid. | Density, Target Block Size, Entity Cap. |

### Voxelization & Downsampling

For mesh models (`gltf`, `glb`, `obj`) and voxel models (`vox`), the converter maps the 3D geometry onto a grid defined by a **Voxel Density** parameter:
- **Mesh formats**: Rasterizes triangles by stepping through surfaces and sampling texture coordinates, vertex colors, or material colors to populate voxels. If the resulting entity count exceeds the maximum cap, the converter automatically scales down density and retries.
- **Vox formats**: Downsamples voxel volume using a color majority-vote algorithm inside voxel groups when a resolution factor greater than 1 is passed.

### Optimization (Greedy Meshing)

To prevent client rendering lag, the converter runs a **Greedy Meshing** algorithm on the voxel grid. Contiguous voxels sharing the same block material are merged along the X, Z, and Y axes into a single expanded Block Display entity. This reduces entity passenger counts by up to **80%** while maintaining pixel-perfect models.

### Mapping Files (`mappings/*.yml`)

Mapping files customize how textures or colors convert to Minecraft block materials. Save mapping files as `plugins/SansCraftBDE/mappings/<model_name>.yml` (e.g. for `wooden_chair.vox`, save to `mappings/wooden_chair.yml`).

```yaml
# Mapping configurations for converting wooden_chair model

# Texture mappings (Used for Blockbench models)
# Maps the texture name specified in Blockbench to a Minecraft block
texture_mappings:
  oak_log: "minecraft:oak_log"
  custom_leather: "minecraft:brown_wool"

# Color mappings (Used for Vox, glTF, glb, and OBJ voxelization)
# Maps hexadecimal color codes directly to Bukkit Material names
color_mappings:
  "#ff0000": "RED_CONCRETE"
  "#3a2512": "DARK_OAK_PLANKS"
  "#ffffff": "WHITE_WOOL"
```

If a color is not mapped in `color_mappings`, the converter falls back to a **perceptually-weighted Redmean distance algorithm** to select the closest matching block from a pre-defined palette of wools, concretes, terracottas, stone types, woods, and mineral blocks. If no close match is found, it falls back to the default block specified in `config.yml`.

---

## Custom Blocks Framework

The custom blocks framework allows administrators to define custom decorative or functional blocks using BDE display models.

### Mechanics & Collision

1. **Collision**: When a player places a custom block, SansCraftBDE places a solid `BARRIER` block at that location. This provides physical collision, support for adjacent blocks, and placement boundaries.
2. **Visual Model**: The plugin spawns the selected BDE model on top of the barrier block using the configured scale and position offset values (usually centered at `[0.5, 0.0, 0.5]`).
3. **Placing & Breaking**:
   - Spawns and links the visual entities on placing.
   - Cleans up all entities and removes the barrier on breaking.
   - Drops the correct custom block item stack with persistent data markers (unless the breaking player is in Creative mode).
   - Triggers version-safe block-break sound effects (`Sound.BLOCK_STONE_BREAK`) and particles (`Particle.CLOUD`).
4. **Data Persistence**: Placed custom blocks are stored locally in `placed_blocks.json` and are dynamically loaded/unloaded as chunk load events occur.

### Custom Block Commands

- `/bde block give <player> <custom_block_id> [amount]`
  Gives a player the placing tool item configured for the custom block.
- `/bde block link <custom_block_id>`
  Links the item stack currently held in the player's main hand to place the specified custom block. This adds the necessary PersistentData NBT tags, display name, and lore formatting.

---

## Physical Vehicle Engine

SansCraftBDE includes a complete, high-performance vehicle simulation engine designed for Paper servers.

### Steering & Physics Stats

- **WASD Packet Interception**: Listens to raw client steering packets via Netty. When a driver presses movement keys, the plugin computes physics vectors directly.
- **Handling Parameters**: Each vehicle has configurable physics attributes:
  - `top_speed` (blocks/tick): Maximum forward driving velocity.
  - `acceleration` (blocks/tick²): Forward acceleration rate.
  - `deceleration` (blocks/tick²): Friction/braking slowing rate.
  - `reverse_speed` (blocks/tick): Maximum backward driving velocity.
  - `turn_speed` (degrees/tick): Maximum angular steering yaw velocity.
- **Floor Alignment**: The engine automatically detects the ground height beneath the vehicle, ensuring smooth climbing over slabs, stairs, and slopes.
- **Model Yaw Alignment**: When driving, model yaw rotations are applied inside the display transformation matrices, which are smoothly interpolated on the client side at high frame rates.

### Seat Configurations & GUI Editor

Vehicles support a driver seat and multiple passenger seats:
- **Mounting Priority**:
  - **Sneak (Shift) + Right-Click**: Direct mount to the driver seat.
  - **Standard Right-Click**: Opens a seat selection GUI or mounts the single vacant seat.
- **Seat GUI Editor (`/bde gui`)**: Allows real-time translation (X/Y/Z) and rotation editing of passenger seat locations.
- **Triple Seat Rotation**:
  1. *Perfect (0°)*: Standard forward alignment.
  2. *Cardinal (90°/180°/270°)*: Snap alignment to horizontal directions.
  3. *Custom Double Precision*: Enter specific rotation values (e.g. `45.5°`) via chat prompts.

### Catalog GUI & Spawning Logic

Spawning vehicles from the catalog (`/bde vehicles`) includes alignment features:
- Automatically aligns the bottom bounding box of the display model with the solid block beneath it.
- Snaps orientation to the closest cardinal angle (yaw) relative to the spawning player.

---

## Command Reference

All sub-command permissions are included in the `sanscraft.bde.admin` permission.

| Command                   | Arguments                                              | Permission                 | Description                                                                                                                                                                               |
|---------------------------|--------------------------------------------------------|----------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `/bde`                    | *(None)*                                               | `sanscraft.bde`            | Lists all sub-commands.                                                                                                                                                                   |
| `/bde spawn`              | `<model_id> [scale] [x] [y] [z] [yaw] [flags]`         | `sanscraft.bde.spawn`      | Spawns a BDE model instance at the designated coordinates. Supports `~` relative coords. Flags: `simple` (cardinal snaps & level pitch), `onground` (snaps bounding box bottom to floor). |
| `/bde vehicles`           | *(None)*                                               | `sanscraft.bde.vehicles`   | Opens the vehicle catalog folder explorer interface.                                                                                                                                      |
| `/bde gui`                | *(None)*                                               | `sanscraft.bde.gui`        | Opens the configuration dashboard for the currently selected model.                                                                                                                       |
| `/bde select`             | *(None)*                                               | `sanscraft.bde.select`     | Selects the nearest spawned model instance within 6 blocks.                                                                                                                               |
| `/bde clear` / `deselect` | *(None)*                                               | `sanscraft.bde.deselect`   | Clears the current model selection.                                                                                                                                                       |
| `/bde remove`             | *(None)*                                               | `sanscraft.bde.remove`     | Permanently deletes the nearest spawned model instance.                                                                                                                                   |
| `/bde move`               | `<x> <y> <z> [yaw]`                                    | `sanscraft.bde.move`       | Relocates the selected model by a relative coordinate offset.                                                                                                                             |
| `/bde rotate`             | `<yaw_degrees>`                                        | `sanscraft.bde.rotate`     | Rotates the selected model by a relative yaw offset.                                                                                                                                      |
| `/bde list`               | *(None)*                                               | `sanscraft.bde.list`       | Outputs a list of all active spawned model instances.                                                                                                                                     |
| `/bde anim`               | `<play\|stop\|pause\|resume\|speed> [name/speed]`      | `sanscraft.bde.animate`    | Manages model animations.                                                                                                                                                                 |
| `/bde convert`            | `<format> <file> [density] [size_blocks] [entity_cap]` | `sanscraft.bde.convert`    | Converts an external 3D model into BDE JSON format.                                                                                                                                       |
| `/bde block give`         | `<player> <block_id> [amount]`                         | `sanscraft.bde.block.give` | Gives the specified player a custom block placement item.                                                                                                                                 |
| `/bde block link`         | `<custom_block_id>`                                    | `sanscraft.bde.block.link` | Converts the item in hand into a custom block placing item.                                                                                                                               |
| `/bde debug`              | `[vehicles\|steering]`                                 | `sanscraft.bde.debug`      | Toggles verbose diagnostic logging in the server console.                                                                                                                                 |

---

## Configuration Templates

### `config.yml`
```yaml
# SansCraft Block Display Engine Integration Config

# BDE Server API Endpoint
api-endpoint: "https://block-display.com/server-api/"

# How long to cache downloaded models locally in minutes (1440 = 24 hours)
cache-duration-minutes: 1440

# Whether to delete all spawned models when the server shuts down.
# If false, the models will persist across restarts, but they will become
# unselectable, non-functional, and non-deletable via the BDE plugin.
# You will need another plugin (i.e. EasyArmorStands) to select and delete models.
cleanup-models-on-shutdown: true

# Voxel conversion defaults
voxels:
  # Default block to use if a color index cannot be mapped
  default-block: "minecraft:white_concrete"
  
  # Optimize models by default using Greedy Meshing
  use-greedy-meshing: true

  # Maximum block display entities that a converted mesh model can produce
  max-display-entities: 1000

# Blockbench conversion defaults
blockbench:
  # Default block to use for unmapped textures
  default-block: "minecraft:oak_planks"

# GUI and Selection Settings
gui:
  # The item used to select and edit BDE models in-game
  selector-tool: "BLAZE_ROD"
```

### `custom_blocks.yml`
```yaml
# Custom Blocks Configuration
# Maps custom block IDs to block display models (BDE JSON format).
# Custom blocks use a physical BARRIER block for solid collision,
# and render the model as a client-side display on top.

custom_blocks:
  magma_forge:
    # Model ID or filename (in models/ folder, without .json extension)
    model: "magma_forge"
    # Scale multiplier
    scale: 1.0
    # Positional offset from the block's minimum corner (x, y, z)
    offset: [0.5, 0.0, 0.5]
    # Display item properties given to players
    item:
      material: "BRICK"
      name: "<red>Magma Forge"
      lore:
        - "<gray>A custom workstation for forge operations."
        - "<gold>Place to deploy."

  wooden_chair:
    model: "wooden_chair"
    scale: 0.8
    offset: [0.5, 0.0, 0.5]
    item:
      material: "OAK_STAIRS"
      name: "<yellow>Wooden Chair"
      lore:
        - "<gray>A detailed 3D chair model."
```

### BDE Model JSON Structure

The converted models are stored in JSON format using passenger arrangements:

```json
{
  "version": "26.1",
  "type": "vehicle",
  "project_id": "sci_fi_tank",
  "passengers": [
    "{id:\"minecraft:block_display\",block_state:{Name:\"minecraft:gray_concrete\"},transformation:[1.000000f,0.000000f,0.000000f,0.000000f,0.000000f,1.000000f,0.000000f,0.000000f,0.000000f,0.000000f,1.000000f,0.000000f,0.000000f,0.000000f,0.000000f,1.000000f],Tags:[\"bde_0\"]}"
  ],
  "vehicle": {
    "type": "armor_stand",
    "name": "Sci-Fi Heavy Tank",
    "icon": "IRON_BLOCK",
    "seat_offset": [0.0, 1.2, -0.5],
    "driver_seat_name": "Commander Seat",
    "driver_seat_icon": "SADDLE",
    "passenger_seats": [
      {
        "offset": [0.0, 1.5, 0.8],
        "name": "Gunner Seat",
        "icon": "MINECART",
        "yaw": 0.0
      }
    ],
    "front_yaw_offset": 0.0,
    "driver_seat_yaw": 0.0,
    "stats": {
      "top_speed": 0.2,
      "acceleration": 0.01,
      "deceleration": 0.04,
      "reverse_speed": 0.08,
      "turn_speed": 2.5
    }
  }
}
```

---

## Developer & Contribution Guide

### Build Target Profiles

Ensure you have Maven installed and Java Development Kit (JDK) configured.

- **Modern Build Profile** (Java 21, Paper 1.21.11+):
  ```bash
  mvn clean package
  ```
  Produces `target/sanscraft-bde-1.0.0-SNAPSHOT.jar` targeting Java 21 bytecode.

> [!WARNING]
> **Legacy Build Profile** (Java 17, Paper 1.20.4 - 1.21.11):
> Support for the legacy build profile (`-Plegacy`) has been **temporarily dropped** so we can focus resources on resolving other critical bugs. We plan to re-enable and update legacy compatibility in a future release.

### Continuous Integration Workflow

The project uses GitHub Actions to run automated testing and package compilation.
- **Location**: `.github/workflows/build-and-release.yml`
- **Behavior**: On pushes to the `main` branch, the workflow compiles the modern target build profile, runs checks, and drafts a release containing the compiled JAR artifact.

---

## License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.
