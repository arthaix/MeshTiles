# MeshTiles

Imports 3D models (`.obj`) into Minecraft 1.12.2 as [LittleTiles](https://www.curseforge.com/minecraft/mc-mods/littletiles) tiles.
Built for city-scale builds: a million-triangle model is voxelized in well under a second, streamed to the server and
placed block by block without freezing anyone.

Author: Aleksei Usenko (arthaix). All rights reserved: you may use the released jar, but not modify or redistribute it (see LICENSE).

## Features

- **Fast**: streaming `.obj` parser on primitive arrays, multithreaded region voxelizer with an exact per-cell
  rasterizer (watertight shell, no duplicates), greedy merging of tiles into boxes.
  1 000 000 triangles → 10 million voxels → 1.5 million tiles in ~0.3 s.
- **1 unit = 1 block**, plus a scale factor. Blender axes (Z up) by default.
- **A block per material**: every `usemtl` material gets its own block, colour mode (none / Kd from `.mtl` /
  texture sampled from `map_Kd`), and its own detail level (1, 2, 4, 8, 16, 32 or 64 tiles per block).
  At detail 1 the **MC** box places real Minecraft blocks instead of LittleTiles (terrain, fills, rough massing).
- **Material order matters**: materials are placed top to bottom, and where surfaces overlap the top one wins.
  Tiles are never placed into space that is already occupied, so no z-fighting.
- **Server-side placement** with a per-tick budget, a progress bar with time left, cancel, and a persistent import history
  with undo by number (`/meshtiles list`, `/meshtiles undo <id>`) and redo of what an undo removed.
- **Preview**: wireframe of the model at its target position and a bounding frame, before you place.
- Positioning: snap corner (XZ / Y), rotation around the importer block, mirror inside the bounding box,
  "Origin" mode (Blender scene origin = importer block), offsets.
- File chooser with recent files, material presets remembered per material name, teleport to the model.
- Recipe item for small models (the classic LittleTiles blueprint).

## Usage

1. Craft or grab the **MeshTiles Importer** (LittleTiles premade tab, or search "MeshTiles"). Place it: it is the
   origin of the model.
2. Right-click it. Pick the `.obj` with `...` (or paste the path), press **Scan**.
3. For each material choose a block, detail level (1/2/4/8/16/32/64), colour mode and, if needed, `skip`.
   Detail 1 + **MC** = plain Minecraft blocks, no tiles.
   Reorder with `^` / `v`: top materials are placed first and win overlaps (e.g. road markings above asphalt).
4. Set scale, axes, rotation, mirror, snapping and offset. **Voxelize** and check the wireframe in the world.
5. **Place**. A progress bar shows blocks done and time left. **Undo** reverts the last import, **Redo** puts it back;
   `/meshtiles list` shows older ones.

Importing needs creative mode or op. Every client needs the same mod jar as the server.

### Positioning cheat sheet

| Setting | Effect |
|---|---|
| Snap XZ | model's min X/Z corner on the importer block |
| Snap Y | model's lowest point on the importer block (changes when the model's bottom changes) |
| Origin | Blender scene origin (0,0,0) on the importer block, no snapping |
| Rotation | whole model (frame included) rotates around the importer block |
| Mirror X/Y/Z | flips the model inside its bounding box, the box stays |
| Offset | added after rotation, in Blender axes (Z = up) |

The **File Y / File Z** button says which axis is up in the `.obj` itself: Blender's default export is File Y; choose File Z only if you exported with Forward Y, Up Z.

## Commands

```
/meshtiles list              imports recorded in this world (id, player, time, summary)
/meshtiles undo [id]         undo your last import, or the given one (op level 2 for other players' imports)
/meshtiles redo [id]         put an undone import back (what the undo removed, tile for tile)
/meshtiles cancel [player]   stop a running import
/meshtiles status            what is being placed right now
```

## Configuration (`config/meshtiles.cfg`)

| Key | Default | Meaning |
|---|---|---|
| `blocksPerTick` | 48 | world blocks filled per server tick while importing |
| `maxMillisPerTick` | 25 | time budget per tick |
| `undoBlocksPerTick` | 1500 | blocks removed per tick while undoing |
| `replaceSolidBlocks` | false | overwrite stone/dirt etc. (default: only air, plants, water and existing LittleTiles) |
| `keepUndo` / `historySize` | true / 25 | undo data kept per world |
| `colorLevels` | 32 | colour quantization steps per channel (fewer = fewer tiles) |
| `voxelThreads` | 0 | voxelizer threads, 0 = all cores |
| `recipeMaxBoxes` | 200000 | refuse recipe items bigger than this |

## Building

Requires JDK 8 for the game toolchain (Gradle itself runs on JDK 21, see `gradle.properties`).
LittleTiles and CreativeCore are not on a public Maven, so put their jars into a local Maven layout:

```
localmaven/com/creativemd/littletiles/1.5.14/littletiles-1.5.14.jar   (+ a minimal .pom)
localmaven/com/creativemd/creativecore/1.10.61/creativecore-1.10.61.jar (+ a minimal .pom)
```

then

```
./gradlew build          # build/libs/meshtiles-<version>.jar
./gradlew runClient      # dev client
```

`tools/gen_test_obj.py` generates test models; `tools/count_lt_tiles.py <world>/region` counts LittleTiles blocks in a save.

## Requirements

Minecraft 1.12.2, Forge 14.23.5.x, CreativeCore 1.10.x, LittleTiles 1.5.x.
