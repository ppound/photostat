package com.photostat.services;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.Iterator;

/**
 * Resizes and JPEG-compresses images for transmission to AI/analysis backends.
 * <p>
 * Reads efficiently via subsampling so multi-megapixel originals don't have to be
 * decoded at full resolution. Returns {@code null} if the image can't be read
 * (e.g. an unsupported RAW format) or encoded.
 */
public final class ImageOptimizer {

    private ImageOptimizer() {}

    /**
     * @param imageFile source image
     * @param maxSize   maximum width/height in pixels (aspect ratio preserved)
     * @param quality   JPEG quality 0..1
     * @return optimized JPEG bytes, or {@code null} on failure
     */
    public static byte[] optimizeToJpeg(File imageFile, int maxSize, float quality) {
        try {
            // Read the image efficiently using subsampling.
            BufferedImage originalImage = null;
            try (javax.imageio.stream.ImageInputStream iis = ImageIO.createImageInputStream(imageFile)) {
                Iterator<javax.imageio.ImageReader> readers = ImageIO.getImageReaders(iis);
                if (readers.hasNext()) {
                    javax.imageio.ImageReader reader = readers.next();
                    reader.setInput(iis, true, true);

                    int origWidth = reader.getWidth(0);
                    int origHeight = reader.getHeight(0);

                    int scale = Math.max(1, Math.min(origWidth / maxSize, origHeight / maxSize));
                    int subsampling = 1;
                    while (subsampling * 2 <= scale) {
                        subsampling *= 2;
                    }

                    javax.imageio.ImageReadParam param = reader.getDefaultReadParam();
                    if (subsampling > 1) {
                        param.setSourceSubsampling(subsampling, subsampling, 0, 0);
                    }

                    originalImage = reader.read(0, param);
                    reader.dispose();
                }
            }

            if (originalImage == null) {
                return null;
            }

            int originalWidth = originalImage.getWidth();
            int originalHeight = originalImage.getHeight();

            // Calculate new dimensions maintaining aspect ratio.
            int newWidth = originalWidth;
            int newHeight = originalHeight;
            if (originalWidth > maxSize || originalHeight > maxSize) {
                if (originalWidth > originalHeight) {
                    newWidth = maxSize;
                    newHeight = (int) ((double) originalHeight / originalWidth * maxSize);
                } else {
                    newHeight = maxSize;
                    newWidth = (int) ((double) originalWidth / originalHeight * maxSize);
                }
            }

            BufferedImage resizedImage = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2d = resizedImage.createGraphics();
            try {
                g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                // White background for transparent source images.
                g2d.setColor(Color.WHITE);
                g2d.fillRect(0, 0, newWidth, newHeight);
                g2d.drawImage(originalImage, 0, 0, newWidth, newHeight, null);
            } finally {
                g2d.dispose();
            }

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
            if (!writers.hasNext()) {
                return null;
            }
            ImageWriter writer = writers.next();
            ImageOutputStream ios = ImageIO.createImageOutputStream(outputStream);
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            writer.write(null, new IIOImage(resizedImage, null, null), param);
            writer.dispose();
            ios.close();

            return outputStream.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }
}
