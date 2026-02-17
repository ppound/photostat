package com.photostat.models;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * Data model representing a cluster of similar faces (likely the same person).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class FaceCluster {

    @JsonProperty("cluster_id")
    private String clusterId;

    @JsonProperty("person_name")
    private String personName;

    @JsonProperty("face_ids")
    private List<String> faceIds;

    @JsonProperty("representative_face_id")
    private String representativeFaceId;

    @JsonIgnore
    private List<FaceDetection> faces;

    @JsonIgnore
    private int photoCount;

    public FaceCluster() {
        this.faceIds = new ArrayList<>();
        this.faces = new ArrayList<>();
    }

    public FaceCluster(String clusterId) {
        this();
        this.clusterId = clusterId;
    }

    // Getters and Setters

    public String getClusterId() {
        return clusterId;
    }

    public void setClusterId(String clusterId) {
        this.clusterId = clusterId;
    }

    public String getPersonName() {
        return personName;
    }

    public void setPersonName(String personName) {
        this.personName = personName;
    }

    public List<String> getFaceIds() {
        return faceIds;
    }

    public void setFaceIds(List<String> faceIds) {
        this.faceIds = faceIds;
    }

    public String getRepresentativeFaceId() {
        return representativeFaceId;
    }

    public void setRepresentativeFaceId(String representativeFaceId) {
        this.representativeFaceId = representativeFaceId;
    }

    public List<FaceDetection> getFaces() {
        return faces;
    }

    public void setFaces(List<FaceDetection> faces) {
        this.faces = faces;
    }

    public int getPhotoCount() {
        return photoCount;
    }

    public void setPhotoCount(int photoCount) {
        this.photoCount = photoCount;
    }

    public String getDisplayName() {
        if (personName != null && !personName.isEmpty()) {
            return personName;
        }
        return "Unknown #" + clusterId;
    }

    @Override
    public String toString() {
        return "FaceCluster{clusterId='" + clusterId + "', personName='" + personName +
                "', faces=" + faceIds.size() + "}";
    }
}
