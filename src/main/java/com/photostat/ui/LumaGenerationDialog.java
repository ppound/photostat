package com.photostat.ui;

import com.photostat.models.ImageMetadata;
import com.photostat.services.ConfigService;
import com.photostat.services.IndexerService;
import com.photostat.services.LumaService;
import com.photostat.services.LoggingService;
import com.photostat.services.ThumbnailService;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.File;
import java.io.FileInputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Dialog for generating images using the Luma AI API.
 */
public class LumaGenerationDialog extends Stage {

    private final ConfigService configService;
    private final LumaService lumaService;
    private final LoggingService logger;
    private final List<ImageMetadata> sourceImages;

    private TextArea promptArea;
    private ComboBox<String> refTypeCombo;
    private ComboBox<String> aspectRatioCombo;
    private Slider weightSlider;
    private Label weightValueLabel;
    private TextField outputDirField;
    private TextField filenameField;
    private CheckBox indexCheckbox;

    private Button generateButton;
    private Button cancelButton;
    private ProgressBar progressBar;
    private Label statusLabel;
    private VBox resultSection;
    private ImageView resultImageView;

    private boolean generating = false;
    private File generatedFile = null;

    public LumaGenerationDialog(Stage owner, List<ImageMetadata> sourceImages) {
        this.configService = ConfigService.getInstance();
        this.lumaService = LumaService.getInstance();
        this.logger = LoggingService.getInstance();
        this.sourceImages = sourceImages;

        initOwner(owner);
        initModality(Modality.APPLICATION_MODAL);
        setTitle("Generate Image with Luma AI");
        setResizable(true);

        setScene(new Scene(createContent()));
        setWidth(550);
        setHeight(700);

        // Center on owner
        if (owner != null) {
            setX(owner.getX() + (owner.getWidth() - 550) / 2);
            setY(owner.getY() + (owner.getHeight() - 700) / 2);
        }
    }

