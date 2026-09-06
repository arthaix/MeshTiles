# MeshTiles for Blender: Minecraft blocks, detail and placement order per material, exported next to the .obj
# for the MeshTiles importer (Minecraft 1.12.2, LittleTiles).
#
# Author: Aleksei Usenko (arthaix)
# SPDX-License-Identifier: GPL-3.0-or-later

bl_info = {
    "name": "MeshTiles",
    "author": "Aleksei Usenko (arthaix)",
    "version": (1, 0, 0),
    "blender": (3, 6, 0),
    "location": "Material Properties > MeshTiles, 3D Viewport > Sidebar > MeshTiles, File > Export",
    "description": "Assign Minecraft blocks to materials and export models for the MeshTiles importer",
    "category": "Import-Export",
}

import glob
import json
import os

import bpy
from bpy.props import BoolProperty, EnumProperty, IntProperty, PointerProperty, StringProperty
from bpy_extras.io_utils import ExportHelper

ADDON_ID = __package__ or __name__
SIDECAR_SUFFIX = ".meshtiles.json"

GRID_ITEMS = [(str(g), str(g), "%d tiles per block edge" % g) for g in (1, 2, 4, 8, 16, 32, 64)]
MODE_ITEMS = [
    ("NONE", "None", "Plain block, no tint"),
    ("KD", "Kd", "Flat colour from the material base colour"),
    ("TEXTURE", "Texture", "Colour sampled from the image texture per tile"),
]
SUPPORTED_TYPES = {"MESH", "CURVE", "SURFACE", "META", "FONT"}


# ---------------------------------------------------------------------------------------------------------------
# Block list (written by the MeshTiles importer in Minecraft: .minecraft/meshtiles/blocks.json)
# ---------------------------------------------------------------------------------------------------------------

_cache = {"path": None, "stamp": None, "items": [], "by_id": {}}


def candidate_block_files():
    appdata = os.environ.get("APPDATA", "")
    home = os.path.expanduser("~")
    patterns = [
        os.path.join(appdata, "PrismLauncher", "instances", "*", "minecraft", "meshtiles", "blocks.json"),
        os.path.join(appdata, "PrismLauncher", "instances", "*", ".minecraft", "meshtiles", "blocks.json"),
        os.path.join(appdata, "MultiMC", "instances", "*", ".minecraft", "meshtiles", "blocks.json"),
        os.path.join(appdata, ".minecraft", "meshtiles", "blocks.json"),
        os.path.join(home, "curseforge", "minecraft", "Instances", "*", "meshtiles", "blocks.json"),
        os.path.join(home, ".minecraft", "meshtiles", "blocks.json"),
        os.path.join(home, "Library", "Application Support", "minecraft", "meshtiles", "blocks.json"),
    ]
    found = set()
    for pattern in patterns:
        found.update(glob.glob(pattern))
    return sorted(found, key=os.path.getmtime, reverse=True)


def addon_prefs():
    addon = bpy.context.preferences.addons.get(ADDON_ID)
    return addon.preferences if addon else None


def block_file():
    prefs = addon_prefs()
    if prefs and prefs.blocks_path:
        path = bpy.path.abspath(prefs.blocks_path)
        if os.path.isfile(path):
            return path
    candidates = candidate_block_files()
    return candidates[0] if candidates else None


def block_list():
    path = block_file()
    if not path:
        _cache.update(path=None, stamp=None, items=[], by_id={})
        return _cache
    stamp = (os.path.getmtime(path), os.path.getsize(path))
    if path != _cache["path"] or stamp != _cache["stamp"]:
        try:
            with open(path, encoding="utf-8") as f:
                data = json.load(f)
            items = [b for b in data.get("blocks", []) if isinstance(b, dict) and b.get("id")]
        except (OSError, ValueError):
            items = []
        _cache.update(path=path, stamp=stamp, items=items, by_id={b["id"]: b for b in items})
    return _cache


def search_blocks(self, context, edit_text):
    text = (edit_text or "").strip().lower()
    result = []
    for b in block_list()["items"]:
        if not text or text in b["id"].lower() or text in str(b.get("name", "")).lower():
            result.append(b["id"])
    return result


