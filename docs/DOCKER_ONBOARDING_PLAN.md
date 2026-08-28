# Docker Onboarding & One-Click Services — Implementation Plan

Branch: `docker-setup-wizard`

## Goal

A user installs PhotoStat from the MSI/DMG and gets a working full stack —
OpenSearch plus the faces/analysis/aesthetic backends — without reading
`docker/README.md` or typing a compose command. After setup, the whole backend
starts and stops from one button.

## Scope decision

We are **not** building a single installer binary that bundles Docker itself.
That path is blocked by Docker Desktop redistribution terms, `jpackage`'s
inability to chain installers or run custom actions, WSL 2 reboots on Windows,
and macOS `.pkg` signing/notarization requirements.

Instead the installers stay exactly as they are (they already bundle a JRE), and
all orchestration moves *into the app*. "Install Docker" becomes a guided
one-click action with a UAC prompt rather than a silent bundled install.

## What already exists

| Piece | Where |
|---|---|
| JRE bundled into MSI/DMG | `.github/workflows/release.yml` (`jpackage`) |
| Prebuilt images on GHCR | `.github/workflows/docker-publish.yml` |
| Compose file deployed to `~/.photostat` | `ConfigService.extractBundledComposeFiles()` (`ConfigService.java:67`) |
| Per-service `/health` checks | `FaceRecognitionService:155`, `ImageAnalysisService:689`, `AestheticService:86` |
| External-CLI wrapper pattern to copy | `RcloneService.java` |
| OpenSearch reachability check | `OpenSearchService.isConnected()` (`:133`) |

Nothing in the codebase currently shells out to `docker`. That is the gap.

---

## Phase 1 — `DockerService` (~1 day)

New `src/main/java/com/photostat/services/DockerService.java`, singleton,
modeled on `RcloneService`: `ProcessBuilder`, `Consumer<Progress>` callbacks,
`volatile Process currentProcess` for cancellation, `LoggingService` for the
command line.

Detection:
- `isCliInstalled()` — `docker --version`, exit 0
- `isDaemonRunning()` — `docker info`, exit 0, short timeout
- `resolveComposeCommand()` — prefer `docker compose` (v2 plugin), fall back to
  `docker-compose` (v1 standalone), cache the result

Compose operations, all against `~/.photostat/docker-compose.yml` and the GPU
overlay when configured:
- `composePull(services, progressCallback)` — line-parsed progress
- `composeUp(services, progressCallback)` — `up -d`
- `composeStop()` / `composeStart()` — the daily path; faster than `down`/`up`
- `composeDown()` — teardown; named volumes survive, so model weights persist
- `status()` — `docker compose ps --format json` parsed into per-service state

Daemon startup:
- `startEngine(progressCallback)` — launch Docker Desktop
  (`C:\Program Files\Docker\Docker\Docker Desktop.exe` on Windows,
  `open -a Docker` on macOS, `systemctl --user start docker` / instructions on
  Linux), then poll `docker info` until ready or a 90s timeout

Extract a small command-runner seam so command construction and `ps` JSON
parsing are unit-testable without a Docker daemon present.

## Phase 1.5 — Harden the compose defaults (done)

Slotted in ahead of the UI work: automating setup should propagate a safe
configuration, not an exposed one. Both fixes benefit existing users too.

- **Loopback port binding.** Every published port in both compose files is now
  `127.0.0.1:<port>:<port>`. Docker's default for `"9200:9200"` is `0.0.0.0`,
  which put an unauthenticated OpenSearch — and the whole photo index — on the
  user's local network. Verified: the daemon's own bind error reports
  `0.0.0.0:9200`, and OpenSearch answered unauthenticated on a non-loopback
  address before the change.
- **Version-pinned image tags.** `:cpu`/`:gpu` floating tags meant a registry or
  CI compromise could retroactively change what an installed version runs.
  Pinned to `2.6.1-cpu` / `2.6.1-gpu`, both confirmed published on GHCR.
- **Upgrade path.** `ConfigService.extractBundledComposeFiles()` previously
  wrote the live compose file only when absent, so neither fix would ever have
  reached an existing install. It now also rewrites the live file when it still
  matches the *previously shipped* dist copy — i.e. the user never edited it.
  Genuinely customised files are preserved, and warn per exposed port.

## Phase 2 — Services tab (done)

