# MC P2P 联机：Paper 服务端插件（自动开房+广播）+ Fabric 客户端 Mod（一键加入）

## Summary

在现有 `ez-mc`（Node CLI，基于 EasyTier 的 P2P 组网）之上，新增两个 Minecraft 端组件，把当前"手动敲命令 + 读日志 + 拼 JVM 参数"的流程，升级为：

1. **Paper/Spigot 服务端插件（房主侧）**：服务端启动时自动确保 ez-mc 房主节点在线，拿到虚拟 IP，并把 `虚拟IP:server-port` 与房间码广播出去。
2. **Fabric 客户端 Mod（玩家侧）**：解析 `ezmc://` 房间码，一键调用 ez-mc 拉起玩家节点，并在 mod 内部用 Mixin 注入自有 SOCKS5 客户端，把发往房主虚拟 IP 的流量经本机代理转发，做到运行时可开可关、无需启动器 JVM 参数。

三者通过一个新增的 **ez-mc 本地守护（daemon + HTTP 桥）**统一集成，最大限度复用现有 Node 逻辑。

## 关键决策（来自澄清）

- 服务端平台：**Paper/Spigot（Bukkit API）**
- 客户端工具链：**Fabric**
- P2P 集成方式：**复用 ez-mc 作本地守护**（新增 daemon HTTP 桥）
- 客户端网络方案：**mod 内置 SOCKS 代理（Mixin 注入连接栈）**

## 现状分析（来自探索）

`/workspace/ez-mc` 现有结构（Node ESM + commander）：

- `src/config.js`：`~/.ezmc/` 下 config/state/bin/logs 路径约定；`randomSecret()`、`randomSubnet()`（生成 `10.a.b.1` 虚拟 IP）、`DEFAULT_PEER`。
- `src/easytier.js`：构建房主/玩家 `easytier-core` 参数（`--no-tun`、`-i` 虚拟 IP、`--socks5`、`-r` rpcPortal、`-p` peer）；`spawnCore()` 以 detached 子进程启动；`queryStatus()`/`queryPeers()` 用 easytier-cli 查询。
- `src/cli.js`：命令 `install/doctor/create/join/share/addr/servers/jvm/status/stop`；`create` 生成房间码 + 启动房主节点；`join` 解析房间码 + 启动玩家节点(SOCKS5)；`share` 从 `latest.log` 读 `Local game hosted on port N`；`freePort()`；`currentConnection()`/`isAlive()` 从 state 取活连接。
- `src/roomcode.js`：房间码 `ezmc://<name>?secret=base64url(secret)&host=<host>&peer=base64url(peer)`；`encode/decode`、`connectionId`（FNV-1a）。
- `src/minecraft.js`：`detectLanPort()`（读日志找集成服务端端口）、`proxyJvmArgs()`（JVM SOCKS 参数）、`addServerToServersDat()`（写 servers.dat，NBT 编解码）、`minecraftDir()`。
- `package.json`：`type: module`，仅依赖 `commander`，入口 `bin/ezmc.js`。

工作流现状（全部手动）：
- 房主：`ezmc create <name>` → 游戏内「对局域网开放」→ `ezmc share` 拿到 `虚拟IP:端口`。
- 玩家：`ezmc join <房间码>` → 启动器加 `ezmc jvm` 的 JVM 代理参数 → 连 `虚拟IP:端口`。

**语义校正**：采用 Paper 专用服务端后，"开房"不再依赖集成服务端的动态端口；服务端固定监听 `server.properties` 的 `server-port`，插件只需确保 ez-mc 房主节点在线并广播 `虚拟IP:server-port`。

## 目标项目结构（新增，彼此独立构建）

```
/workspace/
├── ez-mc/                    # 现有 Node CLI —— 本次扩展 daemon
│   └── src/
│       ├── daemon.js        # 新增：本地 HTTP / JSON 桥
│       └── cli.js           # 修改：新增 `daemon` 子命令
└── mc-p2p/
    ├── plugin/               # Paper 插件（Gradle + Java）
    │   └── src/main/java/.../EzpPlugin.java
    └── mod/                  # Fabric Mod（Gradle + fabric-loom）
        └── src/main/java/.../EzpClient.java
```

