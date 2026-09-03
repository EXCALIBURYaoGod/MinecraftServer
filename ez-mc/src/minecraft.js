import { promises as fs } from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import zlib from 'node:zlib';

/**
 * Minecraft 侧的集成辅助：
 *  1. 从游戏日志自动捕获"对局域网开放"监听的端口
 *  2. 生成 SOCKS5 代理所需的 JVM 参数
 *  3. 把好友房间写入 servers.dat，让玩家进入多人模式后直接可见
 */

/** 默认 .minecraft 目录（依据官方启动器约定）。 */
export function minecraftDir() {
  const home = os.homedir();
  const platform = process.platform;
  if (platform === 'win32') return path.join(home, 'AppData', 'Roaming', '.minecraft');
  if (platform === 'darwin') return path.join(home, 'Library', 'Application Support', 'minecraft');
  return path.join(home, '.minecraft');
}

/** 从 latest.log 轮询解析"本地游戏托管端口"。 */
export async function detectLanPort(logFile, { timeoutMs = 60_000 } = {}) {
  const startedAt = Date.now();
  while (Date.now() - startedAt < timeoutMs) {
    let content = '';
    try {
      content = await fs.readFile(logFile, 'utf8');
    } catch {
      // 日志文件可能还不存在，继续等待
    }
    const match = content.match(/Local game hosted on port (\d+)/);
    if (match) return parseInt(match[1], 10);
    await new Promise((r) => setTimeout(r, 1000));
  }
  return null;
}

/**
 * 生成玩家启动 Minecraft 所需的 JVM 代理参数。
 * socksNonProxyHosts 极其关键：本地皮肤验证 / 资源下载等必须绕过代理。
 */
export function proxyJvmArgs(proxyPort) {
  return [
    `-DsocksProxyHost=127.0.0.1`,
    `-DsocksProxyPort=${proxyPort}`,
    `-DsocksNonProxyHosts=localhost|127.0.0.1`,
    `-Djava.net.preferIPv4Stack=true`,
  ];
}

/** 把房间写入 servers.dat（gzip + NBT，Java 原版格式）。 */
export async function addServerToServersDat({ name, address, mcDir = minecraftDir() }) {
  const filePath = path.join(mcDir, 'servers.dat');
  let existing = null;
  try {
    const buf = await fs.readFile(filePath);
    existing = zlib.gunzipSync(buf);
  } catch {
    // 无现存的 servers.dat，从空列表开始
  }

  const list = existing ? extractServerList(existing) : [];
  const servers = list.map((s) => ({ name: s.name, ip: s.ip }));
  // 去重：相同地址不重复添加
  if (!servers.some((s) => s.ip === address)) {
    servers.push({ name, ip: address });
  }

  const nbt = encodeRootCompound({ servers: servers.map(toServerCompound) });
  const gz = zlib.gzipSync(nbt);

  if (!existing) {
    await fs.mkdir(mcDir, { recursive: true });
  } else {
    // 备份原文件，便于回退
    await fs.copyFile(filePath, `${filePath}.bak`);
  }
  await fs.writeFile(filePath, gz);
  return filePath;
}

function toServerCompound({ name, ip }) {
  return { name, ip, acceptTextures: 1 };
}

// ---------- 简易 NBT 编码器（服务器列表所需的最小集合） ----------

const TAGS = { END: 0, BYTE: 1, SHORT: 2, INT: 3, LONG: 4, FLOAT: 5, DOUBLE: 6,
  BYTE_ARRAY: 7, STRING: 8, LIST: 9, COMPOUND: 10, INT_ARRAY: 11, LONG_ARRAY: 12 };

function writeString(buf, offset, str) {
  const len = Buffer.byteLength(str, 'utf8');
  buf.writeUInt16BE(len, offset);
  offset += 2;
  buf.write(str, offset, 'utf8');
  return offset + len;
}

function writeStringLength(str) {
  return 2 + Buffer.byteLength(str, 'utf8');
}

