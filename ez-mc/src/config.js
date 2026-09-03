import { promises as fs } from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { getRandomValues } from 'node:crypto';

/**
 * 全局配置与状态管理。
 * 所有数据存放在每个用户目录下的 ~/.ezmc/ 中：
 *   - ~/.ezmc/config.json           用户自定义配置（peer、proxy 端口等）
 *   - ~/.ezmc/state.json            当前活跃连接（创建房/加入房）的记录
 *   - ~/.ezmc/bin/                  easytier-core / easytier-cli 可执行文件
 */
export const DATA_DIR = path.join(os.homedir(), '.ezmc');

export const CONFIG_PATH = path.join(DATA_DIR, 'config.json');
export const STATE_PATH = path.join(DATA_DIR, 'state.json');
export const BIN_DIR = path.join(DATA_DIR, 'bin');
export const LOG_DIR = path.join(DATA_DIR, 'logs');

export const DEFAULT_PEER = 'tcp://public.easytier.top:11010';

export function easytierCorePath() {
  return path.join(BIN_DIR, process.platform === 'win32' ? 'easytier-core.exe' : 'easytier-core');
}

export function easytierCliPath() {
  return path.join(BIN_DIR, process.platform === 'win32' ? 'easytier-cli.exe' : 'easytier-cli');
}

async function readJson(filePath, fallback) {
  try {
    const raw = await fs.readFile(filePath, 'utf8');
    return JSON.parse(raw);
  } catch {
    return fallback;
  }
}

export async function loadConfig() {
  return readJson(CONFIG_PATH, {});
}

export async function saveConfig(config) {
  await fs.mkdir(DATA_DIR, { recursive: true });
  await fs.writeFile(CONFIG_PATH, JSON.stringify(config, null, 2), 'utf8');
}

export async function loadState() {
  return readJson(STATE_PATH, { connections: {} });
}

export async function saveState(state) {
  await fs.mkdir(DATA_DIR, { recursive: true });
  await fs.writeFile(STATE_PATH, JSON.stringify(state, null, 2), 'utf8');
}

/** 生成 <= n 位的随机安全网络密钥（去掉了易混字符）。 */
export function randomSecret(bytes = 15) {
  const alphabet = 'abcdefghjkmnpqrstuvwxyz23456789ABCDEFGHJKMNPQRSTUVWXYZ';
  const arr = new Uint8Array(bytes);
  getRandomValues(arr);
  return Array.from(arr, (b) => alphabet[b % alphabet.length]).join('');
}

/** 生成一个不与公共内网冲突的私有虚拟网段，10.a.b.0/24，并返回网段与房主 IP。 */
export function randomSubnet() {
  // 避开 10.0.0.0/8 中的常见公共/保留段使用冲突范围，取 10.100~10.250
  const a = 100 + Math.floor(Math.random() * 151); // 100..250
  const b = Math.floor(Math.random() * 256); // 0..255
  return {
    cidr: `10.${a}.${b}.0/24`,
    hostIp: `10.${a}.${b}.1`,
  };
}

export function logPath(connId) {
  return path.join(LOG_DIR, `${connId}.log`);
}