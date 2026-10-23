package dev.realbots.nms;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.StubMethod;
import net.bytebuddy.matcher.ElementMatchers;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.UUID;

/**
 * Creates REAL server-side players (a ServerPlayer that goes through PlayerList#placeNewPlayer),
 * so the bot has a tab list entry, a body in the world, an inventory, and shows up in
 * Bukkit.getOnlinePlayers().
 *
 * Everything is reflection over Mojang-mapped names. Paper ships Mojang-mapped server classes
 * from 1.20.5 on, so one jar works on every Paper from 1.20.5 to the newest release. All lookups
 * are done once in the constructor; if a future version renames something, the constructor
 * throws a clear exception and the plugin disables the engine instead of crashing the server.
 */
public final class NmsBridge {

    private final ClassLoader loader = Bukkit.getServer().getClass().getClassLoader();

    private final Class<?> connectionCls;
    private final Class<?> serverPlayerCls;
    private final Class<?> componentCls;
    private final Class<?> fakeConnectionCls;
    private final Class<?> embeddedChannelCls;

    private final Constructor<?> playerCtor;
    private final Constructor<?> fakeConnectionCtor;
    private final Constructor<?> cookieCtor;
    private final Method createDefaultClientInfo;
    private final Method placeNewPlayer;
    private final Method getServerMethod;
    private final Method getPlayerListMethod;
    private final Method literalMethod;
    private final Method doTickMethod;
    private final Method setPosMethod;
    private final Field connectionField;      // ServerPlayer#connection
    private final Object serverboundFlow;

    // optional helpers - the bot still works if one of them is missing
    private Method resetPositionMethod;
    private Method chunkSourceMethod;
    private Method chunkMoveMethod;

    public NmsBridge() throws Exception {
        connectionCls = cls("net.minecraft.network.Connection");
        serverPlayerCls = cls("net.minecraft.server.level.ServerPlayer");
        componentCls = cls("net.minecraft.network.chat.Component");
        Class<?> gameProfileCls = cls("com.mojang.authlib.GameProfile");
        Class<?> clientInfoCls = cls("net.minecraft.server.level.ClientInformation");
        Class<?> cookieCls = cls("net.minecraft.server.network.CommonListenerCookie");
        Class<?> packetFlowCls = cls("net.minecraft.network.protocol.PacketFlow");
        Class<?> playerListCls = cls("net.minecraft.server.players.PlayerList");
        Class<?> entityCls = cls("net.minecraft.world.entity.Entity");
        embeddedChannelCls = cls("io.netty.channel.embedded.EmbeddedChannel");

        serverboundFlow = packetFlowCls.getField("SERVERBOUND").get(null);
        createDefaultClientInfo = clientInfoCls.getMethod("createDefault");

        Constructor<?> found = null;
        for (Constructor<?> c : serverPlayerCls.getConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length == 4 && p[2] == gameProfileCls && p[3] == clientInfoCls) {
                found = c;
                break;
            }
        }
        if (found == null) throw new NoSuchMethodException("ServerPlayer constructor not found");
        playerCtor = found;

        Constructor<?>[] cookieCtors = cookieCls.getConstructors();
        if (cookieCtors.length == 0) throw new NoSuchMethodException("CommonListenerCookie constructor not found");
        cookieCtor = cookieCtors[0];

        placeNewPlayer = playerListCls.getMethod("placeNewPlayer", connectionCls, serverPlayerCls, cookieCls);
        getServerMethod = Bukkit.getServer().getClass().getMethod("getServer");
        getPlayerListMethod = getServerMethod.getReturnType().getMethod("getPlayerList");
        literalMethod = componentCls.getMethod("literal", String.class);
        doTickMethod = serverPlayerCls.getMethod("doTick");
        setPosMethod = entityCls.getMethod("setPos", double.class, double.class, double.class);
        connectionField = serverPlayerCls.getField("connection");

        // A Connection that never touches the network: every send / pipeline call is a no-op.
        fakeConnectionCls = new ByteBuddy()
                .subclass(connectionCls)
                .name(connectionCls.getPackageName() + ".RealBotsConnection")
                .method(ElementMatchers.namedOneOf(
                        "send", "setReadOnly", "handleDisconnection", "flushChannel",
                        "setListenerForServerboundHandshake",
                        "setupInboundProtocol", "setupOutboundProtocol"))
                .intercept(StubMethod.INSTANCE)
                .make()
                .load(connectionCls.getClassLoader(),
                        ClassLoadingStrategy.UsingLookup.of(
                                MethodHandles.privateLookupIn(connectionCls, MethodHandles.lookup())))
                .getLoaded();
        fakeConnectionCtor = fakeConnectionCls.getConstructor(packetFlowCls);

