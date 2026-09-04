package io.ezp.mod.net;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 极简 SOCKS5 客户端（零第三方依赖，纯 Java）。
 *
 * 作用：连接本机 ez-mc / easytier 启动的 SOCKS5 代理，并发送 CONNECT 请求，
 * 把流量转发到房主的虚拟 IP（如 10.a.b.1）。这是「mod 内置 SOCKS 代理」的核心，
 * 逻辑独立于 Minecraft，可单独单元测试。
 *
 * 流程：
 *   1. 握手：05 01 00（无认证）
 *   2. CONNECT：05 01 00 [ATYP] [DST.ADDR] [DST.PORT]
 *   3. 校验应答 REP == 0x00 后返回已建立的 Socket
 */
public final class Socks5Client {

    private Socks5Client() {}

    /**
     * 经 socksHost:socksPort 代理 CONNECT 到 targetHost:targetPort。
     *
     * @param timeoutMs 握手超时（毫秒）
     * @return 已建立的上游连接；任一步失败会先关闭再抛出
     */
    public static Socket connect(String socksHost, int socksPort,
                                 String targetHost, int targetPort, int timeoutMs) throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(socksHost, socksPort), timeoutMs);
        s.setSoTimeout(timeoutMs);
        try {
            // 1) 握手（仅支持无认证）: VER=5 NMETHODS=1 METHODS=[0]
            s.getOutputStream().write(new byte[]{0x05, 0x01, 0x00});
            s.getOutputStream().flush();
            byte[] greet = readExact(s, 2);
            if (greet.length != 2 || greet[0] != 0x05 || greet[1] != 0x00) {
                throw new IOException("SOCKS5 握手失败");
            }

            // 2) CONNECT: VER=5 CMD=1 RSV=0 ATYP=1(IPv4) DST.ADDR(4) DST.PORT(2)
            InetAddress addr = InetAddress.getByName(targetHost);
            byte[] ip = addr.getAddress();
            if (ip.length != 4) {
                throw new IOException("仅支持 IPv4 虚拟地址: " + targetHost);
            }
            byte[] req = new byte[10];
            req[0] = 0x05;      // VER
            req[1] = 0x01;      // CMD = CONNECT
            req[2] = 0x00;      // RSV
            req[3] = 0x01;      // ATYP = IPv4
            System.arraycopy(ip, 0, req, 4, 4);
            req[8] = (byte) (targetPort >> 8 & 0xFF);
            req[9] = (byte) (targetPort & 0xFF);
            s.getOutputStream().write(req);
            s.getOutputStream().flush();

            // 3) 应答: VER REP RSV ATYP BND.ADDR... BND.PORT
            byte[] rep = readExact(s, 4);
            if (rep.length != 4 || rep[0] != 0x05) {
                throw new IOException("SOCKS5 应答非法");
            }
            if (rep[1] != 0x00) {
                throw new IOException("SOCKS5 CONNECT 失败，状态码=" + (rep[1] & 0xFF));
            }
            int atyp = rep[3];
            int bnd = atyp == 0x01 ? 4 : (atyp == 0x03 ? 1 : 16);
            byte[] rest = readExact(s, bnd + 2);
            if (rest.length != bnd + 2) {
                throw new IOException("SOCKS5 应答不完整");
            }
        } catch (IOException e) {
            try {
                s.close();
            } catch (IOException ignored) {
                // ignore
            }
            throw e;
        }
        return s;
    }

    /** 读取恰好 n 字节，读到 EOF 提前结束。 */
    private static byte[] readExact(Socket s, int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = s.getInputStream().read(buf, off, n - off);
            if (r < 0) {
                break;
            }
            off += r;
        }
        byte[] out = new byte[off];
        System.arraycopy(buf, 0, out, 0, off);
        return out;
    }
}