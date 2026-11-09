#!/bin/bash
# Собирает движок AmneziaWG (Go) для iPhone: ios/vendor/WireGuardKitGo/out/libwg-go.a
# Нужны macOS с Xcode и Go. Запускается в GitHub Actions перед сборкой приложения.
set -euo pipefail

# Проверенная версия amneziawg-apple (MIT). Меняйте только вместе с ios/PacketTunnel/WireGuardKit.
AWG_REPO="https://github.com/amnezia-vpn/amneziawg-apple.git"
AWG_COMMIT="9d5ee60edefa95b933a738dd7cda671dd18021fc"

cd "$(dirname "$0")/.."
VENDOR="$PWD/vendor"
SRC="$VENDOR/amneziawg-apple"
GO_DIR="$VENDOR/WireGuardKitGo"

rm -rf "$SRC" "$GO_DIR"
mkdir -p "$VENDOR"
git clone --quiet "$AWG_REPO" "$SRC"
git -C "$SRC" checkout --quiet "$AWG_COMMIT"
cp -R "$SRC/Sources/WireGuardKitGo" "$GO_DIR"

cd "$GO_DIR"
# Xray LocalVPN не нужен: без него движок меньше и легче укладывается в лимит памяти iOS
rm -f api-xray.go
sed -i '' '/libxray_sockcallback/d; /LibXray/d' wireguard.h
go mod tidy

make PLATFORM_NAME=iphoneos ARCHS=arm64 \
  SDKROOT="$(xcrun --sdk iphoneos --show-sdk-path)" \
  DEPLOYMENT_TARGET_CLANG_FLAG_NAME=miphoneos-version-min \
  DEPLOYMENT_TARGET_CLANG_ENV_NAME=IPHONEOS_DEPLOYMENT_TARGET \
  IPHONEOS_DEPLOYMENT_TARGET=15.0 \
  CONFIGURATION_BUILD_DIR="$GO_DIR/out" \
  CONFIGURATION_TEMP_DIR="$VENDOR/.tmp"

ls -lh "$GO_DIR/out/libwg-go.a"
