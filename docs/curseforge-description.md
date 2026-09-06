**MeshTiles** imports 3D models (`.obj`) into Minecraft 1.12.2 as [LittleTiles](https://www.curseforge.com/minecraft/mc-mods/littletiles) tiles.
Made for city-scale building: roads, bridges, buildings and whole districts modelled in Blender at 1:1 scale.

## What it does

- **Fast.** A million-triangle model is voxelized in well under a second on the client. Multithreaded, watertight,
  no duplicates, tiles merged into large boxes.
- **Real scale.** 1 model unit = 1 block, plus a scale factor. Offsets and mirrors in Blender axes (Z up).
- **A block per material.** Every material of the model gets its own block, colour mode (plain / colour from `.mtl` /
  texture sampled per tile) and its own detail level: 1, 2, 4, 8, 16, 32 or 64 tiles per block.
  At detail 1 a material can be placed as real Minecraft blocks instead of tiles.
- **Order matters.** Materials are placed top to bottom and the top one wins where surfaces overlap. Nothing is ever
  placed into occupied space, so no z-fighting.
- **Server-friendly placement.** The model is streamed to the server and placed with a per-tick budget. A progress bar with
  time left, cancel any time, other players keep playing.
- **Undo and redo that survive restarts.** Every import is recorded in the world; `/meshtiles list`, `/meshtiles undo <id>`,
  `/meshtiles redo <id>`.
- **Preview before placing.** Wireframe of the model at its exact target position plus a bounding frame.
- **Positioning.** Rotate around the importer block, mirror inside the bounding box, snap the corner or use the
  Blender scene origin, offsets in blocks. Re-exporting the model never moves it.
- File chooser with recent files, material presets remembered by material name, teleport to the model, a classic
  recipe item for small models.

## How to use

1. Craft or grab the **MeshTiles Importer** (LittleTiles premade tab, or search "MeshTiles") and place it. It is the origin of the model.
2. Right-click it, choose the `.obj` with `...`, press **Scan**.
3. Assign a block, detail level and colour mode to each material. Reorder with `^` / `v`.
4. Set scale, axes, rotation and offset. **Voxelize** and check the wireframe in the world.
5. **Place**. A progress bar shows blocks done and time left. **Undo** if needed, **Redo** to put it back.

Importing requires creative mode or op. The mod must be installed on the server and on every client.

## Requirements

- Minecraft 1.12.2, Forge 14.23.5.2847 or newer
- [CreativeCore](https://www.curseforge.com/minecraft/mc-mods/creativecore) 1.10.x
- [LittleTiles](https://www.curseforge.com/minecraft/mc-mods/littletiles) 1.5.x

## Configuration

`config/meshtiles.cfg`: placement budget per tick (`blocksPerTick`, `maxMillisPerTick`), undo speed, whether solid
blocks may be replaced, undo history size, colour quantization, voxelizer threads.

## License

All rights reserved. You may use the released jar on your clients and servers; modification and redistribution are
not permitted. Source: [github.com/arthaix/MeshTiles](https://github.com/arthaix/MeshTiles).