`src/main/java/com/photostat/ui/ServicesPanel.java`, added to the `MainWindow`
TabPane alongside the existing eight tabs, with `setUserData("services")` to
match the existing convention and a `refresh()` on tab activation.

Health probes reuse the existing `/health` code rather than adding HTTP calls.
`FaceRecognitionService` and `ImageAnalysisService` gained small public
`isDockerServiceHealthy()` wrappers over their existing private health fetches,
so the panel can report on the *container* even when the app is configured for
local Python — `isPythonAvailable()` / `isMoondreamAvailable()` branch on mode
and would otherwise probe the wrong backend.

Verified by driving the real panel through a full cycle against a scratch
compose project: rows went `not created` → `Up` → `Exited` with the per-row
buttons flipping `Start` → `Stop` → `Start`.

Layout:
- Header: engine state (Docker not installed / stopped / running) + **Start All**,
  **Stop All**, **Check for Image Updates**
- One row per service (opensearch, faces, analysis, aesthetic): name, port,
  state dot, individual Start/Stop, and a health indicator driven by the
  *existing* `/health` methods rather than new HTTP code
- Collapsible log area fed by the progress callbacks

All compose calls run on a JavaFX `Task`; UI updates via `Platform.runLater()`.

## Phase 3 — First-run setup wizard (done)

`SetupWizardDialog` (Welcome → Docker → Engine → Services → Download and start →
Finished), shown from `App.start()` after the main window paints when
`docker.setup_completed` is false, and reopenable from the Services tab's
**Setup...** button. `DockerInstallService` handles detection and installation.

Safety decisions from the risk review, all implemented:

- **No silent install.** The Docker step lists every system change — WSL 2 and
  Virtual Machine Platform, a SYSTEM service, `docker-users` membership,
  login autostart — plus a licence link, and the install button stays disabled
  until the user ticks an explicit consent box.
- **Never reboots.** Nothing shells out to a restart; when one is needed the
  wizard says so and tells the user to reopen it afterwards. A unit test asserts
  the install commands contain no reboot or `sudo`.
- **Conflict pre-flight.** VirtualBox and VMware Workstation are detected and
  warned about before WSL 2 is enabled.
- **Declining is free.** Plain Cancel leaves `setup_completed` false so the
  wizard returns; only Finish, or an explicit "Don't show this again", suppresses
  it. The welcome step says the containers are optional.

The `docker` config section from phase 4 was added here, since the wizard needs
`setup_completed`, `gpu` and `services` to persist. `DockerService` now reads
`gpu` and `docker_path` from it rather than in-memory fields. Phase 4 is left
with wiring `auto_start_on_launch` and `stop_on_exit` into the app lifecycle.

### Original plan

## Phase 3 — First-run setup wizard (~1.5 days)

New `src/main/java/com/photostat/ui/SetupWizardDialog.java`. Steps:

1. **Welcome** — what the backends do, and that they are optional
2. **Docker check** — if missing, offer one-click install:
   - Windows: `winget install --id Docker.DockerDesktop -e --accept-package-agreements --accept-source-agreements`
   - macOS: `brew install --cask docker` when brew is present
   - Fallback everywhere: open <https://www.docker.com/products/docker-desktop/>
     using the existing open-external pattern at `SettingsDialog.java:1912`
   - Handle "installed but needs a reboot" by saving state and resuming next launch
3. **Engine start** — Start Docker Desktop, poll until ready
4. **Profile** — CPU or GPU (NVIDIA), and which optional services to manage
5. **Pull** — cancellable, with an explicit size warning (CPU images are several
   GB; GPU images carry torch + CUDA and are far larger)
6. **Start & verify** — `up -d`, then the existing `/health` checks
7. **Done**

Triggered from `App.start()` after `primaryStage.show()` when
`docker.setupCompleted` is false. Re-openable from the Services tab.

## Phase 4 — Config & lifecycle (done)

The config section landed in phase 3, so this phase was only the lifecycle
wiring and its two toggles, both on the Services tab.

- **`auto_start_on_launch`** — starts the configured services on a daemon thread
  after the main window paints, bringing up the Docker engine first if needed.
  Failures are logged, not surfaced: the app works without the containers, and
  an error dialog on every launch would be worse than a retry from the tab.
- **`stop_on_exit`** — the close handler consumes the window event and shows a
  small modal progress window, because `compose stop` sends SIGTERM and waits
  before killing, which takes tens of seconds across four containers. A "Close
  anyway" button abandons the wait, leaving the containers up, which is harmless.

