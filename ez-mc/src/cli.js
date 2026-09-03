import { Command } from 'commander';
import net from 'node:net';
import {
  loadConfig,
  saveConfig,
  loadState,
  saveState,
  randomSecret,
  randomSubnet,
  DEFAULT_PEER,
} from './config.js';
import { isInstalled, spawnCore, queryStatus, queryPeers, buildHostArgs, buildGuestArgs } from './easytier.js';
import { encodeRoomCode, decodeRoomCode, connectionId } from './roomcode.js';
import { downloadEasyTier } from './download.js';
import { detectLanPort, proxyJvmArgs, addServerToServersDat, minecraftDir } from './minecraft.js';
import { log } from './logger.js';

/** 尝试在 preferred 端口监听，若被占用则改用随机空闲端口。 */
function freePort(preferred) {
  return new Promise((resolve) => {
    const server = net.createServer();
    server.once('error', () => server.listen(0));
    server.listen(preferred === undefined ? 0 : preferred, () => {
      const { port } = server.address();
      server.close(() => resolve(port));
    });
  });
}

async function ensureCore() {
  if (await isInstalled()) return;
  log.warn('未找到 easytier-core，正在自动下载 EasyTier …');
  await downloadEasyTier((m) => log.info(m));
}

function resolvePeer(config, explicit) {
  return explicit || config.peer || DEFAULT_PEER;
}

/** 判断 pid 是否还存活。 */
function isAlive(pid) {
  if (!pid) return false;
  try {
    process.kill(pid, 0);
    return true;
  } catch (e) {
    return e.code === 'EPERM';
  }
}

async function currentConnection(state) {
  const conns = state.connections;
  return Object.values(conns).find((c) => isAlive(c.pid)) || null;
}

function killPid(pid) {
  if (!pid) return false;
  try {
    if (process.platform === 'win32') {
      import('node:child_process').then(({ execSync }) => execSync(`taskkill /pid ${pid} /T /F`));
    } else {
      process.kill(pid, 'SIGTERM');
    }
    return true;
  } catch {
    return false;
  }
}

/** 终止所有已记录且仍存活的节点，并清空 connections。 */
async function resetConnections(state) {
  for (const c of Object.values(state.connections)) {
    if (isAlive(c.pid)) killPid(c.pid);
  }
  state.connections = {};
  await saveState(state);
}

