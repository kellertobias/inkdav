#!/bin/sh
set -eu

apk="${1:?APK path is required}"
expected_version="${2:-}"
signature_mode="${3:-unsigned}"

if [ ! -f "$apk" ]; then
  echo "APK not found: $apk" >&2
  exit 2
fi

if [ -z "${ANDROID_HOME:-}" ]; then
  echo "ANDROID_HOME is required" >&2
  exit 2
fi

build_tools=$(find "$ANDROID_HOME/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)
aapt="$build_tools/aapt"
zipalign="$build_tools/zipalign"
apksigner="$build_tools/apksigner"

for tool in "$aapt" "$zipalign" "$apksigner"; do
  if [ ! -x "$tool" ]; then
    echo "Required Android build tool not found: $tool" >&2
    exit 2
  fi
done

badging=$($aapt dump badging "$apk")
echo "$badging" | grep -F "package: name='de.tobisk.inkdav'" >/dev/null
echo "$badging" | grep -F "sdkVersion:'26'" >/dev/null
echo "$badging" | grep -F "targetSdkVersion:'36'" >/dev/null

if [ -n "$expected_version" ]; then
  echo "$badging" | grep -F "versionName='$expected_version'" >/dev/null
fi

native_libraries=$(unzip -Z1 "$apk" | sed -n 's#^lib/[^/]*/\([^/]*\.so\)$#\1#p' | sort -u)
for library in $native_libraries; do
  unzip -Z1 "$apk" | grep -F "lib/arm64-v8a/$library" >/dev/null
done

$zipalign -c -P 16 4 "$apk"
if [ "$signature_mode" = "signed" ]; then
  $apksigner verify --verbose --print-certs "$apk"
fi

echo "Verified BOOX Note Air5 C APK: Android 15 compatible, ARM64 native libraries present, version ${expected_version:-from manifest}."
