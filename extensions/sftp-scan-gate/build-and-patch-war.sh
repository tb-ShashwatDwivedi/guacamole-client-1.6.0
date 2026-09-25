#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
WAR="$ROOT/client/tbPAM.war"
EXT_DIR="$ROOT/config/guacamole/extensions"
SCAN_JAR="$EXT_DIR/guacamole-sftp-scan-gate.jar"
PATCH_DIR="$SCRIPT_DIR/frontend-patches"
BUILD_DIR="$SCRIPT_DIR/target"
WAR_EXTRACT="$BUILD_DIR/war-extract"
CLASSES_OUT="$BUILD_DIR/classes"

echo "==> Extracting tbPAM.war for compile classpath"
rm -rf "$BUILD_DIR"
mkdir -p "$CLASSES_OUT" "$WAR_EXTRACT"
( cd "$WAR_EXTRACT" && jar xf "$WAR" )

CP="$WAR_EXTRACT/WEB-INF/classes"
for jar in "$WAR_EXTRACT/WEB-INF/lib/"*.jar; do
    CP="$CP:$jar"
done

echo "==> Compiling sftp-scan-gate classes"
find "$SCRIPT_DIR/src/main/java" -name "*.java" > "$BUILD_DIR/sources.txt"
javac -encoding UTF-8 --release 17 -cp "$CP" -d "$CLASSES_OUT" @"$BUILD_DIR/sources.txt"

echo "==> Running scan-hold self-check"
( cd "$ROOT" && java -cp "$CLASSES_OUT:$CP" com.local.guac.sftp.scan.SftpScanHoldSelfCheck )

echo "==> Running hold-panel open self-check"
node "$SCRIPT_DIR/hold-panel-open-selfcheck.js"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "==> Extracting tbPAM.war for patching"
( cd "$WORK" && jar xf "$WAR" )

WAR_CLASSES="$WORK/WEB-INF/classes"
mkdir -p "$WAR_CLASSES/com/local/guac/sftp/scan"
mkdir -p "$WAR_CLASSES/org/apache/guacamole/rest/tunnel"

echo "==> Copying compiled classes into WAR"
rm -rf "$WAR_CLASSES/com/local/guac/sftp/scan"
mkdir -p "$WAR_CLASSES/com/local/guac/sftp/scan"
cp "$CLASSES_OUT/com/local/guac/sftp/scan/"*.class "$WAR_CLASSES/com/local/guac/sftp/scan/"
cp "$CLASSES_OUT/org/apache/guacamole/rest/tunnel/StreamResource"*.class "$WAR_CLASSES/org/apache/guacamole/rest/tunnel/"
cp "$CLASSES_OUT/org/apache/guacamole/rest/tunnel/TunnelResource.class" "$WAR_CLASSES/org/apache/guacamole/rest/tunnel/"
cp "$CLASSES_OUT/org/apache/guacamole/rest/tunnel/ScanHoldResource.class" "$WAR_CLASSES/org/apache/guacamole/rest/tunnel/"
mkdir -p "$WAR_CLASSES/org/apache/guacamole/tunnel"
cp "$CLASSES_OUT/org/apache/guacamole/tunnel/StreamInterceptingTunnel.class" "$WAR_CLASSES/org/apache/guacamole/tunnel/"

echo "==> Applying frontend patches"
cp "$PATCH_DIR/sftp-scan-gate.js" "$WORK/sftp-scan-gate.js"
cp "$PATCH_DIR/sftp-scan-gate.css" "$WORK/sftp-scan-gate.css"

WORK="$WORK" PATCH_DIR="$PATCH_DIR" python3 <<PY
import json
import os
from pathlib import Path

work = Path(os.environ["WORK"])
en_path = work / "translations/en.json"
data = json.loads(en_path.read_text())
data.setdefault("CLIENT", {})["DIALOG_HEADER_FILE_SCAN_WARNING"] = "File scan warning"
en_path.write_text(json.dumps(data, indent=4, ensure_ascii=False) + "\n")
PY

WORK="$WORK" PATCH_DIR="$PATCH_DIR" python3 <<'PY'
import json
import os
import re
from pathlib import Path

work = Path(os.environ["WORK"])
patch_dir = Path(os.environ["PATCH_DIR"])

index_path = work / "index.html"
html = index_path.read_text()
needle = '<script src="templates.js"></script>'
insert = '<script src="templates.js"></script>\n<link rel="stylesheet" href="sftp-scan-gate.css">\n<script src="sftp-scan-gate.js"></script>'
if 'sftp-scan-gate.js' not in html:
    index_path.write_text(html.replace(needle, insert))
elif 'sftp-scan-gate.css' not in html:
    index_path.write_text(html.replace(
        '<script src="sftp-scan-gate.js"></script>',
        '<link rel="stylesheet" href="sftp-scan-gate.css">\n<script src="sftp-scan-gate.js"></script>'))

