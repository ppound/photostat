# PhotoStat 2.2.0 — On This Day

This release adds an "On This Day" surface for revisiting photos taken on the same calendar day in previous years — the kind of memory browsing that's familiar from Google Photos and Apple Photos.

## What's New

### On This Day Tab

A new top-level tab shows photos taken on a specific month/day across every year in your collection.

- **Date picker** defaults to today; the year is ignored, so only the month and day are matched.
- **± days window** widens the match to include neighbouring calendar days — useful for trips that spanned several days. Defaults to 0 (exact day only).
- **Year-breakdown header** summarises totals and lists counts per year, e.g. *"23 photos across 5 years on June 1 — 2024 (8), 2023 (5), 2022 (4), …"*.
- **Same table, same shortcuts.** The tab reuses the regular results table, so thumbnails, ratings (1–5, 0 to clear), slideshow, and the detail panel all work exactly as on the Search tab.
- **Auto-refreshes** when you open the tab — today's photos are one click away.

### On This Day Quick Filter on the Search Tab

The same logic is also available as a checkbox inside the **Date Range** section of the Search tab, so it composes with every other filter.

- Combine with people, places, tags, ratings, cameras, lenses — *"On this day + Person: Mom + Place: Cottage"* is one search.
- When the checkbox is on, the regular From/To pickers are disabled (the same-day match overrides them).
- Round-trips through Back/forward search history like any other filter.

## Under the Hood

- The filter expands into a `bool/should` of one `date_taken` range per year (1900–present), so OpenSearch uses the indexed field directly. No scripts, no painless, no surprises.
- The year aggregation that powers the breakdown header is the same one the Charts and Timeline tabs already use — no new index changes.

## Documentation

- `docs/USER_GUIDE.md` — new **On This Day** section, plus a fix to the First Launch tab list (was "six tabs", now lists all eight including Timeline and On This Day).
- `README.md` — On This Day added to the Core Capabilities list.

## Test Coverage

- **115 tests passing**.

## Upgrading

Drop-in upgrade — no config changes, no index changes, no sidecar changes.

---

**Full Changelog**: https://github.com/ppound/photostat/compare/v2.1.0...v2.2.0
