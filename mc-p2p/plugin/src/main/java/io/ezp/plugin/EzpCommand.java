package io.ezp.plugin;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.List;
import java.util.Map;

/**
 * /ezp 命令实现：status / broadcast / room / stop / reload。
 */
public class EzpCommand implements CommandExecutor, TabCompleter {

    private final EzpPlugin plugin;

    public EzpCommand(EzpPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase();
        switch (sub) {
            case "status" -> status(sender);
            case "broadcast" -> plugin.broadcastAddress();
            case "room" -> {
                if (plugin.lastRoomCode() != null) {
                    sender.sendMessage("§a房间码：§f" + plugin.lastRoomCode());
                } else {
                    sender.sendMessage("§c尚无房主连接，请确保 auto-host 或运行 /ezp reload。");
                }
            }
            case "stop" -> {
                try {
                    HttpUtil.send("POST", plugin.daemonUrl() + "/stop", "{}");
                    sender.sendMessage("§a已停止 P2P 节点。");
                } catch (Exception e) {
                    sender.sendMessage("§c停止失败：" + e.getMessage());
                }
            }
            case "reload" -> {
                plugin.reloadSettings();
                plugin.refreshLinkTypeQuiet();
                sender.sendMessage("§a配置已重载。");
                org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    try {
                        Map<String, String> r = HttpUtil.send(
                                "POST", plugin.daemonUrl() + "/host",
                                "{\"name\":\"" + EzpPlugin.esc(plugin.roomName()) + "\"}");
                        if (r.containsKey("ip")) sender.sendMessage("§a虚拟 IP：" + r.get("ip"));
                    } catch (Exception e) {
                        sender.sendMessage("§c建房失败：" + e.getMessage());
                    }
                });
            }
            default -> sender.sendMessage("§c用法：/ezp <status|broadcast|room|stop|reload>");
        }
        return true;
    }

    private void status(CommandSender sender) {
        sender.sendMessage("§7—— ez-mc P2P 状态 ——");
        try {
            Map<String, String> r = HttpUtil.send("GET", plugin.daemonUrl() + "/status", null);
            boolean active = "true".equals(r.get("active"));
            if (!active) {
                sender.sendMessage("§c守护在线，但当前没有活跃节点。");
                return;
            }
            sender.sendMessage("§e类型：§f" + r.get("role") + "  §e房间：§f" + r.get("name"));
            sender.sendMessage("§e虚拟 IP：§f" + r.get("host"));
            if (r.containsKey("proxyPort")) sender.sendMessage("§e代理端口：§f" + r.get("proxyPort"));
            String lt = HttpUtil.getString(r, "linkType", "unknown");
            sender.sendMessage("§e连接类型：§f" + ("direct".equals(lt) ? "直连 (P2P)"
                    : "relay".equals(lt) ? "中继 (relay)" : "未知"));
        } catch (Exception e) {
            sender.sendMessage("§c无法连接守护：" + e.getMessage()
                    + "（先运行 `ezmc daemon`）");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("status", "broadcast", "room", "stop", "reload").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        }
        return null;
    }
}