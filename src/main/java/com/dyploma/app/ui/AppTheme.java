package com.dyploma.app.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.io.InputStream;

public final class AppTheme {

    private static final String WATERMARK = "made by Lizhewskij Kostiantyn";

    private AppTheme() {
    }

    public static Parent wrap(Region content) {
        content.getStyleClass().addAll("app-surface", "screen-content");

        HBox brandBar = buildBrandBar();
        VBox frame = new VBox(22, brandBar, content);
        frame.setFillWidth(true);

        double contentMaxWidth = content.getMaxWidth();
        if (contentMaxWidth > 0 && contentMaxWidth < Double.MAX_VALUE) {
            frame.setMaxWidth(contentMaxWidth);
            brandBar.setMaxWidth(contentMaxWidth);
        }

        StackPane holder = new StackPane(frame);
        holder.getStyleClass().add("app-shell");
        holder.setPadding(new Insets(44, 56, 58, 56));
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
                glow("app-glow-amber", 520, 520, Pos.TOP_LEFT, new Insets(-190, 0, 0, -250)),
                glow("app-glow-teal", 620, 620, Pos.BOTTOM_RIGHT, new Insets(0, -300, -250, 0)),
                glow("app-glow-citrus", 440, 440, Pos.TOP_RIGHT, new Insets(-140, -60, 0, 0)),
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

        Label kicker = new Label("Institute identity");
        kicker.getStyleClass().add("brand-kicker");

        Label caption = new Label("Orange and white academic style with your institute logo");
        caption.getStyleClass().add("brand-caption");

        VBox copy = new VBox(2, kicker, caption);

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
        plate.setMinSize(92, 86);
        plate.setPrefSize(92, 86);
        plate.setMaxSize(92, 86);

        try (InputStream stream = AppTheme.class.getResourceAsStream("/com/dyploma/app/ui/institute-logo.png")) {
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
