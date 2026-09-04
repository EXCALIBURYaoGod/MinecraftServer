import { Command } from 'commander';
import { isInstalled } from './easytier.js';
import * as ops from './ops.js';
import { proxyJvmArgs, addServerToServersDat, detectLanPort, minecraftDir } from './minecraft.js';
import { log } from './logger.js';

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
      const { downloadEasyTier } = await import('./download.js');
      await downloadEasyTier((m) => log.info(m));
      log.ok('安装完成');
    });

  // ---------- doctor ----------
  program.command('doctor')
    .description('检查运行环境与网络链接状态')
    .action(async () => {
      const okCore = await isInstalled();
      log[(okCore ? 'ok' : 'error')](okCore ? 'easytier-core 已安装' : 'easytier-core 未安装（运行 ezmc install）');
      const st = await ops.opStatus();
      if (st.active) {
        log.info(`当前连接：${st.role === 'host' ? '房主' : '玩家'} @ ${st.name}`);
        log.info(`虚拟 IP：${st.host}${st.proxyPort ? `，SOCKS5 代理端口：${st.proxyPort}` : ''}`);
        log.ok(`进程存活（${st.running ? '是' : '否'}，PID ${st.pid}）`);
        if (st.peersText) log.dim(st.peersText.join('\n'));
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
      try {
        const conn = await ops.opHost({ name, ip: opts.ip, peer: opts.peer });
        log.ok(`房间「${name}」已创建，虚拟 IP ${conn.host}`);
        log.info('把下面的房间码分享给好友，让他们加入：');
        log.code(conn.roomCode);
        log.dim('专用服务端：直接按 server.properties 的 server-port 对外联机。');
      } catch (e) {
        log.error(e.message || String(e));
      }
    });

  // ---------- join ----------
  program.command('join')
    .description('加入房间（玩家）：解析房间码并启动 SOCKS5 代理节点')
    .argument('<roomcode>', '房主分享的房间码')
    .option('--proxy-port <port>', '本地 SOCKS5 代理端口', '1080')
    .option('--server <ip:port>', '同时把房主地址写入 servers.dat')
    .option('--force', '已有活跃连接时强制替换')
    .action(async (roomcode, opts) => {
      try {
        const conn = await ops.opGuest({ roomcode, proxyPort: opts.proxyPort });
        log.ok(`已加入房间「${conn.name}」，房主虚拟 IP ${conn.host}`);
        log.info(`本地 SOCKS5 代理：127.0.0.1:${conn.proxyPort}`);
        log.dim(' 1) 把上面地址填入多人游戏服务器（房主给端口）。');
        log.dim(' 2) 在这个终端/集成里使用如下 JVM 参数启动客户端（mod 内置代理时无需）：');
        proxyJvmArgs(conn.proxyPort).forEach((a) => log.dim(`    ${a}`));
        if (opts.server) {
          await writeServer(opts.server, conn.name);
        }
      } catch (e) {
        log.error(e.message || String(e));
      }
    });

  // ---------- share (host, 集成服务端场景) ----------
  program.command('share')
    .description('房主：捕获取集成服务端/专用服务端端口并打印联机地址')
    .option('--port <int>', '直接指定游戏端口，跳过日志监听')
    .option('-f, --file <path>', 'Minecraft 日志文件路径', undefined)
    .option('-t, --timeout <ms>', '等待端口超时', '120000')
    .option('-w, --write-server', '同时写入本机 servers.dat')
    .action(async (opts) => {
      const st = await ops.opStatus();
      if (!st.active || st.role !== 'host') {
        log.error('当前没有房主连接，请先 ezmc create <name>');
        return;
      }
      let port = opts.port ? parseInt(opts.port, 10) : null;
      if (!port) {
        const logFile = opts.file || `${minecraftDir()}/logs/latest.log`;
        log.info(`监听 ${logFile} 等待游戏开放局域网…（按 Ctrl+C 取消）`);
        port = await detectLanPort(logFile, { timeoutMs: parseInt(opts.timeout, 10) });
        if (!port) {
          log.error(`超时未从日志检测到端口。请确认已开放局域网，或用 --port 手动指定。`);
          return;
        }
      }
      const address = `${st.host}:${port}`;
      log.ok(`联机地址：${address}`);
      log.dim('让好友运行 ezmc join <房间码> 后使用该地址连接。');
      if (opts.writeServer) {
        await writeServer(address, st.name);
      }
    });

  // ---------- status ----------
  program.command('status')
    .description('显示活跃连接与节点/对等状态')
    .action(async () => {
      const st = await ops.opStatus();
      if (!st.active) { log.dim('当前没有活跃连接。运行 ezmc create <name> 建房，或 ezmc join <code> 加入。'); return; }
      log.title(`[${st.role === 'host' ? '房主' : '玩家'}] ${st.name}  (虚拟IP ${st.host})`);
      if (st.proxyPort) log.dim(`SOCKS5 代理：127.0.0.1:${st.proxyPort}`);
      if (!st.running) {
        log.error('节点进程未运行');
        return;
      }
      log.ok('节点在线');
      if (st.peersText) log.dim('—— 对等节点 ——\n' + st.peersText.join('\n'));
      log.dim(`连接类型：${st.linkType === 'direct' ? '直连 (P2P)' : st.linkType === 'relay' ? '中继 (relay)' : '未知'}`);
    });

  // ---------- addr ----------
  program.command('addr')
    .description('打印当前连接的虚拟 IP')
    .action(async () => {
      const st = await ops.opStatus();
      if (!st.active) { log.error('没有活跃连接'); return; }
      log.code(st.host);
      if (st.proxyPort) log.dim(`SOCKS5：127.0.0.1:${st.proxyPort}`);
    });

  // ---------- roomcode ----------
  program.command('roomcode')
    .description('打印当前房主连接的房间码')
    .action(async () => {
      const st = await ops.opStatus();
      if (!st.active) { log.error('没有活跃连接'); return; }
      if (st.roomCode) { log.code(st.roomCode); return; }
      log.error('当前连接不是房主，无房间码');
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
      const st = await ops.opStatus();
      if (!st.active || !st.proxyPort) { log.error('没有活跃的玩家连接（先 ezmc join）'); return; }
      proxyJvmArgs(st.proxyPort).forEach((a) => log.code(a));
    });

  // ---------- daemon ----------
  program.command('daemon')
    .description('启动本地 HTTP 守护，供服务端插件 / 客户端 Mod 调用')
    .option('--port <port>', '监听端口', '29876')
    .action(async (opts) => {
      const { startDaemon } = await import('./daemon.js');
      await startDaemon({ port: parseInt(opts.port, 10) });
    });

  // ---------- stop ----------
  program.command('stop')
    .description('终止所有（或指定）活跃连接')
    .action(async () => {
      const n = await ops.opStop();
      log[(n ? 'ok' : 'dim')](n ? `已停止 ${n} 个连接` : '没有需要停止的连接');
    });

  // 全局配置
  program.configureOutput?.({ writeErr: (s) => process.stderr.write(s) });
  return program;
}

async function writeServer(address, name, mcDir) {
  const file = await addServerToServersDat({ name, address, mcDir });
  log.ok(`已把「${name}」写入 ${file}`);
  log.dim('启动 Minecraft 进入「多人游戏」即可看到该房间。启用 mod 内置代理时不需额外 JVM 参数。');
}