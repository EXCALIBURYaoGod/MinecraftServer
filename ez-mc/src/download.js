import { promises as fs } from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { BIN_DIR, easytierCorePath, easytierCliPath } from './config.js';

/**
 * 自动下载并解压 easytier-core / easytier-cli 到 ~/.ezmc/bin/。
 * 仅需在首次使用时调用一次。
 *
 * 注意：GitHub Release 下载 API 在使用代理时可能被限流(403)，
 * 因此这里不使用 /releases/latest API，改为直接检索 releases 页面，
 * 这也更省事、无需 API token。
 */

const RELEASES_BASE = 'https://github.com/EasyTier/EasyTier';
const ASSET_RE = /easytier-([a-z0-9]+)-([a-z0-9_-]+)-v?([0-9.]+)\.(zip|tgz)/g;

function detectTarget() {
  const mapPlatform = { linux: 'linux', win32: 'windows', darwin: 'macos', freebsd: 'freebsd' };
  const mapArch = {
    x64: 'x86_64', arm64: 'aarch64', arm: 'arm', armhf: 'armv7',
    loong64: 'loongarch64', riscv64: 'riscv64', mips: 'mips', mipsel: 'mipsel',
  };
  const platform = mapPlatform[process.platform];
  const arch = mapArch[process.arch];
  if (!platform || !arch) {
    throw new Error(`暂不支持该平台/架构：${process.platform}/${process.arch}`);
  }
  return { platform, arch };
}

async function fetchText(url) {
  const res = await fetch(url, { redirect: 'follow', headers: { 'User-Agent': 'ezmc-cli' } });
  if (!res.ok) throw new Error(`HTTP ${res.status}: ${url}`);
  return { text: await res.text(), finalUrl: res.url || '' };
}

