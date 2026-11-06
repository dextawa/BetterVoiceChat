package top.dext.bvc.mod.legacy;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import top.dext.bvc.client.SettingsModel;
import top.dext.bvc.client.VoiceClient;

/**
 * Local voice settings for 1.8.9 and 1.12.2 (same GUI API in both). Each row: an inactive label
 * button, a {@code <} button that steps back and a value button that steps forward.
 */
public final class LegacySettingsScreen extends GuiScreen {

    private static final int ROW_HEIGHT = 24;
    private static final int LABEL_WIDTH = 150;
    private static final int VALUE_WIDTH = 150;
    private static final int DONE = 1;
    private static final int PREVIOUS = 2;
    private static final int NEXT = 3;
    private static final int FIRST_ROW = 100;

    private final SettingsModel model;
    private final List<SettingsModel.Row> rows;
    private GuiButton hint;
    private final List<SettingsModel.Row> shown = new ArrayList<SettingsModel.Row>();
    private final List<GuiButton> values = new ArrayList<GuiButton>();
    private int page;
    private int ticks;

    public LegacySettingsScreen(VoiceClient client) {
        this.model = new SettingsModel(client);
        this.rows = model.rows();
    }

    @Override
    @SuppressWarnings("unchecked")
    public void initGui() {
        buttonList.clear();
        shown.clear();
        values.clear();
        // Same layout as the modern screen: title, rows (< value >), hint line, pager.
        int top = 8;
        int bottom = height - 26;
        int rowsTop = top + 26;
        int rowsBottom = bottom - 26;
        int perPage = Math.max(1, (rowsBottom - rowsTop) / ROW_HEIGHT);
        int pages = (rows.size() + perPage - 1) / perPage;
        page = Math.max(0, Math.min(page, pages - 1));
        int total = LABEL_WIDTH + 4 + 20 + 2 + VALUE_WIDTH + 2 + 20;
        int left = width / 2 - total / 2;
        GuiButton title = new GuiButton(0, left, top, total, 20, I18n.format(SettingsModel.TITLE)
                + (pages > 1 ? "  " + (page + 1) + "/" + pages : ""));
        title.enabled = false;
        buttonList.add(title);
        int y = rowsTop;
        for (int i = page * perPage; i < Math.min(rows.size(), (page + 1) * perPage); i++) {
            SettingsModel.Row row = rows.get(i);
            int index = shown.size();
            GuiButton label = new GuiButton(0, left, y, LABEL_WIDTH, 20, labelOf(row));
            label.enabled = false;
            buttonList.add(label);
            int x = left + LABEL_WIDTH + 4;
            buttonList.add(new GuiButton(FIRST_ROW + index * 2, x, y, 20, 20, "<"));
            GuiButton value = new GuiButton(FIRST_ROW + index * 2 + 1, x + 22, y, VALUE_WIDTH, 20, valueOf(row));
            buttonList.add(value);
            buttonList.add(new GuiButton(FIRST_ROW + index * 2 + 1, x + 22 + VALUE_WIDTH + 2, y, 20, 20, ">"));
            values.add(value);
            shown.add(row);
            y += ROW_HEIGHT;
        }
        hint = new GuiButton(0, left, rowsBottom + 2, total, 20, "");
        hint.enabled = false;
        hint.visible = false;
        buttonList.add(hint);
        int doneWidth = pages > 1 ? total - 2 * 54 : total;
        if (pages > 1) {
            GuiButton previous = new GuiButton(PREVIOUS, left, bottom, 50, 20, "<");
            previous.enabled = page > 0;
            buttonList.add(previous);
            GuiButton next = new GuiButton(NEXT, left + total - 50, bottom, 50, 20, ">");
            next.enabled = page < pages - 1;
            buttonList.add(next);
        }
        buttonList.add(new GuiButton(DONE, left + (total - doneWidth) / 2, bottom, doneWidth, 20, I18n.format("gui.done")));
        refresh();
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == DONE) {
            mc.displayGuiScreen(null);
        } else if (button.id == PREVIOUS || button.id == NEXT) {
            page += button.id == NEXT ? 1 : -1;
            initGui();
        } else if (button.id >= FIRST_ROW) {
            int index = (button.id - FIRST_ROW) / 2;
            boolean back = (button.id - FIRST_ROW) % 2 == 0;
            if (index < shown.size()) {
                shown.get(index).press(back);
                refresh();
            }
        }
    }

    private void refresh() {
        for (int i = 0; i < values.size(); i++) {
            values.get(i).displayString = valueOf(shown.get(i));
        }
        if (hint != null) {
            String key = model.testStatusKey();
            hint.visible = key != null;
            hint.displayString = key == null ? "" : I18n.format(key);
        }
    }

    @Override
    public void onGuiClosed() {
        model.close();
    }

    @Override
    public void updateScreen() {
        if (++ticks % 2 == 0) {
            refresh();
        }
        // Dev runs (-Dbvc.devSettings): press every value button once and log the rows.
        if (ticks == 20 && Boolean.getBoolean("bvc.devSettings")) {
            StringBuilder out = new StringBuilder("BVC settings screen:");
            for (SettingsModel.Row row : shown) {
                String before = valueOf(row);
                row.press(false);
                String after = valueOf(row);
                row.press(true);
                out.append(" | ").append(labelOf(row)).append(" = ").append(before).append(" -> ").append(after);
            }
            refresh();
            org.apache.logging.log4j.LogManager.getLogger("BetterVoiceChat").info(out.toString());
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private static String labelOf(SettingsModel.Row row) {
        return row.literalLabel() != null ? row.literalLabel() : I18n.format(row.labelKey);
    }

    private static String valueOf(SettingsModel.Row row) {
        return row.valueKey() != null ? I18n.format(row.valueKey()) : row.value();
    }
}
