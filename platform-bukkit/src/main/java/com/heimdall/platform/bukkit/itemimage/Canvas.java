package com.heimdall.platform.bukkit.itemimage;

import java.awt.image.BufferedImage;

/**
 * An ARGB image addressed in GUI pixels, drawn at an integer scale with no smoothing.
 *
 * <p>Minecraft's GUI is laid out in GUI pixels and shown at an integer scale; drawing the same way,
 * one GUI pixel to {@code scale} by {@code scale} image pixels, keeps every glyph and border edge
 * crisp. Compositing is plain source-over, done by hand: the image is small and this avoids any
 * dependence on what a headless JVM's {@code Graphics2D} pipeline does with alpha.
 */
final class Canvas {

    final BufferedImage image;
    final int scale;

    Canvas(int guiWidth, int guiHeight, int scale) {
        this.scale = scale;
        this.image = new BufferedImage(Math.max(1, guiWidth * scale), Math.max(1, guiHeight * scale),
                BufferedImage.TYPE_INT_ARGB);
    }

    /** Fills a rectangle given in GUI pixels (fractions allowed), source-over. */
    void fill(double x, double y, double w, double h, int argb) {
        int x0 = (int) Math.round(x * scale);
        int y0 = (int) Math.round(y * scale);
        int x1 = (int) Math.round((x + w) * scale);
        int y1 = (int) Math.round((y + h) * scale);
        for (int py = Math.max(0, y0); py < Math.min(image.getHeight(), y1); py++) {
            for (int px = Math.max(0, x0); px < Math.min(image.getWidth(), x1); px++) {
                blend(px, py, argb);
            }
        }
    }

    /** A vertical gradient from {@code top} to {@code bottom} (both ARGB) over a GUI rectangle. */
    void gradient(double x, double y, double w, double h, int top, int bottom) {
        int y0 = (int) Math.round(y * scale);
        int y1 = (int) Math.round((y + h) * scale);
        int x0 = (int) Math.round(x * scale);
        int x1 = (int) Math.round((x + w) * scale);
        int rows = Math.max(1, y1 - y0);
        for (int py = Math.max(0, y0); py < Math.min(image.getHeight(), y1); py++) {
            double t = rows <= 1 ? 0 : (py - y0) / (double) (rows - 1);
            int argb = lerp(top, bottom, t);
            for (int px = Math.max(0, x0); px < Math.min(image.getWidth(), x1); px++) {
                blend(px, py, argb);
            }
        }
    }

    /** Source-over of one ARGB colour onto one image pixel. */
    void blend(int px, int py, int argb) {
        if (px < 0 || py < 0 || px >= image.getWidth() || py >= image.getHeight()) {
            return;
        }
        int sa = (argb >>> 24) & 0xFF;
        if (sa == 0) {
            return;
        }
        if (sa == 0xFF) {
            image.setRGB(px, py, argb);
            return;
        }
        int dst = image.getRGB(px, py);
        int da = (dst >>> 24) & 0xFF;
        double a = sa / 255.0;
        double b = da / 255.0 * (1 - a);
        double outA = a + b;
        if (outA <= 0) {
            return;
        }
        int r = (int) Math.round((((argb >> 16) & 0xFF) * a + ((dst >> 16) & 0xFF) * b) / outA);
        int g = (int) Math.round((((argb >> 8) & 0xFF) * a + ((dst >> 8) & 0xFF) * b) / outA);
        int bl = (int) Math.round(((argb & 0xFF) * a + (dst & 0xFF) * b) / outA);
        image.setRGB(px, py, ((int) Math.round(outA * 255) << 24) | (r << 16) | (g << 8) | bl);
    }

    /** Draws {@code source} (already at output resolution) with its top-left at output pixel (px, py). */
    void drawImage(BufferedImage source, int px, int py) {
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                blend(px + x, py + y, source.getRGB(x, y));
            }
        }
    }

    static int lerp(int from, int to, double t) {
        int out = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            int a = (from >>> shift) & 0xFF;
            int b = (to >>> shift) & 0xFF;
            out |= ((int) Math.round(a + (b - a) * t) & 0xFF) << shift;
        }
        return out;
    }
}
