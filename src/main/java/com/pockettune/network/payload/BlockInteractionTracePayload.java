package com.pockettune.network.payload;

import com.pockettune.PocketTune;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

public record BlockInteractionTracePayload(
        UUID requestId,
        BlockPos pos,
        Stage stage,
        boolean successful,
        String detail
) implements CustomPacketPayload {
    public static final int MAX_DETAIL_LENGTH = 240;
    public static final Type<BlockInteractionTracePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(PocketTune.MOD_ID, "block_interaction_trace"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BlockInteractionTracePayload> STREAM_CODEC =
            StreamCodec.of(BlockInteractionTracePayload::encode, BlockInteractionTracePayload::decode);

    public BlockInteractionTracePayload {
        detail = detail == null ? "" : detail.strip();
        if (detail.length() > MAX_DETAIL_LENGTH) {
            detail = detail.substring(0, MAX_DETAIL_LENGTH);
        }
    }

    private static void encode(RegistryFriendlyByteBuf buffer, BlockInteractionTracePayload payload) {
        buffer.writeUUID(payload.requestId());
        BlockPos.STREAM_CODEC.encode(buffer, payload.pos());
        buffer.writeByte(payload.stage().ordinal());
        buffer.writeBoolean(payload.successful());
        buffer.writeUtf(payload.detail(), MAX_DETAIL_LENGTH);
    }

    private static BlockInteractionTracePayload decode(RegistryFriendlyByteBuf buffer) {
        UUID requestId = buffer.readUUID();
        BlockPos pos = BlockPos.STREAM_CODEC.decode(buffer);
        int stageIndex = buffer.readUnsignedByte();
        Stage[] stages = Stage.values();
        if (stageIndex >= stages.length) {
            throw new DecoderException("Unknown PocketTune block interaction stage: " + stageIndex);
        }
        return new BlockInteractionTracePayload(
                requestId,
                pos,
                stages[stageIndex],
                buffer.readBoolean(),
                buffer.readUtf(MAX_DETAIL_LENGTH)
        );
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public enum Stage {
        CLIENT_RIGHT_CLICK("C-EVENT", "RightClickBlock"),
        CLIENT_PACKET_SENT("C-PACKET", "Pickup sent"),
        SERVER_PACKET_RECEIVED("S-PACKET", "Pickup received"),
        SERVER_RIGHT_CLICK_EVENT("S-EVENT", "RightClickBlock consumed"),
        SERVER_BLOCK_ENTITY_FOUND("S-BE", "BlockEntity validated"),
        SERVER_INTERACTION_VALIDATED("S-EVENT", "Access validated"),
        SERVER_PREPARE_SENT("S-PACKET", "Prepare sent"),
        SERVER_DESTROY_CALLBACK("S-CALLBACK", "destroyBlock result"),
        SERVER_BLOCK_STATE_AFTER("S-STATE", "State after removal"),
        SERVER_BLOCK_ENTITY_REMOVED("S-BE", "BlockEntity removal result"),
        SERVER_ITEM_GRANTED("S-ITEM", "Item added to inventory"),
        SERVER_ITEM_DROPPED("S-ITEM", "Item dropped into world"),
        SERVER_RESULT_SENT("S-PACKET", "Result sent"),
        CLIENT_PREPARE_RECEIVED("C-PACKET", "Prepare received"),
        CLIENT_CONTROLLER_DETACHED("C-CALLBACK", "Controller detached to item"),
        CLIENT_RESULT_RECEIVED("C-PACKET", "Result received"),
        CLIENT_BLOCK_ENTITY_LOADED("C-BE", "BlockEntity created"),
        CLIENT_BLOCK_ENTITY_REMOVED("C-BE", "BlockEntity removed"),
        CLIENT_ATTACH_BLOCKED("C-GATE", "Early reattachment blocked"),
        CLIENT_CONTROLLER_ATTACHED("C-CALLBACK", "Controller attached to block"),
        CLIENT_ROLLBACK("C-CALLBACK", "Pickup rolled back"),
        CLIENT_SYNC_MATCH("C-SYNC", "State synchronized"),
        CLIENT_SYNC_TIMEOUT("C-SYNC", "State mismatch");

        private final String sideLabel;
        private final String displayName;

        Stage(String sideLabel, String displayName) {
            this.sideLabel = sideLabel;
            this.displayName = displayName;
        }

        public String sideLabel() {
            return sideLabel;
        }

        public String displayName() {
            return displayName;
        }
    }
}
