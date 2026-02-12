package com.photostat.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Data model representing image metadata extracted from EXIF data.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ImageMetadata {

    @JsonProperty("file_path")
    private String filePath;

    @JsonProperty("file_name")
    private String fileName;

    @JsonProperty("file_size")
    private Long fileSize;

    @JsonProperty("file_type")
    private String fileType;

    @JsonProperty("camera_make")
    private String cameraMake;

    @JsonProperty("camera_model")
    private String cameraModel;

    @JsonProperty("lens_model")
    private String lensModel;

    @JsonProperty("date_taken")
    private LocalDateTime dateTaken;

    @JsonProperty("date_indexed")
    private LocalDateTime dateIndexed;

    @JsonProperty("iso")
    private Integer iso;

    @JsonProperty("aperture")
    private Double aperture;

    @JsonProperty("shutter_speed")
    private String shutterSpeed;

    @JsonProperty("focal_length")
    private Double focalLength;

    @JsonProperty("image_width")
    private Integer imageWidth;

    @JsonProperty("image_height")
    private Integer imageHeight;

    @JsonProperty("orientation")
    private String orientation;

    @JsonProperty("gps_latitude")
    private Double gpsLatitude;

    @JsonProperty("gps_longitude")
    private Double gpsLongitude;

    @JsonProperty("gps_location")
    private Map<String, Double> gpsLocation;

    @JsonProperty("artist")
    private String artist;

    @JsonProperty("copyright")
    private String copyright;

    @JsonProperty("software")
    private String software;

    @JsonProperty("all_exif")
    private Map<String, Object> allExif;

    // Custom user-defined metadata
    @JsonProperty("persons")
    private List<String> persons;

    @JsonProperty("place")
    private String place;

    @JsonProperty("tags")
    private List<String> tags;

    @JsonProperty("rating")
    private String rating;

    @JsonProperty("content_hash")
    private String contentHash;

    @JsonProperty("perceptual_hash")
    private String perceptualHash;

    public ImageMetadata() {
        this.allExif = new HashMap<>();
        this.persons = new ArrayList<>();
        this.tags = new ArrayList<>();
        this.dateIndexed = LocalDateTime.now();
    }

    // Getters and Setters

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public String getFileType() {
        return fileType;
    }

    public void setFileType(String fileType) {
        this.fileType = fileType;
    }

    public String getCameraMake() {
        return cameraMake;
    }

    public void setCameraMake(String cameraMake) {
        this.cameraMake = cameraMake;
    }

    public String getCameraModel() {
        return cameraModel;
    }

    public void setCameraModel(String cameraModel) {
        this.cameraModel = cameraModel;
    }

    public String getLensModel() {
        return lensModel;
    }

    public void setLensModel(String lensModel) {
        this.lensModel = lensModel;
    }

    public LocalDateTime getDateTaken() {
        return dateTaken;
    }

    public void setDateTaken(LocalDateTime dateTaken) {
        this.dateTaken = dateTaken;
    }

    public LocalDateTime getDateIndexed() {
        return dateIndexed;
    }

    public void setDateIndexed(LocalDateTime dateIndexed) {
        this.dateIndexed = dateIndexed;
    }

    public Integer getIso() {
        return iso;
    }

    public void setIso(Integer iso) {
        this.iso = iso;
    }

    public Double getAperture() {
        return aperture;
    }

    public void setAperture(Double aperture) {
        this.aperture = aperture;
    }

    public String getShutterSpeed() {
        return shutterSpeed;
    }

    public void setShutterSpeed(String shutterSpeed) {
        this.shutterSpeed = shutterSpeed;
    }

    public Double getFocalLength() {
        return focalLength;
    }

    public void setFocalLength(Double focalLength) {
        this.focalLength = focalLength;
    }

    public Integer getImageWidth() {
        return imageWidth;
    }

    public void setImageWidth(Integer imageWidth) {
        this.imageWidth = imageWidth;
    }

    public Integer getImageHeight() {
        return imageHeight;
    }

    public void setImageHeight(Integer imageHeight) {
        this.imageHeight = imageHeight;
    }

    public String getOrientation() {
        return orientation;
    }

    public void setOrientation(String orientation) {
        this.orientation = orientation;
    }

    public Double getGpsLatitude() {
        return gpsLatitude;
    }

    public void setGpsLatitude(Double gpsLatitude) {
        this.gpsLatitude = gpsLatitude;
        updateGpsLocation();
    }

    public Double getGpsLongitude() {
        return gpsLongitude;
    }

    public void setGpsLongitude(Double gpsLongitude) {
        this.gpsLongitude = gpsLongitude;
        updateGpsLocation();
    }

    public Map<String, Double> getGpsLocation() {
        return gpsLocation;
    }

    public void setGpsLocation(Map<String, Double> gpsLocation) {
        this.gpsLocation = gpsLocation;
    }

    private void updateGpsLocation() {
        if (gpsLatitude != null && gpsLongitude != null) {
            gpsLocation = new HashMap<>();
            gpsLocation.put("lat", gpsLatitude);
            gpsLocation.put("lon", gpsLongitude);
        }
    }

    public String getArtist() {
        return artist;
    }

    public void setArtist(String artist) {
        this.artist = artist;
    }

    public String getCopyright() {
        return copyright;
    }

    public void setCopyright(String copyright) {
        this.copyright = copyright;
    }

    public String getSoftware() {
        return software;
    }

    public void setSoftware(String software) {
        this.software = software;
    }

    public Map<String, Object> getAllExif() {
        return allExif;
    }

    public void setAllExif(Map<String, Object> allExif) {
        this.allExif = allExif;
    }

    public void addExifTag(String key, Object value) {
        if (allExif == null) {
            allExif = new HashMap<>();
        }
        allExif.put(key, value);
    }

    public List<String> getPersons() {
        return persons;
    }

    public void setPersons(List<String> persons) {
        this.persons = persons;
    }

    public void addPerson(String person) {
        if (persons == null) {
            persons = new ArrayList<>();
        }
        if (!persons.contains(person)) {
            persons.add(person);
        }
    }

    public void removePerson(String person) {
        if (persons != null) {
            persons.remove(person);
        }
    }

    public String getPlace() {
        return place;
    }

    public void setPlace(String place) {
        this.place = place;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags;
    }

    public void addTag(String tag) {
        if (tags == null) {
            tags = new ArrayList<>();
        }
        if (!tags.contains(tag)) {
            tags.add(tag);
        }
    }

    public void removeTag(String tag) {
        if (tags != null) {
            tags.remove(tag);
        }
    }

    public String getRating() {
        return rating;
    }

    public void setRating(String rating) {
        this.rating = rating;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public String getPerceptualHash() {
        return perceptualHash;
    }

    public void setPerceptualHash(String perceptualHash) {
        this.perceptualHash = perceptualHash;
    }

    public String getPersonsString() {
        if (persons == null || persons.isEmpty()) {
            return "";
        }
        return String.join(", ", persons);
    }

    public String getTagsString() {
        if (tags == null || tags.isEmpty()) {
            return "";
        }
        return String.join(", ", tags);
    }

    /**
     * Get display string for dimensions
     */
    public String getDimensionsString() {
        if (imageWidth != null && imageHeight != null) {
            return imageWidth + " x " + imageHeight;
        }
        return "";
    }

    /**
     * Get display string for aperture
     */
    public String getApertureString() {
        if (aperture != null) {
            return String.format("f/%.1f", aperture);
        }
        return "";
    }

    /**
     * Get display string for focal length
     */
    public String getFocalLengthString() {
        if (focalLength != null) {
            return String.format("%.0fmm", focalLength);
        }
        return "";
    }

    /**
     * Get file size in human readable format
     */
    public String getFileSizeString() {
        if (fileSize == null) {
            return "";
        }
        if (fileSize < 1024) {
            return fileSize + " B";
        } else if (fileSize < 1024 * 1024) {
            return String.format("%.1f KB", fileSize / 1024.0);
        } else if (fileSize < 1024 * 1024 * 1024) {
            return String.format("%.1f MB", fileSize / (1024.0 * 1024.0));
        } else {
            return String.format("%.1f GB", fileSize / (1024.0 * 1024.0 * 1024.0));
        }
    }

    @Override
    public String toString() {
        return "ImageMetadata{" +
                "fileName='" + fileName + '\'' +
                ", cameraMake='" + cameraMake + '\'' +
                ", cameraModel='" + cameraModel + '\'' +
                ", dateTaken=" + dateTaken +
                ", iso=" + iso +
                ", aperture=" + aperture +
                '}';
    }
}
