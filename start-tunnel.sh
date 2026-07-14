#!/bin/bash

CONFIG_DIR="$(cd "$(dirname "$0")" && pwd)/.cloudflared"

echo "================================================"
echo "  LAN Chat Server - Cloudflare Tunnel"
echo "================================================"
echo ""
echo "  公网地址: https://chat.atti.cc.cd"
echo "  按 Ctrl+C 停止隧道"
echo ""

cloudflared tunnel --config "$CONFIG_DIR/config.yml" run
