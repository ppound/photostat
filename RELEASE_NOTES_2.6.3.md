# PhotoStat 2.6.3 — Guided Setup, Apple Silicon, and Security Fixes

This release makes the Docker backends set themselves up. A first-run wizard installs Docker, starts the engine, and brings up the services for you, and a new **Services** tab starts and stops the whole backend from one place.

It also closes a network exposure in the shipped Compose files and publishes the CPU images for Apple Silicon. **Please read the Security and Upgrading sections** — one fix needs your containers recreated to take effect.

> **Note on 2.6.2:** that version was tagged but never released — a bug that stopped the macOS installer from finding Docker was caught first. Everything intended for 2.6.2 is included here. Upgrading from 2.6.1 gets you all of it.

## Security

### Backend ports were reachable from your network

The bundled Compose files published every service port with a bare mapping such as `"9200:9200"`, which Docker binds to **all interfaces**, not just your machine. OpenSearch ships here with `plugins.security.disabled=true`, and the faces, analysis, and aesthetic services accept unauthenticated requests. On any shared network — an office, a café, a hotel — that left your entire photo index readable, writable, and deletable by anyone who could reach the port, and the AI services open to arbitrary requests.

Every port is now published on `127.0.0.1`, so only the machine running them can connect. A test in the build fails if that ever regresses.

**This does not apply retroactively to containers you have already created** — see Upgrading.

### Image tags are pinned to the release

The Compose files referenced floating `:cpu` / `:gpu` tags, so a compromise of the registry or CI could have changed what an already-installed version runs. They now pin `2.6.3-cpu` / `2.6.3-gpu`.

### Compose file updates now reach existing installs

Neither fix above would have shipped to anyone already running PhotoStat: the live `~/.photostat/docker-compose.yml` was only written when it was missing. It is now also refreshed when it still matches the previously shipped `.dist` copy — meaning you never edited it, so nothing is lost. Genuinely customised files are still left alone, and PhotoStat logs a warning naming each port that is still exposed.

## New: First-run setup wizard

PhotoStat now offers to set up its backends the first time it starts:

1. Checks whether Docker is installed, and offers to install Docker Desktop via `winget` (Windows) or a Homebrew cask (macOS)
2. Starts the Docker engine if it isn't running
3. Lets you choose a CPU or GPU profile and which services to run
4. Downloads the images and starts everything

Installing Docker Desktop changes machine-wide settings, so the wizard lists exactly what it alters — WSL 2 and Virtual Machine Platform on Windows, a service running as SYSTEM, `docker-users` group membership, login autostart — links Docker's licence terms, and does nothing until you tick an explicit consent box. It warns if VirtualBox or VMware Workstation is installed, since WSL 2 can affect them. **PhotoStat never restarts your machine.**

All of it is optional. Cancel and PhotoStat works as before against your own OpenSearch, with the AI features in local Python mode or off.

## New: Services tab

Day-to-day control of the backends, without a terminal:

- Docker engine state, with a button to start it
- **Start All** / **Stop All**, plus a Start/Stop button per service
- Container state and health per service
- **Check for Image Updates** to pull newer images
- A log showing exactly what Docker is doing
- Optional **start when PhotoStat opens** and **stop when PhotoStat closes** (both off by default)

Reopen the wizard any time from **Services → Setup...**.

## Fixes

### macOS: "Docker is not installed" when launched from the installer

The app installed from the `.dmg` reported that Docker was missing on machines where Docker Desktop was installed and running — while the same build launched with `java -jar` from a terminal worked fine.

A macOS app launched from Finder does not inherit your shell's `PATH`; it gets only `/usr/bin:/bin:/usr/sbin:/sbin`, which excludes `/usr/local/bin` where Docker Desktop puts its CLI. PhotoStat now looks for the Docker executable in the locations the installers actually use, on all three platforms. Homebrew detection had the same defect and is fixed too.

