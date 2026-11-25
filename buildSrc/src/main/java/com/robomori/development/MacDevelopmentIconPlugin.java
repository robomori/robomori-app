package com.robomori.development;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.tasks.Exec;
import org.gradle.api.tasks.JavaExec;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Gives local Java launches the macOS Dock icon and app name, with a DEV badge on the icon so a run from source is
 * easy to tell apart from the installed Robomori.
 */
public class MacDevelopmentIconPlugin implements Plugin<Project> {
    @Override public void apply(Project project) {
        if (!System.getProperty("os.name").startsWith("Mac")) return;

        var packagedIcon = project.file("packaging/app-icon.icns");
        var plainIcon = project.getLayout().getBuildDirectory().file("development-icon/app-icon-plain.png").get().getAsFile();
        var icon = project.getLayout().getBuildDirectory().file("development-icon/app-icon.png").get().getAsFile();
        var prepareIcon = project.getTasks().register("prepareDevelopmentMacIcon", Exec.class, task -> {
            task.getInputs().file(packagedIcon);
            task.getOutputs().file(icon);
            task.doFirst(ignored -> icon.getParentFile().mkdirs());
            task.commandLine("/usr/bin/sips", "-s", "format", "png", packagedIcon.getAbsolutePath(), "--out", plainIcon.getAbsolutePath());
            task.doLast(ignored -> addDevBadge(plainIcon, icon));
        });
        project.getTasks().withType(JavaExec.class)
            .matching(task -> task.getName().equals("run") || task.getName().equals("hotRun"))
            .configureEach(task -> {
                task.dependsOn(prepareIcon);
                task.jvmArgs("-Xdock:icon=" + icon.getAbsolutePath());
                task.jvmArgs("-Xdock:name=Robomori");
            });
    }

    /** Draws an orange "DEV" pill over the bottom right of [source]'s icon, inside the icon's own shape. */
    private static void addDevBadge(File source, File target) {
        try {
            var plain = ImageIO.read(source);
            var size = plain.getWidth();
            var image = new BufferedImage(size, plain.getHeight(), BufferedImage.TYPE_INT_ARGB);
            var graphics = image.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.drawImage(plain, 0, 0, null);

            // macOS icons keep about a tenth of their size as transparent margin; the badge sits just inside it.
            var height = size * 0.2;
            var width = size * 0.42;
            var x = size * 0.9 - width;
            var y = size * 0.9 - height;
            graphics.setColor(new Color(0xF97316));
            graphics.fill(new RoundRectangle2D.Double(x, y, width, height, height, height));
            graphics.setColor(Color.WHITE);
            graphics.setStroke(new java.awt.BasicStroke((float) (size * 0.012)));
            graphics.draw(new RoundRectangle2D.Double(x, y, width, height, height, height));

            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, (int) (height * 0.62)));
            var metrics = graphics.getFontMetrics();
            var text = "DEV";
            var textX = x + (width - metrics.stringWidth(text)) / 2;
            var textY = y + (height - metrics.getHeight()) / 2 + metrics.getAscent();
            graphics.drawString(text, (float) textX, (float) textY);
            graphics.dispose();
            ImageIO.write(image, "png", target);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
