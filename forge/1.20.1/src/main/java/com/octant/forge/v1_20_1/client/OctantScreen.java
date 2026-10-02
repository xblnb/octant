package com.octant.forge.v1_20_1.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class OctantScreen extends Screen {

    private static final String CMD = com.octant.forge.v1_20_1.OctantMod.MOD_ID + " ";

    private final Screen parent;

    public OctantScreen(Screen parent) {
        super(Component.literal("Octant（卦限）· 本地报告"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = this.height / 2 - 56;
        addAction(cx, y, "状态 / 同意情况", CMD + "status");
        addAction(cx, y + 24, "授权本地导出", CMD + "grant");
        addAction(cx, y + 48, "撤销授权", CMD + "revoke");
        addAction(cx, y + 72, "导出报告（本地生成）", CMD + "export");
        this.addRenderableWidget(Button.builder(Component.literal("返回"),
                b -> this.close()).bounds(cx - 105, y + 102, 210, 20).build());
    }

    private void addAction(int cx, int y, String label, String command) {
        this.addRenderableWidget(Button.builder(Component.literal(label), b -> {
            if (this.minecraft != null && this.minecraft.player != null) {
                this.minecraft.player.connection.sendCommand(command);
                this.close();
            }
        }).bounds(cx - 105, y, 210, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);
        g.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 86, 0xF5F5F7);
        g.drawCenteredString(this.font,
                Component.literal("报告在你的电脑上生成，不上传任何内容；可随时用「撤销授权」撤回。"),
                this.width / 2, this.height / 2 - 70, 0xA1A1A6);
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        this.close();
    }

    private void close() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }
}
