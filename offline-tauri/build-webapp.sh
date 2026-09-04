#!/usr/bin/env bash
# Build offline-webapp from an App Inventor source checkout.
# Usage: build-webapp.sh [repository-root]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="${1:-$(cd "$SCRIPT_DIR/.." && pwd)}"

if [ -z "$REPO_ROOT" ] || [ ! -d "$REPO_ROOT/appinventor/appengine" ]; then
  echo "usage: $0 <repository-root>" >&2
  echo "error: expected <repository-root>/appinventor/appengine" >&2
  exit 1
fi

APPINV="$REPO_ROOT/appinventor"
OUT="$REPO_ROOT/offline-webapp"
INDEX_TOOL="$SCRIPT_DIR/tools/generate-index.py"
TEMPLATE_TOOL="$SCRIPT_DIR/tools/package-templates.py"

cd "$APPINV"
ant MakeAuthKey
ant -Dlocal.services=true -Dlocale=en -Drelease=true webapp

mkdir -p "$OUT"
rm -rf "$OUT/ode" "$OUT/static" "$OUT/reference" "$OUT/templates" "$OUT/index.html"
cp -R appengine/build/war/ode "$OUT/ode"
cp -R appengine/build/war/static "$OUT/static"
cp -R appengine/build/war/reference "$OUT/reference"
cp appengine/build/war/favicon.ico "$OUT/"

# Package built-in template archives and a deterministic manifest. Prefer the
# Ant-filtered WAR output, but fall back to the checked-in WAR templates when
# Ant does not copy this static directory.
TEMPLATE_SOURCE="appengine/build/war/templates"
if [ ! -d "$TEMPLATE_SOURCE" ]; then
  TEMPLATE_SOURCE="appengine/war/templates"
fi
python3 "$TEMPLATE_TOOL" "$TEMPLATE_SOURCE" "$OUT/templates"

# Prevent duplicate cache.js evaluation in the GWT install iframe on Chromium/WebView2.
sed -i.bak 's#ode.onScriptDownloaded=function(a){l(function(){m(a)})}#ode.onScriptDownloaded=function(a){if(a\&\&a[0]\&\&a[0].indexOf("sourceURL=ode-0.js")!==-1){var w=window.parent||window;if(w.__gwtCacheLoaded){return}w.__gwtCacheLoaded=true}l(function(){m(a)})}#' "$OUT/ode/ode.nocache.js"
rm -f "$OUT/ode/ode.nocache.js.bak"
python3 "$INDEX_TOOL" appengine/build/war/index.jsp appengine/build/war/ode "$OUT/index.html"

printf 'offline-webapp built at %s\n' "$OUT"
ls "$OUT"
