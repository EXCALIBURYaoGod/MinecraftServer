import { spawn } from 'node:child_process';
import { promises as fs } from 'node:fs';
import {
  easytierCorePath,
  easytierCliPath,
  BIN_DIR,
  LOG_DIR,
  logPath,
} from './config.js';

/**
 * 构建 / 管理 easytier-core 子进程。
 *
 * 免提权架构：
 *  - 房主 (host):  --no-tun -i <hostIp>  → 节点通过虚拟 IP 对外可被访问
 *  - 玩家 (guest): --no-tun -d --proxy-port <port> → 通过本地 SOCKS5 代理主动访问房主
 * 两者都通过 -p <公共共享节点> 完成 NAT 打洞与节点发现，无需公网 IP 或中心服务器。
 */

/** 检查 easytier-core 二进制是否就绪。 */
export async function isInstalled() {
  try {
    await fs.access(easytierCorePath());
    return true;
  } catch {
    return false;
  }
}

/** 构建房主 easytier-core 参数。 */
export function buildHostArgs({ name, secret, hostIp, peer, rpcPortal }) {
  const args = [
    '--no-tun',
    '--network-name', name,
    '--network-secret', secret,
    '-i', hostIp,
    '-r', rpcPortal,
    '-m', `ezmc-host-${name}`,
  ];
  for (const p of splitPeers(peer)) {
    args.push('-p', p);
  }
  return args;
}

/** 构建玩家 easytier-core 参数。 */
export function buildGuestArgs({ name, secret, peer, rpcPortal, proxyPort }) {
  const args = [
    '--no-tun',
    '--network-name', name,
    '--network-secret', secret,
    '-d',
    '--socks5', String(proxyPort),
    '-r', rpcPortal,
    '-m', `ezmc-guest-${name}`,
  ];
  for (const p of splitPeers(peer)) {
    args.push('-p', p);
  }
  return args;
}

/** peer 支持以空格或逗号分隔传入多个节点。 */
function splitPeers(peer) {
  const value = (peer || '').trim();
  if (!value) return [];
  return value.split(/[\s,]+/).filter(Boolean);
}

/**
 * 以独立进程方式启动 easytier-core，返回子进程。
 * @param {string[]} args
 * @param {string} connId 连接 ID，用于日志文件命名
 */
export function spawnCore(args, connId) {
  return new Promise(async (resolve, reject) => {
    try {
      await fs.mkdir(BIN_DIR, { recursive: true });
      await fs.mkdir(LOG_DIR, { recursive: true });
      const fh = await fs.open(logPath(connId), 'a');
      const child = spawn(easytierCorePath(), args, {
        detached: true, // 脱离当前终端，独立运行
        stdio: ['ignore', fh.fd, fh.fd],
      });
      child.on('error', reject);
      child.on('spawn', () => {
        child.unref(); // 允许父进程退出后，easytier 继续在后台运行
        resolve(child);
      });
    } catch (err) {
      reject(err);
    }
  });
}

/**
 * 查询节点状态。
 * @returns {Promise<string|null>} 输出文本，失败返回 null
 */
export async function queryStatus(rpcPortal) {
  return new Promise((resolve) => {
    const child = spawn(easytierCliPath(), ['-p', rpcPortal, 'node']);
    let out = '';
    let err = '';
    child.stdout.on('data', (d) => (out += d));
    child.stderr.on('data', (d) => (err += d));
    const done = () => resolve(out.trim() || err.trim() || null);
    child.on('error', () => resolve(null));
    child.on('exit', done);
  });
}

/** 查询对等节点列表（判断是否真正打洞/互通）。 */
export async function queryPeers(rpcPortal) {
  return new Promise((resolve) => {
    const child = spawn(easytierCliPath(), ['-p', rpcPortal, 'peer']);
    let out = '';
    child.stdout.on('data', (d) => (out += d));
    const done = () => resolve(out);
    child.on('error', () => resolve(''));
    child.on('exit', done);
  });
}