package com.photostat.services;

import com.adobe.internal.xmp.XMPConst;
import com.adobe.internal.xmp.XMPException;
import com.adobe.internal.xmp.XMPMeta;
import com.adobe.internal.xmp.XMPMetaFactory;
import com.adobe.internal.xmp.options.PropertyOptions;
import com.adobe.internal.xmp.options.SerializeOptions;
import com.adobe.internal.xmp.properties.XMPProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Sidecar backend that stores metadata in {@code .xmp} files using the
 * Adobe XMP Core library. Fields are written using standard XMP namespaces so
 * the files are readable by Lightroom, Bridge, digiKam, ExifTool, and other
 * XMP-aware tools.
 *
 * <p>Field mapping:
 * <ul>
 *   <li>{@code rating} → {@code xmp:Rating} (integer)</li>
 *   <li>{@code tags} → {@code dc:subject} (Bag of strings)</li>
 *   <li>{@code persons} → {@code Iptc4xmpExt:PersonInImage} (Bag of strings)</li>
 *   <li>{@code place} → {@code photostat:place} (free-form; PhotoStat's place
 *       field can hold non-geographic values like "Restaurant" or "Beach" that
 *       would not be semantically correct in a standard IPTC City field)</li>
 *   <li>{@code analysisHash} → {@code photostat:analysisHash}</li>
 *   <li>{@code cloudUploads} → {@code photostat:cloudUploads} (Bag of strings)</li>
 * </ul>
 *
 * <p>Sidecar path convention: {@code image.jpg.xmp} (append, not replace) so we
 * never collide when both a JPEG and a RAW of the same basename exist, and so
 * the naming scheme is consistent with the JSON sidecar format.
 */
class XmpSidecarBackend implements SidecarBackend {

    static final String SIDECAR_EXTENSION = ".xmp";

    static final String NS_PHOTOSTAT = "http://photostat.app/xmp/1.0/";
    private static final String PREFIX_PHOTOSTAT = "photostat";

    private final LoggingService logger;

    XmpSidecarBackend() {
        this.logger = LoggingService.getInstance();
        try {
            XMPMetaFactory.getSchemaRegistry().registerNamespace(NS_PHOTOSTAT, PREFIX_PHOTOSTAT);
        } catch (XMPException e) {
            logger.warn("XmpSidecarBackend", "Failed to register photostat namespace: " + e.getMessage());
        }
    }

    @Override
    public Path getSidecarPath(String imagePath) {
        return Path.of(imagePath + SIDECAR_EXTENSION);
    }

    @Override
    public boolean exists(String imagePath) {
        return Files.exists(getSidecarPath(imagePath));
    }

    @Override
    public SidecarService.SidecarData read(String imagePath) {
        Path sidecarPath = getSidecarPath(imagePath);
        if (!Files.exists(sidecarPath)) {
            return null;
        }

        try {
            byte[] bytes = Files.readAllBytes(sidecarPath);
            XMPMeta meta = XMPMetaFactory.parseFromBuffer(bytes);

            SidecarService.SidecarData data = new SidecarService.SidecarData();

            // Rating → xmp:Rating
            XMPProperty ratingProp = meta.getProperty(XMPConst.NS_XMP, "Rating");
            if (ratingProp != null && ratingProp.getValue() != null && !ratingProp.getValue().isEmpty()) {
                data.setRating(ratingProp.getValue());
            }

            // Tags → dc:subject
            List<String> tags = readBag(meta, XMPConst.NS_DC, "subject");
            if (!tags.isEmpty()) {
                data.setTags(tags);
            }

            // Persons → Iptc4xmpExt:PersonInImage
            List<String> persons = readBag(meta, XMPConst.NS_IPTCEXT, "PersonInImage");
            if (!persons.isEmpty()) {
                data.setPersons(persons);
            }

            // Place → photostat:place (custom namespace; place is free-form and
            // may hold non-geographic values, so we don't map it to IPTC City)
            XMPProperty placeProp = meta.getProperty(NS_PHOTOSTAT, "place");
            if (placeProp != null && placeProp.getValue() != null && !placeProp.getValue().isEmpty()) {
                data.setPlace(placeProp.getValue());
            }

            // analysisHash → photostat:analysisHash
            XMPProperty hashProp = meta.getProperty(NS_PHOTOSTAT, "analysisHash");
            if (hashProp != null && hashProp.getValue() != null && !hashProp.getValue().isEmpty()) {
                data.setAnalysisHash(hashProp.getValue());
            }

            // cloudUploads → photostat:cloudUploads
            List<String> uploads = readBag(meta, NS_PHOTOSTAT, "cloudUploads");
            if (!uploads.isEmpty()) {
                data.setCloudUploads(uploads);
            }

            logger.debug("XmpSidecarBackend", "Read sidecar for: " + imagePath);
            return data;

        } catch (IOException | XMPException e) {
            logger.error("XmpSidecarBackend", "Failed to read sidecar: " + sidecarPath, e);
            return null;
        }
    }

