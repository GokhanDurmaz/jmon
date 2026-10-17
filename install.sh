
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
    echo "JMon command directory is already in PATH."
    ;;
  *)
    SHELL_NAME="$(basename "${SHELL:-/bin/zsh}")"

    case "$SHELL_NAME" in
      zsh)
        SHELL_RC="$HOME/.zshrc"
        ;;
      bash)
        if [[ "$(uname -s)" == "Darwin" ]]; then
          SHELL_RC="$HOME/.bash_profile"
        else
          SHELL_RC="$HOME/.bashrc"
        fi
        ;;
      *)
        SHELL_RC=""
        ;;
    esac

    if [[ -n "$SHELL_RC" ]]; then
      mkdir -p "$(dirname "$SHELL_RC")"
      touch "$SHELL_RC"

      if ! grep -Fq '# JMon CLI PATH' "$SHELL_RC"; then
        {
          echo
          echo '# JMon CLI PATH'
          printf 'export PATH="%s:$PATH"\n' "$BIN_DIR"
        } >> "$SHELL_RC"

        echo "Added JMon to PATH in $SHELL_RC"
      else
        echo "JMon PATH configuration already exists in $SHELL_RC"
      fi

      echo "Open a new terminal or run:"
      echo "  source \"$SHELL_RC\""
    else
      echo "Add $BIN_DIR to your shell's PATH manually."
    fi
    ;;
esac

echo
echo "Verify installation with: jmon --help"


