package com.octant.forge.v1_20_1.client;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = com.octant.forge.v1_20_1.OctantMod.MOD_ID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class OctantPauseButton {

    private OctantPauseButton() {
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof PauseScreen pause)) {
            return;
        }
        int width = 200;
        int height = 20;
        int x = pause.width / 2 - width / 2;
        int y = pause.height - height - 8;
        event.addListener(Button.builder(
                        Component.literal("Octant 报告"),
                        b -> net.minecraft.client.Minecraft.getInstance()
                                .setScreen(new OctantScreen(pause)))
                .bounds(x, y, width, height)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                        Component.literal("本地生成游玩报告；不上传任何内容")))
                .build());
    }
}
