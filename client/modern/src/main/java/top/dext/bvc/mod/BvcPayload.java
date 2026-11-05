package top.dext.bvc.mod;

import net.minecraft.network.FriendlyByteBuf;
//? if >=1.20.5 {
import net.minecraft.network.codec.StreamCodec;
//?}
//? if >=1.20.2 {
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
//?}
//? if >=1.21.11 {
import net.minecraft.resources.Identifier;
//?} else {
/*import net.minecraft.resources.ResourceLocation;
*///?}
import top.dext.bvc.protocol.BvcProtocol;

/**
 * The raw bytes of one {@code bvc:voice} plugin message. The client core does all decoding.
 */
public final class BvcPayload /*? if >=1.20.2 {*/ implements CustomPacketPayload /*?}*/ {

    //? if >=1.21.11 {
    public static final Identifier ID = Identifier.parse(BvcProtocol.CHANNEL_VOICE);
    //?} else if >=1.21 {
    /*public static final ResourceLocation ID = ResourceLocation.parse(BvcProtocol.CHANNEL_VOICE);
    *///?} else {
    /*public static final ResourceLocation ID = new ResourceLocation(BvcProtocol.CHANNEL_VOICE);
    *///?}

    //? if >=1.20.5 {
    public static final Type<BvcPayload> TYPE = new Type<>(ID);

    public static final StreamCodec<FriendlyByteBuf, BvcPayload> CODEC = CustomPacketPayload.codec(BvcPayload::write, BvcPayload::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
    //?} else if >=1.20.2 {
    /*@Override
    public ResourceLocation id() {
        return ID;
    }
    *///?}

    private final byte[] data;

    public BvcPayload(byte[] data) {
        this.data = data;
    }

    public byte[] data() {
        return data;
    }

    public static BvcPayload read(FriendlyByteBuf buf) {
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        return new BvcPayload(data);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeBytes(data);
    }
}
