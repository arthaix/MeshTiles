package ru.arthaix.meshtiles.gui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import com.creativemd.littletiles.client.gui.LittleSubGuiUtils;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.NonNullList;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import ru.arthaix.meshtiles.client.ClientPrefs;
import ru.arthaix.meshtiles.common.BlockRef;
import ru.arthaix.meshtiles.voxel.MaterialSetup;

/** Recently used blocks on top, then a grid of every block LittleTiles can use, with a search field. */
@SideOnly(Side.CLIENT)
public class GuiBlockPicker extends CompactScreen {

    private static final int COLS = 18, ROWS = 10, SLOT = 18;
    private static final int RECENT_Y = 36, GRID_Y = RECENT_Y + SLOT + 16;
    private static final int W = COLS * SLOT + 12, H = GRID_Y + ROWS * SLOT + 30;
    private static final int ACCENT = 0xFF00B8CC;
    private static List<ItemStack> all;

    private final GuiScreen parent;
    private final String current;
    private final Consumer<String> onPick;
    private final List<ItemStack> filtered = new ArrayList<>();
    private final List<ItemStack> recent = new ArrayList<>();
    private GuiTextField search;
    private String query = "";
    private int left, top, scrollRows;

    public GuiBlockPicker(GuiScreen parent, String current, Consumer<String> onPick) {
        this.parent = parent;
        this.current = current;
        this.onPick = onPick;
    }

    @Override
    protected int neededWidth() {
        return W + 4;
    }

    @Override
    protected int neededHeight() {
        return H + 4;
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        left = (width - W) / 2;
        top = (height - H) / 2;
        search = new GuiTextField(0, fontRenderer, left + 6, top + 6, W - 12, 14);
        search.setMaxStringLength(64);
        search.setText(query);
        search.setFocused(true);
        buttonList.clear();
        buttonList.add(new GuiButton(1, left + W - 66, top + H - 24, 60, 20, "Cancel"));
        buttonList.add(new GuiButton(2, left + W - 206, top + H - 24, 134, 20, "Air (clears space)"));
        if (all == null) all = collect();
        loadRecent();
        refilter();
    }

    private static List<ItemStack> collect() {
        LittleSubGuiUtils.LittleBlockSelector selector = new LittleSubGuiUtils.LittleBlockSelector();
        List<ItemStack> list = new ArrayList<>();
        for (Item item : Item.REGISTRY) {
            if (!(item instanceof ItemBlock)) continue;
            NonNullList<ItemStack> sub = NonNullList.create();
            try {
                item.getSubItems(CreativeTabs.SEARCH, sub);
            } catch (Throwable ignored) {
                // some mods throw here; their blocks are simply not listed
            }
            for (ItemStack s : sub) {
                try {
                    if (!s.isEmpty() && selector.allow(s)) list.add(s);
                } catch (Throwable ignored) {
                    // a block LittleTiles cannot inspect is left out
                }
            }
        }
        return list;
    }

    /** Recently picked blocks that still exist and are usable, newest first, one row at most. */
    private void loadRecent() {
        recent.clear();
        LittleSubGuiUtils.LittleBlockSelector selector = new LittleSubGuiUtils.LittleBlockSelector();
        for (String id : ClientPrefs.recentBlocks()) {
            try {
                ItemStack s = BlockRef.parse(id).toStack();
                if (s.isEmpty() || !BlockRef.toId(s).equals(id) || !selector.allow(s)) continue;
                recent.add(s);
            } catch (Throwable ignored) {
                // a block from a mod that is gone
            }
            if (recent.size() == COLS) break;
        }
    }

    private void refilter() {
        filtered.clear();
        String q = query.toLowerCase(Locale.ROOT).trim();
        for (ItemStack s : all) {
            if (q.isEmpty()) {
                filtered.add(s);
                continue;
            }
            String name;
            try {
                name = s.getDisplayName().toLowerCase(Locale.ROOT);
            } catch (Throwable t) {
                name = "";
            }
            if (name.contains(q) || BlockRef.toId(s).contains(q)) filtered.add(s);
        }
        scrollRows = Math.max(0, Math.min(scrollRows, maxScroll()));
    }

    private int maxScroll() {
        return Math.max(0, (filtered.size() + COLS - 1) / COLS - ROWS);
    }

