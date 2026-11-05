package top.dext.bvc.proxy.velocity;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import com.velocitypowered.api.proxy.ProxyServer;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import top.dext.bvc.proxy.BvcProxyCore;

/**
 * Wraps Velocity's client channel initializer so every new connection gets BVC's stream
 * detector in front. Velocity exposes the initializer through a public holder on its
 * internal {@code ConnectionManager}; only reaching that object needs reflection.
 */
final class VelocityInjector {

    private VelocityInjector() {
    }

    @SuppressWarnings("unchecked")
    static void inject(ProxyServer proxy, BvcProxyCore core) throws ReflectiveOperationException {
        Field managerField = proxy.getClass().getDeclaredField("cm");
        managerField.setAccessible(true);
        Object connectionManager = managerField.get(proxy);
        Object holder = connectionManager.getClass().getMethod("getServerChannelInitializer").invoke(connectionManager);
        Method get = holder.getClass().getMethod("get");
        Method set = holder.getClass().getMethod("set", ChannelInitializer.class);
        ChannelInitializer<Channel> original = (ChannelInitializer<Channel>) get.invoke(holder);

        Method initChannel = ChannelInitializer.class.getDeclaredMethod("initChannel", Channel.class);
        initChannel.setAccessible(true);

        set.invoke(holder, new ChannelInitializer<Channel>() {
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
        });
    }
}
