/**
 * 房间码编解码。
 *
 * 房间码是一个 URL 风格字符串，用于把房主创建的虚拟网络信息分享给好友。
 * 格式：ezmc://<networkName>?secret=<secret>&host=<hostIp>&peer=<peerUri>
 *
 * 其中 secret 是 EasyTier 的网络密钥，host 是房主在虚拟网内的 IPv4 地址，
 * peer 是加入网络所需的公共共享节点（默认使用 EasyTier 官方公共节点）。
 */

export const ROOM_SCHEME = 'ezmc:';

/** 对字段做 Base64URL 安全编码，避免特殊字符进入 URL/终端。 */
function fieldEncode(value) {
  return Buffer.from(String(value), 'utf8').toString('base64url');
}

function fieldDecode(value) {
  try {
    return Buffer.from(value, 'base64url').toString('utf8');
  } catch {
    return value;
  }
}

/**
 * 编码房间码。
 * @param {{ name: string, secret: string, host: string, peer?: string }} room
 */
export function encodeRoomCode(room) {
  const params = new URLSearchParams();
  params.set('secret', fieldEncode(room.secret));
  params.set('host', room.host);
  if (room.peer) params.set('peer', fieldEncode(room.peer));
  return `ezmc://${encodeURIComponent(room.name)}?${params.toString()}`;
}

/**
 * 解码房间码。
 * @param {string} code
 * @returns {{ name: string, secret: string, host: string, peer?: string }}
 */
export function decodeRoomCode(code) {
  const trimmed = code.trim();
  const prefix = `${ROOM_SCHEME}//`;
  if (!trimmed.startsWith(prefix)) {
    throw new Error(`无效的房间码：必须以 ${prefix} 开头`);
  }
  const rest = trimmed.slice(prefix.length);
  const qIndex = rest.indexOf('?');
  const nameRaw = qIndex === -1 ? rest : rest.slice(0, qIndex);
  const query = qIndex === -1 ? '' : rest.slice(qIndex + 1);
  const params = new URLSearchParams(query);

  const name = decodeURIComponent(nameRaw);
  const secret = fieldDecode(params.get('secret') || '');
  const host = params.get('host') || '';
  const peer = params.get('peer') ? fieldDecode(params.get('peer')) : undefined;

  if (!name || !secret || !host) {
    throw new Error('房间码不完整，缺少 房间名 / 密钥 / 房主IP 中的一项');
  }
  return { name, secret, host, peer };
}

/** 生成一个便于记忆的房间引用 ID（用于状态文件中的唯一键）。 */
export function connectionId(name, secret, host) {
  const str = `${name}|${secret}|${host}`;
  // 简单 FNV-1a 32 位哈希，生成短 id（Node 内建 crypto 亦可，但保持零依赖）。
  let hash = 0x811c9dc5;
  for (let i = 0; i < str.length; i++) {
    hash ^= str.charCodeAt(i);
    hash = (hash * 0x01000193) >>> 0;
  }
  return `conn-${hash.toString(16)}-${(host || 'x').replace(/\./g, '-')}`;
}