#!/bin/bash
# 触发 AndroidMirror GitHub Actions 远程编译 (release APK)
# 用法: bash trigger-build-androidmirror.sh
#
# 网络说明:
#   api.github.com 国内直连容易超时，脚本默认通过 gh-proxy.com 代理 curl 请求。
#   如果你的网络可以直接访问 GitHub API，设置 USE_PROXY=false 即可。
# 依赖: git credential store 中有 github.com 的凭据（Ethan-ai-sudo）

TOKEN=$(echo -e "protocol=https\nhost=github.com" | git credential fill | grep "^password=" | cut -d= -f2-)
if [ -z "$TOKEN" ]; then
  echo "ERROR: 未从 git credential store 找到 GitHub token"
  echo "请确认 ~/.git-credentials 中存在 https://xxx:TOKEN@github.com 格式的记录"
  exit 1
fi

# 网络代理（api.github.com 国内直连常超时）
USE_PROXY="${USE_PROXY:-true}"
if [ "$USE_PROXY" = "true" ]; then
  PROXY="--proxy https://gh-proxy.com"
  echo "[网络] 使用 gh-proxy.com 代理"
else
  PROXY=""
  echo "[网络] 直连 GitHub API"
fi

echo "触发 AndroidMirror 编译..."
RESPONSE=$(curl -s $PROXY \
  -X POST \
  -H "Authorization: Bearer $TOKEN" \
  -H "Accept: application/vnd.github+json" \
  -H "X-GitHub-Api-Version: 2022-11-28" \
  https://api.github.com/repos/Ethan-ai-sudo/AndroidMirror/actions/workflows/android.yml/dispatches \
  -d '{"ref":"main"}')

if [ -z "$RESPONSE" ]; then
  echo "OK: AndroidMirror 编译已触发"
  echo "  查看: https://github.com/Ethan-ai-sudo/AndroidMirror/actions"
else
  echo "响应: $RESPONSE"
fi