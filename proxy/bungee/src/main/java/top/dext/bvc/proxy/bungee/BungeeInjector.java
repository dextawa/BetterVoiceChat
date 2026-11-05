package top.dext.bvc.proxy.bungee;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.protocol.channel.BungeeChannelInitializer;
import top.dext.bvc.proxy.BvcProxyCore;

/**
 * Puts BVC's stream detector in front of every new client connection.
 * <p>
 * Current BungeeCord has an official hook for this ({@code ProxyServer.unsafe()
 * .setFrontendChannelInitializer}); older builds and forks only have the static
 * {@code PipelineUtils.SERVER_CHILD} field, which is replaced through {@code sun.misc.Unsafe}.
 */
final class BungeeInjector {

    private BungeeInjector() {
    }

    static void inject(ProxyServer proxy, BvcProxyCore core) throws ReflectiveOperationException {
        try {
            injectOfficial(proxy, core);
        } catch (NoSuchMethodError | NoClassDefFoundError e) {
            injectLegacy(core);
        }
    }

    private static void injectOfficial(ProxyServer proxy, BvcProxyCore core) {
        ProxyServer.Unsafe unsafe = proxy.unsafe();
        BungeeChannelInitializer original = unsafe.getFrontendChannelInitializer();
        unsafe.setFrontendChannelInitializer(BungeeChannelInitializer.create(channel -> {
            if (!original.getChannelAcceptor().accept(channel)) {
                return false;
            }
            core.initChannel(channel);
            return true;
        }));
    }

    @SuppressWarnings("unchecked")
    private static void injectLegacy(BvcProxyCore core) throws ReflectiveOperationException {
        Class<?> pipelineUtils = Class.forName("net.md_5.bungee.netty.PipelineUtils");
        Field field = pipelineUtils.getDeclaredField("SERVER_CHILD");
        field.setAccessible(true);
        ChannelInitializer<Channel> original = (ChannelInitializer<Channel>) field.get(null);
        Method initChannel = ChannelInitializer.class.getDeclaredMethod("initChannel", Channel.class);
        initChannel.setAccessible(true);

        ChannelInitializer<Channel> wrapped = new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel channel) throws Exception {
                try {
                    initChannel.invoke(original, channel);
                } catch (InvocationTargetException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof Exception exception) {
                        throw exception;
                    }
                    throw new IllegalStateException(cause);
                }
                core.initChannel(channel);
            }
        };

        Field theUnsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) theUnsafe.get(null);
        unsafe.putObject(unsafe.staticFieldBase(field), unsafe.staticFieldOffset(field), wrapped);
    }
}
