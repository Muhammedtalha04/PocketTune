package com.pockettune;

import com.pockettune.config.PocketTuneClientConfig;
import com.pockettune.config.PocketTuneCommonConfig;
import com.pockettune.config.PocketTuneServerConfig;
import com.pockettune.client.PocketTuneClientModEvents;
import com.pockettune.network.ModNetworking;
import com.pockettune.registry.ModBlockEntities;
import com.pockettune.registry.ModBlocks;
import com.pockettune.registry.ModItems;
import com.pockettune.registry.ModDataComponents;
import com.pockettune.server.PocketTuneServerLifecycle;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

@Mod(PocketTune.MOD_ID)
public final class PocketTune {
    public static final String MOD_ID = "pockettune";

    public PocketTune(IEventBus modEventBus, ModContainer modContainer) {
        ModBlocks.register(modEventBus);
        ModDataComponents.register(modEventBus);
        ModItems.register(modEventBus);
        ModBlockEntities.register(modEventBus);
        modEventBus.addListener(ModNetworking::registerPayloads);
        modContainer.registerConfig(ModConfig.Type.COMMON, PocketTuneCommonConfig.SPEC);
        modContainer.registerConfig(ModConfig.Type.SERVER, PocketTuneServerConfig.SPEC);
        modContainer.registerConfig(ModConfig.Type.CLIENT, PocketTuneClientConfig.SPEC);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            modEventBus.addListener(PocketTuneClientModEvents::onClientSetup);
            modEventBus.addListener(PocketTuneClientModEvents::onRegisterGuiLayers);
        }
        PocketTuneServerLifecycle.register();
        modEventBus.addListener(this::addCreativeTabContents);
    }

    private void addCreativeTabContents(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(ModItems.SPEAKER.get());
        }
    }
}