hold_panel = (
    '<div id="sftp-scan-hold-widget" ng-if="$root.sftpScanHoldPanel.count > 0" '
    'class="sftp-scan-hold-widget" ng-cloak>'
    '<button type="button" class="sftp-scan-hold-badge" '
    'ng-click="$root.toggleSftpScanHoldPanel()" '
    'title="{{$root.sftpScanHoldPanel.count}} file(s) awaiting review">'
    '<svg class="sftp-scan-hold-badge-icon" viewBox="0 0 24 24" aria-hidden="true">'
    '<path fill="#6aaee8" d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8l-6-6zm1 '
    '7V3.5L18.5 9H15zM8 13h8v2H8v-2zm0 4h8v2H8v-2zm0-8h5v2H8V9z"/>'
    '</svg>'
    '<span class="sftp-scan-hold-count">{{$root.sftpScanHoldPanel.count}}</span>'
    '</button>'
    '<div class="sftp-scan-hold-panel" ng-if="$root.sftpScanHoldPanel.open">'
    '<div class="sftp-scan-hold-header">'
    '<div>'
    '<div class="sftp-scan-hold-title">Files awaiting review</div>'
    '<div class="sftp-scan-hold-subtitle">'
    '{{$root.sftpScanHoldPanel.count}} pending</div>'
    '</div>'
    '<button type="button" class="sftp-scan-hold-close" '
    'ng-click="$root.closeSftpScanHoldPanel()" title="Close">&times;</button>'
    '</div>'
    '<div class="sftp-scan-hold-list">'
    '<div class="sftp-scan-hold-entry" ng-repeat="entry in $root.sftpScanHoldPanel.entries">'
    '<strong>{{entry.filename}}</strong>'
    '<div>{{entry.statusLabel}}</div>'
    '<div ng-if="entry.userMessage && entry.userMessage !== entry.statusLabel">'
    '{{entry.userMessage}}</div>'
    '<button type="button" class="sftp-scan-hold-cancel-btn" '
    'ng-click="$root.cancelSftpScanHoldEntry(entry)">Cancel transfer</button>'
    '</div></div></div></div>'
)
html = index_path.read_text()
hold_start = '<div id="sftp-scan-hold-widget"'
if hold_start in html:
    pre, rest = html.split(hold_start, 1)
    body_idx = rest.find('</body>')
    html = pre + hold_panel + '\n' + rest[body_idx:]
else:
    html = html.replace('</body>', hold_panel + '\n</body>')
index_path.write_text(html)

# File selection for header Download (no right-click menu)
guac_bundles = sorted(work.glob('guacamole.*.js'))
click_with_select = (
    'a.isNormalFile(C)&&(K.addClass("normal-file"),I=function(){a.downloadFile(C)});'
    'K.on("click",function(){K.hasClass("focused")?(I(),K.removeClass("focused")):'
    '(K.parent().children().removeClass("focused"),K.addClass("focused"),'
    'a.$apply(function(){a.isNormalFile(C)?a.$root.sftpSelectedFile='
    '{filesystem:a.filesystem,file:C,streamName:C.streamName,name:C.name}:'
    'a.$root.sftpSelectedFile=null}))});'
)
marker = 'a.isNormalFile(C)&&(K.addClass("normal-file"),I=function(){a.downloadFile(C)}'
selectstart = 'K.on("selectstart"'
for bundle in guac_bundles:
    text = bundle.read_text()
    start = text.find(marker)
    end = text.find(selectstart, start) if start >= 0 else -1
    if start < 0 or end < 0:
        print('WARN: guacFileBrowser click handler not found in', bundle.name)
        continue
    bundle.write_text(text[:start] + click_with_select + text[end:])
    print('patched file-select (no contextmenu) in', bundle.name)

# Keep source copy in sync for reference
browser_js = patch_dir / 'guacFileBrowser.js'
browser_html = patch_dir / 'guacFileBrowser.html'
client_html = patch_dir / 'client.html'
if browser_js.exists():
    dest = work / 'app/client/directives/guacFileBrowser.js'
    if dest.parent.exists():
        dest.write_text(browser_js.read_text())
if browser_html.exists():
    dest = work / 'app/client/templates/guacFileBrowser.html'
    if dest.parent.exists():
        dest.write_text(browser_html.read_text())
if client_html.exists():
    dest = work / 'app/client/templates/client.html'
    if dest.parent.exists():
        dest.write_text(client_html.read_text())

def patch_template_cache(tpl, template_path, html):
    key = "$templateCache.put('" + template_path + "',"
    if key not in tpl:
        return tpl
    start = tpl.index(key)
    end = tpl.index("');", start) + 3
    return tpl[:start] + key + json.dumps(html) + tpl[end:]

manager_html = (patch_dir / "guacFileTransferManager.html").read_text()
transfer_html = (patch_dir / "guacFileTransfer.html").read_text()
(work / "app/client/templates/guacFileTransferManager.html").write_text(manager_html)
(work / "app/client/templates/guacFileTransfer.html").write_text(transfer_html)

templates_js = work / "templates.js"
if templates_js.exists():
    tpl = templates_js.read_text()
    tpl = patch_template_cache(tpl, "app/client/templates/guacFileTransferManager.html", manager_html)
    tpl = patch_template_cache(tpl, "app/client/templates/guacFileTransfer.html", transfer_html)
    if browser_html.exists():
        tpl = patch_template_cache(tpl, "app/client/templates/guacFileBrowser.html", browser_html.read_text())
    if client_html.exists():
        tpl = patch_template_cache(tpl, "app/client/templates/client.html", client_html.read_text())
        print('patched Download button into client.html template')
    templates_js.write_text(tpl)
PY

echo "==> Packaging scan support JAR"
mkdir -p "$EXT_DIR"
jar cf "$SCAN_JAR" -C "$CLASSES_OUT" com/local/guac/sftp/scan

echo "==> Repacking tbPAM.war"
( cd "$WORK" && jar cf "$WAR" . )

echo "==> Done. Patched $WAR and created $SCAN_JAR"
