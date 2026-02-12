package com.photostat.ui;

import com.photostat.models.ImageMetadata;
import com.photostat.services.LoggingService;
import com.photostat.services.OpenSearchService;
import com.photostat.services.SidecarService;
import com.photostat.services.ThumbnailService;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Full-screen slideshow for browsing search results with keyboard navigation and rating.
 */
public class SlideshowStage extends Stage {

    private static final Set<String> RAW_EXTENSIONS = Set.of(
            ".cr2", ".cr3", ".nef", ".arw", ".orf", ".rw2", ".dng", ".raf",
            ".pef", ".srw", ".x3f", ".raw", ".rwl"
    );

    private final List<ImageMetadata> images;
    private final BiConsumer<ImageMetadata, String> ratingCallback;
    private final ThumbnailService thumbnailService;
    private final OpenSearchService openSearchService;
    private final SidecarService sidecarService;
    private final LoggingService logger;

    private int currentIndex;
    private final ImageView imageView;
    private final HBox hudBar;
    private final Label filenameLabel;
    private final Label counterLabel;
    private final Label ratingLabel;
    private final Label exifLabel;
    private final Label toastLabel;

    private final PauseTransition hudHideTimer;
    private final FadeTransition hudFadeOut;
    private boolean hudPinned = false;

    // Preloaded images for smooth navigation
    private Image preloadedNext;
    private Image preloadedPrev;
    private int preloadedNextIndex = -1;
    private int preloadedPrevIndex = -1;

    public SlideshowStage(List<ImageMetadata> images, int startIndex,
                          BiConsumer<ImageMetadata, String> ratingCallback) {
        this.images = images;
        this.currentIndex = Math.max(0, Math.min(startIndex, images.size() - 1));
        this.ratingCallback = ratingCallback;
        this.thumbnailService = ThumbnailService.getInstance();
        this.openSearchService = OpenSearchService.getInstance();
        this.sidecarService = SidecarService.getInstance();
        this.logger = LoggingService.getInstance();

        // Root layout
        StackPane root = new StackPane();
        root.setStyle("-fx-background-color: black;");

        // Image view
        double screenW = Screen.getPrimary().getBounds().getWidth();
        double screenH = Screen.getPrimary().getBounds().getHeight();
        imageView = new ImageView();
        imageView.setPreserveRatio(true);
        imageView.setFitWidth(screenW);
        imageView.setFitHeight(screenH);
        imageView.setSmooth(true);

        // HUD overlay bar
        filenameLabel = new Label();
        filenameLabel.setStyle("-fx-text-fill: white; -fx-font-size: 14px;");

        counterLabel = new Label();
        counterLabel.setStyle("-fx-text-fill: #cccccc; -fx-font-size: 13px;");

        ratingLabel = new Label();
        ratingLabel.setStyle("-fx-text-fill: gold; -fx-font-size: 16px;");

        exifLabel = new Label();
        exifLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 12px;");

        HBox leftHud = new HBox(12, ratingLabel, filenameLabel);
        leftHud.setAlignment(Pos.CENTER_LEFT);

        HBox rightHud = new HBox(12, exifLabel, counterLabel);
        rightHud.setAlignment(Pos.CENTER_RIGHT);

        hudBar = new HBox();
        hudBar.setStyle("-fx-background-color: rgba(0,0,0,0.7); -fx-background-radius: 0;");
        hudBar.setPadding(new Insets(8, 16, 8, 16));
        hudBar.setAlignment(Pos.CENTER);
        hudBar.setMaxHeight(40);
        HBox.setHgrow(leftHud, javafx.scene.layout.Priority.ALWAYS);
        hudBar.getChildren().addAll(leftHud, rightHud);
        StackPane.setAlignment(hudBar, Pos.BOTTOM_CENTER);

        // Toast label for rating feedback
        toastLabel = new Label();
        toastLabel.setStyle("-fx-text-fill: gold; -fx-font-size: 28px; -fx-font-weight: bold; "
                + "-fx-effect: dropshadow(gaussian, black, 10, 0.8, 0, 0);");
        toastLabel.setOpacity(0);
        StackPane.setAlignment(toastLabel, Pos.CENTER);

        root.getChildren().addAll(imageView, hudBar, toastLabel);

        // HUD auto-hide timer
        hudFadeOut = new FadeTransition(Duration.millis(500), hudBar);
        hudFadeOut.setFromValue(1.0);
        hudFadeOut.setToValue(0.0);

        hudHideTimer = new PauseTransition(Duration.seconds(3));
        hudHideTimer.setOnFinished(e -> {
            if (!hudPinned) {
                hudFadeOut.play();
            }
        });

        // Scene & stage setup
        Scene scene = new Scene(root, screenW, screenH, Color.BLACK);
        setScene(scene);
        setTitle("Slideshow");
        setFullScreen(true);
        setFullScreenExitKeyCombination(KeyCombination.NO_MATCH);

        // Key bindings
        scene.addEventHandler(KeyEvent.KEY_PRESSED, this::handleKeyPress);

        // Mouse movement shows HUD
        scene.addEventHandler(MouseEvent.MOUSE_MOVED, e -> showHud());

        // Load the initial image
        showImage();
    }

