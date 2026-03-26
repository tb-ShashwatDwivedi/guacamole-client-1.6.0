# tbPAM Guacamole fork — change log and operations notes

This document records modifications applied to **Apache Guacamole 1.6.0** in this workspace (`guacamole-client-1.6.0`, `guacamole-server-1.6.0`), including fixes for UI/regression issues, build hygiene, deployment, and session-recording keystroke defaults.

---

## 1. Web client (`guacamole-client-1.6.0`)

### 1.1 Global notification directive restored

**Problem:** `notification/directives/guacNotification.js` had been overwritten with content that registered `guacClientNotification` on the `client` module instead of the real **`guacNotification`** directive. The global modal in `index.html` (`<guac-notification>`) no longer compiled, so:

- Delete/save confirmation dialogs did not render actionable buttons.
- REST delete/save flows that depend on `guacNotification.showStatus()` appeared to do nothing (no API calls after confirm).

**Change:** Restored the stock Apache implementation: `angular.module('notification').directive('guacNotification', …)` with template `app/notification/templates/guacNotification.html`.

**File:**

- `guacamole/src/main/frontend/src/app/notification/directives/guacNotification.js`

A backup of the previous broken content may exist as `guacNotification.js.old` (optional cleanup).

---

### 1.2 Connection group delete binding

**Problem:** `manageConnectionGroup.html` used `delete-object="deleteConnectionGroup()"`, which does not match the `managementButtons` directive isolated scope binding `delete: '&'`.

**Change:** `delete-object="deleteConnectionGroup()"` → **`delete="deleteConnectionGroup()"`**

**File:**

- `guacamole/src/main/frontend/src/app/manage/templates/manageConnectionGroup.html`

---

### 1.3 Client session menu shortcut (Ctrl+Alt+Shift)

**Problem:** The menu toggle had been changed from **Ctrl+Alt+Shift** to **Ctrl+Alt** only. While the session menu is open, all keyboard events are blocked from the remote session. The easier shortcut increased accidental toggles and could look like “frozen” RDP or missing keystrokes in recordings (keys never sent to guacd).

**Change:** Reverted to upstream behavior:

- Restored `SHIFT_KEYS` and `MENU_KEYS` including Shift.
- `isMenuShortcutPressed` again requires Ctrl, Alt, and Shift.
- Restored the `substituteKeysPressed` JSDoc block to a proper `/** … */` comment.

**File:**

- `guacamole/src/main/frontend/src/app/client/controllers/clientController.js`

**Note:** Other tbPAM-specific tweaks in the same file (e.g. empty `clientMenuActions`, filesystem panel helpers) were left as-is unless separately reverted.

---

### 1.4 Apache RAT — `guac_com.sh` license header

**Problem:** `apache-rat-plugin` failed during `mvn validate` with one unapproved file: `guac_com.sh` (no license header).

**Change:** Added the standard Apache License 2.0 header block after `#!/bin/bash` (same style as other shell scripts in the tree).

**File:**

- `guac_com.sh` (repository root)

---

### 1.5 Build environment — `JAVA_HOME`

**Problem:** `mvn package` failed on `maven-javadoc-plugin` when `JAVA_HOME` was unset.

**Change:** On the build host, added to `/root/.bashrc`:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
```

Interactive shells should `source ~/.bashrc` or open a new terminal before building.

---

### 1.6 Deployment (reference)

**Built artifact:** `guacamole/target/guacamole-1.6.0.war`

**Example deployment:** Stop Tomcat, replace the WAR under `/var/lib/tomcat9/webapps/` (e.g. `tbPAM.war`), remove the exploded directory if replacing, set ownership to the Tomcat user, start Tomcat.

Adjust paths and context name to match your installation.

---

## 2. Guacamole server (`guacamole-server-1.6.0`)

### 2.1 Session recordings — keystroke events default

**Behavior (upstream):** For screen session recordings, **key events are only written to the recording file when `recording-include-keys` is enabled.** The stock default for this parameter was **off** (`false` / `0`), so recordings often contained video (and mouse/touch per other flags) but **no keystroke protocol instructions**, and the web player’s keystroke view stayed empty.

**Change:** Default for **`recording-include-keys`** set to **on** when the connection argument is missing or invalid, so keystrokes are recorded unless an administrator explicitly sets the parameter to `false`.

| Protocol   | File |
|-----------|------|
| RDP       | `src/protocols/rdp/settings.c` — default `0` → `1` |
| SSH       | `src/protocols/ssh/settings.c` — `false` → `true` |
| Telnet    | `src/protocols/telnet/settings.c` |
| VNC       | `src/protocols/vnc/settings.c` |
| Kubernetes | `src/protocols/kubernetes/settings.c` |
| DBSHELL   | `src/protocols/dbshell/settings.c` |

The RDP enum documentation block above `IDX_RECORDING_INCLUDE_KEYS` was updated to describe the new default and the privacy trade-off.

**Security note:** Recording keystrokes can capture passwords and other secrets. Connections that **already** store `recording-include-keys` as `false` in the database keep that value until edited.

**Operational note:** After rebuilding **guacd**, install and restart the service (e.g. `sudo make install`, `sudo systemctl restart guacd`). The web client does not need changes for this server behavior.

---

## 3. Items explicitly not changed in these tasks

- **JDBC / tunnel / live monitoring** edits in `guacamole-auth-jdbc` (Section D–style changes) were not reverted or modified as part of the notification/menu fixes.
- **`authenticationService.js`** MFA/`submittedParamsForError` behavior was not reverted.
- **`index.html`** (license comment / build meta) was not restored.
- **tbPAMPAM** Tomcat context was not redeployed in the documented session unless noted separately.

---

## 4. Rebuild quick reference

| Component | Command (typical) |
|-----------|-------------------|
| Client WAR | `export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 && cd /opt/guacamole-client-1.6.0 && mvn clean package -DskipTests` |
| Server     | `cd /opt/guacamole-server-1.6.0 && make && sudo make install && sudo systemctl restart guacd` |

---

## 5. Related upstream concepts

- **Branding:** Apache documents optional **branding extensions** (`doc/guacamole-branding-example`) instead of forking core notification or menu code.
- **Recording:** `include_keys` in `guac_recording` and `guac_recording_report_key()` — see `src/libguac/guacamole/recording.h` and `src/libguac/recording.c`.

---

*Document generated for the tbPAM Guacamole 1.6.0 fork. Update this file when you add further changes.*
