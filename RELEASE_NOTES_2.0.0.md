# PhotoStat 2.0.0 — The XMP Interop Release

This is a major release focused on making PhotoStat's metadata portable. Rating, tags, persons, place, and analysis state can now live in industry-standard **XMP sidecar files** that Lightroom, Bridge, digiKam, ExifTool, and other XMP-aware tools can read and write. No more vendor lock-in.

## What's New

### XMP Sidecar Support

- New `.xmp` sidecar backend alongside the existing `.photostat.json` format, using the Adobe XMP Core library.
- Field mapping uses standard namespaces wherever possible:
  - `rating` → `xmp:Rating` (numeric, per XMP spec)
  - `tags` → `dc:subject`
  - `persons` → `Iptc4xmpExt:PersonInImage`
  - `place`, `analysisHash`, `cloudUploads` → custom `photostat:` namespace (free-form place values like "Restaurant" don't fit IPTC City)
- Sidecar path convention is `image.jpg.xmp` (append, not replace) so JPEG and RAW of the same basename don't collide.
- Settings → **Sidecars** lets you pick the format: **JSON only**, **XMP only**, or **Both**. A **Read both on load** toggle lets PhotoStat fall back to the other format if the primary isn't found — useful during migration.

### JSON → XMP Migration Button

- New **Convert JSON sidecars to XMP** button on the Index tab for one-shot migration of existing collections.
- Preserves every field including `analysisHash` and `cloudUploads`, so no re-analysis is triggered.
- Optional "delete JSON after conversion" flag.

### Fixes Uncovered While Wiring Up XMP

- **`xmp:Rating` now writes the correct numeric form.** PhotoStat stores ratings internally as asterisk strings (`"*"` .. `"*****"`), and previously was writing those literal asterisks into `xmp:Rating` — which the XMP spec defines as a Real in `[-1..5]`. External tools couldn't read PhotoStat ratings. Fixed at the XMP backend boundary with full tolerance for Lightroom-style floats (`"3.0"`), zero/rejected values, and legacy asterisk files written before this fix.
- **AI-generated "persons" now route into tags.** Descriptive AI output like `"elderly man"` or `"woman in red dress"` doesn't match `Iptc4xmpExt:PersonInImage`'s named-people semantic. The `persons` field is now reserved for **face recognition and manual entry**. The default analysis prompt was updated to include people descriptions in tags instead.

> **Upgrading?** After installing 2.0, open Settings → AI Analysis and click **Reset to Default** on the prompt to pick up the new people-description wording. Note that this invalidates your analysis cache — see `docs/AI_ANALYSIS.md` for the full workflow.

### UX

- The Index tab's **Delete All Documents** button is renamed to **Clear Index** with a tooltip clarifying it only clears the OpenSearch index — your files and sidecars are not touched.

## Why 2.0?

Under the hood, this release is backward-compatible — JSON sidecars still work, and your existing `.photostat.json` files need no migration. The version bump signals two things:

1. **A new default-worthy sidecar format.** XMP interop is a big enough shift in how PhotoStat stores metadata that it deserves a marketing bump.
2. **Behavioral fix for AI persons.** If you relied on AI-generated person descriptions appearing in the `persons` facet, they will now appear in `tags` instead. This is the closest thing to a behavior break in the release.

## Documentation

- `docs/USER_GUIDE.md` — expanded Sidecar Files section with format comparison, field mapping table, and Convert button walkthrough
- `docs/AI_ANALYSIS.md` — new "Picking Up an Updated Default Prompt" subsection explaining the Reset to Default workflow
- `docs/CONFIGURATION.md` — new Sidecar Settings section (`sidecar.format`, `sidecar.read_both`)

## Test Coverage

- **115 tests passing**, including 17 new `XmpSidecarBackendTest` cases and 12 new `SidecarServiceFacadeTest` cases covering round-trip, format selection, read-both fallback, partial update merges, and JSON→XMP conversion.

---

**Full Changelog**: https://github.com/ppound/photostat/compare/v1.9.15...v2.0.0
