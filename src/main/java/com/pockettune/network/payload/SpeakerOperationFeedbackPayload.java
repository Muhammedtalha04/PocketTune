package com.pockettune.network.payload;

import com.pockettune.PocketTune;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record SpeakerOperationFeedbackPayload(BlockPos pos, State state, String message)
        implements CustomPacketPayload {
    public static final int MAX_MESSAGE_LENGTH = 256;
    public static final Type<SpeakerOperationFeedbackPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(PocketTune.MOD_ID, "speaker_operation_feedback"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SpeakerOperationFeedbackPayload> STREAM_CODEC =
            StreamCodec.of(SpeakerOperationFeedbackPayload::encode, SpeakerOperationFeedbackPayload::decode);

    public SpeakerOperationFeedbackPayload {
        message = message == null ? "" : message;
        if (message.length() > MAX_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("PocketTune feedback message is too long");
        }
    }

    private static void encode(RegistryFriendlyByteBuf buffer, SpeakerOperationFeedbackPayload payload) {
        BlockPos.STREAM_CODEC.encode(buffer, payload.pos());
        buffer.writeByte(payload.state().ordinal());
        buffer.writeUtf(payload.message(), MAX_MESSAGE_LENGTH);
    }

    private static SpeakerOperationFeedbackPayload decode(RegistryFriendlyByteBuf buffer) {
        BlockPos pos = BlockPos.STREAM_CODEC.decode(buffer);
        int stateIndex = buffer.readUnsignedByte();
        State[] states = State.values();
        if (stateIndex >= states.length) {
            throw new DecoderException("Invalid PocketTune feedback state: " + stateIndex);
        }
        return new SpeakerOperationFeedbackPayload(
                pos,
                states[stateIndex],
                buffer.readUtf(MAX_MESSAGE_LENGTH)
        );
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public enum State {
        PENDING,
        SUCCESS,
        ERROR
    }
}
