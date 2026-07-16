package com.pockettune.registry;

import com.pockettune.PocketTune;
import com.pockettune.model.PortableSpeakerState;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModDataComponents {
    private static final DeferredRegister.DataComponents DATA_COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, PocketTune.MOD_ID);

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<PortableSpeakerState>>
            PORTABLE_SPEAKER_STATE = DATA_COMPONENTS.registerComponentType(
                    "portable_speaker_state",
                    builder -> builder
                            .persistent(PortableSpeakerState.CODEC)
                            .networkSynchronized(PortableSpeakerState.STREAM_CODEC)
                            .cacheEncoding()
            );

    private ModDataComponents() {
    }

    public static void register(IEventBus modEventBus) {
        DATA_COMPONENTS.register(modEventBus);
    }
}
