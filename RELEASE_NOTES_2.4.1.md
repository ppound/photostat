# PhotoStat 2.4.1 — Face Crop Fix

A patch release fixing face thumbnails when using the Dockerized face-recognition backend introduced in 2.4.0.

## Fixes

### Docker face crops showed the top-left corner instead of the face

The Docker faces backend downscales each image (to a max of 1600px) before sending it for detection, so InsightFace returns face bounding boxes in the *optimized* image's coordinate space. Those coordinates were stored as-is, but face crops are decoded from the **original** full-resolution file — so a box detected at, say, `(687, 102)` in a 1600px image landed in the top-left of a 5000px original instead of on the face.

Bounding boxes are now scaled back to original-image pixels before they're stored, so crops land on the actual face. The local (non-Docker) backend reads the original file directly and was never affected.

Verified against a real image on a GPU container: the scaled-back box matches a full-resolution detection within a few pixels.

> **If you already detected faces via the Docker backend**, those entries have the wrong coordinates stored and need to be re-detected to repopulate correct crops. New detections are correct immediately.

## Notes

- The client-side image optimization itself was confirmed worthwhile — it makes detection roughly 4–8× faster with ~20× smaller HTTP payloads and no loss of detection/recognition quality at 1600px. The bug was only the missing coordinate mapping, which is now fixed.

## Test Coverage

- **115 tests passing.**

## Upgrading

Drop-in upgrade from 2.4.0. Re-run face detection on any Docker-detected images to fix their crops.

---

**Full Changelog**: https://github.com/ppound/photostat/compare/v2.4.0...v2.4.1