export function buildCli() {
  const program = new Command();
  program.name('ezmc').description('基于 EasyTier 的 Minecraft P2P 联机工具（免提权 · 免中心服务器）')
    .version('1.0.0');

  // ---------- install ----------
  program.command('install')
    .description('下载并安装 easytier-core / easytier-cli 到 ~/.ezmc/bin')
    .option('-f, --force', '强制重新下载')
    .action(async (opts) => {
      if (!opts.force && (await isInstalled())) {
        log.ok('EasyTier 已安装。用 --force 可重新下载。');
        return;
      }
      await downloadEasyTier((m) => log.info(m));
      log.ok('安装完成');
    });

  // ---------- doctor ----------
  program.command('doctor')
    .description('检查运行环境与网络链接状态')
    .action(async () => {
      const okCore = await isInstalled();
      log[(okCore ? 'ok' : 'error')](okCore ? 'easytier-core 已安装' : 'easytier-core 未安装（运行 ezmc install）');
      const state = await loadState();
      const conn = await currentConnection(state);
      if (conn) {
        log.info(`当前连接：${conn.role === 'host' ? '房主' : '玩家'} @ ${conn.name}`);
        log.info(`虚拟 IP：${conn.host}${conn.proxyPort ? `，SOCKS5 代理端口：${conn.proxyPort}` : ''}`);
        if (isAlive(conn.pid)) {
          const st = await queryStatus(conn.rpcPortal);
          log.ok(`进程存活（PID ${conn.pid}）`);
          if (st) log.dim(st.split('\n').filter(Boolean).slice(0, 3).join('\n'));
        } else {
          log.error('进程未运行');
        }
      } else {
        log.info('当前没有活跃连接');
      }
    });

  // ---------- create ----------
  program.command('create')
    .description('创建房间（房主）：生成虚拟网络并启动节点')
    .argument('<name>', '房间名称，也是 EasyTier 网络名称')
    .option('-i, --ip <ip>', '指定房主虚拟 IP（默认自动生成私有网段）')
    .option('-p, --peer <uri>', '公共共享节点，可空格/逗号分隔多个')
    .option('--force', '已有活跃连接时强制替换')
    .action(async (name, opts) => {
      await ensureCore();
      const config = await loadConfig();
      const state = await loadState();
      const live = await currentConnection(state);
      if (live && !opts.force) {
        log.error(`已有活跃连接（${live.role === 'host' ? '房主' : '玩家'}·${live.name}）。用 --force 替换或先 ezmc stop`);
        return;
      }
      await resetConnections(state); // 释放旧的节点/端口，避免 Address in use

      const secret = randomSecret();
      const subnet = opts.ip ? { hostIp: opts.ip } : randomSubnet();
      const peer = resolvePeer(config, opts.peer);
      const rpcPortal = `127.0.0.1:${await freePort(15888)}`;

      const id = connectionId(name, secret, subnet.hostIp);
      const conn = {
        role: 'host',
        name, secret, host: subnet.hostIp, peer,
        rpcPortal, proxyPort: null, pid: null,
        roomCode: null,
      };
      conn.roomCode = encodeRoomCode({ name, secret, host: subnet.hostIp, peer });

      const child = await spawnCore(
        buildHostArgs({ name: conn.name, secret: conn.secret, hostIp: conn.host, peer: conn.peer, rpcPortal: conn.rpcPortal }),
        id,
      );
      conn.pid = child.pid;

      state.connections[id] = conn;
      await saveState(state);
      log.ok(`房间「${name}」已创建，虚拟 IP ${subnet.hostIp}`);
      log.info('把下面的房间码分享给好友，让他们加入：');
      log.code(conn.roomCode);
      log.dim('在游戏内点击「对局域网开放」后，运行 ezmc share 获取联机地址。');
    });

  // ---------- join ----------
  program.command('join')
    .description('加入房间（玩家）：解析房间码并启动 SOCKS5 代理节点')
    .argument('<roomcode>', '房主分享的房间码')
    .option('--proxy-port <port>', '本地 SOCKS5 代理端口', '1080')
    .option('--server <ip:port>', '同时把房主地址写入 servers.dat')
    .option('--force', '已有活跃连接时强制替换')
    .action(async (roomcode, opts) => {
      await ensureCore();
      const state = await loadState();
      const live = await currentConnection(state);
      if (live && !opts.force) {
        log.error(`已有活跃连接（${live.role === 'host' ? '房主' : '玩家'}·${live.name}）。用 --force 替换或先 ezmc stop`);
        return;
      }
      await resetConnections(state); // 释放旧的节点/端口，避免 Address in use
      const room = decodeRoomCode(roomcode);
      const proxyPort = await freePort(parseInt(opts.proxyPort, 10));
      const rpcPortal = `127.0.0.1:${await freePort(0)}`; // 随机空闲端口，避免与本地其他节点冲突
      const peer = room.peer || DEFAULT_PEER;

      const id = connectionId(room.name, room.secret, room.host);
      const conn = {
        role: 'guest',
        name: room.name, secret: room.secret,
        host: room.host, peer, rpcPortal, proxyPort, pid: null,
        roomCode: roomcode,
      };
      const child = await spawnCore(buildGuestArgs(conn), id);
      conn.pid = child.pid;
      state.connections[id] = conn;
      await saveState(state);

      log.ok(`已加入房间「${room.name}」，房主虚拟 IP ${room.host}`);
      log.info(`本地 SOCKS5 代理：127.0.0.1:${proxyPort}`);
      log.dim('玩家连接方式：');
      log.dim(' 1) 告诉房主你的加入状态');
      log.dim(` 2) 房主运行 ezmc share 后会得到地址（形如 ${room.host}:端口）`);
      log.dim(' 3) 在多人游戏里添加服务器，或运行：');
      log.dim(`    ezmc servers ${room.host}:端口 --code "${roomcode}"`);
      log.dim(' 4) 用下面的 JVM 参数启动 Minecraft：');
      proxyJvmArgs(proxyPort).forEach((a) => log.dim(`    ${a}`));

      if (opts.server) {
        await writeServer(opts.server, room.name);
      }
    });

  // ---------- share (host) ----------
  program.command('share')
    .description('房主：捕获游戏端口并打印联机地址')
    .option('--port <int>', '直接指定游戏端口，跳过日志监听')
    .option('-f, --file <path>', 'Minecraft 日志文件路径', undefined)
    .option('-t, --timeout <ms>', '等待端口超时', '120000')
    .option('-w, --write-server', '同时写入本机 servers.dat')
    .action(async (opts) => {
      const state = await loadState();
      const conn = await currentConnection(state);
      if (!conn || conn.role !== 'host') {
        log.error('当前没有房主连接，请先 ezmc create <name>');
        return;
      }
      let port = opts.port ? parseInt(opts.port, 10) : null;
      if (!port) {
        const logFile = opts.file || `${minecraftDir()}/logs/latest.log`;
        log.info(`监听 ${logFile} 等待游戏开放局域网…（按 Ctrl+C 取消）`);
        port = await detectLanPort(logFile, { timeoutMs: parseInt(opts.timeout, 10) });
        if (!port) {
          log.error(`超时未从日志检测到端口。请确认你已在游戏内点击「对局域网开放」，或用 --port 手动指定。`);
          return;
        }
      }
      const address = `${conn.host}:${port}`;
      log.ok(`联机地址：${address}`);
      log.dim('让好友运行 ezmc join <房间码> 后使用该地址连接。');
      if (opts.writeServer) {
        await writeServer(address, conn.name);
      }
    });

  // ---------- addr ----------
  program.command('addr')
    .description('打印当前连接的虚拟 IP')
    .action(async () => {
      const state = await loadState();
      const conn = await currentConnection(state);
      if (!conn) { log.error('没有活跃连接'); return; }
      log.code(conn.host);
      if (conn.proxyPort) log.dim(`SOCKS5：127.0.0.1:${conn.proxyPort}`);
    });

  // ---------- servers ----------
  program.command('servers')
    .description('玩家：把房主地址写入 servers.dat，进入多人即可看到')
    .argument('<address>', '房主的联机地址，形如 10.x.x.x:端口')
    .option('--name <name>', '显示在服务器列表里的名称', '好友房间')
    .option('--mc <dir>', '自定义 .minecraft 目录')
    .action(async (address, opts) => {
      await writeServer(address, opts.name, opts.mc);
    });

  // ---------- jvm ----------
  program.command('jvm')
    .description('玩家：打印启动 Minecraft 所需的 SOCKS5 JVM 参数')
    .action(async () => {
      const state = await loadState();
      const conn = await currentConnection(state);
      if (!conn || !conn.proxyPort) { log.error('没有活跃的玩家连接（先 ezmc join）'); return; }
      proxyJvmArgs(conn.proxyPort).forEach((a) => log.code(a));
    });

  // ---------- status ----------
  program.command('status')
    .description('显示活跃连接与节点/对等状态')
    .action(async () => {
      const state = await loadState();
      const conn = await currentConnection(state);
      if (!conn) { log.dim('当前没有活跃连接。运行 ezmc create <name> 建房，或 ezmc join <code> 加入。'); return; }
      log.title(`[${conn.role === 'host' ? '房主' : '玩家'}] ${conn.name}  (虚拟IP ${conn.host})`);
      if (conn.proxyPort) log.dim(`SOCKS5 代理：127.0.0.1:${conn.proxyPort}`);
      if (!isAlive(conn.pid)) {
        log.error('节点进程未运行');
        return;
      }
      const st = await queryStatus(conn.rpcPortal);
      log[(st ? 'ok' : 'warn')](st ? '节点在线' : '节点运行中，RPC 状态暂不可用');
      if (st) log.dim(st.split('\n').filter(Boolean).slice(0, 8).join('\n'));
      const peers = await queryPeers(conn.rpcPortal);
      if (peers) log.dim('—— 对等节点 ——\n' + peers);
    });

  // ---------- stop ----------
  program.command('stop')
    .description('终止所有（或指定）活跃连接')
    .action(async () => {
      const state = await loadState();
      const ids = Object.keys(state.connections);
      if (ids.length === 0) { log.dim('没有需要停止的连接'); return; }
      for (const id of ids) {
        const c = state.connections[id];
        const alive = killPid(c.pid);
        log[(alive ? 'ok' : 'dim')](alive ? `已停止 ${c.role === 'host' ? '房主' : '玩家'} · ${c.name}` : `节点 ${c.name} 已不在运行`);
      }
      state.connections = {};
      await saveState(state);
    });

  // 全局配置
  program.configureOutput?.({ writeErr: (s) => process.stderr.write(s) });
  return program;
}

async function writeServer(address, name, mcDir) {
  const file = await addServerToServersDat({ name, address, mcDir });
  log.ok(`已把「${name}」写入 ${file}`);
  log.dim('启动 Minecraft 进入「多人游戏」即可看到该房间。记得用 ezmc jvm 的 SOCKS5 参数启动游戏。');
}