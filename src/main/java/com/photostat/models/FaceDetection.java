package com.photostat.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Data model representing a single detected face in an image.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class FaceDetection {

    @JsonProperty("face_id")
    private String faceId;

    @JsonProperty("image_path")
    private String imagePath;

    @JsonProperty("x")
    private int x;

    @JsonProperty("y")
    private int y;

    @JsonProperty("width")
    private int width;

    @JsonProperty("height")
    private int height;

    @JsonProperty("confidence")
    private double confidence;

    @JsonProperty("embedding")
    private double[] embedding;

    public FaceDetection() {
    }

    public FaceDetection(String imagePath, int x, int y, int width, int height, double confidence, double[] embedding) {
        this.imagePath = imagePath;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.confidence = confidence;
        this.embedding = embedding;
        this.faceId = generateFaceId(imagePath, x, y, width, height);
    }

    private static String generateFaceId(String imagePath, int x, int y, int w, int h) {
        String input = imagePath + "|" + x + "|" + y + "|" + w + "|" + h;
        return Integer.toHexString(input.hashCode());
    }

    // Getters and Setters

    public String getFaceId() {
        return faceId;
    }

    public void setFaceId(String faceId) {
        this.faceId = faceId;
    }

    public String getImagePath() {
        return imagePath;
    }

    public void setImagePath(String imagePath) {
        this.imagePath = imagePath;
    }

    public int getX() {
        return x;
    }

    public void setX(int x) {
        this.x = x;
    }

    public int getY() {
        return y;
    }

    public void setY(int y) {
        this.y = y;
    }

    public int getWidth() {
        return width;
    }

    public void setWidth(int width) {
        this.width = width;
    }

    public int getHeight() {
        return height;
    }

    public void setHeight(int height) {
        this.height = height;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    public double[] getEmbedding() {
        return embedding;
    }

    public void setEmbedding(double[] embedding) {
        this.embedding = embedding;
    }

    @Override
    public String toString() {
        return "FaceDetection{faceId='" + faceId + "', imagePath='" + imagePath +
                "', bbox=[" + x + "," + y + "," + width + "," + height +
                "], confidence=" + confidence + "}";
    }
}
