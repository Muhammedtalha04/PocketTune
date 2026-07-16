package com.pockettune.client;

import com.pockettune.PocketTune;
import com.pockettune.client.gui.overlay.PortableMusicOverlay;
import com.pockettune.client.screen.PocketTuneConfigScreen;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;

public final class PocketTuneClientModEvents {
    private PocketTuneClientModEvents() {
    }

    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> ModList.get().getModContainerById(com.pockettune.PocketTune.MOD_ID).ifPresent(container ->
                container.registerExtensionPoint(
                        IConfigScreenFactory.class,
                        (IConfigScreenFactory) (ignored, parent) -> new PocketTuneConfigScreen(parent)
                )
        ));
    }

    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(
                ResourceLocation.fromNamespaceAndPath(PocketTune.MOD_ID, "portable_music_overlay"),
                PortableMusicOverlay::render
        );
    }
}
