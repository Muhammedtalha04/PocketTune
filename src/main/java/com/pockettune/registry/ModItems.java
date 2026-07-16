package com.pockettune.registry;

import com.pockettune.PocketTune;
import com.pockettune.item.SpeakerBlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(PocketTune.MOD_ID);

    public static final DeferredItem<SpeakerBlockItem> SPEAKER = ITEMS.registerItem(
            "speaker",
            properties -> new SpeakerBlockItem(ModBlocks.SPEAKER.get(), properties),
            new Item.Properties().stacksTo(1)
    );

    private ModItems() {
    }

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
    }
}
