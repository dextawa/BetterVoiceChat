package top.dext.bvc.client;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import top.dext.bvc.protocol.BvcProtocol;
import top.dext.bvc.protocol.data.Activation;
import top.dext.bvc.protocol.data.VoiceMode;
import top.dext.bvc.protocol.packet.BackendToProxy;
import top.dext.bvc.protocol.packet.Packet;
import top.dext.bvc.proxy.BvcProxyCore;
import top.dext.bvc.proxy.platform.BvcLogger;
import top.dext.bvc.proxy.platform.CommandSource;
import top.dext.bvc.proxy.platform.PlatformPlayer;
import top.dext.bvc.proxy.platform.ProxyPlatform;

/**
 * The real proxy core plus real client cores in one process. Plugin messages hop through
 * per-player executors (like a network would); the stream transport uses real loopback TCP
 * against a Netty server with BVC's detector installed, exactly like on a proxy.
 */
final class InProcessNetwork implements AutoCloseable {

    static final String SERVER = "lobby";

    final BvcProxyCore core;
    final Platform platform;
    private final EventLoopGroup group = new NioEventLoopGroup(2);
    private final Channel serverChannel;
    final InetSocketAddress address;
    private final List<Client> clients = new ArrayList<>();
    private long backendSequence = 1;

    InProcessNetwork(Path dataDirectory, String proxyConfig) throws Exception {
        // A free port for the web panel, so parallel test runs never collide on 8790.
        Files.createDirectories(dataDirectory);
        Files.writeString(dataDirectory.resolve("config.yml"), (proxyConfig == null ? "" : proxyConfig) + "\nweb:\n  port: 0\n");
        platform = new Platform(dataDirectory);
        core = new BvcProxyCore(platform);
        core.enable();
        serverChannel = new ServerBootstrap()
                .group(group)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel channel) {
                        // Stand-in for the Minecraft handlers a real proxy installs.
                        channel.pipeline().addLast("minecraft", new ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                                ((ByteBuf) msg).release();
                                ctx.close();
                            }
                        });
                        core.initChannel(channel);
                    }
                })
                .bind("127.0.0.1", 0).sync().channel();
        address = (InetSocketAddress) serverChannel.localAddress();
    }

    /** A player with the mod joins the proxy and lands on {@link #SERVER}. */
    Client join(String name) {
        Client client = new Client(name);
        clients.add(client);
        platform.players.put(client.player.uuid, client.player);
        core.onPlayerJoin(client.player);
        client.player.server = SERVER;
        core.onServerConnected(client.player, SERVER);
        client.voice.onJoinServer(address, "localhost");
        return client;
    }

    int idOf(Client client) {
        return core.sessions().get(client.player.uuid).id();
    }

    /** What the sub-server plugin would send. */
    void backend(Packet<BackendToProxy.Handler> packet) {
        core.onBackendMessage(clients.get(0).player, SERVER, BackendToProxy.REGISTRY.encode(packet));
    }

    void backendHello() {
        backend(new BackendToProxy.BackendHello(BvcProtocol.VERSION, "test", "Test", 1L));
    }

    void zone(VoiceMode mode, float radius, Client... members) {
        List<BackendToProxy.ZoneInfo.Entry> zones = new ArrayList<>();
        List<BackendToProxy.Membership.Entry> membership = new ArrayList<>();
        for (Client member : members) {
            zones.add(new BackendToProxy.ZoneInfo.Entry(idOf(member), "", mode, Activation.ANY, radius));
            membership.add(new BackendToProxy.Membership.Entry(idOf(member), 0, mode == VoiceMode.FREE ? 1 : 0));
        }
        backend(new BackendToProxy.Membership(backendSequence++, membership));
        backend(new BackendToProxy.ZoneInfo(backendSequence++, zones));
    }

    void neighbors(Client speaker, Client... listeners) {
        int[] ids = new int[listeners.length];
        for (int i = 0; i < listeners.length; i++) {
            ids[i] = idOf(listeners[i]);
        }
        backend(new BackendToProxy.Neighbors(backendSequence++, List.of(new BackendToProxy.Neighbors.Entry(idOf(speaker), ids))));
    }

    static boolean await(BooleanSupplier condition, long millis) throws InterruptedException {
        long until = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < until) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }

    @Override
    public void close() {
        for (Client client : clients) {
            client.close();
        }
        serverChannel.close().syncUninterruptibly();
        group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        core.disable();
        platform.scheduler.shutdownNow();
    }

    // ------------------------------------------------------------------ one client

    final class Client {
        final Player player;
        final FakeAudio audio = new FakeAudio();
        final VoiceClient voice;
        private final ExecutorService toProxy = Executors.newSingleThreadExecutor();
        private final ExecutorService toClient = Executors.newSingleThreadExecutor();

        Client(String name) {
            player = new Player(name, this);
            voice = new VoiceClient(new ClientPlatform() {
                @Override
                public void sendPluginMessage(byte[] payload) {
                    toProxy.execute(() -> core.onClientMessage(player, payload));
                }

                @Override
                public String minecraftVersion() {
                    return "test";
                }

                @Override
                public String loader() {
                    return "test";
                }

                @Override
                public String modVersion() {
                    return "test";
                }

                @Override
                public int minecraftProtocol() {
                    return 770;
                }

                @Override
                public void showMessage(String text) {
                }

                @Override
                public ClientLogger logger() {
                    return ClientLogger.STDERR;
                }
            }, ClientSettings.inMemory(), audio);
        }

        void deliver(byte[] payload) {
            toClient.execute(() -> voice.onPluginMessage(payload));
        }

        ClientStatus status() {
            return voice.status();
        }

        void close() {
            voice.shutdown();
            toProxy.shutdownNow();
            toClient.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ proxy platform

    static final class Player implements PlatformPlayer {
        final UUID uuid;
        final String name;
        final Client client;
        volatile String server;

        Player(String name, Client client) {
            this.name = name;
            this.uuid = UUID.nameUUIDFromBytes(name.getBytes());
            this.client = client;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public UUID uuid() {
            return uuid;
        }

        @Override
        public boolean hasPermission(String permission) {
            return false;
        }

        @Override
        public void sendMessage(String text) {
        }

        @Override
        public String currentServer() {
            return server;
        }

        @Override
        public void sendPluginMessage(String channel, byte[] data) {
            if (BvcProtocol.CHANNEL_VOICE.equals(channel)) {
                client.deliver(data);
            }
        }

        @Override
        public boolean sendToBackend(String channel, byte[] data) {
            return server != null;
        }

        @Override
        public void disconnect(String text) {
        }

        @Override
        public boolean isOnline() {
            return true;
        }

        @Override
        public SocketAddress remoteAddress() {
            return new InetSocketAddress("127.0.0.1", 1);
        }
    }

    static final class Platform implements ProxyPlatform {
        final Map<UUID, Player> players = new ConcurrentHashMap<>();
        final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        private final Path dataDirectory;

        Platform(Path dataDirectory) {
            this.dataDirectory = dataDirectory;
        }

        @Override
        public String platformName() {
            return "InProcess";
        }

        @Override
        public String pluginVersion() {
            return "test";
        }

        @Override
        public Path dataDirectory() {
            return dataDirectory;
        }

        @Override
        public BvcLogger logger() {
            return new BvcLogger() {
                @Override
                public void info(String message) {
                }

                @Override
                public void warn(String message) {
                    System.err.println("PROXY WARN " + message);
                }

                @Override
                public void warn(String message, Throwable error) {
                    System.err.println("PROXY WARN " + message + ": " + error);
                }

                @Override
                public void error(String message, Throwable error) {
                    System.err.println("PROXY ERROR " + message + ": " + error);
                }

                @Override
                public void debug(String message) {
                }
            };
        }

        @Override
        public Optional<PlatformPlayer> player(UUID uuid) {
            return Optional.ofNullable(players.get(uuid));
        }

        @Override
        public Optional<PlatformPlayer> player(String name) {
            return players.values().stream().filter(p -> p.name.equalsIgnoreCase(name)).map(p -> (PlatformPlayer) p).findFirst();
        }

        @Override
        public Collection<? extends PlatformPlayer> players() {
            return players.values();
        }

        @Override
        public Collection<String> serverNames() {
            return List.of(SERVER);
        }

        @Override
        public CommandSource console() {
            return null;
        }

        @Override
        public Cancellable scheduleRepeating(Runnable task, long delay, long period, TimeUnit unit) {
            var future = scheduler.scheduleAtFixedRate(task, delay, period, unit);
            return () -> future.cancel(false);
        }

        @Override
        public Cancellable scheduleLater(Runnable task, long delay, TimeUnit unit) {
            var future = scheduler.schedule(task, delay, unit);
            return () -> future.cancel(false);
        }
    }
}
