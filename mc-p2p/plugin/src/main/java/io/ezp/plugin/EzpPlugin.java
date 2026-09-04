package io.ezp.plugin;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/**
 * ez-mc P2P 联机插件主类。
 * 职责：
 *  - 服务端启动后自动确保 ez-mc 房主节点在线（/host）
 *  - 拿到虚拟 IP 后，与 server.properties 端口拼成联机地址并广播
 *  - 提供 /ezp 命令查看状态（含连接类型 direct/relay）、重播、取房间码等
 */
public class EzpPlugin extends JavaPlugin {

    private String daemonUrl;
    private String roomName;
    private boolean autoHost;
    private boolean shutdownStop;
    private boolean broadcastOnStart;

    // 最近一次建房结果缓存
    private volatile String lastIp;
    private volatile String lastRoomCode;
    private volatile String lastLinkType;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadSettings();

        getCommand("ezp").setExecutor(new EzpCommand(this));
        getLogger().info("ez-mc P2P 插件已加载。daemon=" + daemonUrl);

        if (autoHost) {
            // 延迟片刻，等服务端网络/属性就绪后再建房
            Bukkit.getScheduler().runTaskLaterAsynchronously(this, this::ensureHost, 40L);
        }
    }

    @Override
    public void onDisable() {
        if (shutdownStop) {
            try {
                HttpUtil.send("POST", daemonUrl + "/stop", "{}");
                getLogger().info("已停止 P2P 节点。");
            } catch (Exception e) {
                getLogger().warning("停止 P2P 节点失败: " + e.getMessage());
            }
        }
    }

    void reloadSettings() {
        reloadConfig();
        FileConfiguration c = getConfig();
        daemonUrl = c.getString("daemon-url", "http://127.0.0.1:29876");
        roomName = c.getString("room-name", "my-room");
        autoHost = c.getBoolean("auto-host", true);
        shutdownStop = c.getBoolean("shutdown-stop", false);
        broadcastOnStart = c.getBoolean("broadcast-on-start", true);
    }

    /** 异步确保房主节点在线，成功后广播。 */
    private void ensureHost() {
        try {
            String json = "{\"name\":\"" + esc(roomName) + "\"}";
            Map<String, String> r = HttpUtil.send("POST", daemonUrl + "/host", json);
            lastIp = HttpUtil.getString(r, "ip", null);
            lastRoomCode = HttpUtil.getString(r, "roomCode", null);
            String peer = HttpUtil.getString(r, "peer", null);
            getLogger().info("ez-mc 房主节点在线，虚拟 IP " + lastIp + (peer == null ? "" : "，peer " + peer));
            // 刷新连接类型（direct/relay），让首次广播即带上有意义的状态
            refreshLinkTypeQuiet();
            if (broadcastOnStart && lastIp != null) {
                broadcastAddress();
            }
        } catch (Exception e) {
            // 预留连接类型查询，稍后可能补充
            getLogger().warning("自动建房失败：" + e.getMessage()
                    + "（是否已运行 `ezmc daemon`？）");
            refreshLinkTypeQuiet();
        }
    }

    /** 读取本机 server.properties 端口（默认 25565）。 */
    int serverPort() {
        try {
            File props = new File("server.properties");
            if (props.isFile()) {
                List<String> lines = Files.readAllLines(props.toPath(), StandardCharsets.UTF_8);
                for (String line : lines) {
                    String t = line.trim();
                    if (t.startsWith("server-port=")) {
                        return Integer.parseInt(t.substring("server-port=".length()).trim());
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return 25565;
    }

    /** 向在线玩家广播联机地址；并在控制台打印完整房间码。 */
    public void broadcastAddress() {
        if (lastIp == null) {
            getLogger().warning("尚无虚拟 IP，无法广播。先运行 /ezp reload 或等待自动建房。");
            return;
        }
        String address = lastIp + ":" + serverPort();
        Bukkit.broadcastMessage("§a[Ezp] 联机地址：§f" + address
                + "  §7(连接类型：§b" + linkLabel() + "§7)");
        getLogger().info("联机地址 " + address);
        if (lastRoomCode != null) {
            getLogger().info("房间码：" + lastRoomCode);
        }
        // 写入状态文件，供外部（如启动脚本、收集服务）读取
        writeStateFile(address);
    }

    private void writeStateFile(String address) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("address: ").append(address).append('\n');
            sb.append("ip: ").append(lastIp).append('\n');
            if (lastRoomCode != null) sb.append("roomCode: ").append(lastRoomCode).append('\n');
            sb.append("linkType: ").append(lastLinkType == null ? "unknown" : lastLinkType).append('\n');
            File f = new File(getDataFolder(), "state.yml");
            if (!getDataFolder().isDirectory()) getDataFolder().mkdirs();
            Files.write(f.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    /** 查询守护状态，刷新连接类型。 */
    public void refreshLinkTypeQuiet() {
        try {
            Map<String, String> r = HttpUtil.send("GET", daemonUrl + "/status", null);
            String lt = HttpUtil.getString(r, "linkType", null);
            if (lt != null) lastLinkType = lt.isEmpty() ? null : lt;
        } catch (Exception ignored) {
        }
    }

    String linkLabel() {
        String lt = lastLinkType;
        if (lt == null) return "unknown";
        return switch (lt) {
            case "direct" -> "直连 (P2P)";
            case "relay" -> "中继 (relay)";
            default -> lt;
        };
    }

    String daemonUrl() { return daemonUrl; }
    String roomName() { return roomName; }
    String lastIp() { return lastIp; }
    String lastRoomCode() { return lastRoomCode; }
    String lastLinkType() { return lastLinkType; }

    static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}