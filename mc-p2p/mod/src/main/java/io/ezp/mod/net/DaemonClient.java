package io.ezp.mod.net;

import io.ezp.mod.util.HttpUtil;

import java.util.Map;

/**
 * 访问本机 ez-mc 守护进程（HTTP 桥）的客户端封装。
 * 供一键加入（/guest）、断开（/stop）、状态与连接类型查询（/status）使用。
 */
public final class DaemonClient {

    public static final String DEFAULT_URL = "http://127.0.0.1:29876";

    private final String base;

    public DaemonClient() {
        this(DEFAULT_URL);
    }

    public DaemonClient(String base) {
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    /** 检查守护是否在线。 */
    public boolean health() throws Exception {
        Map<String, String> r = HttpUtil.send("GET", base + "/health", null);
        return Boolean.parseBoolean(r.get("ok"));
    }

    /**
     * 一键加入：让 daemon 解析房间码并启动玩家 easytier 节点（本机 SOCKS5 代理）。
     * 返回 { ip, proxyPort, pid, name }。
     */
    public Map<String, String> guest(String roomCode) throws Exception {
        // proxyPort 传 0：让 daemon 自动分配随机空闲端口
        String body = "{\"roomCode\":\"" + jsonEscape(roomCode) + "\",\"proxyPort\":0}";
        return HttpUtil.send("POST", base + "/guest", body);
    }

    /** 停止所有活跃节点。 */
    public Map<String, String> stop() throws Exception {
        return HttpUtil.send("POST", base + "/stop", "{}");
    }

    /** 当前连接状态：{ active, role, name, host, linkType, ... }。 */
    public Map<String, String> status() throws Exception {
        return HttpUtil.send("GET", base + "/status", null);
    }

    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}