/** 编码一个 Compound。每个 compound 都以 END(0) 结束（符合 NBT 规范）。 */
function encodeCompound(obj) {
  const parts = [];
  for (const [key, value] of Object.entries(obj)) {
    const name = uint16(key);
    const keyBytes = Buffer.from(key, 'utf8');
    if (Array.isArray(value)) {
      // servers 是一个 List<TAG_Compound>
      const count = Buffer.alloc(4);
      count.writeInt32BE(value.length);
      const elements = value.map((v) => encodeCompound(v)); // 元素含内部 END
      parts.push(Buffer.from([TAGS.LIST]), name, keyBytes, Buffer.from([TAGS.COMPOUND]), count, ...elements);
    } else if (value && typeof value === 'object') {
      // 嵌套 Compound
      parts.push(Buffer.from([TAGS.COMPOUND]), name, keyBytes, encodeCompound(value));
    } else if (typeof value === 'string') {
      const strBuf = Buffer.alloc(writeStringLength(value));
      writeString(strBuf, 0, value);
      parts.push(Buffer.from([TAGS.STRING]), name, keyBytes, strBuf);
    } else if (typeof value === 'number') {
      const intBuf = Buffer.alloc(4);
      intBuf.writeInt32BE(value | 0);
      parts.push(Buffer.from([TAGS.INT]), name, keyBytes, intBuf);
    }
  }
  parts.push(Buffer.from([TAGS.END]));
  return Buffer.concat(parts);
}

/**
 * servers.dat 的根节点是"命名 Compound"：0x0a + 空串名 + 内部体(含 END)。
 * extractServerList 正是用 buf[0]===10 来识别根类型并从 offset 1 开始解析的。
 */
function encodeRootCompound(obj) {
  return Buffer.concat([
    Buffer.from([TAGS.COMPOUND]),
    uint16(''),
    encodeCompound(obj),
  ]);
}

function uint16(str) {
  const b = Buffer.alloc(2);
  b.writeUInt16BE(Buffer.byteLength(str, 'utf8'), 0);
  return b;
}

/** 从既有 NBT 中提取服务器 IP 列表（用于去重 / 追加）。 */
export function extractServerList(buf) {
  const out = [];
  if (!buf || buf.length === 0) return out;
  try {
    if (buf[0] === 10) {
      // 跳过根节点名字，指向 compound 内容的首个标签
      const nameInfo = parseNbtString(buf, 1);
      const parsed = parseCompound(buf, nameInfo.offset);
      if (parsed.data && Array.isArray(parsed.data.servers)) out.push(...parsed.data.servers);
    }
  } catch {
    // 解析失败时返回空列表，避免覆盖损坏文件
  }
  return out.filter((s) => s && typeof s === 'object');
}

function parseNbtString(buf, offset) {
  const len = buf.readUInt16BE(offset);
  offset += 2;
  return { value: buf.toString('utf8', offset, offset + len), offset: offset + len };
}

function parseCompound(buf, offset) {
  const data = {};
  let o = offset;
  for (;;) {
    const type = buf[o]; o += 1;
    if (type === 0) break; // END
    const name = parseNbtString(buf, o);
    o = name.offset;
    switch (type) {
      case 1: data[name.value] = buf.readInt8(o); o += 1; break;
      case 2: data[name.value] = buf.readInt16BE(o); o += 2; break;
      case 3: data[name.value] = buf.readInt32BE(o); o += 4; break;
      case 4: o += 8; break;
      case 5: o += 4; break;
      case 6: o += 8; break;
      case 7: o += 4 + buf.readInt32BE(o); break;
      case 8: { const s = parseNbtString(buf, o); data[name.value] = s.value; o = s.offset; break; }
      case 9: {
        const elemType = buf[o];
        const count = buf.readInt32BE(o + 1);
        o += 5;
        const arr = [];
        for (let i = 0; i < count; i++) {
          if (elemType === 10) {
            const sub = parseCompound(buf, o);
            arr.push(sub.data);
            o = sub.offset;
          } else if (elemType === 8) {
            const s = parseNbtString(buf, o);
            arr.push(s.value);
            o = s.offset;
          } else {
            return { data, offset: o }; // 未知元素类型，谨慎停止
          }
        }
        data[name.value] = arr;
        break;
      }
      case 10: { const sub = parseCompound(buf, o); data[name.value] = sub.data; o = sub.offset; break; }
      case 11: o += 4 + buf.readInt32BE(o) * 4; break;
      case 12: o += 4 + buf.readInt32BE(o) * 8; break;
      default: return { data, offset: o };
    }
  }
  return { data, offset: o };
}