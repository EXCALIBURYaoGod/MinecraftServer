# mc-p2p — Minecraft P2P 联机（服务端插件 + 客户端 Mod）

基于 [ez-mc]（Ez-mc / EasyTier P2P）的一键联机方案。房主侧服务端自动开房并广播地址；玩家侧客户端一键加入并内置 SOCKS 代理直连房主虚拟 IP。三者通过 ez-mc 本地守护（HTTP 桥）协同。

```
┌───────────── 房主机 ─────────────┐        ┌───────────── 玩家机 ─────────────┐
│ Paper 服务端 + ezp-plugin         │        │ MC 客户端 + ezp-mod (Fabric)     │
│  ├ 启动→ POST /host 自动建房        │        │  ├ /ezp join → POST /guest        │
│  ├ GET /status 采集连接类型         │  HTTP   │  ├ 内置 LocalProxyForwarder       │
│  └ broadcast 联机地址+连接类型      │ ◀──────▶ │  ├ Mixin 改写连接目标到本地代理     │
└──────────────┬───────────────────┘   29876  └──────────────┬──────────────────┘
               │  spawn easytier-core                         │  spawn easytier-core
               └──────────  P2P 打洞（direct） / 中继（relay）─────────┘
                              同一虚拟局域网，玩家经虚拟 IP 访问房主
```

## 组件

| 组件 | 目录 | 说明 |
|------|------|------|
| ez-mc 本地守护 | `../ez-mc/src/daemon.js` | 回环 HTTP 桥，暴露 RPC 给插件/mod |
| 服务端插件 | `./plugin` | Paper 1.21.4（Java 21），自动建房+广播 |
| 客户端 mod | `./mod` | Fabric 1.21.4，一键加入+内置 SOCKS 代理 |
| 一键构建 | `./build.sh` | 同时产出插件 jar 与 mod jar |

## daemon 端点（默认 `http://127.0.0.1:29876`）

| 端点 | 方法 | 说明 |
|------|------|------|
| `/health` | GET | 存活探测 `{ok:true}` |
| `/status` | GET | 当前连接状态，含 `linkType: direct\|relay\|unknown` |
| `/room/:name` | GET | 房主房间信息（房间码/虚拟IP/连接类型） |
| `/host` | POST | 建房，body `{name, ip?, peer?}` → `{roomCode, ip, peer, pid}` |
| `/guest` | POST | 加入，body `{roomCode, proxyPort?}` → `{ip, proxyPort, pid, name}` |
| `/stop` | POST | 停止所有节点 `{stopped:n}` |

仅接受回环来源，非回环请求一律 `403`。

## 连接类型显示（增强）

`/status` 通过 `easytier-cli peers` 输出判断节点间直连状态：

- **direct**：`Direct / P2P / DirectConnection / true` 命中 → HUD 绿色「P2P 直接连接」
- **relay**：节点间依赖转发服务器 → HUD 橙色「中继连接」
- **unknown**：节点未连 / 探测失败 → HUD 灰色「连接类型探测中…」

显示位置：
- **客户端 mod**：HUD 左上角常显 `Ezp · 连接类型 · 虚拟IP:端口`；`/ezp status` 命令输出链接类型。
- **服务端插件**：自动建房成功后，向全部玩家广播 `联机地址 …（连接类型：直连(P2P)/中继(relay)）`，并把类型写入 `plugins/ezp/state.yml` 的 `linkType` 字段。

## 构建

```bash
# 一键构建插件 + mod（依赖 npm install + Gradle）
./build.sh

# 单独构建插件（Gradle 8.14.5 / 系统 gradle）
cd plugin && gradle build -x test

# 单独构建 mod（Fabric Loom 需 Gradle 9.5）
cd mod && /opt/gradle-9.5.0/bin/gradle build -x test
```

产物：
- 插件：`plugin/build/libs/ezp-1.0.0.jar` → 放入 Paper 服务端 `plugins/`
- mod：`mod/build/libs/ezp-mod-1.0.0.jar` → 放入客户端 `mods/`

## 使用流程

1. 房主与玩家都先 `npm install && node bin/ezmc.js daemon`（确保本地守护在线）。
2. 房主：启动 Paper 服务端，插件自动 `POST /host` 建房并广播联机地址与连接类型。
3. 玩家：进入游戏，`/ezp join <房间码>` 一键加入；连接后 HUD 显示当前连接类型（直接/中继）。
4. 退出：`/ezp leave` 断开节点并关闭本地代理。

## 命令一览

- 服务端（插件）：`/ezp`（查看状态/连接类型）、`/ezp reload`、`/ezp broadcast`
- 客户端（mod）：`/ezp join <房间码>`、`/ezp leave`、`/ezp status`
- ez-mc（CLI）：`node bin/ezmc.js daemon [--port 29876]`、`host`、`join`、`stop`

[ez-mc]: ../ez-mc