## 变更明细

### A. ez-mc：新增本地守护 HTTP 桥（`daemon`）

目标：让无 Node 编程经验的 Java 组件能通过 HTTP 拿到房间码/虚拟 IP/启停节点/查状态，避免插件直接对接 easytier 二进制与 state 文件。

- **修改 `src/cli.js`**：新增 `program.command('daemon')`，`--port <port>`（默认 `29876`），启动后常驻并打印 `http://127.0.0.1:29876`。
- **新增 `src/daemon.js`**：使用 Node 内置 `node:http` 提供最小 JSON-REST 端点（零新依赖，延续项目零第三方依赖风格）：
  - `GET /health` → `{ ok: true }`
  - `GET /room/:name` → 若该房间有活跃 host 连接则返回 `{ roomCode, ip, peer }`，否则 `404`
  - `POST /host`  body `{ name, peer? }` → 复用 `cli.js` create 逻辑启动房主节点（生成 secret/subnet），返回 `{ roomCode, ip, peer, pid }`；重复调用幂等（返现有连接）。
  - `POST /guest`  body `{ roomCode, proxyPort? }` → 复用 join 逻辑启动玩家节点，返回 `{ ip, proxyPort, pid }`。
  - `POST /stop` → 复用 stop 逻辑终止所有连接。
  - `GET /status` → 返回当前活跃连接 + `queryPeers` 摘要。
  - 复用 `src/easytier.js`、`src/config.js`、`src/roomcode.js`、`src/cli.js` 中已导出的 `freePort`/`currentConnection`/`isAlive` 辅助；把 `cli.js` 里 `freePort`/`currentConnection`/`resetConnections`/`ensureCore` 提升为共享导出供 daemon 使用。
  - JSON 错误统一 `{ error: string }` + 非 2xx；请求体用 `JSON.parse` 容错。

### B. Paper 服务端插件 `plugin/`

实现"自动开房(网络)" + "地址广播"。

- **脚手架**：Gradle + `com.papermc.paper:dev-bundle`（或 spigot-api），Java 17+，`api-version` 对齐现代 Paper。Gradle 基于 jar 拷贝 / `ShadowJar` 不含多余依赖（HTTP 用 JDK `HttpURLConnection`，零第三方依赖）。
- **主类 `EzpPlugin extends JavaPlugin`**：
  - `onEnable`：读取 `plugins/Ezp/config.yml`（`room-name`、`daemon-url`、`auto-host`）。若 `auto-host: true`，启动异步任务向 `daemon /host` 发起请求，拿到 `roomCode` 与 `ip`；等待服务端地则 todo。
  - 监听 `ServerStartedEvent` / 或读 `server.properties` 的 `server-port` 得最终端口，拼 `broadcastAddress = ip + ":" + serverPort`。
  - **地址广播**：
    - `Bukkit.broadcastMessage("联机地址 vIP:port · 房间码：<code>")`；
    - 控制台日志打印完整房间码；
    - 可选：把 `{ address, roomCode }` 写入 `plugins/Ezp/state.yml` 供外部读取。
  - 命令：`/ezp` 子命令 `status`（查 daemon 状态）、`broadcast`（手动重播地址）、`reload`。
  - `onDisable`：按配置决定是否 `POST /stop` 保留节点（默认保留，便于房主停机不拆房？——默认 `shutdown-stop: false` 保留）。
- **如何感知虚拟 IP**：均从 daemon `/host` 响应取；插件不解析房间码格式。

### C. Fabric 客户端 Mod `mod/`

实现"一键加入"：解析房间码 → 拉起玩家节点 → mod 内置 SOCKS 代理直连虚拟 IP。

