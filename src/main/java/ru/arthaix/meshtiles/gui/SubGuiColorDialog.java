package ru.arthaix.meshtiles.gui;

import org.lwjgl.util.Color;

import com.creativemd.creativecore.common.gui.container.SubGui;
import com.creativemd.creativecore.common.gui.controls.gui.GuiButton;
import com.creativemd.creativecore.common.gui.controls.gui.GuiColorPicker;
import com.creativemd.creativecore.common.gui.controls.gui.GuiLabel;
import com.creativemd.creativecore.common.utils.mc.ColorUtils;

import net.minecraft.nbt.NBTTagCompound;

/** Small client-only layer with a colour picker; the result is delivered through onLayerClosed(nbt "color"). */
public class SubGuiColorDialog extends SubGui {

    public final String materialName;
    private final int initial;
    private GuiColorPicker picker;

    public SubGuiColorDialog(String materialName, int initialColor) {
        super(210, 130);
        this.materialName = materialName;
        this.initial = initialColor;
    }

    @Override
    public void createControls() {
        controls.add(new GuiLabel("Colour for " + materialName, 5, 5));
        picker = new GuiColorPicker("picker", 5, 20, ColorUtils.IntToRGBA(initial), true, 0);
        controls.add(picker);
        controls.add(new GuiButton("ok", "OK", 5, 110, 50, 14) {
            @Override
            public void onClicked(int x, int y, int button) {
                NBTTagCompound nbt = new NBTTagCompound();
                nbt.setString("material", materialName);
                nbt.setInteger("color", ColorUtils.RGBAToInt(picker.color));
                closeLayer(nbt);
            }
        });
        controls.add(new GuiButton("cancel", "Cancel", 60, 110, 50, 14) {
            @Override
            public void onClicked(int x, int y, int button) {
                closeLayer(new NBTTagCompound());
            }
        });
    }

    public static Color toColor(int argb) {
        return ColorUtils.IntToRGBA(argb);
    }
}
