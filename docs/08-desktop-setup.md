# 08 — Desktop setup (one time, about 1–2 hours)

Machine: a Windows desktop with plenty of RAM. Phone: a recent Android phone.
RAM matters most here. The GPU only speeds up the emulator's rendering. Scanning itself runs on the phone.

## 0. Disk and paths
- Free space needed: about **40 GB** (Android Studio, SDK, emulator image, Gradle cache, OpenCV).
- If C: is small, put the SDK, Gradle cache, and emulator images on another drive (step 2).
- Copy this plan folder to **`D:\dev\paperless`** (or any short path **without spaces**).
- Enable Windows long paths (admin PowerShell):
  `New-ItemProperty -Path HKLM:\SYSTEM\CurrentControlSet\Control\FileSystem -Name LongPathsEnabled -Value 1 -PropertyType DWORD -Force`
- Optional but speeds builds up: add a Windows Defender exclusion for `D:\dev`, the Gradle cache folder, and the SDK folder.

## 1. Install
| Tool | How | Check |
|---|---|---|
| Git | git-scm.com (includes Git Bash, which Claude Code uses) | `git --version` |
| Android Studio (latest stable) | developer.android.com/studio → in the wizard choose Standard; install the SDK, Platform-Tools, an emulator, and one phone system image (x86_64) | Opens fine |
| SDK extras | SDK Manager → SDK Tools: **Google USB Driver**, Android SDK Command-line Tools | — |
| Node.js LTS | nodejs.org (Codex CLI and `codex-usage.sh` need it) | `node -v` |
| Codex CLI | `npm i -g @openai/codex` → `codex login` (ChatGPT account) | `codex --version`; `bash tools/codex-usage.sh` prints usage |
| Claude Code | install per docs.claude.com → `claude` → log in | `claude --version` |
| Python 3.12 | python.org (tick "Add to PATH") | `python --version` |
| scrcpy (optional, nice) | github.com/Genymobile/scrcpy (mirrors the phone screen on the desktop) | `scrcpy` |
| claude-nap (optional) | a small personal tool that pauses and resumes at the usage limit | — |

## 2. Environment variables (System Properties → Environment Variables → User)
```
ANDROID_HOME      = D:\Android\Sdk          (or wherever the SDK went)
GRADLE_USER_HOME  = D:\.gradle              (keeps the cache off C:)
ANDROID_AVD_HOME  = D:\Android\avd           (optional, emulator images)
JAVA_HOME         = C:\Program Files\Android\Android Studio\jbr
PATH += %ANDROID_HOME%\platform-tools ; %ANDROID_HOME%\emulator ; %JAVA_HOME%\bin
```
Open a **new** terminal afterwards. Check: `adb version`, `java -version`.

## 3. The phone
1. Settings → About phone → tap **Build number** 7 times → Developer options are now on.
2. Developer options → **USB debugging** ON. (Optional: **Wireless debugging** ON, so you can work without the cable: `adb pair <ip:port>` then `adb connect <ip:port>`.)
3. Plug it into the desktop → accept the "Allow USB debugging" prompt (tick *Always allow*).
4. `adb devices` should list the phone as `device`.
5. Optional: Developer options → "Stay awake" while charging, which helps long test runs.

## 4. Python lab environment (inside the project, after copying)
```powershell
cd D:\dev\paperless
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install opencv-python numpy pillow reportlab pypdf scikit-image matplotlib
```
(`scikit-image` is for SSIM metrics; `matplotlib` makes the contact sheets.)

## 5. Git
```powershell
cd D:\dev\paperless
git init
git add . && git commit -m "Plan docs"
# optional: create a PRIVATE GitHub repo and push
```
Add a `.gitignore` in S1 (Claude does it): `build/`, `.gradle/`, `.venv/`, `local.properties`, `test-data/scanner/real/`, `work/`, `*.keystore`.

## 6. Gradle tuning (Claude adds this in S1; listed here for reference)
`gradle.properties`:
```
org.gradle.jvmargs=-Xmx6g -XX:+UseParallelGC
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.configuration-cache=true
kotlin.daemon.jvmargs=-Xmx3g
```

## 7. Sanity check before S1
In Android Studio: New Project → *Empty Activity* (Compose) → Run on the phone → you see "Hello Android". Then close it. Claude builds the real project from scratch in S1. This step just proves the toolchain and the phone connection work.

## 8. Memory and preferences on the new machine
Claude's auto-memory from another machine doesn't come along. That's fine: every rule that matters (orchestration, worker counts, usage gates, phone notifications, Sonnet-only subagents) is written into **`CLAUDE.md`** in this folder, which Claude Code loads automatically.
