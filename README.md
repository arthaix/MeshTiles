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
  A material with a `map_Kd` texture starts on Texture, everything else on none (the block's own colours);
  Kd is a manual choice.
  At detail 1 the **MC** box places real Minecraft blocks instead of LittleTiles (terrain, fills, rough massing).
- **Air clears space**: pick "Air (clears space)" as the block of a material and its volume is emptied instead of built.
  Whole blocks, and everything a closed surface encloses (model a box, get a pit). Blocks with contents (chests, rails,
  machines) and LittleTiles structures are left standing; undo puts back everything that was cleared.
- **Material order matters**: materials are placed top to bottom, and where surfaces overlap the top one wins.
  Tiles are never placed into space that is already occupied, so no z-fighting.
- **Server-side placement** with a per-tick budget, a progress bar with time left, cancel, and a persistent import history
  with undo by number (`/meshtiles list`, `/meshtiles undo <id>`) and redo of what an undo removed.
- **Preview**: wireframe of the model at its target position and a bounding frame, before you place.
- **Speed slider**: from 1 block per second to full speed, adjustable while the model is being built, to watch it
  rise from the ground up.
- Positioning: snap corner (XZ / Y), rotation around the importer block, mirror inside the bounding box,
  "Origin" mode (Blender scene origin = importer block), offsets.
- File chooser with recent files, material presets remembered per material name, teleport to the model.
- **Blender add-on**: choose blocks, detail and placement order per material in Blender; the importer picks them up.
- Recipe item for small models (the classic LittleTiles blueprint).

## Usage

1. Craft or grab the **MeshTiles Importer** (LittleTiles premade tab, or search "MeshTiles"). Place it: it is the
   origin of the model.
2. Right-click it. Pick the `.obj` with `...` (or paste the path), press **Scan**.
3. For each material choose a block, detail level (1/2/4/8/16/32/64), colour mode and, if needed, `skip`.
   Detail 1 + **MC** = plain Minecraft blocks, no tiles.
   Reorder with the arrows: top materials are placed first and win overlaps (e.g. road markings above asphalt).
4. Set scale, axes, rotation, mirror, snapping and offset. **Voxelize** and check the wireframe in the world.
5. **Place**. A progress bar shows blocks done and time left, the **Speed** slider sets the pace. **Undo** reverts the last import, **Redo** puts it back;
   `/meshtiles list` shows older ones.

Importing needs creative mode or op. Every client needs the same mod jar as the server.

### Positioning cheat sheet

| Setting | Effect |
|---|---|
| Snap XZ | model's min X/Z corner on the importer block |
| Snap Y | model's lowest point on the importer block (changes when the model's bottom changes) |
| Origin | Blender scene origin (0,0,0) on the importer block, no snapping (on by default) |
| Rotation | whole model (frame included) rotates around the importer block |
| Mirror X/Y/Z | flips the model inside its bounding box, the box stays |
| Offset | added after rotation, in Blender axes (Z = up) |

The **File Y / File Z** button says which axis is up in the `.obj` itself: Blender's default export is File Y; choose File Z only if you exported with Forward Y, Up Z.

## Blender add-on

`blender/meshtiles` is an add-on for Blender 4.2 and newer that assigns the import settings in Blender itself.

1. In Minecraft, open the MeshTiles Importer once. It writes the list of usable blocks to `.minecraft/meshtiles/blocks.json`
   (with names, creative tabs and average texture colours). The add-on finds it in the usual launcher folders,
   or set the path in the add-on preferences.
2. In Blender, install the add-on zip (Edit > Preferences > Get Extensions > Install from Disk).
3. Material Properties > MeshTiles: pick the block (searchable), detail, colour mode, MC and Skip. The material's
   viewport colour takes the block colour. 3D Viewport > Sidebar > MeshTiles lists all materials in placement
   order with up/down arrows.
4. File > Export > MeshTiles (or the sidebar button) writes the `.obj` and `model.meshtiles.json` next to it.
5. In the importer press Scan: blocks, detail, colour mode, flags, order and the file axis are taken from Blender.
   Voxelize re-reads the settings whenever they were exported again. Only settings changed in Blender are applied,
   everything else keeps the importer's own choice.

The add-on is licensed under GPL-3.0-or-later, as Blender requires for add-ons; the mod keeps its own licence.

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