def srgb_to_linear(c):
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def block_color(block_id):
    b = block_list()["by_id"].get(block_id)
    value = (b or {}).get("color", "")
    if len(value) != 7 or not value.startswith("#"):
        return None
    try:
        return tuple(int(value[i:i + 2], 16) / 255.0 for i in (1, 3, 5))
    except ValueError:
        return None


def on_block_changed(self, context):
    prefs = addon_prefs()
    if prefs is not None and not prefs.tint_viewport:
        return
    rgb = block_color(self.block.strip())
    if rgb:
        self.id_data.diffuse_color = (srgb_to_linear(rgb[0]), srgb_to_linear(rgb[1]), srgb_to_linear(rgb[2]), 1.0)


# ---------------------------------------------------------------------------------------------------------------
# Properties
# ---------------------------------------------------------------------------------------------------------------

class MeshTilesMaterialProps(bpy.types.PropertyGroup):
    block: StringProperty(
        name="Block",
        description="Minecraft block for this material, from the block list of the MeshTiles importer",
        search=search_blocks,
        update=on_block_changed,
    )
    grid: EnumProperty(name="Detail", description="Tiles per block edge", items=GRID_ITEMS, default="16")
    mode: EnumProperty(name="Colour", description="How tiles of this material are coloured", items=MODE_ITEMS, default="NONE")
    mc: BoolProperty(name="Minecraft blocks", description="With detail 1: place real Minecraft blocks instead of LittleTiles")
    skip: BoolProperty(name="Skip", description="Do not import this material")
    order: IntProperty(name="Order", description="Placement order: lower is placed first and wins where surfaces overlap")


class MeshTilesPreferences(bpy.types.AddonPreferences):
    bl_idname = ADDON_ID

    blocks_path: StringProperty(
        name="Block list",
        subtype="FILE_PATH",
        description="blocks.json written by the MeshTiles importer. Empty: found automatically in the usual launcher folders",
    )
    tint_viewport: BoolProperty(
        name="Tint viewport colour",
        description="Set the material viewport display colour to the average colour of the chosen block",
        default=True,
    )

    def draw(self, context):
        layout = self.layout
        layout.prop(self, "blocks_path")
        layout.prop(self, "tint_viewport")
        draw_block_list_status(layout)


def draw_block_list_status(layout):
    blocks = block_list()
    if blocks["path"]:
        layout.label(text="%d blocks from %s" % (len(blocks["items"]), blocks["path"]), icon="CHECKMARK")
    else:
        layout.label(text="No block list yet: open the MeshTiles importer in Minecraft once", icon="ERROR")


# ---------------------------------------------------------------------------------------------------------------
# Materials of the exported objects, in placement order
# ---------------------------------------------------------------------------------------------------------------

def export_objects(context, selected_only):
    if selected_only:
        return [o for o in context.selected_objects if o.type in SUPPORTED_TYPES]
    return [o for o in context.scene.objects if o.type in SUPPORTED_TYPES and o.visible_get()]


def used_materials(context, selected_only=False):
    materials = []
    for obj in export_objects(context, selected_only):
        for slot in obj.material_slots:
            if slot.material is not None and slot.material not in materials:
                materials.append(slot.material)
    materials.sort(key=lambda m: m.meshtiles.order)  # stable: equal orders keep first-use order
    return materials


def obj_material_name(name):
    # the OBJ exporter writes spaces in material names as underscores
    return name.replace(" ", "_")


def sidecar_path(obj_path):
    return os.path.splitext(obj_path)[0] + SIDECAR_SUFFIX


def write_sidecar(context, obj_path, selected_only):
    entries = []
    for mat in used_materials(context, selected_only):
        p = mat.meshtiles
        entry = {"name": obj_material_name(mat.name), "blender_name": mat.name}
        if p.block.strip():
            entry["block"] = p.block.strip()
        # only what was set explicitly: untouched materials keep the importer's own choice
        if p.is_property_set("grid"):
            entry["grid"] = int(p.grid)
        if p.is_property_set("mode"):
            entry["mode"] = p.mode.lower()
        if p.is_property_set("mc"):
            entry["mc"] = bool(p.mc)
        if p.is_property_set("skip"):
            entry["skip"] = bool(p.skip)
        entries.append(entry)
    data = {
        "format": 1,
        "generator": "MeshTiles for Blender %d.%d.%d" % bl_info["version"],
        "up_axis": "Y",
        "materials": entries,
    }
    path = sidecar_path(obj_path)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)
        f.write("\n")
    return path, len(entries)


