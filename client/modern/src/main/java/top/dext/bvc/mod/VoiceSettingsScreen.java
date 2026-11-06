package top.dext.bvc.mod;

import java.util.ArrayList;
import java.util.List;

//? if <1.20 {
/*import com.mojang.blaze3d.vertex.PoseStack;
*///?} else if <1.20.2 {
/*import net.minecraft.client.gui.GuiGraphics;
*///?}
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import top.dext.bvc.client.SettingsModel;
import top.dext.bvc.client.VoiceClient;

/**
 * Local voice settings (activation, threshold, volumes, devices, HUD, per-player volume).
 * Every row is a label plus two buttons: {@code <} steps back, the value button steps forward.
 * Labels are inactive buttons, so no text drawing differs between Minecraft versions.
 */
public final class VoiceSettingsScreen extends Screen {

    private static final int ROW_HEIGHT = 24;
    private static final int LABEL_WIDTH = 150;
    private static final int VALUE_WIDTH = 150;

    private final Screen parent;
    private final SettingsModel model;
    private final List<SettingsModel.Row> rows;
    private final List<Button> valueButtons = new ArrayList<>();
    private final List<SettingsModel.Row> shownRows = new ArrayList<>();
    private Button hint;
    private int page;
    private int ticks;

    public VoiceSettingsScreen(Screen parent, VoiceClient client) {
        super(VoiceHud.tr(SettingsModel.TITLE));
        this.parent = parent;
        this.model = new SettingsModel(client);
        this.rows = model.rows();
    }

    @Override
    protected void init() {
        valueButtons.clear();
        shownRows.clear();
        int perPage = Math.max(1, (height - 70) / ROW_HEIGHT);
        int pages = (rows.size() + perPage - 1) / perPage;
        page = Math.max(0, Math.min(page, pages - 1));
        int left = width / 2 - (LABEL_WIDTH + 24 + VALUE_WIDTH) / 2;
        int y = 30;
        for (int i = page * perPage; i < Math.min(rows.size(), (page + 1) * perPage); i++) {
            final SettingsModel.Row row = rows.get(i);
            Button label = button(left, y, LABEL_WIDTH, labelOf(row), b -> { });
            label.active = false;
            add(label);
            add(button(left + LABEL_WIDTH + 2, y, 20, VoiceHud.literal("<"), b -> {
                row.press(true);
                refresh();
            }));
            Button value = button(left + LABEL_WIDTH + 24, y, VALUE_WIDTH, valueOf(row), b -> {
                row.press(false);
                refresh();
            });
            add(value);
            valueButtons.add(value);
            shownRows.add(row);
            y += ROW_HEIGHT;
        }
        int bottom = height - 28;
        hint = button(width / 2 - 160, bottom - 24, 320, VoiceHud.literal(""), b -> { });
        hint.active = false;
        hint.visible = false;
        add(hint);
        if (pages > 1) {
            Button previous = button(width / 2 - 154, bottom, 50, VoiceHud.literal("<<"), b -> turn(-1));
            previous.active = page > 0;
            add(previous);
            Button next = button(width / 2 + 104, bottom, 50, VoiceHud.literal(">>"), b -> turn(1));
            next.active = page < pages - 1;
            add(next);
        }
        add(button(width / 2 - 100, bottom, 200, VoiceHud.tr("gui.done"), b -> onClose()));
    }

    private void turn(int delta) {
        page += delta;
        //? if >=1.19 {
        rebuildWidgets();
        //?} else {
        /*init(minecraft, width, height);
        *///?}
    }

    private void refresh() {
        for (int i = 0; i < valueButtons.size(); i++) {
            valueButtons.get(i).setMessage(valueOf(shownRows.get(i)));
        }
        if (hint != null) {
            String key = model.testStatusKey();
            hint.visible = key != null;
            if (key != null) {
                hint.setMessage(VoiceHud.tr(key));
            }
        }
    }

    @Override
    public void tick() {
        // The threshold and test rows show the live microphone level.
        if (++ticks % 2 == 0) {
            refresh();
        }
        // Dev runs (-Dbvc.devSettings): press every value button once and log the rows, so a
        // test can check the screen without looking at it.
        if (ticks == 20 && Boolean.getBoolean("bvc.devSettings")) {
            StringBuilder out = new StringBuilder("BVC settings screen:");
            for (int i = 0; i < shownRows.size(); i++) {
                SettingsModel.Row row = shownRows.get(i);
                String before = valueOf(row).getString();
                row.press(false);
                String after = valueOf(row).getString();
                row.press(true);
                out.append(" | ").append(labelOf(row).getString()).append(" = ").append(before).append(" -> ").append(after);
            }
            refresh();
            BvcModClient.log(out.toString());
            // Run the microphone test for a while: voice must stay connected the whole time.
            BvcModClient.get().voice().startMicrophoneTest(true);
        }
        if (ticks == 120 && Boolean.getBoolean("bvc.devSettings")) {
            top.dext.bvc.client.audio.MicrophoneTest test = BvcModClient.get().voice().microphoneTest();
            top.dext.bvc.client.ClientStatus status = BvcModClient.get().voice().status();
            BvcModClient.log("BVC mic test: state=" + test.state() + " level=" + Math.round(test.levelDb())
                    + " voiceConnected=" + status.isConnected() + " micAvailable=" + status.inputAvailable);
            BvcModClient.get().voice().stopMicrophoneTest();
        }
    }

    @Override
    public void removed() {
        model.close();
    }

    @Override
    public void onClose() {
        model.close();
        //? if >=26.2 {
        minecraft.setScreenAndShow(parent);
        //?} else {
        /*minecraft.setScreen(parent);
        *///?}
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    //? if <1.20 {
    /*@Override
    public void render(PoseStack graphics, int mouseX, int mouseY, float delta) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, delta);
    }
    *///?} else if <1.20.2 {
    /*@Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, delta);
    }
    *///?}

    private static Component labelOf(SettingsModel.Row row) {
        return row.literalLabel() != null ? VoiceHud.literal(row.literalLabel()) : VoiceHud.tr(row.labelKey);
    }

    private static Component valueOf(SettingsModel.Row row) {
        return row.valueKey() != null ? VoiceHud.tr(row.valueKey()) : VoiceHud.literal(row.value());
    }

    private void add(Button button) {
        //? if >=1.17 {
        addRenderableWidget(button);
        //?} else {
        /*addButton(button);
        *///?}
    }

    private static Button button(int x, int y, int w, Component text, Button.OnPress onPress) {
        //? if >=1.19.4 {
        return Button.builder(text, onPress).bounds(x, y, w, 20).build();
        //?} else {
        /*return new Button(x, y, w, 20, text, onPress);
        *///?}
    }
}
