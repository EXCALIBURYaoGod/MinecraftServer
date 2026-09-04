import net from 'node:net';
import { promises as fs } from 'node:fs';
import {
  loadConfig,
  loadState,
  saveState,
  randomSecret,
  randomSubnet,
  DEFAULT_PEER,
  CONFIG_PATH,
} from './config.js';
import { isInstalled, spawnCore, queryStatus, queryPeers, buildHostArgs, buildGuestArgs } from './easytier.js';
import { encodeRoomCode, decodeRoomCode, connectionId } from './roomcode.js';
import { downloadEasyTier } from './download.js';

/**
 * 可复用的联机操作层。
 * 被 CLI（src/cli.js）与本地守护（src/daemon.js）共同使用，
 * 保证两者对 easytier 节点的启停/状态管理逻辑一致。
 */

/** 尝试在 preferred 端口监听，若被占用则改用随机空闲端口。 */
export function freePort(preferred) {
  return new Promise((resolve) => {
    const server = net.createServer();
    server.once('error', () => server.listen(0));
    server.listen(preferred === undefined ? 0 : preferred, () => {
      const { port } = server.address();
      server.close(() => resolve(port));
    });
  });
}

/** 确保 easytier-core 已安装，否则自动下载。 */
export async function ensureCore() {
  if (await isInstalled()) return;
  await downloadEasyTier(() => {});
}

function resolvePeer(config, explicit) {
  return explicit || config.peer || DEFAULT_PEER;
}

/** 判断 pid 是否还存活。 */
export function isAlive(pid) {
  if (!pid) return false;
  try {
    process.kill(pid, 0);
    return true;
  } catch (e) {
    return e.code === 'EPERM';
  }
}

/** 返回当前仍存活的连接对象（state.connections 中 pid 存活的那个）。 */
export async function currentConnection(state) {
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
export async function resetConnections(state) {
  for (const c of Object.values(state.connections)) {
    if (isAlive(c.pid)) killPid(c.pid);
  }
  state.connections = {};
  await saveState(state);
}

/**
 * 房主建房：生成房间码并启动房主 easytier-core 节点。
 * @returns {Promise<object>} conn（含 roomCode / host / peer / pid / rpcPortal）
 */
export async function opHost({ name, ip, peer }) {
  await ensureCore();
  const config = await loadConfig();
  const state = await loadState();
  const live = await currentConnection(state);
  if (live) {
    throw new Error(
      `已有活跃连接（${live.role === 'host' ? '房主' : '玩家'}·${live.name}），请先停止。`
    );
  }
  await resetConnections(state); // 释放旧节点/端口，避免 Address in use

  const secret = randomSecret();
  const subnet = ip ? { hostIp: ip } : randomSubnet();
  const peerRes = resolvePeer(config, peer);
  const rpcPortal = `127.0.0.1:${await freePort(15888)}`;

  const id = connectionId(name, secret, subnet.hostIp);
  const conn = {
    role: 'host', name, secret, host: subnet.hostIp, peer: peerRes,
    rpcPortal, proxyPort: null, pid: null,
  };
  conn.roomCode = encodeRoomCode({ name, secret, host: subnet.hostIp, peer: peerRes });

  const child = await spawnCore(
    buildHostArgs({ name: conn.name, secret: conn.secret, hostIp: conn.host, peer: conn.peer, rpcPortal: conn.rpcPortal }),
    id,
  );
  conn.pid = child.pid;

  state.connections[id] = conn;
  await saveState(state);
  return conn;
}

/**
 * 玩家加入：解析房间码并启动玩家 easytier-core 节点（本地 SOCKS5 代理）。
 * @returns {Promise<object>} conn（含 host / proxyPort / pid / rpcPortal）
 */
export async function opGuest({ roomcode, proxyPort }) {
  await ensureCore();
  const state = await loadState();
  const live = await currentConnection(state);
  if (live) {
    throw new Error(
      `已有活跃连接（${live.role === 'host' ? '房主' : '玩家'}·${live.name}），请先停止。`
    );
  }
  await resetConnections(state);

  const room = decodeRoomCode(roomcode);
  const resolvedProxyPort = await freePort(proxyPort === undefined ? 1080 : parseInt(proxyPort, 10));
  const rpcPortal = `127.0.0.1:${await freePort(0)}`;
  const peer = room.peer || DEFAULT_PEER;

  const id = connectionId(room.name, room.secret, room.host);
  const conn = {
    role: 'guest', name: room.name, secret: room.secret,
    host: room.host, peer, rpcPortal, proxyPort: resolvedProxyPort, pid: null,
    roomCode: roomcode,
  };
  const child = await spawnCore(buildGuestArgs(conn), id);
  conn.pid = child.pid;
  state.connections[id] = conn;
  await saveState(state);
  return conn;
}

/** 终止所有活跃连接，返回被终止的数量。 */
export async function opStop() {
  const state = await loadState();
  const ids = Object.keys(state.connections);
  let n = 0;
  for (const id of ids) {
    const c = state.connections[id];
    if (killPid(c.pid)) n++;
  }
  state.connections = {};
  await saveState(state);
  return n;
}

/**
 * 当前状态摘要。供 CLI / daemon / 插件 / mod 共享。
 * 返回结构字段稳定，便于 Java 侧解析。
 */
export async function opStatus() {
  const state = await loadState();
  const conn = await currentConnection(state);
  if (!conn) return { active: false };
  const base = {
    active: true,
    role: conn.role,
    name: conn.name,
    host: conn.host,
    pid: conn.pid,
    running: isAlive(conn.pid),
  };
  if (conn.proxyPort) base.proxyPort = conn.proxyPort;
  if (conn.roomCode) base.roomCode = conn.roomCode;
  if (conn.peer) base.peer = conn.peer;
  if (base.running && conn.rpcPortal) {
    try {
      const peers = await queryPeers(conn.rpcPortal);
      // 判定直连 / 中继：节点间存在 P2P 直连则为 direct，反之为 relay
      const text = peers || '';
      const direct = /Direct|P2P|DirectConnection|true/i.test(text);
      base.linkType = direct ? 'direct' : 'relay';
      base.peersText = text.trim().split('\n').filter(Boolean).slice(0, 12);
    } catch {
      base.linkType = 'unknown';
    }
  }
  return base;
}

/** 返回命令返回文件路径（供外部引用）。 */
export function configPath() {
  return CONFIG_PATH;
}