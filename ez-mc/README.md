# ezmc — 基于 EasyTier 的 Minecraft P2P 联机工具

免提权 · 免中心服务器 · 直接分享房间码即可联机。基于 [EasyTier](https://github.com/EasyTier/EasyTier) 的 P2P VPN，Minecraft 原版「局域网联机」在广域网也能无障碍游玩。

## 特性

- **免管理员权限**：使用 EasyTier 的 `--no-tun` 用户态模式，无需创建虚拟网卡。
- **免中心服务器**：只依赖 EasyTier 公共共享节点做 NAT 打洞与发现，无需自建服务器、无需公网 IP。
- **一条房间码搞定**：房主生成房间码，好友粘贴即自动配置网络并加群。
- **零配置接入 Minecraft**：自动把房间写入 `servers.dat`、检测游戏端口、生成 JVM 的 SOCKS5 代理参数。
- **跨平台**：Windows / macOS / Linux，`--no-tun` 均无需特权。

## 工作原理

```
房主                                 玩家
┌──────────────┐                ┌──────────────────┐
│ ezmc create  │                │ ezmc join <房间码> │
│ e.t core --no-tun -i 10.1.2.1 │ e.t core --no-tun  │
│  (虚拟IP可访问) │                │   --socks5 1080    │
└──────┬───────┘                └─────────┬────────┘
       │                                SOCKS5 代理
       │   EasyTier 虚拟网络            ┌──────────┐
       └═══════════════(NAT打洞)══════▶ │ 游戏客户端 │
             经公共共享节点发现对端       └──────────┘
```

- **房主**启动 `easytier-core --no-tun -i <虚拟IP>`，对外通过虚拟 IP 可被访问。
- **玩家**启动 `easytier-core --no-tun --socks5 <端口>`，本地开 SOCKS5 代理访问房主虚拟 IP。
- 双方通过 `-p` 指定的公共共享节点完成打洞与对端发现，整个过程无需任何中心服务器。

## 安装

要求：Node.js ≥ 18

```bash
npm install -g .
# 首次使用会自动下载 easytier-core / easytier-cli，也可手动：
ezmc install
```

工具会将状态与二进制存放在 `~/.ezmc/`，不会污染系统目录。

## 快速开始

### 房主（建房）

```bash
ezmc create 好友房间
```

输出类似：

```
✓ 房间「好友房间」已创建，虚拟 IP 10.227.74.1
把下面的房间码分享给好友，让他们加入：
  ezmc://%E5%A5%BD%E5%8F%8B%E6%88%BF%E9%97%B4?secret=...&host=10.227.74.1&peer=...
```

把这个房间码发给好友即可。然后：

1. 在 Minecraft 中点击「**对局域网开放**」。
2. 运行 `ezmc share`，自动从游戏日志捕获监听端口，打印出联机地址 `10.227.74.1:25565` 等。
3. 把地址连同提示一起发给好友。

```bash
# 若日志检测不到，可手动指定端口：
ezmc share --port 25565
```

### 玩家（加入）

```bash
ezmc join "ezmc://%E5%A5%BD%E5%8F%8B%E6%88%BF%E9%97%B4?secret=...&host=10.227.74.1&peer=..."
```

会自动启动本地 SOCKS5 代理（默认 `127.0.0.1:1080`）并打印联机地址。之后按提示操作：

```bash
# 1) 若房主给了地址，写入 servers.dat，进入多人游戏即可看到房间
ezmc servers 10.227.74.1:25565

# 2) 用 SOCKS5 参数启动 Minecraft 游戏客户端（重要！）
ezmc jvm
#   -DsocksProxyHost=127.0.0.1
#   -DsocksProxyPort=1080
#   -DsocksNonProxyHosts=localhost|127.0.0.1
#   -Djava.net.preferIPv4Stack=true
```

把 `ezmc jvm` 输出的参数加到游戏启动器的「JVM 参数」里，然后进入多人模式连接 `10.227.74.1:25565` 即可。

> 皮肤验证、资源下载等本地流量已通过 `socksNonProxyHosts` 绕过代理，不会冲突。

## 命令一览

| 命令 | 说明 |
| --- | --- |
| `ezmc install` | 下载并安装 easytier-core / easytier-cli |
| `ezmc doctor` | 检查环境与连接状态 |
| `ezmc create <name>` | 房主创建房间并生成房间码 |
| `ezmc join <roomcode>` | 玩家加入房间并启动 SOCKS5 代理 |
| `ezmc share` | 房主捕获游戏端口并打印联机地址 |
| `ezmc addr` | 打印当前虚拟 IP |
| `ezmc servers <ip:port>` | 把地址写入 servers.dat（多人游戏可见） |
| `ezmc jvm` | 打印启动 Minecraft 所需的 SOCKS5 JVM 参数 |
| `ezmc status` | 显示节点与对端（打洞）状态 |
| `ezmc stop` | 停止所有连接 |

常用选项：`--force` 替换当前连接；`--peer <uri>` 自定义公共共享节点；`join --proxy-port <port>` 自定义代理端口。

## 自定义公共共享节点

默认使用 `tcp://public.easytier.top:11010`。可修改 `~/.ezmc/config.json`：

```json
{ "peer": "tcp://your-relay.example.com:11010" }
```

## 常见问题

- **`Address in use`**：上一个节点还在运行，先 `ezmc stop` 再用 `--force`。
- **打洞后仍连不上**：运行 `ezmc status` 查看 `peer` 列表，确认双方都在同一网络（`network_name`/`secret` 一致）。不同 NAT 环境需要双方都支持 UDP 打洞，EasyTier 会自动回退到中继模式。
- **`servers.dat` 被覆盖**：每次写入前会备份为 `servers.dat.bak`，可手动还原。

## 目录结构

```
~/.ezmc/
├── config.json    # 用户配置（peer 等）
├── state.json     # 当前活跃连接记录
├── bin/           # easytier-core / easytier-cli
└── logs/          # 各节点运行日志
```

## License

MIT