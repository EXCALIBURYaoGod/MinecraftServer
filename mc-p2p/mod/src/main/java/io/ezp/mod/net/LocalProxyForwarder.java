package io.ezp.mod.net;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 本机 TCP 转发器。
 *
 * Minecraft 原本要直连「房主虚拟 IP:端口」，但 guest 机没有到虚拟网络的 TUN 路由，
 * 只能走本机 SOCKS5 代理。因此本转发器在 127.0.0.1 上监听一个本地端口，接收
 * Minecraft 发来的普通 TCP 流量，再用内置 {@link Socks5Client} 经 easytier
 * SOCKS5 代理 CONNECT 到房主虚拟 IP，双向搬运字节。这是「mod 内置 SOCKS 转发」。
 */
public final class LocalProxyForwarder implements AutoCloseable {

    private final String socksHost;
    private final int socksPort;
    private final String targetHost;
    private final int targetPort;
    private final ServerSocket server;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private volatile boolean running = true;

    public LocalProxyForwarder(String socksHost, int socksPort,
                               String targetHost, int targetPort, String listenHost) throws IOException {
        this.socksHost = socksHost;
        this.socksPort = socksPort;
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.server = new ServerSocket(0, 50, InetAddress.getByName(listenHost));
    }

    /** 本地转发端口，Minecraft 改写后的连接目标。 */
    public int port() {
        return server.getLocalPort();
    }

    /** 转发目标（房主虚拟 IP:端口）。 */
    public String target() {
        return targetHost + ":" + targetPort;
    }

    public void start() {
        Thread acceptor = new Thread(() -> {
            while (running) {
                try {
                    Socket client = server.accept();
                    pool.execute(() -> handle(client));
                } catch (IOException e) {
                    if (running) {
                        break;
                    }
                }
            }
        }, "ezp-local-forwarder");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    private void handle(Socket client) {
        try (Socket c = client;
             Socket upstream = Socks5Client.connect(socksHost, socksPort, targetHost, targetPort, 5000)) {
            Thread a = copy(c, upstream);
            copy(upstream, c);
            a.join();
        } catch (IOException | InterruptedException ignored) {
            // 单次连接失败不影响其他连接
        }
    }

    private static Thread copy(Socket from, Socket to) {
        Thread t = new Thread(() -> {
            try (InputStream in = from.getInputStream();
                 OutputStream out = to.getOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    out.flush();
                }
            } catch (IOException ignored) {
                // socket 关闭即可
            }
        }, "ezp-copy");
        t.setDaemon(true);
        t.start();
        return t;
    }

    @Override
    public void close() {
        running = false;
        try {
            server.close();
        } catch (IOException ignored) {
            // ignore
        }
        pool.shutdownNow();
    }
}