package io.ezp.mod.net;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全局 SOCKS 路由表。
 *
 * 仅把「目标为房主虚拟 IP」的连接改写为本地转发端口，其余连接保持直连，
 * 等价于现有 ez-mc 的 `socksNonProxyHosts` 作用：皮肤站、Mojang 鉴权、
 * 资源下载等流量一律不走代理。
 */
public final class SocksManager {

    /** 房主虚拟 IP -> 本地转发端口。 */
    private static final Map<String, Integer> ROUTES = new ConcurrentHashMap<>();

    private SocksManager() {}

    public static void route(String virtIp, int forwardPort) {
        ROUTES.put(virtIp, forwardPort);
    }

    public static void unroute(String virtIp) {
        ROUTES.remove(virtIp);
    }

    /** 判断某个主机是否需要经代理路由。 */
    public static boolean isRouted(String host) {
        return ROUTES.containsKey(host);
    }

    /**
     * 若目标是已托管的路由虚拟 IP，返回改写后的本地转发地址；否则原样返回。
     * 供 {@code ClientConnection.connect} 的 Mixin 使用。
     */
    public static InetSocketAddress maybeRewrite(InetSocketAddress addr) {
        if (addr == null) {
            return null;
        }
        String host = addr.getAddress() != null
                ? addr.getAddress().getHostAddress()
                : addr.getHostString();
        Integer fwd = ROUTES.get(host);
        if (fwd == null) {
            return addr;
        }
        return new InetSocketAddress("127.0.0.1", fwd);
    }
}