### Apple Silicon: "no matching manifest for linux/arm64/v8"

The backend images were built only for Intel/AMD, so pulling them on an M-series Mac failed. The publish workflow never specified a platform list, so every image was amd64-only.

The CPU images are now built for **linux/amd64 and linux/arm64** and merged into a single multi-arch tag, so `docker pull` picks the right one automatically. The GPU images remain Intel-only by design — they are CUDA builds, and CUDA does not exist on Apple Silicon; Macs should use the CPU profile.

### Indexing errors now explain themselves

Indexing on a machine whose Docker disk had filled up reported only `Indexing error: Forbidden access`. The real cause was entirely diagnosable: OpenSearch stops accepting writes once its disk passes the 95% flood-stage watermark and marks every index read-only — and it does not lift that block by itself when space is freed.

PhotoStat now explains a full disk (naming the Docker disk image, since "disk full" naturally reads as your photo drive), a tripped circuit breaker, an unreachable server, and rejected credentials. See Troubleshooting for how to clear the block.

### Index tab falsely reported "Cannot connect to OpenSearch"

The document count was read only when the tab was built, when indexing finished, and after deleting a directory. A single failure at startup left that message on screen for the rest of the session — even while indexing was plainly working. Starting OpenSearch as a container makes this far more likely, since PhotoStat may be starting it as the tab is being built.

The count is now retried while OpenSearch comes up, re-read whenever you select the Index tab, and the failures are told apart instead of all claiming a connection problem. A fresh install with nothing indexed yet now correctly reads **Documents indexed: 0**, where it previously claimed it could not connect.

### Docker errors now say something useful

A failed pull reported only `exit code 18`. PhotoStat now explains architecture mismatches, a stopped engine, port conflicts (naming the file to edit), a full disk, a missing image, and a registry blocked by a proxy.

## Under the Hood

- New `DockerService` wrapping the Docker CLI: engine detection that separates "installed" from "running", Compose v2/v1 resolution, and pull/up/start/stop/down/status against `~/.photostat`.
- New `DockerInstallService`, which delegates installation to the platform package manager so the bits come from Docker's own servers. Linux stays manual, since Docker Engine needs root.
- The publish workflow builds each architecture on its own native runner rather than under QEMU emulation, and fails if a published image is missing an architecture.
- The release workflow creates its GitHub release when absent, instead of failing when a tag is pushed first.
- New `docker` section in `config.json` for setup state, GPU profile, managed services, and the two lifecycle toggles. See [docs/CONFIGURATION.md](docs/CONFIGURATION.md).

## Test Coverage

- **170 tests passing.**

## Upgrading

Drop-in upgrade from 2.6.x. No re-indexing needed.

**Recreate your containers to pick up the loopback fix.** Existing containers keep the network binding they were created with, so upgrading PhotoStat alone does not close the exposure. In the **Services** tab click **Stop All**, then **Start All**. From a terminal:

```bash
docker compose -f ~/.photostat/docker-compose.yml down
docker compose -f ~/.photostat/docker-compose.yml up -d
```

Named volumes are untouched either way, so your index and downloaded models survive.

**If you edited `~/.photostat/docker-compose.yml`**, PhotoStat preserves it and will not apply the fix for you. Compare it against `docker-compose.dist.yml` beside it, and prefix each port mapping with `127.0.0.1:`. PhotoStat logs a warning at startup naming any port still published on all interfaces.

**Apple Silicon users** get native arm64 images for the first time. If you previously worked around this with a `platform: linux/amd64` line, remove it to stop running under emulation.

**Watch your Docker disk.** The backend images and their model weights are several GB, and Docker's disk image is a fixed size. If OpenSearch stops accepting writes, see [Troubleshooting](docs/TROUBLESHOOTING.md#opensearch-is-read-only--disk-usage-exceeded-flood-stage-watermark).

---

**Full Changelog**: https://github.com/ppound/photostat/compare/v2.6.1...v2.6.3