    private void handleKeyPress(KeyEvent event) {
        // Show HUD on any key press
        showHud();

        if (event.isControlDown() || event.isAltDown() || event.isMetaDown() || event.isShiftDown()) {
            return;
        }

        KeyCode code = event.getCode();

        switch (code) {
            case RIGHT:
            case DOWN:
            case SPACE:
            case PAGE_DOWN:
                navigateNext();
                event.consume();
                break;
            case LEFT:
            case UP:
            case PAGE_UP:
                navigatePrev();
                event.consume();
                break;
            case HOME:
                navigateTo(0);
                event.consume();
                break;
            case END:
                navigateTo(images.size() - 1);
                event.consume();
                break;
            case ESCAPE:
                close();
                event.consume();
                break;
            case I:
                toggleHud();
                event.consume();
                break;
            default:
                // Rating keys
                String rating = ratingFromKeyCode(code);
                if (rating != null) {
                    applyRating(rating.isEmpty() ? null : rating);
                    event.consume();
                }
                break;
        }
    }

    private String ratingFromKeyCode(KeyCode code) {
        if (code == KeyCode.DIGIT1 || code == KeyCode.NUMPAD1) return "*";
        if (code == KeyCode.DIGIT2 || code == KeyCode.NUMPAD2) return "**";
        if (code == KeyCode.DIGIT3 || code == KeyCode.NUMPAD3) return "***";
        if (code == KeyCode.DIGIT4 || code == KeyCode.NUMPAD4) return "****";
        if (code == KeyCode.DIGIT5 || code == KeyCode.NUMPAD5) return "*****";
        if (code == KeyCode.DIGIT0 || code == KeyCode.NUMPAD0) return "";
        return null;
    }

    private void navigateNext() {
        if (currentIndex < images.size() - 1) {
            navigateTo(currentIndex + 1);
        }
    }

    private void navigatePrev() {
        if (currentIndex > 0) {
            navigateTo(currentIndex - 1);
        }
    }

    private void navigateTo(int index) {
        if (index < 0 || index >= images.size() || index == currentIndex) {
            return;
        }
        currentIndex = index;
        showImage();
    }

    private void showImage() {
        ImageMetadata metadata = images.get(currentIndex);
        String filePath = metadata.getFilePath();

        // Check if we have a preloaded image for this index
        if (currentIndex == preloadedNextIndex && preloadedNext != null) {
            imageView.setImage(preloadedNext);
            preloadedNext = null;
            preloadedNextIndex = -1;
        } else if (currentIndex == preloadedPrevIndex && preloadedPrev != null) {
            imageView.setImage(preloadedPrev);
            preloadedPrev = null;
            preloadedPrevIndex = -1;
        } else {
            loadAndDisplayImage(filePath);
        }

        updateHud(metadata);
        preloadAdjacent();
    }

    private void loadAndDisplayImage(String filePath) {
        String ext = getFileExtension(filePath).toLowerCase();
        if (RAW_EXTENSIONS.contains(ext)) {
            // RAW files: use ThumbnailService
            thumbnailService.getThumbnailAsync(filePath, (path, thumbnail) -> {
                Platform.runLater(() -> {
                    if (filePath.equals(images.get(currentIndex).getFilePath())) {
                        imageView.setImage(thumbnail);
                    }
                });
            });
        } else {
            // Standard formats: load at screen resolution
            double screenW = Screen.getPrimary().getBounds().getWidth();
            double screenH = Screen.getPrimary().getBounds().getHeight();
            Image image = new Image(Path.of(filePath).toUri().toString(),
                    screenW, screenH, true, true, true);
            imageView.setImage(image);
        }
    }

