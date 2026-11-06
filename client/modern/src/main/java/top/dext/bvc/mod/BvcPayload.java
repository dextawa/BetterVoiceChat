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
 * The raw bytes of one BVC plugin message: {@code bvc:voice} on clients, {@code bvc:proxy} /
 * {@code bvc:server} on servers. The client and backend cores do all decoding.
 */
public final class BvcPayload /*? if >=1.20.2 {*/ implements CustomPacketPayload /*?}*/ {

    //? if >=1.21.11 {
    public static final Identifier ID = Identifier.parse(BvcProtocol.CHANNEL_VOICE);
    public static final Identifier PROXY_ID = Identifier.parse(BvcProtocol.CHANNEL_PROXY);
    public static final Identifier SERVER_ID = Identifier.parse(BvcProtocol.CHANNEL_SERVER);
    //?} else if >=1.21 {
    /*public static final ResourceLocation ID = ResourceLocation.parse(BvcProtocol.CHANNEL_VOICE);
    public static final ResourceLocation PROXY_ID = ResourceLocation.parse(BvcProtocol.CHANNEL_PROXY);
    public static final ResourceLocation SERVER_ID = ResourceLocation.parse(BvcProtocol.CHANNEL_SERVER);
    *///?} else {
    /*public static final ResourceLocation ID = new ResourceLocation(BvcProtocol.CHANNEL_VOICE);
    public static final ResourceLocation PROXY_ID = new ResourceLocation(BvcProtocol.CHANNEL_PROXY);
    public static final ResourceLocation SERVER_ID = new ResourceLocation(BvcProtocol.CHANNEL_SERVER);
    *///?}

    private final byte[] data;

    //? if >=1.20.5 {
    public static final Type<BvcPayload> TYPE = new Type<>(ID);
    public static final Type<BvcPayload> PROXY_TYPE = new Type<>(PROXY_ID);
    public static final Type<BvcPayload> SERVER_TYPE = new Type<>(SERVER_ID);

    public static final StreamCodec<FriendlyByteBuf, BvcPayload> CODEC = codec(TYPE);
    public static final StreamCodec<FriendlyByteBuf, BvcPayload> PROXY_CODEC = codec(PROXY_TYPE);
    public static final StreamCodec<FriendlyByteBuf, BvcPayload> SERVER_CODEC = codec(SERVER_TYPE);

    private static StreamCodec<FriendlyByteBuf, BvcPayload> codec(Type<BvcPayload> type) {
        return CustomPacketPayload.codec(BvcPayload::write, buf -> new BvcPayload(type, bytes(buf)));
    }

    private final Type<BvcPayload> type;

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return type;
    }

    public BvcPayload(byte[] data) {
        this(TYPE, data);
    }

    public BvcPayload(Type<BvcPayload> type, byte[] data) {
        this.type = type;
        this.data = data;
    }
    //?} else if >=1.20.2 {
    /*private final ResourceLocation id;

    @Override
    public ResourceLocation id() {
        return id;
    }

    public BvcPayload(byte[] data) {
        this(ID, data);
    }

    public BvcPayload(ResourceLocation id, byte[] data) {
        this.id = id;
        this.data = data;
    }

    public static BvcPayload readProxy(FriendlyByteBuf buf) {
        return new BvcPayload(PROXY_ID, bytes(buf));
    }

    public static BvcPayload readServer(FriendlyByteBuf buf) {
        return new BvcPayload(SERVER_ID, bytes(buf));
    }
    *///?} else {
    /*public BvcPayload(byte[] data) {
        this.data = data;
    }
    *///?}

    public byte[] data() {
        return data;
    }

    public static BvcPayload read(FriendlyByteBuf buf) {
        return new BvcPayload(bytes(buf));
    }

    /** Copies the rest of {@code buf}; loaders release it after the handler returns. */
    public static byte[] bytes(FriendlyByteBuf buf) {
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        return data;
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeBytes(data);
    }
}