/** 解析最新 tag 与匹配的发布包。 */
async function resolveReleaseAsset({ platform, arch }) {
  // 1) 跟随 /releases/latest 重定向拿到最新 tag，例如 v2.6.4
  const { finalUrl } = await fetchText(`${RELEASES_BASE}/releases/latest`);
  const tagMatch = finalUrl.match(/\/tag\/([^/?#]+)/);
  const tag = tagMatch ? tagMatch[1] : null;
  if (!tag) throw new Error('无法定位 EasyTier 最新版本号');

  // 2) 抓取该 tag 的资产列表页面，挑出匹配平台/架构的包
  const { text } = await fetchText(`${RELEASES_BASE}/releases/expanded_assets/${tag}`);
  const found = [];
  for (const m of text.matchAll(ASSET_RE)) {
    const [, p, a, v, ext] = m;
    if (p === platform && a === arch) {
      found.push({ file: m[0], tag, ver: v, ext });
    }
  }
  if (found.length === 0) throw new Error(`在 ${tag} 中未找到 ${platform}-${arch} 的 EasyTier 包`);
  // 优先 zip，其次 tgz
  found.sort((x, y) => (x.ext === 'zip' ? -1 : 1) - (y.ext === 'zip' ? -1 : 1));
  const hit = found[0];
  return {
    url: `${RELEASES_BASE}/releases/download/${tag}/${hit.file}`,
    ext: hit.ext,
  };
}

async function downloadBuffer(url) {
  const res = await fetch(url, { redirect: 'follow', headers: { 'User-Agent': 'ezmc-cli' } });
  if (!res.ok) throw new Error(`HTTP ${res.status}: ${url}`);
  return Buffer.from(await res.arrayBuffer());
}

// ---- 解压 --- //

/** 解压 .tgz（gzip + tar），返回成员相对路径 -> Buffer 的映射。 */
function untar(tgzBuf) {
  const gzip = zlib.gunzipSync(tgzBuf);
  const files = {};
  let off = 0;
  // 512 字节块头
  const headerLen = 512;
  while (off + headerLen <= gzip.length) {
    const header = gzip.subarray(off, off + headerLen);
    if (header.every((b) => b === 0)) break; // 全零 = 结束
    const name = header.subarray(0, 100).toString('utf8').replace(/\0+$/, '');
    if (name.endsWith('/')) { off += headerLen + 512; continue; } // 目录
    const size = parseInt(header.subarray(124, 136).toString('utf8').replace(/\0/g, '').trim(), 8) || 0;
    const body = gzip.subarray(off + headerLen, off + headerLen + size);
    files[normalize(removeVersionDir(name))] = Buffer.from(body);
    off += headerLen + Math.ceil(size / headerLen) * headerLen;
  }
  return files;
}

/** 解压 .zip（只读实现，支持 store/stored 与 deflate 方法）。 */
function unzip(zipBuf) {
  const files = {};
  // 1) 定位 End of Central Directory
  let eocd = -1;
  for (let i = zipBuf.length - 22; i >= 0; i--) {
    if (zipBuf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd === -1) throw new Error('无效的 zip 文件');
  const count = zipBuf.readUInt16LE(eocd + 10);
  const cdSize = zipBuf.readUInt32LE(eocd + 12);
  const cdOff = zipBuf.readUInt32LE(eocd + 16);

  // 2) 读取中央目录条目，解析本地头偏移
  let p = cdOff;
  const entries = [];
  for (let i = 0; i < count; i++) {
    if (zipBuf.readUInt32LE(p) !== 0x02014b50) throw new Error('中央目录条目无效');
    const method = zipBuf.readUInt16LE(p + 10);
    const compSize = zipBuf.readUInt32LE(p + 20);
    const nameLen = zipBuf.readUInt16LE(p + 28);
    const extraLen = zipBuf.readUInt16LE(p + 30);
    const commentLen = zipBuf.readUInt16LE(p + 32);
    const localOff = zipBuf.readUInt32LE(p + 42);
    const name = zipBuf.subarray(p + 46, p + 46 + nameLen).toString('utf8');
    p += 46 + nameLen + extraLen + commentLen;
    entries.push({ name, method, compSize, localOff });
  }

  for (const e of entries) {
    if (e.name.endsWith('/')) continue;
    const lh = e.localOff;
    const localNameLen = zipBuf.readUInt16LE(lh + 26);
    const localExtraLen = zipBuf.readUInt16LE(lh + 28);
    const dataStart = lh + 30 + localNameLen + localExtraLen;
    const raw = zipBuf.subarray(dataStart, dataStart + e.compSize);
    let data;
    if (e.method === 0) data = Buffer.from(raw);
    else if (e.method === 8) data = zlib.inflateRawSync(raw);
    else continue; // 其它压缩方式暂时跳过
    files[normalize(removeVersionDir(e.name))] = data;
  }
  return files;
}

/** 去掉解压后可能存在的版本目录前缀，便于在扁平目录里找到可执行文件。 */
function removeVersionDir(name) {
  return name.split('/').slice(1).join('/');
}
function normalize(name) {
  return name.replace(/\/+/g, '/').replace(/^\/+/, '');
}

async function extract(files, destDir) {
  let coreName = null;
  let cliName = null;
  for (const name of Object.keys(files)) {
    const base = path.basename(name);
    if (/^easytier-core/i.test(base)) coreName = name;
    if (/^easytier-cli/i.test(base)) cliName = name;
  }
  if (!coreName) {
    throw new Error('解压后未找到 easytier-core，可用的文件：' + Object.keys(files).join(', '));
  }
  await fs.mkdir(destDir, { recursive: true });
  await fs.writeFile(easytierCorePath(), files[coreName]);
  if (cliName) await fs.writeFile(easytierCliPath(), files[cliName]);
  if (process.platform !== 'win32') {
    await fs.chmod(easytierCorePath(), 0o755);
    if (cliName) await fs.chmod(easytierCliPath(), 0o755);
  }
}

/** 下载并安装 easytier 二进制。progressCb(str) 用于展示进度。 */
export async function downloadEasyTier(progressCb = () => {}) {
  progressCb('查询仓库最新版本…');
  const target = detectTarget();
  const asset = await resolveReleaseAsset(target);
  progressCb(`下载 ${path.basename(asset.url)} …`);
  const buf = await downloadBuffer(asset.url);
  progressCb(`解压到 ${BIN_DIR} …`);
  const files = asset.ext === 'zip' ? unzip(buf) : untar(buf);
  await extract(files, BIN_DIR);
  progressCb('安装完成');
}