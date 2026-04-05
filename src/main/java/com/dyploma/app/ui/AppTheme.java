package com.dyploma.app.ui;

import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.TranslateTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.StrokeLineCap;
import javafx.util.Duration;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public final class AppTheme {

    private static final String WATERMARK = "made by Lizhewskij Kostiantyn";
    private static MediaPlayer backgroundPlayer;
    private static boolean backgroundVideoDisabled;

    private AppTheme() {
    }

    public static Parent wrap(Region content) {
        content.getStyleClass().addAll("app-surface", "screen-content");

        HBox brandBar = buildBrandBar();
        VBox frame = new VBox(24, brandBar, content);
        frame.setFillWidth(true);

        double contentMaxWidth = content.getMaxWidth();
        if (contentMaxWidth > 0 && contentMaxWidth < Double.MAX_VALUE) {
            frame.setMaxWidth(contentMaxWidth);
            brandBar.setMaxWidth(contentMaxWidth);
        }

        StackPane holder = new StackPane(frame);
        holder.getStyleClass().add("app-shell");
        holder.setPadding(new Insets(44, 56, 60, 56));
        holder.setMaxWidth(Double.MAX_VALUE);
        StackPane.setAlignment(frame, Pos.TOP_CENTER);

        ScrollPane scroller = new ScrollPane(holder);
        scroller.getStyleClass().add("app-scroll");
        scroller.setFitToWidth(true);
        scroller.setPannable(true);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        Label watermark = new Label(WATERMARK);
        watermark.getStyleClass().add("watermark-label");
        watermark.setMouseTransparent(true);
        StackPane.setAlignment(watermark, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(watermark, new Insets(0, 28, 18, 0));

        StackPane screen = new StackPane(
                glow("app-glow-amber", 540, 540, Pos.TOP_RIGHT, new Insets(-200, -220, 0, 0)),
                glow("app-glow-deep", 680, 680, Pos.BOTTOM_LEFT, new Insets(0, 0, -280, -280)),
                buildBackdrop(),
                scroller,
                watermark
        );
        screen.getStyleClass().add("app-screen");
        return screen;
    }

    public static void styleTitle(Label label) {
        label.getStyleClass().add("screen-title");
    }

    public static void styleSubtitle(Label label) {
        label.getStyleClass().add("screen-subtitle");
        label.setWrapText(true);
    }

    public static void styleLead(Label label) {
        label.getStyleClass().add("lead-label");
        label.setWrapText(true);
    }

    public static void styleHint(Label label) {
        label.getStyleClass().add("hint-label");
        label.setWrapText(true);
    }

    public static void styleStatus(Label label) {
        label.getStyleClass().add("status-pill");
        label.setWrapText(true);
    }

    public static void styleSectionTitle(Label label) {
        label.getStyleClass().add("section-title");
        label.setWrapText(true);
    }

    public static void stylePrimaryButton(Button button) {
        styleButton(button, "primary-button");
    }

    public static void styleSecondaryButton(Button button) {
        styleButton(button, "secondary-button");
    }

    public static void styleDangerButton(Button button) {
        styleButton(button, "danger-button");
    }

    public static void styleField(Control control) {
        control.getStyleClass().add("form-control");
        control.setMaxWidth(Double.MAX_VALUE);
    }

    public static void styleSection(Pane pane) {
        pane.getStyleClass().add("section-card");
    }

    private static void styleButton(Button button, String variant) {
        button.getStyleClass().add(variant);
        button.setMaxWidth(Double.MAX_VALUE);
    }

    private static HBox buildBrandBar() {
        StackPane logoPlate = buildInstituteLogo();

        Label kicker = new Label("Talk to your data");
        kicker.getStyleClass().add("brand-kicker");

        Label meta = new Label("Query, analyze, and explore your database with AI.");
        meta.getStyleClass().add("brand-meta");

        VBox copy = new VBox(3, kicker, meta);
        copy.setAlignment(Pos.CENTER_LEFT);

        Label chip = new Label("Lizhewskij Kostiantyn");
        chip.getStyleClass().add("brand-chip");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox bar = new HBox(16, logoPlate, copy, spacer, chip);
        bar.getStyleClass().add("brand-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    private static StackPane buildInstituteLogo() {
        StackPane plate = new StackPane();
        plate.getStyleClass().add("brand-logo-plate");
        plate.setMinSize(96, 90);
        plate.setPrefSize(96, 90);
        plate.setMaxSize(96, 90);

        try (InputStream stream = openLogoStream()) {
            if (stream != null) {
                ImageView imageView = new ImageView(new Image(stream));
                imageView.setFitWidth(76);
                imageView.setFitHeight(76);
                imageView.setPreserveRatio(true);
                imageView.setSmooth(true);
                plate.getChildren().add(imageView);
            } else {
                Label fallback = new Label("S:");
                fallback.getStyleClass().add("brand-logo-fallback");
                plate.getChildren().add(fallback);
            }
        } catch (Exception ex) {
            Label fallback = new Label("S:");
            fallback.getStyleClass().add("brand-logo-fallback");
            plate.getChildren().add(fallback);
        }
        return plate;
    }

    private static InputStream openLogoStream() {
        InputStream stream = AppTheme.class.getResourceAsStream("/com/dyploma/app/ui/institute-logo-transparent.png");
        if (stream != null) {
            return stream;
        }
        return AppTheme.class.getResourceAsStream("/com/dyploma/app/ui/institute-logo.png");
    }

    private static Pane buildMeteorSky() {
        Pane sky = new Pane();
        sky.setManaged(false);
        sky.setMouseTransparent(true);

        addStaticStars(sky);
        addMeteors(sky);
        return sky;
    }

    private static Parent buildBackdrop() {
        StackPane backdrop = new StackPane();
        backdrop.setMouseTransparent(true);
        backdrop.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

        Parent videoLayer = buildVideoLayer();
        if (videoLayer != null) {
            Region overlay = new Region();
            overlay.getStyleClass().add("video-overlay");
            backdrop.getChildren().addAll(videoLayer, overlay);
            return backdrop;
        }

        return buildMeteorSky();
    }

    private static Parent buildVideoLayer() {
        if (backgroundVideoDisabled) {
            return null;
        }

        try {
            MediaPlayer player = getOrCreateBackgroundPlayer();
            if (player == null) {
                return null;
            }

            StackPane videoHolder = new StackPane();
            videoHolder.setMouseTransparent(true);
            videoHolder.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

            MediaView view = new MediaView(player);
            view.setPreserveRatio(false);
            view.setSmooth(true);
            view.setOpacity(0.82);
            view.fitWidthProperty().bind(videoHolder.widthProperty());
            view.fitHeightProperty().bind(videoHolder.heightProperty());
            player.play();

            videoHolder.getChildren().add(view);
            return videoHolder;
        } catch (Exception ex) {
            backgroundVideoDisabled = true;
            System.out.println("[DEBUG_LOG] Background video disabled: " + ex.getMessage());
            return null;
        }
    }

    private static void addStaticStars(Pane sky) {
        double[][] stars = {
                {70, 90, 2.0, 0.58}, {140, 170, 1.6, 0.38}, {260, 70, 1.8, 0.46},
                {360, 210, 2.2, 0.44}, {500, 120, 1.4, 0.32}, {620, 90, 2.4, 0.62},
                {760, 180, 1.8, 0.46}, {900, 80, 1.6, 0.36}, {1040, 210, 2.0, 0.40},
                {1180, 110, 1.7, 0.34}, {1320, 90, 2.2, 0.48}, {1460, 170, 1.9, 0.36},
                {180, 430, 1.4, 0.28}, {340, 530, 1.8, 0.30}, {570, 460, 2.0, 0.34},
                {840, 420, 1.5, 0.28}, {1090, 500, 1.8, 0.34}, {1290, 450, 1.4, 0.24},
                {250, 760, 1.9, 0.28}, {480, 690, 1.6, 0.22}, {720, 760, 2.1, 0.30},
                {960, 700, 1.7, 0.24}, {1210, 760, 1.8, 0.28}
        };

        for (double[] config : stars) {
            Circle halo = new Circle(config[2] * 2.4, Color.rgb(201, 108, 46, config[3] * 0.18));
            halo.setLayoutX(config[0]);
            halo.setLayoutY(config[1]);

            Circle core = new Circle(config[2], Color.rgb(233, 183, 129, config[3]));
            core.setLayoutX(config[0]);
            core.setLayoutY(config[1]);

            sky.getChildren().addAll(halo, core);
        }
    }

    private static void addMeteors(Pane sky) {
        double[][] meteors = {
                {120, -120, 250, 560, 6200, 0, 132},
                {320, -220, 280, 620, 7000, 900, 154},
                {560, -140, 240, 540, 5800, 1800, 118},
                {790, -260, 300, 680, 7600, 2600, 164},
                {1020, -170, 250, 560, 6100, 3600, 128},
                {1260, -250, 290, 660, 7400, 4700, 158},
                {220, -340, 260, 640, 8000, 5200, 146},
                {980, -360, 260, 640, 7800, 6200, 150}
        };

        for (double[] config : meteors) {
            Group meteor = createMeteor(config[6]);
            meteor.setLayoutX(config[0]);
            meteor.setLayoutY(config[1]);
            sky.getChildren().add(meteor);

            TranslateTransition fall = new TranslateTransition(Duration.millis(config[4]), meteor);
            fall.setFromX(0);
            fall.setFromY(0);
            fall.setToX(config[2]);
            fall.setToY(config[3]);
            fall.setCycleCount(Animation.INDEFINITE);
            fall.setInterpolator(Interpolator.LINEAR);
            fall.setDelay(Duration.millis(config[5]));
            fall.play();
        }
    }

    private static Group createMeteor(double length) {
        Line trail = new Line(0, 0, length, 0);
        trail.setStroke(Color.rgb(224, 162, 101, 0.82));
        trail.setStrokeWidth(2.6);
        trail.setStrokeLineCap(StrokeLineCap.ROUND);

        Line glow = new Line(0, 0, length + 10, 0);
        glow.setStroke(Color.rgb(201, 108, 46, 0.24));
        glow.setStrokeWidth(7.5);
        glow.setStrokeLineCap(StrokeLineCap.ROUND);

        Circle headGlow = new Circle(length + 2, 0, 6.2, Color.rgb(201, 108, 46, 0.24));
        Circle head = new Circle(length + 2, 0, 3.4, Color.rgb(239, 193, 141, 0.94));

        Group group = new Group(glow, trail, headGlow, head);
        group.setOpacity(0.92);
        group.setRotate(31);
        return group;
    }

    private static MediaPlayer getOrCreateBackgroundPlayer() {
        if (backgroundPlayer != null) {
            return backgroundPlayer;
        }

        String videoUri = resolveBackgroundVideoUri();
        if (videoUri == null || videoUri.isBlank()) {
            return null;
        }

        Media media = new Media(videoUri);
        backgroundPlayer = new MediaPlayer(media);
        backgroundPlayer.setAutoPlay(true);
        backgroundPlayer.setMute(true);
        backgroundPlayer.setVolume(0);
        backgroundPlayer.setCycleCount(MediaPlayer.INDEFINITE);
        backgroundPlayer.setOnError(() -> {
            backgroundVideoDisabled = true;
            System.out.println("[DEBUG_LOG] Background media error: " + backgroundPlayer.getError());
        });
        return backgroundPlayer;
    }

    private static String resolveBackgroundVideoUri() {
        try {
            var resource = AppTheme.class.getResource("/com/dyploma/app/ui/background-video.mp4");
            if (resource != null) {
                return resource.toExternalForm();
            }
        } catch (Exception ignore) {
        }

        try {
            Path path = Path.of("src", "main", "resources", "com", "dyploma", "app", "ui", "background-video.mp4");
            if (Files.exists(path)) {
                return path.toAbsolutePath().toUri().toString();
            }
        } catch (Exception ignore) {
        }

        return null;
    }

    private static Region glow(String variant, double width, double height, Pos alignment, Insets margin) {
        Region glow = new Region();
        glow.getStyleClass().addAll("app-glow", variant);
        glow.setManaged(false);
        glow.setMouseTransparent(true);
        glow.setPrefSize(width, height);
        StackPane.setAlignment(glow, alignment);
        StackPane.setMargin(glow, margin);
        return glow;
    }
}
