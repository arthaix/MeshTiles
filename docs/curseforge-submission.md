# CurseForge submission checklist

Create the project at https://authors.curseforge.com/ → Start a Project → Minecraft → Mods.

| Field | Value |
|---|---|
| Project name | MeshTiles |
| Summary (≤ 200 chars) | Import 3D models (.obj) into Minecraft as LittleTiles: fast voxelizer, a block and detail level per material, server-side placement with undo. |
| Category | Utility & QoL (primary), Server Utility, Cosmetic |
| License | Custom License → paste `LICENSE` |
| Source | https://github.com/arthaix/MeshTiles |
| Issues | https://github.com/arthaix/MeshTiles/issues |
| Icon | `docs/icon.png` (400×400) |
| Description | contents of `docs/curseforge-description.md` (the editor accepts Markdown) |

## File upload

| Field | Value |
|---|---|
| File | `build/libs/meshtiles-1.0.0.jar` (the non-`-dev` jar) |
| Display name | MeshTiles 1.0.0 |
| Release type | Release |
| Game versions | Minecraft 1.12.2, Forge, Java 8 |
| Changelog | First release. |
| Dependencies | **Required**: CreativeCore (project `creativecore`), LittleTiles (project `littletiles`) |

Environment tags: Client and Server (both).

## Before submitting

- Take 3–5 in-game screenshots for the gallery (the importer GUI, a wireframe preview, a placed road/bridge,
  a close-up of mixed detail levels). Screenshots only, no edited or generated images.
- Moderation usually takes a few hours to a couple of days; the file is not downloadable until approved.
- After approval, add the CurseForge link to `README.md` and `mcmod.info` (`url`).
