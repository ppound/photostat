# PhotoStat 2.1.0 — Batch Rename & Index Hygiene

This release focuses on bulk file management: a new batch-rename workflow with full sidecar and index awareness, plus an opt-in orphan sweep on Re-index All for cleaning up after external file changes. Several quieter fixes to copy/move/delete tighten up the file-operations story across both sidecar formats.

## What's New

### Batch Rename

- New **Rename...** button in the results toolbar opens a find/replace dialog.
- **Source toggle:** rename your current **Selected** images or the full **Current results** set (up to OpenSearch's 10K cap) — handy after faceting to a Person, Place, or Tag.
- **Substring or Regex** modes for the find pattern.
- **Live preview table** shows `Current name → New name` with three statuses:
  - **Will rename to <newname>** — eligible
  - **No match** — find string absent, file left alone (greyed out)
  - **Conflict: target exists** or **Conflict: duplicate target** — destination collisions, highlighted red and blocking Apply
- **Progress dialog** during the apply pass so multi-second batches don't look like a no-op; completion summary reports renames, re-indexed count, errors, and preserved analysis-cache count.

### Optional Orphan Sweep on Re-index All

- The Re-index All confirmation now offers **"Also remove orphaned index entries"** (unchecked by default).
- When enabled, after re-indexing finishes, OpenSearch documents whose files no longer exist on disk are deleted — useful for cleaning up after files have been renamed, moved, or deleted outside of PhotoStat.
- **Safe against unmounted drives by construction.** Any configured directory that isn't accessible at sweep time (e.g. an external drive that isn't plugged in) is skipped entirely; its entries are preserved regardless of the checkbox state.

### Sidecar Hygiene Across File Operations

- **Move, copy, and delete previously hardcoded `.photostat.json` and ignored `.xmp` sidecars** — they now iterate every sidecar that exists alongside the image, so XMP sidecars finally travel correctly with all file operations.
- **Batch rename uses the same generalized handling** and additionally records the original basename in a new `previousFilenames` list (JSON-only) so sibling RAW files can still be located later. The JSON sidecar is created just for this if it didn't exist.

### Analysis-Cache Preservation Across Rename

The AI analysis hash includes the file path, so a rename would otherwise invalidate the cache and trigger paid re-analysis. The batch-rename action now snapshots each file's cache validity *before* renaming and refreshes the hash at the new path after, so previously-analyzed files don't get re-billed. The completion summary reports how many cache entries were preserved.

## Documentation

- `docs/USER_GUIDE.md` — new **Batch Rename** walkthrough under Managing Files, and a new **Re-index All** subsection covering both modes including the orphan-sweep checkbox and unmounted-drive safety.
- `docs/AI_ANALYSIS.md` — corrected the documented `analysisHash` composition (it includes the file path) and explained how batch rename preserves the cache.
- `docs/TROUBLESHOOTING.md` — new entry for "Stale entries: files renamed, moved, or deleted outside PhotoStat" pointing at the orphan-sweep option.

## Test Coverage

- **115 tests passing**, including the existing `XmpSidecarBackendTest` and `SidecarServiceFacadeTest` suites which still pass against the generalized file-operations and the new `previousFilenames` field in `SidecarData`.

## Upgrading

Drop-in upgrade — no config changes required. Existing `.photostat.json` and `.xmp` sidecars are forward-compatible. The new `previousFilenames` field only appears in sidecars after you use the **Rename...** button.

---

**Full Changelog**: https://github.com/ppound/photostat/compare/v2.0.0...v2.1.0
