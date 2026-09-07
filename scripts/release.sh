#!/usr/bin/env bash
# Cloud / Linux GitHub + DogeCloud release. Run from repo root after pushing master.
# Do not set HTTP(S)_PROXY. Do not use --offline or a Windows Gradle home.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

unset HTTP_PROXY HTTPS_PROXY ALL_PROXY http_proxy https_proxy all_proxy || true
export NO_PROXY="*"
export no_proxy="*"

python3 - <<'PY'
from pathlib import Path
import re, sys
text = Path("app/build.gradle.kts").read_text(encoding="utf-8")
code = re.search(r"versionCode\s*=\s*(\d+)", text)
name = re.search(r'versionName\s*=\s*"([^"]+)"', text)
if not code or not name:
    sys.exit("versionCode/versionName not found")
Path(".release_meta").write_text(f"{code.group(1)}\n{name.group(1)}\n", encoding="utf-8")
PY
CODE="$(sed -n '1p' .release_meta)"
NAME="$(sed -n '2p' .release_meta)"
rm -f .release_meta
TAG="v${NAME}"
NOTES_FILE="$ROOT/release-notes.txt"
APK_NAME="sysukcb-${NAME}.apk"
APK_SRC="$ROOT/app/build/outputs/apk/release/app-release.apk"
COS_FILE="$ROOT/.cos_url"

if [[ ! -f keystore.properties || ! -f keystore/kcb-release.jks ]]; then
  echo "missing keystore.properties or keystore/kcb-release.jks" >&2
  exit 1
fi

write_notes() {
  python3 - "$NAME" "$CODE" "$NOTES_FILE" <<'PY'
import re, sys
from pathlib import Path
name, code, out = sys.argv[1], sys.argv[2], sys.argv[3]
text = Path("CHANGELOG.md").read_text(encoding="utf-8") if Path("CHANGELOG.md").is_file() else ""
notes = "Fixes and improvements."
m = re.search(rf"(?ms)^##\s+{re.escape(name)}\s*\r?\n(.*?)(?=^##\s|\Z)", text)
if m:
    body = re.sub(r"(?m)^(versionCode|versionName|cosUrl)\s*=.*\r?\n?", "", m.group(1)).strip()
    if body:
        notes = body
Path(out).write_text(f"versionCode={code}\nversionName={name}\n\n{notes}\n", encoding="utf-8")
PY
}

release_exists=0
if gh release view "$TAG" >/dev/null 2>&1; then
  release_exists=1
  existing="$(gh release view "$TAG" --json body -q .body)"
  if printf '%s\n' "$existing" | grep -q '^cosUrl='; then
    echo "Release $TAG already has cosUrl, skip."
    exit 0
  fi
  echo "Release $TAG exists without cosUrl, upload DogeCloud only."
fi

if [[ "$release_exists" -eq 0 ]]; then
  echo "Building release $NAME ($CODE)..."
  ./gradlew :app:assembleRelease
  if [[ ! -f "$APK_SRC" ]]; then
    echo "missing $APK_SRC" >&2
    exit 1
  fi
  write_notes
  cp -f "$APK_SRC" "$APK_NAME"
  gh release create "$TAG" "$APK_NAME" --title "KcbD $NAME" --latest --notes-file "$NOTES_FILE"
  echo "Created GitHub Release $TAG"
fi

if [[ ! -f "$APK_NAME" ]]; then
  if [[ -f "$APK_SRC" ]]; then
    cp -f "$APK_SRC" "$APK_NAME"
  else
    gh release download "$TAG" --pattern "*.apk" --dir "$ROOT"
    if [[ ! -f "$APK_NAME" ]]; then
      echo "no APK for DogeCloud upload" >&2
      exit 1
    fi
  fi
fi

python3 -c "import boto3" >/dev/null 2>&1 || python3 -m pip install --quiet boto3
export VERSION_NAME="$NAME"
export APK_PATH="$(readlink -f "$APK_NAME")"
export COS_URL_FILE="$COS_FILE"
python3 "$ROOT/scripts/upload_dogecloud.py"

if [[ -f "$COS_FILE" ]]; then
  url="$(tr -d '\r' < "$COS_FILE" | sed -n '1p')"
  if [[ -n "$url" ]]; then
    if [[ ! -f "$NOTES_FILE" ]]; then
      gh release view "$TAG" --json body -q .body > "$NOTES_FILE"
    fi
    python3 - "$NOTES_FILE" "$url" <<'PY'
from pathlib import Path
import sys
path, url = sys.argv[1], sys.argv[2]
text = Path(path).read_text(encoding="utf-8")
if not any(line.startswith("cosUrl=") for line in text.splitlines()):
    lines = text.splitlines()
    insert = 0
    for i, line in enumerate(lines):
        if line.startswith("versionName="):
            insert = i + 1
            break
    lines[insert:insert] = [f"cosUrl={url}"]
    Path(path).write_text("\n".join(lines).rstrip() + "\n", encoding="utf-8")
PY
    gh release edit "$TAG" --notes-file "$NOTES_FILE"
    echo "Wrote cosUrl into Release notes"
  fi
fi

rm -f "$APK_NAME" "$NOTES_FILE" "$COS_FILE"
echo "Local release done: $TAG"