# ---------------------------------------------------------------------------------------------------------------
# Operators
# ---------------------------------------------------------------------------------------------------------------

class MESHTILES_OT_export_obj(bpy.types.Operator, ExportHelper):
    bl_idname = "meshtiles.export_obj"
    bl_label = "Export for MeshTiles"
    bl_description = "Export the model as .obj with materials and write the MeshTiles block settings next to it"
    bl_options = {"PRESET"}

    filename_ext = ".obj"
    filter_glob: StringProperty(default="*.obj", options={"HIDDEN"})
    selected_only: BoolProperty(name="Selected Only", description="Export only selected objects", default=False)
    apply_modifiers: BoolProperty(name="Apply Modifiers", default=True)

    def invoke(self, context, event):
        last = context.scene.meshtiles_last_path
        if last and not self.filepath:
            self.filepath = bpy.path.abspath(last)
        return ExportHelper.invoke(self, context, event)

    def execute(self, context):
        path = self.filepath
        result = bpy.ops.wm.obj_export(
            filepath=path,
            export_selected_objects=self.selected_only,
            apply_modifiers=self.apply_modifiers,
            export_materials=True,
            forward_axis="NEGATIVE_Z",
            up_axis="Y",
        )
        if "FINISHED" not in result:
            self.report({"ERROR"}, "OBJ export failed")
            return {"CANCELLED"}
        side, count = write_sidecar(context, path, self.selected_only)
        context.scene.meshtiles_last_path = path
        context.scene.meshtiles_last_selected_only = self.selected_only
        self.report({"INFO"}, "Exported %s with settings for %d materials" % (os.path.basename(path), count))
        return {"FINISHED"}


class MESHTILES_OT_write_settings(bpy.types.Operator):
    bl_idname = "meshtiles.write_settings"
    bl_label = "Update Settings Only"
    bl_description = "Rewrite only the block settings next to the last exported .obj (no geometry export)"

    @classmethod
    def poll(cls, context):
        return bool(context.scene.meshtiles_last_path)

    def execute(self, context):
        path = bpy.path.abspath(context.scene.meshtiles_last_path)
        if not os.path.isfile(path):
            self.report({"ERROR"}, "The last exported file no longer exists: " + path)
            return {"CANCELLED"}
        side, count = write_sidecar(context, path, context.scene.meshtiles_last_selected_only)
        self.report({"INFO"}, "Settings for %d materials written to %s" % (count, os.path.basename(side)))
        return {"FINISHED"}


class MESHTILES_OT_move_material(bpy.types.Operator):
    bl_idname = "meshtiles.move_material"
    bl_label = "Move Material"
    bl_description = "Move the material up or down in the placement order"
    bl_options = {"REGISTER", "UNDO"}

    material: StringProperty()
    direction: IntProperty()

    def execute(self, context):
        materials = used_materials(context)
        names = [m.name for m in materials]
        if self.material not in names:
            return {"CANCELLED"}
        i = names.index(self.material)
        j = i + self.direction
        if 0 <= j < len(materials):
            materials[i], materials[j] = materials[j], materials[i]
        for k, mat in enumerate(materials):
            mat.meshtiles.order = k
        return {"FINISHED"}


class MESHTILES_OT_reload_blocks(bpy.types.Operator):
    bl_idname = "meshtiles.reload_blocks"
    bl_label = "Reload Block List"
    bl_description = "Read the MeshTiles block list again"

    def execute(self, context):
        _cache.update(path=None, stamp=None)
        blocks = block_list()
        if blocks["path"]:
            self.report({"INFO"}, "%d blocks" % len(blocks["items"]))
        else:
            self.report({"WARNING"}, "No block list found: open the MeshTiles importer in Minecraft once")
        return {"FINISHED"}