Verified end-to-end against the real `App` with a scratch home: with both flags
on, launching started exactly the configured subset and a genuine window-close
stopped them; with both off, the app left already-running containers untouched
in either direction.

### Original plan

## Phase 4 — Config & lifecycle (~0.5 day)

New `docker` section in `ConfigService`, following the existing
`ensure<X>Section` migration pattern (`ConfigService.java:129-278`):

| Key | Default | Purpose |
|---|---|---|
| `setupCompleted` | `false` | drives the first-run wizard |
| `manageContainers` | `true` | master switch |
| `autoStartOnLaunch` | `false` | start backends when PhotoStat opens |
| `stopOnExit` | `false` | stop backends when PhotoStat closes |
| `gpu` | `false` | apply the GPU overlay compose file |
| `services` | all | which optional services to manage |
| `dockerPath` | `docker` | override for non-PATH installs |

Lifecycle wiring in `App.java`:
- `start()` — wizard on first run; `autoStartOnLaunch` kicks off a background
  start task
- `setOnCloseRequest` (`App.java:82`) — honour `stopOnExit` here rather than in
  `stop()`, so a brief progress dialog can still be shown before FX shuts down

## Phase 5 — Docs, build, test (done)

- `README.md` — Quick Start restructured to install PhotoStat first and let the
  wizard do the rest, with the manual Docker/OpenSearch route kept in a
  collapsed "advanced" block. Added a Backend Services feature section. Fixed
  the manual OpenSearch `docker run` snippet, which told users to publish 9200
  on all interfaces with security disabled.
- `docker/README.md` — leads with the Services tab and wizard; the compose
  reference follows. Corrected the `.dist.yml` description, which still claimed
  the live compose file is never overwritten (phase 1.5 changed that).
- `docs/USER_GUIDE.md` — new Backend Services section, first-launch text covers
  the wizard, tab list updated to nine.
- `docs/CONFIGURATION.md` — the `docker` section documented and added to the
  example config.
- `docs/TROUBLESHOOTING.md` — new "Backend Services and Docker" section covering
  engine not installed/not running, port conflicts, slow first-run health,
  blocked pulls, edited compose files, and full removal.

Verifying the config docs against a generated `config.json` surfaced a bug from
phase 3: the `docker` section was added to the migration path but not to
`createDefaultConfig()`, so a fresh install never wrote it. Behaviour was
unaffected (the getters fall back to the same defaults) but the keys were
undiscoverable. Fixed and verified on both the fresh-install and upgrade paths.

### Original plan

## Phase 5 — Docs, build, test (~0.5 day)

- Rewrite the Docker section of `README.md:127` around the wizard, keeping the
  manual compose instructions as the advanced path
- Update `docker/README.md` and `docs/TROUBLESHOOTING.md`
- Full `mvn package` + test run, then copy the JAR to `/mnt/c/Users/Paulp/` for
  Windows verification (the real config/index lives there)

**Total: ~5 days.**

---

## Risks and gotchas

- **`docker compose` v2 vs `docker-compose` v1** — must detect, not assume.
- **Docker Desktop startup is slow** (30–90s) and may show its own licence
  dialog on first run. Never block the FX thread; always offer a timeout path.
- **`winget` is absent on older Windows 10 builds** — the download-page fallback
  is mandatory, not optional.
- **Elevation cannot be avoided.** The winget install triggers UAC, and WSL 2
  enablement may force a reboot. The wizard must survive being interrupted.
- **User edits to `~/.photostat/docker-compose.yml` must be preserved.**
  `extractBundledComposeFiles()` deliberately never clobbers the live file, so
  the GPU toggle must apply the overlay `-f docker-compose.gpu.yml` rather than
  rewriting the live compose file.
- **Port conflicts** on 9200/8001/8002/8003 need a clear error, not a silent
  failed start.
- **Linux** needs Docker Engine via the distro package manager with sudo —
  detect and instruct rather than attempting an install.
- **Disk footprint** — worth surfacing before the pull, not after.

## Testing

- Unit: compose command construction, `docker compose ps --format json`
  parsing, version/engine-state parsing, GPU overlay argument assembly.
- Manual: Windows (primary target), with Docker absent, installed-but-stopped,
  and running.
