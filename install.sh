
#!/usr/bin/env bash
set -Eeuo pipefail

REPO="${JMON_REPO:-GokhanDurmaz/jmon}"
INSTALL_ROOT="${JMON_INSTALL_ROOT:-$HOME/.local/opt/jmon}"
BIN_DIR="${JMON_BIN_DIR:-$HOME/.local/bin}"
ASSET_NAME="jmon.tar.gz"

for cmd in curl tar java; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "Error: '$cmd' is required but was not found." >&2
    exit 1
  fi
done

JAVA_VERSION_OUTPUT="$(java -version 2>&1 | head -n 1)"
if ! java -version 2>&1 | grep -Eq 'version "((1[7-9])|([2-9][0-9]))([.]|")'; then
  echo "Error: JMon requires Java 17 or later." >&2
  echo "Detected: $JAVA_VERSION_OUTPUT" >&2
  exit 1
fi

if [[ "$(uname -s)" != "Darwin" && "$(uname -s)" != "Linux" ]]; then
  echo "Error: JMon installer supports macOS and Linux only." >&2
  exit 1
fi

TMP_DIR="$(mktemp -d)"
cleanup() {
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

DOWNLOAD_URL="https://github.com/${REPO}/releases/latest/download/${ASSET_NAME}"

echo "Downloading JMon from ${REPO}..."
if ! curl --fail --location --silent --show-error \
  "$DOWNLOAD_URL" -o "$TMP_DIR/$ASSET_NAME"; then
  echo "Error: Could not download JMon." >&2
  echo "Make sure the project has a published GitHub Release." >&2
  exit 1
fi

mkdir -p "$TMP_DIR/extracted"
tar -xzf "$TMP_DIR/$ASSET_NAME" -C "$TMP_DIR/extracted"

PACKAGE_DIR="$TMP_DIR/extracted/jmon"
LAUNCHER="$PACKAGE_DIR/bin/jmon"

if [[ ! -x "$LAUNCHER" ]]; then
  echo "Error: Release archive does not contain an executable JMon launcher." >&2
  exit 1
fi

mkdir -p "$INSTALL_ROOT" "$BIN_DIR"
STAGED_DIR="${INSTALL_ROOT}.new"

rm -rf "$STAGED_DIR"
mkdir -p "$STAGED_DIR"
cp -R "$PACKAGE_DIR/." "$STAGED_DIR/"
chmod +x "$STAGED_DIR/bin/jmon"

rm -rf "$INSTALL_ROOT"
mv "$STAGED_DIR" "$INSTALL_ROOT"

ln -sfn "$INSTALL_ROOT/bin/jmon" "$BIN_DIR/jmon"

echo
echo "JMon was installed successfully."
echo "Install location: $INSTALL_ROOT"
echo "Command: $BIN_DIR/jmon"
echo

case ":$PATH:" in
  *":$BIN_DIR:"*)
    echo "Run: jmon --help"
    ;;
  *)
    echo "Add the following directory to your PATH:"
    echo "  $BIN_DIR"
    echo
    echo "For zsh, add this line to ~/.zshrc:"
    echo "  export PATH=\"$BIN_DIR:\$PATH\""
    echo
    echo "Then reload your shell:"
    echo "  source ~/.zshrc"
    ;;
esac