        try {
            Class<?> listenerCls = cls("net.minecraft.server.network.ServerGamePacketListenerImpl");
            resetPositionMethod = listenerCls.getMethod("resetPosition");
        } catch (ReflectiveOperationException ignored) { }
        try {
            Class<?> levelCls = cls("net.minecraft.server.level.ServerLevel");
            chunkSourceMethod = levelCls.getMethod("getChunkSource");
            chunkMoveMethod = chunkSourceMethod.getReturnType().getMethod("move", serverPlayerCls);
        } catch (ReflectiveOperationException ignored) { }
    }

    private Class<?> cls(String name) throws ClassNotFoundException {
        return Class.forName(name, true, loader);
    }

    /** Must be called on the main thread. */
    public Player spawn(String name, UUID uuid, ProfileProperty skin, Location where) throws Exception {
        World world = where.getWorld();
        Object server = getServerMethod.invoke(Bukkit.getServer());
        Object playerList = getPlayerListMethod.invoke(server);
        Object level = world.getClass().getMethod("getHandle").invoke(world);

        PlayerProfile profile = Bukkit.createProfile(uuid, name);
        if (skin != null) profile.setProperty(skin);
        Object gameProfile;
        try {
            gameProfile = profile.getClass().getMethod("buildGameProfile").invoke(profile);
        } catch (NoSuchMethodException e) {
            gameProfile = profile.getClass().getMethod("getGameProfile").invoke(profile);
        }

        Object clientInfo = createDefaultClientInfo.invoke(null);
        Object serverPlayer = playerCtor.newInstance(server, level, gameProfile, clientInfo);

        Object connection = fakeConnectionCtor.newInstance(serverboundFlow);
        setField(connectionCls, connection, "channel", embeddedChannelCls.getConstructor().newInstance());
        setField(connectionCls, connection, "address",
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 25565));

        Class<?>[] types = cookieCtor.getParameterTypes();
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            Class<?> t = types[i];
            if (t.isInstance(gameProfile)) args[i] = gameProfile;
            else if (t.isInstance(clientInfo)) args[i] = clientInfo;
            else if (t == int.class) args[i] = 0;
            else if (t == boolean.class) args[i] = false;
            else if (t == long.class) args[i] = 0L;
            else args[i] = null;
        }
        Object cookie = cookieCtor.newInstance(args);

        placeNewPlayer.invoke(playerList, connection, serverPlayer, cookie);

        Player bukkit = (Player) serverPlayer.getClass().getMethod("getBukkitEntity").invoke(serverPlayer);
        bukkit.teleport(where);
        return bukkit;
    }

    /** Runs the same per-tick logic a connected client would trigger. Main thread only. */
    public void tick(Player p, boolean syncChunks) throws Exception {
        Object handle = handleOf(p);
        doTickMethod.invoke(handle);
        if (syncChunks) {
            if (resetPositionMethod != null) {
                resetPositionMethod.invoke(connectionField.get(handle));
            }
            if (chunkSourceMethod != null && chunkMoveMethod != null) {
                Object level = handle.getClass().getMethod("level").invoke(handle);
                chunkMoveMethod.invoke(chunkSourceMethod.invoke(level), handle);
            }
        }
    }

    public void setPos(Player p, double x, double y, double z) throws Exception {
        setPosMethod.invoke(handleOf(p), x, y, z);
    }

    /** Proper disconnect so the quit event, data save and tab list removal all happen. */
    public void disconnect(Player p, String reason) throws Exception {
        Object handle = handleOf(p);
        Object listener = connectionField.get(handle);
        Object text = literalMethod.invoke(null, reason);
        for (Method m : listener.getClass().getMethods()) {
            if (m.getName().equals("onDisconnect") && m.getParameterCount() == 1) {
                Class<?> t = m.getParameterTypes()[0];
                if (t.isAssignableFrom(componentCls)) {
                    m.invoke(listener, text);
                } else {
                    m.invoke(listener, t.getConstructor(componentCls).newInstance(text));
                }
                return;
            }
        }
        // fallback
        Object playerList = getPlayerListMethod.invoke(getServerMethod.invoke(Bukkit.getServer()));
        playerList.getClass().getMethod("remove", serverPlayerCls).invoke(playerList, handle);
    }

    public Object handleOf(Player p) throws Exception {
        return p.getClass().getMethod("getHandle").invoke(p);
    }

    private static void setField(Class<?> owner, Object target, String field, Object value) throws Exception {
        Field f;
        try {
            f = owner.getField(field);
        } catch (NoSuchFieldException e) {
            f = owner.getDeclaredField(field);
            f.setAccessible(true);
        }
        f.set(target, value);
    }
}