    @Override
    protected void drawContent(int mx, int my, float partialTicks) {
        drawDefaultBackground();
        drawRect(left, top, left + W, top + H, 0xF0101010);
        drawRect(left, top, left + W, top + 1, ACCENT);
        search.drawTextBox();
        int gx = left + 6, ry = top + RECENT_Y, gy = top + GRID_Y;
        drawString(fontRenderer, "Recently used", gx, ry - 11, 0x808080);
        drawString(fontRenderer, "All blocks", gx, gy - 11, 0x808080);
        drawRect(gx, ry, gx + COLS * SLOT, ry + SLOT, 0xFF000000);
        drawRect(gx, gy, gx + COLS * SLOT, gy + ROWS * SLOT, 0xFF000000);
        if (recent.isEmpty()) drawString(fontRenderer, "Blocks you pick will show up here", gx + 4, ry + 5, 0x505050);

        ItemStack hovered = null;
        for (int c = 0; c < recent.size(); c++) {
            ItemStack s = recent.get(c);
            if (highlight(s, gx + c * SLOT, ry, mx, my)) hovered = s;
        }
        for (int r = 0; r < ROWS; r++)
            for (int c = 0; c < COLS; c++) {
                int i = (scrollRows + r) * COLS + c;
                if (i >= filtered.size()) break;
                ItemStack s = filtered.get(i);
                if (highlight(s, gx + c * SLOT, gy + r * SLOT, mx, my)) hovered = s;
            }

        RenderHelper.enableGUIStandardItemLighting();
        for (int c = 0; c < recent.size(); c++) renderStack(recent.get(c), gx + c * SLOT + 1, ry + 1);
        for (int r = 0; r < ROWS; r++)
            for (int c = 0; c < COLS; c++) {
                int i = (scrollRows + r) * COLS + c;
                if (i >= filtered.size()) break;
                renderStack(filtered.get(i), gx + c * SLOT + 1, gy + r * SLOT + 1);
            }
        RenderHelper.disableStandardItemLighting();

        drawString(fontRenderer, filtered.size() + " blocks" + (maxScroll() > 0 ? "   (mouse wheel to scroll)" : ""), left + 6, top + H - 18, 0x808080);
        super.drawContent(mx, my, partialTicks);
        if (hovered != null) {
            try {
                renderToolTip(hovered, mx, my);
            } catch (Throwable ignored) {
                // tooltip of a broken item
            }
        }
    }

    /** Hover or current-block background of one slot; returns true when the mouse is over it. */
    private boolean highlight(ItemStack s, int x, int y, int mx, int my) {
        if (mx >= x && mx < x + SLOT && my >= y && my < y + SLOT) {
            drawRect(x, y, x + SLOT, y + SLOT, 0x60FFFFFF);
            return true;
        }
        if (BlockRef.toId(s).equals(current)) drawRect(x, y, x + SLOT, y + SLOT, 0x6000E5FF);
        return false;
    }

    private void renderStack(ItemStack s, int x, int y) {
        try {
            itemRender.renderItemAndEffectIntoGUI(s, x, y);
        } catch (Throwable ignored) {
            // a broken item model must not break the whole picker
        }
    }

    private void pick(ItemStack s) {
        String id = BlockRef.toId(s);
        ClientPrefs.addRecentBlock(id);
        onPick.accept(id);
        mc.displayGuiScreen(parent);
    }

    @Override
    protected void mouseClicked(int mx, int my, int button) throws IOException {
        super.mouseClicked(mx, my, button);
        if (mc.currentScreen != this) return;
        search.mouseClicked(mx, my, button);
        int gx = left + 6, ry = top + RECENT_Y, gy = top + GRID_Y;
        if (mx >= gx && mx < gx + COLS * SLOT && my >= ry && my < ry + SLOT) {
            int c = (mx - gx) / SLOT;
            if (c < recent.size()) pick(recent.get(c));
            return;
        }
        if (mx >= gx && mx < gx + COLS * SLOT && my >= gy && my < gy + ROWS * SLOT) {
            int i = (scrollRows + (my - gy) / SLOT) * COLS + (mx - gx) / SLOT;
            if (i < filtered.size()) pick(filtered.get(i));
        }
    }

    @Override
    protected void keyTyped(char c, int key) throws IOException {
        if (key == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(parent);
            return;
        }
        if (search.textboxKeyTyped(c, key)) {
            query = search.getText();
            scrollRows = 0;
            refilter();
        }
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int d = Mouse.getEventDWheel();
        if (d != 0) scrollRows = Math.max(0, Math.min(maxScroll(), scrollRows - Integer.signum(d)));
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == 1) mc.displayGuiScreen(parent);
        if (button.id == 2) {
            // not a real block: the material removes whatever stands in its volume
            onPick.accept(MaterialSetup.AIR);
            mc.displayGuiScreen(parent);
        }
    }

    @Override
    public void updateScreen() {
        search.updateCursorCounter();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
