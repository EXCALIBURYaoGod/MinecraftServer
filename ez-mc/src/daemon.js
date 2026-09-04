import http from 'node:http';
import { log } from './logger.js';
import * as ops from './ops.js';

/**
 * 本地 HTTP 守护桥。
 * 供 Paper 服务端插件与 Fabric 客户端 Mod 通过 HTTP 管理 P2P 节点，
 * 端点返回 JSON，字段与 ops.opStatus() 一致，便于 Java 侧解析。
 * 仅监听回环地址，避免暴露到外网。
 *
 * 端点：
 *   GET  /health              -> { ok: true }
 *   GET  /status              -> 当前连接状态（含 linkType: direct|relay）
 *   GET  /room/:name          -> { roomCode, ip, peer } 或 404
 *   POST /host                body { name, ip?, peer? }
 *   POST /guest               body { roomCode, proxyPort? }
 *   POST /stop
 */
export async function startDaemon({ port = 29876 } = {}) {
  const server = http.createServer(async (req, res) => {
    // 统一 JSON 响应
    const send = (code, obj) => {
      const body = JSON.stringify(obj);
      res.writeHead(code, {
        'Content-Type': 'application/json; charset=utf-8',
        'Cache-Control': 'no-store',
      });
      res.end(body);
    };
    const sendError = (code, message) => send(code, { error: message });

    // 只接受回环地址来源
    const remote = req.socket.remoteAddress || '';
    if (remote !== '127.0.0.1' && remote !== '::1' && remote !== '::ffff:127.0.0.1') {
      sendError(403, '仅允许本机回环访问');
      return;
    }

    try {
      const url = new URL(req.url, `http://127.0.0.1:${port}`);
      const method = req.method || 'GET';
      const path = url.pathname;

      // 读取 POST body
      const readBody = () =>
        new Promise((resolve) => {
          let data = '';
          req.on('data', (c) => {
            data += c;
            if (data.length > 1e6) req.destroy();
          });
          req.on('end', () => {
            let obj = {};
            if (data) {
              try {
                obj = JSON.parse(data);
              } catch {
                obj = {};
              }
            }
            resolve(obj);
          });
        });

      if (method === 'GET' && path === '/health') {
        send(200, { ok: true });
        return;
      }

      if (method === 'GET' && path === '/status') {
        send(200, await ops.opStatus());
        return;
      }

      if (method === 'GET' && path.startsWith('/room/')) {
        const name = decodeURIComponent(path.slice('/room/'.length));
        const st = await ops.opStatus();
        if (st.active && st.role === 'host' && st.name === name) {
          send(200, { roomCode: st.roomCode, ip: st.host, peer: st.peer, linkType: st.linkType });
          return;
        }
        sendError(404, `未找到房间「${name}」的房主连接`);
        return;
      }

      if (method === 'POST' && path === '/host') {
        const body = await readBody();
        if (!body.name) { sendError(400, '缺少 name'); return; }
        const conn = await ops.opHost({ name: String(body.name), ip: body.ip, peer: body.peer });
        send(200, {
          roomCode: conn.roomCode, ip: conn.host, peer: conn.peer, pid: conn.pid, name: conn.name,
        });
        return;
      }

      if (method === 'POST' && path === '/guest') {
        const body = await readBody();
        if (!body.roomCode) { sendError(400, '缺少 roomCode'); return; }
        const conn = await ops.opGuest({ roomcode: String(body.roomCode), proxyPort: body.proxyPort });
        send(200, { ip: conn.host, proxyPort: conn.proxyPort, pid: conn.pid, name: conn.name });
        return;
      }

      if (method === 'POST' && path === '/stop') {
        const n = await ops.opStop();
        send(200, { stopped: n });
        return;
      }

      sendError(404, `未知端点 ${method} ${path}`);
    } catch (e) {
      sendError(500, (e && e.message) || String(e));
    }
  });

  server.on('error', (e) => {
    if (e.code === 'EADDRINUSE') {
      log.error(`端口 ${port} 已被占用，请用 --port 换一个。`);
      process.exit(1);
    }
    throw e;
  });

  await new Promise((resolve) => server.listen(port, '127.0.0.1', resolve));
  log.ok(`ez-mc 守护已启动：http://127.0.0.1:${port}`);
  log.dim('端点：/health /status /room/:name /host /guest /stop');
  return server;
}