    private VBox createContent() {
        VBox root = new VBox(15);
        root.setPadding(new Insets(20));

        // Source images section
        root.getChildren().add(createSourceImagesSection());

        root.getChildren().add(new Separator());

        // Prompt section
        root.getChildren().add(createPromptSection());

        // Options section
        root.getChildren().add(createOptionsSection());

        root.getChildren().add(new Separator());

        // Output section
        root.getChildren().add(createOutputSection());

        root.getChildren().add(new Separator());

        // Progress section
        root.getChildren().add(createProgressSection());

        // Result section (hidden initially)
        resultSection = createResultSection();
        resultSection.setVisible(false);
        resultSection.setManaged(false);
        root.getChildren().add(resultSection);

        // Buttons
        root.getChildren().add(createButtonBar());

        // Wrap in scroll pane
        ScrollPane scrollPane = new ScrollPane(root);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        VBox wrapper = new VBox(scrollPane);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);
        return wrapper;
    }

    private VBox createSourceImagesSection() {
        VBox section = new VBox(5);
        Label header = new Label("Source Images (" + sourceImages.size() + ")");
        header.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");

        FlowPane thumbnailPane = new FlowPane(10, 10);
        thumbnailPane.setPadding(new Insets(5));

        ThumbnailService thumbnailService = ThumbnailService.getInstance();
        for (ImageMetadata metadata : sourceImages) {
            VBox imageBox = new VBox(3);
            imageBox.setAlignment(Pos.CENTER);

            ImageView iv = new ImageView();
            iv.setFitWidth(80);
            iv.setFitHeight(80);
            iv.setPreserveRatio(true);

            // Load thumbnail
            thumbnailService.getThumbnailAsync(metadata.getFilePath(), (path, image) -> {
                Platform.runLater(() -> iv.setImage(image));
            });

            Label nameLabel = new Label(metadata.getFileName());
            nameLabel.setMaxWidth(90);
            nameLabel.setStyle("-fx-font-size: 10px;");
            nameLabel.setWrapText(false);
            nameLabel.setEllipsisString("...");

            imageBox.getChildren().addAll(iv, nameLabel);
            thumbnailPane.getChildren().add(imageBox);
        }

        section.getChildren().addAll(header, thumbnailPane);
        return section;
    }

    private VBox createPromptSection() {
        VBox section = new VBox(5);
        Label header = new Label("Prompt");
        header.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");

        promptArea = new TextArea();
        promptArea.setPromptText("Describe the image you want to generate...");
        promptArea.setWrapText(true);
        promptArea.setPrefRowCount(3);

        section.getChildren().addAll(header, promptArea);
        return section;
    }

    private GridPane createOptionsSection() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);

        int row = 0;

        // Reference type
        grid.add(new Label("Reference Type:"), 0, row);
        refTypeCombo = new ComboBox<>();
        refTypeCombo.getItems().addAll("Image Reference", "Style Reference", "Modify Image");
        String defaultRefType = configService.getLumaDefaultRefType();
        switch (defaultRefType) {
            case "style_ref": refTypeCombo.setValue("Style Reference"); break;
            case "modify_image_ref": refTypeCombo.setValue("Modify Image"); break;
            default: refTypeCombo.setValue("Image Reference"); break;
        }
        refTypeCombo.setPrefWidth(200);

        Tooltip refTypeTooltip = new Tooltip(
            "Image Reference: Uses images as content/composition guidance\n" +
            "Style Reference: Transfers the visual style from images\n" +
            "Modify Image: Edits/transforms the source image (single image only)"
        );
        refTypeCombo.setTooltip(refTypeTooltip);
        grid.add(refTypeCombo, 1, row++);

        // Aspect ratio
        grid.add(new Label("Aspect Ratio:"), 0, row);
        aspectRatioCombo = new ComboBox<>();
        aspectRatioCombo.getItems().addAll("1:1", "16:9", "9:16", "4:3", "3:4", "21:9", "9:21");
        aspectRatioCombo.setValue(configService.getLumaDefaultAspectRatio());
        aspectRatioCombo.setPrefWidth(200);
        grid.add(aspectRatioCombo, 1, row++);

        // Weight
        grid.add(new Label("Reference Weight:"), 0, row);
        HBox weightBox = new HBox(10);
        weightBox.setAlignment(Pos.CENTER_LEFT);
        weightSlider = new Slider(0.0, 1.0, configService.getLumaDefaultRefWeight());
        weightSlider.setPrefWidth(150);
        weightSlider.setMajorTickUnit(0.25);
        weightSlider.setShowTickLabels(true);
        weightValueLabel = new Label(String.format("%.2f", weightSlider.getValue()));
        weightSlider.valueProperty().addListener((obs, oldVal, newVal) ->
            weightValueLabel.setText(String.format("%.2f", newVal.doubleValue()))
        );
        weightBox.getChildren().addAll(weightSlider, weightValueLabel);
        grid.add(weightBox, 1, row++);

        return grid;
    }

    private VBox createOutputSection() {
        VBox section = new VBox(8);
        Label header = new Label("Output");
        header.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);

        int row = 0;

        // Output directory
        grid.add(new Label("Save to:"), 0, row);
        outputDirField = new TextField();
        String defaultDir = configService.getLumaDefaultOutputDirectory();
        if (defaultDir.isEmpty()) {
            defaultDir = System.getProperty("user.home");
        }
        outputDirField.setText(defaultDir);
        outputDirField.setPrefWidth(300);

        Button browseButton = new Button("Browse...");
        browseButton.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Select Output Directory");
            String currentDir = outputDirField.getText().trim();
            if (!currentDir.isEmpty()) {
                File dir = new File(currentDir);
                if (dir.isDirectory()) {
                    chooser.setInitialDirectory(dir);
                }
            }
            File selected = chooser.showDialog(this);
            if (selected != null) {
                outputDirField.setText(selected.getAbsolutePath());
            }
        });

        HBox dirBox = new HBox(10, outputDirField, browseButton);
        HBox.setHgrow(outputDirField, Priority.ALWAYS);
        grid.add(dirBox, 1, row++);

        // Filename
        grid.add(new Label("Filename:"), 0, row);
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        filenameField = new TextField("luma_" + timestamp + ".jpg");
        filenameField.setPrefWidth(300);
        grid.add(filenameField, 1, row++);

        // Index checkbox
        indexCheckbox = new CheckBox("Index generated image in OpenSearch");
        indexCheckbox.setSelected(false);
        grid.add(indexCheckbox, 1, row++);

        section.getChildren().addAll(header, grid);
        return section;
    }

    private VBox createProgressSection() {
        VBox section = new VBox(5);

        progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(Double.MAX_VALUE);
        progressBar.setVisible(false);
        progressBar.setManaged(false);

        statusLabel = new Label("");
        statusLabel.setStyle("-fx-font-size: 11px;");
        statusLabel.setVisible(false);
        statusLabel.setManaged(false);

        section.getChildren().addAll(progressBar, statusLabel);
        return section;
    }

    private VBox createResultSection() {
        VBox section = new VBox(10);
        section.setAlignment(Pos.CENTER);
        section.setPadding(new Insets(10));

        Label header = new Label("Generated Image");
        header.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");

        resultImageView = new ImageView();
        resultImageView.setFitWidth(300);
        resultImageView.setFitHeight(300);
        resultImageView.setPreserveRatio(true);

        Button openFileButton = new Button("Open File");
        openFileButton.setOnAction(e -> {
            if (generatedFile != null && generatedFile.exists()) {
                try {
                    java.awt.Desktop.getDesktop().open(generatedFile);
                } catch (Exception ex) {
                    logger.error("LumaGenerationDialog", "Failed to open file", ex);
                }
            }
        });

        section.getChildren().addAll(header, resultImageView, openFileButton);
        return section;
    }

    private HBox createButtonBar() {
        HBox bar = new HBox(10);
        bar.setAlignment(Pos.CENTER_RIGHT);
        bar.setPadding(new Insets(10, 0, 0, 0));

        generateButton = new Button("Generate");
        generateButton.setDefaultButton(true);
        generateButton.setOnAction(e -> startGeneration());

        cancelButton = new Button("Cancel");
        cancelButton.setCancelButton(true);
        cancelButton.setOnAction(e -> {
            if (generating) {
                // Just close — the background thread will notice
                generating = false;
            }
            close();
        });

        bar.getChildren().addAll(generateButton, cancelButton);
        return bar;
    }

    private void startGeneration() {
        String prompt = promptArea.getText().trim();
        if (prompt.isEmpty()) {
            showAlert("Please enter a prompt describing the image to generate.");
            return;
        }

        String outputDir = outputDirField.getText().trim();
        if (outputDir.isEmpty()) {
            showAlert("Please select an output directory.");
            return;
        }

        String filename = filenameField.getText().trim();
        if (filename.isEmpty()) {
            showAlert("Please enter a filename.");
            return;
        }

        File outputFile = new File(outputDir, filename);
        if (outputFile.exists()) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle("File Exists");
            confirm.setHeaderText("Overwrite existing file?");
            confirm.setContentText(outputFile.getAbsolutePath());
            confirm.initOwner(this);
            var result = confirm.showAndWait();
            if (result.isEmpty() || result.get() != ButtonType.OK) {
                return;
            }
        }

        // Save output directory as default
        configService.setLumaDefaultOutputDirectory(outputDir);
        configService.saveConfig();

        // Build options
        LumaService.LumaOptions options = new LumaService.LumaOptions();
        options.aspectRatio = aspectRatioCombo.getValue();
        options.refWeight = weightSlider.getValue();

        String refTypeDisplay = refTypeCombo.getValue();
        switch (refTypeDisplay) {
            case "Style Reference": options.refType = "style_ref"; break;
            case "Modify Image": options.refType = "modify_image_ref"; break;
            default: options.refType = "image_ref"; break;
        }

        // Collect source image files
        List<File> sourceFiles = new ArrayList<>();
        for (ImageMetadata metadata : sourceImages) {
            File f = new File(metadata.getFilePath());
            if (f.exists()) {
                sourceFiles.add(f);
            }
        }

        if (sourceFiles.isEmpty()) {
            showAlert("None of the source image files could be found on disk.");
            return;
        }

        // Start generation
        generating = true;
        generateButton.setDisable(true);
        promptArea.setDisable(true);
        refTypeCombo.setDisable(true);
        aspectRatioCombo.setDisable(true);
        weightSlider.setDisable(true);

        progressBar.setVisible(true);
        progressBar.setManaged(true);
        progressBar.setProgress(-1); // indeterminate
        statusLabel.setVisible(true);
        statusLabel.setManaged(true);
        statusLabel.setText("Starting...");

        new Thread(() -> {
            LumaService.GenerationResult result = lumaService.generateImage(
                    prompt, sourceFiles, options, outputFile,
                    status -> Platform.runLater(() -> statusLabel.setText(status))
            );

            Platform.runLater(() -> {
                generating = false;
                progressBar.setProgress(result.success ? 1.0 : 0);

                if (result.success) {
                    generatedFile = result.outputFile;
                    statusLabel.setText("Generation complete!");
                    statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: green;");

                    // Show result preview
                    try {
                        Image img = new Image(new FileInputStream(generatedFile),
                                300, 300, true, true);
                        resultImageView.setImage(img);
                        resultSection.setVisible(true);
                        resultSection.setManaged(true);
                    } catch (Exception e) {
                        logger.error("LumaGenerationDialog", "Failed to load preview", e);
                    }

                    // Index if requested
                    if (indexCheckbox.isSelected()) {
                        try {
                            IndexerService indexerService = IndexerService.getInstance();
                            indexerService.indexSingleFile(generatedFile.getAbsolutePath());
                            statusLabel.setText("Generation complete! Image indexed.");
                        } catch (Exception e) {
                            logger.error("LumaGenerationDialog", "Failed to index generated image", e);
                            statusLabel.setText("Generated but indexing failed: " + e.getMessage());
                        }
                    }

                    generateButton.setText("Generate Another");
                    generateButton.setDisable(false);
                    generateButton.setOnAction(e -> resetForNewGeneration());
                    cancelButton.setText("Close");
                } else {
                    statusLabel.setText("Failed: " + result.error);
                    statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: red;");
                    generateButton.setDisable(false);
                    promptArea.setDisable(false);
                    refTypeCombo.setDisable(false);
                    aspectRatioCombo.setDisable(false);
                    weightSlider.setDisable(false);
                }
            });
        }).start();
    }

    private void resetForNewGeneration() {
        // Update filename for new generation
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        filenameField.setText("luma_" + timestamp + ".jpg");

        // Reset UI
        promptArea.setDisable(false);
        refTypeCombo.setDisable(false);
        aspectRatioCombo.setDisable(false);
        weightSlider.setDisable(false);
        progressBar.setVisible(false);
        progressBar.setManaged(false);
        statusLabel.setVisible(false);
        statusLabel.setManaged(false);
        statusLabel.setStyle("-fx-font-size: 11px;");
        resultSection.setVisible(false);
        resultSection.setManaged(false);

        generateButton.setText("Generate");
        generateButton.setOnAction(e -> startGeneration());
        cancelButton.setText("Cancel");
    }

    private void showAlert(String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle("Warning");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.initOwner(this);
        alert.showAndWait();
    }
}
