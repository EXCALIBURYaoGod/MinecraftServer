package io.ezp.mod;

import com.mojang.brigadier.arguments.StringArgumentType;
import io.ezp.mod.net.DaemonClient;
import io.ezp.mod.net.LocalProxyForwarder;
import io.ezp.mod.net.P2PSession;
import io.ezp.mod.net.SocksManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.util.Map;

/**
 * Ezp P2P Mod 入口。
 *
 * 功能：
 *   - /ezp join <房间码>        一键加入：daemon 启动玩家节点 -> 内置 SOCKS 转发 -> 改写目标
 *   - /ezp leave                断开所有节点并关闭本地转发
 *   - /ezp status               显示当前连接与连接类型（direct/relay）
 *   - HUD 常显当前连接类型（直接连接 / 中继连接）
 * 注入点：ClientConnection.connect 地址改写，见 {@code mixin.ClientConnectionMixin}。
 */
public class EzpClient implements ClientModInitializer {

    private final DaemonClient daemon = new DaemonClient();
    private int tickCounter = 0;

    @Override
    public void onInitializeClient() {
        registerCommands();
        registerStatusPoller();
        registerHud();
    }

    private void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("ezp")
                        .then(ClientCommandManager.literal("join")
                                .then(ClientCommandManager.argument("code", StringArgumentType.greedyString())
                                        .executes(ctx -> join(ctx.getSource(), StringArgumentType.getString(ctx, "code")))))
                        .then(ClientCommandManager.literal("leave")
                                .executes(ctx -> leave(ctx.getSource())))
                        .then(ClientCommandManager.literal("status")
                                .executes(ctx -> status(ctx.getSource())))));
    }

    private void registerStatusPoller() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            tickCounter++;
            if (tickCounter % 40 != 0 || !P2PSession.active()) {
                return;
            }
            new Thread(() -> {
                try {
                    Map<String, String> st = daemon.status();
                    String lt = st.get("linkType");
                    if (lt != null) {
                        P2PSession.linkType(lt);
                    }
                } catch (Exception ignored) {
                    // 守护离线时保持上次状态，不打扰玩家
                }
            }, "ezp-status").start();
        });
    }

    private void registerHud() {
        HudRenderCallback.EVENT.register(this::renderHud);
    }

    private void renderHud(DrawContext context, Object tickCounterUnused) {
        if (!P2PSession.active()) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        String lt = P2PSession.linkType();
        String label;
        int color;
        switch (lt) {
            case "direct":
                label = "P2P 直接连接";
                color = 0xFF55FF55;
                break;
            case "relay":
                label = "中继连接";
                color = 0xFFFFAA00;
                break;
            default:
                label = "连接类型探测中…";
                color = 0xFFAAAAAA;
        }
        String line = "Ezp · " + label + " · " + P2PSession.virtIp() + ":" + P2PSession.destPort();
        context.drawText(client.textRenderer, Text.literal(line), 2, 2, color, true);
    }

    // ---- 命令实现 ---- //

    private int join(FabricClientCommandSource src, String roomCode) {
        new Thread(() -> {
            try {
                Map<String, String> r = daemon.guest(roomCode.trim());
                String ip = r.get("ip");
                if (ip == null || ip.isEmpty()) {
                    throw new IllegalStateException("未被分配虚拟 IP：" + r);
                }
                int proxyPort = Integer.parseInt(r.get("proxyPort"));
                String name = r.get("name") == null ? "" : r.get("name");
                int destPort = 25565;

                LocalProxyForwarder fwd = new LocalProxyForwarder("127.0.0.1", proxyPort, ip, destPort, "127.0.0.1");
                fwd.start();
                SocksManager.route(ip, fwd.port());
                P2PSession.clear();
                P2PSession.set(fwd, ip, name, destPort);

                send(src, "已加入「" + name + "」，进入多人游戏连接地址："
                        + ip + ":" + destPort + "（已自动经内置 SOCKS 转发）");
            } catch (Exception e) {
                send(src, "✗ 加入失败：" + safeMsg(e) + "（确认 'ezmc daemon' 已在运行）");
            }
        }, "ezp-join").start();
        return 1;
    }

    private int leave(FabricClientCommandSource src) {
        new Thread(() -> {
            P2PSession.clear();
            try {
                daemon.stop();
                send(src, "已断开所有节点，本地代理已关闭");
            } catch (Exception e) {
                send(src, "✗ 停止节点失败：" + safeMsg(e));
            }
        }, "ezp-leave").start();
        return 1;
    }

    private int status(FabricClientCommandSource src) {
        new Thread(() -> {
            try {
                Map<String, String> st = daemon.status();
                if (!"true".equals(st.get("active"))) {
                    send(src, "当前无活跃 P2P 连接。");
                    return;
                }
                String role = "host".equals(st.get("role")) ? "房主" : "玩家";
                String lt = st.get("linkType");
                P2PSession.linkType(lt);
                String link = "direct".equals(lt) ? "直接连接(P2P)"
                        : "relay".equals(lt) ? "中继" : "未知";
                String running = "true".equals(st.get("running")) ? "运行中" : "已停止";
                send(src, "角色=" + role + " 名称=" + st.get("name")
                        + " 虚拟IP=" + st.get("host") + " 连接类型=" + link
                        + " 节点=" + running);
            } catch (Exception e) {
                send(src, "✗ 查询失败：" + safeMsg(e) + "（ez-mc 守护未启动？）");
            }
        }, "ezp-status").start();
        return 1;
    }

    private void send(FabricClientCommandSource src, String msg) {
        MinecraftClient.getInstance().execute(() -> src.sendFeedback(Text.literal(msg)));
    }

    private static String safeMsg(Exception e) {
        String m = e.getMessage();
        return (m == null || m.isEmpty()) ? e.getClass().getSimpleName() : m;
    }
}