- **脚手架**：Gradle + `fabric-loom`，一个 mixin (`client.mixins.json`)。
- **核心难点**：JVM 的 `-DsocksProxyHost` 只能在启动时设置，无法运行时改。因此 mod 需自建代理：实现一个纯 Java SOCKS5 客户端（连接握手 `0x05`，目标地址经代理转发），并用 **Mixin** 把 Minecraft 发往目标服务器的连接改为「先连本地代理，再由代理转发到 `虚拟IP:端口`」。
  - 注入点：`net.minecraft.client.network.ClientConnection` 的 `connect(InetSocketAddress address, boolean useEpoll, ...)` 类方法（依 MC 版本定位），替换 `address` 为本地代理地址，并把真实目标地址传给 mod 内部注册的代理。
  - 更稳妥替代：Mixin 拦截 `ConnectScreen`/`ServerAddress` 解析，将连接目标改写为本地代理 + 保存原地址；适配一个目标 MC 版本（如 1.20.1 / 1.21.x，计划内固定一处并注明）。
- **流程（UI 入口）**：
  1. 在聊天框或 Mod 界面输入/粘贴 `ezmc://...` 房间码 → 解析出 `name/secret/host/peer`（Java 侧解析，不必依赖 Node）。
  2. 调用本地 ez-mc daemon `POST /guest { roomCode }` 启动玩家 easytier 节点，得到 `proxyPort` 与 `ip`。
  3. 写 `servers.dat`（复用现有 NBT 思路，Java 侧用 `nbt` 库或手写最小编解码）加入 `ip:port`，并弹提示。
  4. 开启内置 SOCKS 代理路由映射：`virtualIP:port → 本地 127.0.0.1:proxyPort`，随后引导/静默连接该服务器。
  5. `GET /status` / `POST /stop` 用于断开与节点管理，Mod 界面显示联动/连接状态。
- **皮肤/资源过滤**：仅对"目标为房主虚拟 IP"的连接走代理，其余直连，避免皮肤站、下载流量被代理（对应现有 `socksNonProxyHosts` 的作用）。

### D. 文档与脚本

- 更新 `mc-p2p` 目录 README：安装（`./gradlew build` + 放入 plugins/mods）、启动步骤、config.yml 说明。
- 提供 `mc-p2p/justfile`/`build-all.sh` 一键构建两个子项目。

## 假设与决策

1. 采用 Paper 专用服务端 ⇒ 端口来自 `server.properties`，插件不做动态开端口（"自动开房"=自动确保网络节点）。房主以客户端连自己的 Paper 或经 localhost 连接。
2. 集成统一走 ez-mc daemon HTTP 桥；插件与 mod 均零 Java 第三方运行时依赖（网络用 JDK `HttpURLConnection` / 手写 SOCKS5），避免 Gradle 依赖拉取与兼容性爆炸。
3. Fabric mod 适配**单一目标 MC 版本**（计划内固定并注明，Mixin 注入点按该版本写）——这是最不可泛化的部分，优先保证可用。
4. `servers.dat` / 房间码解析在 Java 侧另做一份最小实现，不依赖 Node 运行（mod 可能在无 Node 环境提示安装 ez-mc）。
5. 首次使用：插件/mod 需检测本机 ez-mc daemon；未就绪时返回友好提示（`ezmc daemon` 未启动），不静默失败。

## 验证步骤

1. **daemon**：`npm start daemon -- --port 29876`；`curl localhost:29876/health` 返回 ok；`curl -X POST localhost:29876/host -d '{"name":"t"}'` 返回 `roomCode/ip/pid` 且 `easytier-core` 进程存在。
2. **插件**：本地起 Paper 服务端并放插件 jar：启动日志显示"ez-mc 节点在线，虚拟 IP…"，控制台/聊天输出 `虚拟IP:25565` 与房间码；`/ezp status` 命中 daemon。
3. **mod（单元级）**：用 `curl` 走本地 SOCKS5 模拟验证：`curl --socks5 127.0.0.1:<proxyPort> <虚拟IP>:<server-port>` 能到达房主端（在网络可达前，至少验证握手与转发逻辑走通）。
4. **端到端**：房主 Paper 服 + ez-mc daemon 在线；玩家启动含 mod 的客户端，粘贴房间码一键加入，成功进入 `虚拟IP:server-port`。
5. **回归**：`ez-mc` 原 CLI 流程（`create/share/join/jvm/servers/status/stop`）仍正常（daemon 改动不破坏既有命令）。

## 里程碑（实施顺序）

1. ez-mc daemon HTTP 桥（共享地基）
2. Paper 插件
3. Fabric mod
4. 文档 / 一键构建脚本
5. 端到端验证