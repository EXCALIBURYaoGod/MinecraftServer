#!/usr/bin/env bash
# 一键构建：ez-mc daemon 依赖 + 服务端插件(ezp-plugin) + 客户端 mod(ezp-mod)
# 产物：
#   plugin/build/libs/ezp-1.0.0.jar
#   mod/build/libs/ezp-mod-1.0.0.jar
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GRADLE_MOD="/opt/gradle-9.5.0/bin/gradle"   # Fabric Loom 1.17.x 要求 Gradle 9.x

echo "==> [1/3] 安装 ez-mc 依赖（commander 等）"
(cd "$ROOT/../ez-mc" && [ -d node_modules ] || npm install)

echo "==> [2/3] 构建服务端插件（ezp-plugin, Java 21 / Paper 1.21.4）"
(cd "$ROOT/plugin" && gradle build -x test --console=plain)

echo "==> [3/3] 构建客户端 mod（ezp-mod, Fabric 1.21.4）"
(cd "$ROOT/mod" && "$GRADLE_MOD" build -x test --console=plain)

echo
echo "构建完成："
ls -lh "$ROOT/plugin/build/libs/ezp-1.0.0.jar"
ls -lh "$ROOT/mod/build/libs/ezp-mod-1.0.0.jar"