    @Override
    public boolean write(String imagePath, SidecarService.SidecarData data) {
        Path sidecarPath = getSidecarPath(imagePath);
        try {
            XMPMeta meta = XMPMetaFactory.create();

            // Rating → xmp:Rating (stored as string — Lightroom/Bridge write it as int
            // but xmp:Rating's XMP type is "Real" and all tools accept a numeric string).
            if (data.getRating() != null && !data.getRating().trim().isEmpty()) {
                meta.setProperty(XMPConst.NS_XMP, "Rating", data.getRating().trim());
            }

            // Tags → dc:subject (unordered bag)
            if (data.getTags() != null && !data.getTags().isEmpty()) {
                PropertyOptions bag = new PropertyOptions().setArray(true);
                for (String tag : data.getTags()) {
                    if (tag != null && !tag.trim().isEmpty()) {
                        meta.appendArrayItem(XMPConst.NS_DC, "subject", bag, tag.trim(), null);
                    }
                }
            }

            // Persons → Iptc4xmpExt:PersonInImage (unordered bag)
            if (data.getPersons() != null && !data.getPersons().isEmpty()) {
                PropertyOptions bag = new PropertyOptions().setArray(true);
                for (String person : data.getPersons()) {
                    if (person != null && !person.trim().isEmpty()) {
                        meta.appendArrayItem(XMPConst.NS_IPTCEXT, "PersonInImage", bag, person.trim(), null);
                    }
                }
            }

            // Place → photostat:place (custom namespace; see class javadoc)
            if (data.getPlace() != null && !data.getPlace().trim().isEmpty()) {
                meta.setProperty(NS_PHOTOSTAT, "place", data.getPlace().trim());
            }

            // analysisHash → photostat:analysisHash
            if (data.getAnalysisHash() != null && !data.getAnalysisHash().isEmpty()) {
                meta.setProperty(NS_PHOTOSTAT, "analysisHash", data.getAnalysisHash());
            }

            // cloudUploads → photostat:cloudUploads (bag)
            if (data.getCloudUploads() != null && !data.getCloudUploads().isEmpty()) {
                PropertyOptions bag = new PropertyOptions().setArray(true);
                for (String upload : data.getCloudUploads()) {
                    if (upload != null && !upload.isEmpty()) {
                        meta.appendArrayItem(NS_PHOTOSTAT, "cloudUploads", bag, upload, null);
                    }
                }
            }

            SerializeOptions options = new SerializeOptions()
                    .setOmitPacketWrapper(false)
                    .setUseCompactFormat(true);
            byte[] serialized = XMPMetaFactory.serializeToBuffer(meta, options);
            Files.write(sidecarPath, serialized);
            logger.info("XmpSidecarBackend", "Wrote sidecar: " + sidecarPath);
            return true;

        } catch (IOException | XMPException e) {
            logger.error("XmpSidecarBackend", "Failed to write sidecar: " + sidecarPath, e);
            return false;
        }
    }

    @Override
    public boolean delete(String imagePath) {
        Path sidecarPath = getSidecarPath(imagePath);
        try {
            if (Files.exists(sidecarPath)) {
                Files.delete(sidecarPath);
                logger.info("XmpSidecarBackend", "Deleted sidecar: " + sidecarPath);
            }
            return true;
        } catch (IOException e) {
            logger.error("XmpSidecarBackend", "Failed to delete sidecar: " + sidecarPath, e);
            return false;
        }
    }

    private List<String> readBag(XMPMeta meta, String namespace, String propName) throws XMPException {
        List<String> result = new ArrayList<>();
        int count = meta.countArrayItems(namespace, propName);
        for (int i = 1; i <= count; i++) {
            XMPProperty item = meta.getArrayItem(namespace, propName, i);
            if (item != null && item.getValue() != null) {
                result.add(item.getValue());
            }
        }
        return result;
    }
}