    private Image loadImageSync(String filePath) {
        String ext = getFileExtension(filePath).toLowerCase();
        if (RAW_EXTENSIONS.contains(ext)) {
            return thumbnailService.getThumbnail(filePath);
        } else {
            double screenW = Screen.getPrimary().getBounds().getWidth();
            double screenH = Screen.getPrimary().getBounds().getHeight();
            return new Image(Path.of(filePath).toUri().toString(),
                    screenW, screenH, true, true, false);
        }
    }

    private void preloadAdjacent() {
        // Preload next
        int nextIdx = currentIndex + 1;
        if (nextIdx < images.size() && nextIdx != preloadedNextIndex) {
            preloadedNextIndex = nextIdx;
            String path = images.get(nextIdx).getFilePath();
            Thread t = new Thread(() -> preloadedNext = loadImageSync(path));
            t.setDaemon(true);
            t.start();
        }
        // Preload prev
        int prevIdx = currentIndex - 1;
        if (prevIdx >= 0 && prevIdx != preloadedPrevIndex) {
            preloadedPrevIndex = prevIdx;
            String path = images.get(prevIdx).getFilePath();
            Thread t = new Thread(() -> preloadedPrev = loadImageSync(path));
            t.setDaemon(true);
            t.start();
        }
    }

    private void updateHud(ImageMetadata metadata) {
        filenameLabel.setText(metadata.getFileName());
        counterLabel.setText((currentIndex + 1) + " / " + images.size());

        String rating = metadata.getRating();
        if (rating != null && !rating.isEmpty()) {
            ratingLabel.setText(rating.replace('*', '\u2605'));
        } else {
            ratingLabel.setText("");
        }

        StringBuilder exif = new StringBuilder();
        if (metadata.getApertureString() != null && !metadata.getApertureString().isEmpty()) {
            exif.append(metadata.getApertureString());
        }
        if (metadata.getShutterSpeed() != null && !metadata.getShutterSpeed().isEmpty()) {
            if (exif.length() > 0) exif.append("  ");
            exif.append(metadata.getShutterSpeed());
        }
        if (metadata.getIso() != null) {
            if (exif.length() > 0) exif.append("  ");
            exif.append("ISO ").append(metadata.getIso());
        }
        if (metadata.getFocalLengthString() != null && !metadata.getFocalLengthString().isEmpty()) {
            if (exif.length() > 0) exif.append("  ");
            exif.append(metadata.getFocalLengthString());
        }
        exifLabel.setText(exif.toString());
    }

    private void applyRating(String rating) {
        ImageMetadata metadata = images.get(currentIndex);
        metadata.setRating(rating);
        updateHud(metadata);

        // Show toast
        String stars = rating != null ? rating.replace('*', '\u2605') : "";
        String toastText = rating != null ? "Rated " + stars : "Rating cleared";
        showToast(toastText);

        // Fire callback to sync with ResultsPanel
        if (ratingCallback != null) {
            ratingCallback.accept(metadata, rating);
        }

        // Persist in background
        Thread thread = new Thread(() -> {
            try {
                openSearchService.updateDocument(metadata);
                sidecarService.writeSidecar(metadata);
            } catch (Exception e) {
                logger.error("SlideshowStage", "Failed to save rating for " + metadata.getFilePath(), e);
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    private void showToast(String text) {
        toastLabel.setText(text);
        toastLabel.setOpacity(1.0);

        PauseTransition pause = new PauseTransition(Duration.seconds(1));
        FadeTransition fade = new FadeTransition(Duration.millis(500), toastLabel);
        fade.setFromValue(1.0);
        fade.setToValue(0.0);
        pause.setOnFinished(e -> fade.play());
        pause.play();
    }

    private void showHud() {
        hudFadeOut.stop();
        hudBar.setOpacity(1.0);
        hudHideTimer.playFromStart();
    }

    private void toggleHud() {
        hudPinned = !hudPinned;
        if (hudPinned) {
            hudFadeOut.stop();
            hudBar.setOpacity(1.0);
            hudHideTimer.stop();
        } else {
            hudHideTimer.playFromStart();
        }
    }

    private static String getFileExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        if (lastDot > 0) {
            return filename.substring(lastDot);
        }
        return "";
    }
}
