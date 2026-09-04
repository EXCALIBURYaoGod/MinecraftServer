package io.ezp.mod.net;

/**
 * 当前 P2P 会话的运行时状态，供后台线程写入、渲染线程读取。
 */
public final class P2PSession {

    /** 房主虚拟 IP。 */
    private static volatile String virtIp;
    /** 房间名。 */
    private static volatile String roomName;
    /** 房主服务端口（server.properties 的 server-port）。 */
    private static volatile int destPort;
    /** 转发器目标。 */
    private static volatile LocalProxyForwarder forwarder;
    /** 连接类型：direct(直接打洞) / relay(中继) / unknown。 */
    private static volatile String linkType = "unknown";

    private P2PSession() {}

    public static boolean active() {
        return forwarder != null;
    }

    public static void set(LocalProxyForwarder fwd, String ip, String name, int port) {
        forwarder = fwd;
        virtIp = ip;
        roomName = name;
        destPort = port;
        linkType = "unknown";
    }

    public static void clear() {
        LocalProxyForwarder f = forwarder;
        if (f != null) {
            try {
                f.close();
            } catch (Exception ignored) {
                // ignore
            }
        }
        if (virtIp != null) {
            SocksManager.unroute(virtIp);
        }
        forwarder = null;
        virtIp = null;
        roomName = null;
        destPort = 0;
        linkType = "unknown";
    }

    public static String virtIp() {
        return virtIp;
    }

    public static String roomName() {
        return roomName;
    }

    public static int destPort() {
        return destPort;
    }

    public static void linkType(String lt) {
        linkType = lt == null ? "unknown" : lt;
    }

    public static String linkType() {
        return linkType;
    }
}