# ---------------------------------------------------------------------------------------------------------------
# UI
# ---------------------------------------------------------------------------------------------------------------

def draw_material_settings(layout, mat):
    p = mat.meshtiles
    layout.prop(p, "block", icon="VIEWZOOM")
    block_id = p.block.strip()
    if block_id:
        info = block_list()["by_id"].get(block_id)
        if info:
            layout.label(text="%s  (%s)" % (info.get("name", ""), info.get("group", "")))
        elif block_list()["items"]:
            layout.label(text="Not in the block list", icon="ERROR")
    row = layout.row(align=True)
    row.prop(p, "grid")
    row.prop(p, "mode", text="")
    row = layout.row(align=True)
    sub = row.row(align=True)
    sub.enabled = p.grid == "1"
    sub.prop(p, "mc")
    row.prop(p, "skip")


class MESHTILES_PT_material(bpy.types.Panel):
    bl_label = "MeshTiles"
    bl_space_type = "PROPERTIES"
    bl_region_type = "WINDOW"
    bl_context = "material"

    @classmethod
    def poll(cls, context):
        return context.material is not None

    def draw(self, context):
        draw_material_settings(self.layout, context.material)


class MESHTILES_PT_sidebar(bpy.types.Panel):
    bl_label = "MeshTiles"
    bl_space_type = "VIEW_3D"
    bl_region_type = "UI"
    bl_category = "MeshTiles"

    def draw(self, context):
        layout = self.layout
        row = layout.row()
        draw_block_list_status(row)
        row.operator("meshtiles.reload_blocks", text="", icon="FILE_REFRESH")

        materials = used_materials(context)
        box = layout.box()
        box.label(text="Placement order (top is placed first)")
        if not materials:
            box.label(text="No materials on visible objects")
        for i, mat in enumerate(materials):
            p = mat.meshtiles
            row = box.row(align=True)
            row.label(text=mat.name, icon="MATERIAL")
            row.prop(p, "block", text="")
            row.prop(p, "grid", text="")
            row.prop(p, "skip", text="", icon="HIDE_ON" if p.skip else "HIDE_OFF")
            up = row.operator("meshtiles.move_material", text="", icon="TRIA_UP")
            up.material, up.direction = mat.name, -1
            down = row.operator("meshtiles.move_material", text="", icon="TRIA_DOWN")
            down.material, down.direction = mat.name, 1

        active = context.object.active_material if context.object else None
        if active is not None:
            sub = layout.box()
            sub.label(text="Active material: " + active.name)
            draw_material_settings(sub, active)

        col = layout.column(align=True)
        col.operator("meshtiles.export_obj", icon="EXPORT")
        col.operator("meshtiles.write_settings", icon="FILE_TICK")
        if context.scene.meshtiles_last_path:
            col.label(text=os.path.basename(bpy.path.abspath(context.scene.meshtiles_last_path)))


def menu_export(self, context):
    self.layout.operator(MESHTILES_OT_export_obj.bl_idname, text="MeshTiles (.obj + block settings)")


CLASSES = (
    MeshTilesMaterialProps,
    MeshTilesPreferences,
    MESHTILES_OT_export_obj,
    MESHTILES_OT_write_settings,
    MESHTILES_OT_move_material,
    MESHTILES_OT_reload_blocks,
    MESHTILES_PT_material,
    MESHTILES_PT_sidebar,
)


def register():
    for cls in CLASSES:
        bpy.utils.register_class(cls)
    bpy.types.Material.meshtiles = PointerProperty(type=MeshTilesMaterialProps)
    bpy.types.Scene.meshtiles_last_path = StringProperty(name="Last MeshTiles export", subtype="FILE_PATH")
    bpy.types.Scene.meshtiles_last_selected_only = BoolProperty(default=False)
    bpy.types.TOPBAR_MT_file_export.append(menu_export)


def unregister():
    bpy.types.TOPBAR_MT_file_export.remove(menu_export)
    del bpy.types.Scene.meshtiles_last_selected_only
    del bpy.types.Scene.meshtiles_last_path
    del bpy.types.Material.meshtiles
    for cls in reversed(CLASSES):
        bpy.utils.unregister_class(cls)
