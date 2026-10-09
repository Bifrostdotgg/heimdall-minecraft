package com.heimdall.platform.bukkit.itemimage;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws an item icon, 16 GUI pixels square, at the card's scale.
 *
 * <h2>Flat items</h2>
 *
 * <p>A {@code builtin/generated} model is its layers drawn over each other, each multiplied by its
 * tint, nearest-neighbour. That is what the inventory shows for every ordinary item.
 *
 * <h2>3D models</h2>
 *
 * <p>Elements are projected the way the inventory projects them: each corner is rotated by its
 * element's rotation, then through the model's {@code display.gui} transform (scale, rotation in X
 * then Y then Z, translation), and viewed orthographically from the front. Under an orthographic
 * view a rectangle stays a parallelogram, so every face is exactly an affine image of its texture
 * region: it is drawn with a {@code Graphics2D} affine transform, clipped to the face, faces sorted
 * back to front. A block's {@code [30, 225, 0]} at {@code 0.625} gives the familiar isometric cube.
 * Faces are shaded by direction (top 1.0, sides 0.8 and 0.6, bottom 0.5) unless the model is lit
 * from the front. Face culling, ambient occlusion and the client's exact light vectors are not
 * reproduced: the goal is a recognisable icon, not a pixel-exact one.
 *
 * <h2>Glint and placeholders</h2>
 *
 * <p>An enchanted item gets a static, tinted glint over its opaque pixels, textured by the client's
 * glint texture when there is one. Anything that resolves to nothing (no vanilla assets, an unknown
 * custom item) is a neutral framed square, never a player skin or a fetched image.
 */
final class IconPainter {

    /** The glint's tint, close to the client's purple. */
    static final int GLINT_RGB = 0x8040CC;

    private IconPainter() {
    }

    /** The icon at {@code 16 * scale} pixels square. */
    static BufferedImage paint(ModelResolver.Icon icon, Textures textures, int scale,
            boolean glint, Deadline deadline) {
        int size = 16 * scale;
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        if (icon.placeholder()) {
            placeholder(out, scale);
            return out;
        }
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
            boolean drewSomething = false;
            Map<String, BufferedImage> tinted = new HashMap<String, BufferedImage>();
            for (ModelResolver.Part part : icon.parts) {
                deadline.check();
                if (part.flat()) {
                    for (int i = 0; i < part.layers.size(); i++) {
                        BufferedImage texture = textures.get(part.layers.get(i));
                        if (texture == null) {
                            continue;
                        }
                        int tint = i < part.layerTints.length ? part.layerTints[i] : -1;
                        g.drawImage(shaded(tinted, part.layers.get(i), texture, tint, 1.0),
                                0, 0, size, size, null);
                        drewSomething = true;
                    }
                } else {
                    drewSomething |= elements(g, part, textures, tinted, scale, deadline);
                }
            }
            if (!drewSomething) {
                placeholder(out, scale);
                return out;
            }
        } finally {
            g.dispose();
        }
        if (glint) {
            glint(out, textures);
        }
        return out;
    }

    // ── 3D ───────────────────────────────────────────────────────────────────

    /** One face ready to draw: its projected corners, depth, and texture mapping. */
    private static final class Projected {
        double[][] screen;
        double depth;
        BufferedImage texture;
        double[] uvPixels;
        int[] cornerOf;
    }

    private static boolean elements(Graphics2D g, ModelResolver.Part part, Textures textures,
            Map<String, BufferedImage> tinted, int scale, Deadline deadline) {
        List<Projected> faces = new ArrayList<Projected>();
        for (ModelResolver.Element element : part.elements) {
            deadline.check();
            for (ModelResolver.Face face : element.faces) {
                BufferedImage texture = textures.get(face.texture);
                if (texture == null) {
                    continue;
                }
                double[][] model = corners(face.direction, element.from, element.to);
                double[][] screen = new double[4][];
                double depth = 0;
                for (int i = 0; i < 4; i++) {
                    double[] v = rotateElement(model[i], element);
                    v = display(v, part.gui);
                    depth += v[2];
                    screen[i] = new double[] {(8 + v[0]) * scale, (8 - v[1]) * scale, v[2]};
                }
                // Outward normal's z after the transform: (v1 - v0) x (v3 - v0), screen y flipped.
                double ax = screen[1][0] - screen[0][0];
                double ay = -(screen[1][1] - screen[0][1]);
                double bx = screen[3][0] - screen[0][0];
                double by = -(screen[3][1] - screen[0][1]);
                if (ax * by - ay * bx <= 1e-6) {
                    continue;
                }
                float[] uv = face.uv != null ? face.uv : defaultUv(face.direction,
                        element.from, element.to);
                double shade = part.sideLit && element.shade ? shade(face.direction) : 1.0;
                int tint = part.tint(face.tintIndex);
                Projected p = new Projected();
                p.screen = screen;
                p.depth = depth / 4;
                p.texture = shaded(tinted, face.texture, texture, tint, shade);
                double sx = texture.getWidth() / 16.0;
                double sy = texture.getHeight() / 16.0;
                p.uvPixels = new double[] {uv[0] * sx, uv[1] * sy, uv[2] * sx, uv[3] * sy};
                int shift = ((face.rotation / 90) % 4 + 4) % 4;
                p.cornerOf = new int[4];
                for (int i = 0; i < 4; i++) {
                    p.cornerOf[i] = (i + shift) % 4;
                }
                faces.add(p);
            }
        }
        Collections.sort(faces, new Comparator<Projected>() {
            @Override
            public int compare(Projected a, Projected b) {
                return Double.compare(a.depth, b.depth);
            }
        });
        boolean drew = false;
        for (Projected face : faces) {
            deadline.check();
            drew |= drawFace(g, face);
        }
        return drew;
    }

    /**
     * Maps the face's texture region onto its projected parallelogram. UV corner 0 is
     * {@code (u1, v1)}, 1 is {@code (u1, v2)}, 2 is {@code (u2, v2)}, 3 is {@code (u2, v1)}, and
     * vertex {@code i} carries corner {@code (i + rotation / 90) % 4}, as the client bakes it.
     */
    private static boolean drawFace(Graphics2D g, Projected face) {
        double[] a = null;
        double[] b = null;
        double[] c = null;
        for (int i = 0; i < 4; i++) {
            if (face.cornerOf[i] == 0) {
                a = face.screen[i];
            } else if (face.cornerOf[i] == 3) {
                b = face.screen[i];
            } else if (face.cornerOf[i] == 1) {
                c = face.screen[i];
            }
        }
        double u1 = face.uvPixels[0];
        double v1 = face.uvPixels[1];
        double u2 = face.uvPixels[2];
        double v2 = face.uvPixels[3];
        if (a == null || b == null || c == null || Math.abs(u2 - u1) < 1e-6
                || Math.abs(v2 - v1) < 1e-6) {
            return false;
        }
        double m00 = (b[0] - a[0]) / (u2 - u1);
        double m10 = (b[1] - a[1]) / (u2 - u1);
        double m01 = (c[0] - a[0]) / (v2 - v1);
        double m11 = (c[1] - a[1]) / (v2 - v1);
        double m02 = a[0] - m00 * u1 - m01 * v1;
        double m12 = a[1] - m10 * u1 - m11 * v1;
        AffineTransform transform = new AffineTransform(m00, m10, m01, m11, m02, m12);
        if (Math.abs(transform.getDeterminant()) < 1e-9) {
            return false;
        }
        Path2D.Double clip = new Path2D.Double();
        clip.moveTo(face.screen[0][0], face.screen[0][1]);
        for (int i = 1; i < 4; i++) {
            clip.lineTo(face.screen[i][0], face.screen[i][1]);
        }
        clip.closePath();
        java.awt.Shape previous = g.getClip();
        g.setClip(clip);
        // The source region, clamped to the texture; the transform places it, the clip trims it.
        int x0 = (int) Math.floor(Math.max(0, Math.min(u1, u2)));
        int y0 = (int) Math.floor(Math.max(0, Math.min(v1, v2)));
        int x1 = (int) Math.ceil(Math.min(face.texture.getWidth(), Math.max(u1, u2)));
        int y1 = (int) Math.ceil(Math.min(face.texture.getHeight(), Math.max(v1, v2)));
        if (x1 > x0 && y1 > y0) {
            AffineTransform place = new AffineTransform(transform);
            place.translate(x0, y0);
            g.drawImage(face.texture.getSubimage(x0, y0, x1 - x0, y1 - y0), place, null);
        }
        g.setClip(previous);
        return true;
    }

    /** The four corners of a face, in the client's vertex order. */
    static double[][] corners(String direction, float[] f, float[] t) {
        double x0 = f[0];
        double y0 = f[1];
        double z0 = f[2];
        double x1 = t[0];
        double y1 = t[1];
        double z1 = t[2];
        if ("down".equals(direction)) {
            return new double[][] {{x0, y0, z1}, {x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}};
        }
        if ("up".equals(direction)) {
            return new double[][] {{x0, y1, z0}, {x0, y1, z1}, {x1, y1, z1}, {x1, y1, z0}};
        }
        if ("north".equals(direction)) {
            return new double[][] {{x1, y1, z0}, {x1, y0, z0}, {x0, y0, z0}, {x0, y1, z0}};
        }
        if ("south".equals(direction)) {
            return new double[][] {{x0, y1, z1}, {x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}};
        }
        if ("west".equals(direction)) {
            return new double[][] {{x0, y1, z0}, {x0, y0, z0}, {x0, y0, z1}, {x0, y1, z1}};
        }
        return new double[][] {{x1, y1, z1}, {x1, y0, z1}, {x1, y0, z0}, {x1, y1, z0}};
    }

    /** The UVs a face gets when its model gives none, as the client derives them. */
    static float[] defaultUv(String direction, float[] f, float[] t) {
        if ("down".equals(direction)) {
            return new float[] {f[0], 16 - t[2], t[0], 16 - f[2]};
        }
        if ("up".equals(direction)) {
            return new float[] {f[0], f[2], t[0], t[2]};
        }
        if ("north".equals(direction)) {
            return new float[] {16 - t[0], 16 - t[1], 16 - f[0], 16 - f[1]};
        }
        if ("south".equals(direction)) {
            return new float[] {f[0], 16 - t[1], t[0], 16 - f[1]};
        }
        if ("west".equals(direction)) {
            return new float[] {f[2], 16 - t[1], t[2], 16 - f[1]};
        }
        return new float[] {16 - t[2], 16 - t[1], 16 - f[2], 16 - f[1]};
    }

    private static double shade(String direction) {
        if ("up".equals(direction)) {
            return 1.0;
        }
        if ("down".equals(direction)) {
            return 0.5;
        }
        if ("north".equals(direction) || "south".equals(direction)) {
            return 0.8;
        }
        return 0.6;
    }

    /** A corner rotated by its element's own rotation, about the element's origin. */
    static double[] rotateElement(double[] v, ModelResolver.Element e) {
        if (e.rotationAxis == 0 || e.rotationAngle == 0) {
            return v;
        }
        double angle = Math.toRadians(e.rotationAngle);
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        double x = v[0] - e.rotationOrigin[0];
        double y = v[1] - e.rotationOrigin[1];
        double z = v[2] - e.rotationOrigin[2];
        double rx = x;
        double ry = y;
        double rz = z;
        double rescale = e.rescale ? 1.0 / Math.max(1e-6, Math.abs(cos)) : 1.0;
        if (e.rotationAxis == 'x') {
            ry = (y * cos - z * sin) * rescale;
            rz = (y * sin + z * cos) * rescale;
        } else if (e.rotationAxis == 'y') {
            rx = (x * cos + z * sin) * rescale;
            rz = (-x * sin + z * cos) * rescale;
        } else if (e.rotationAxis == 'z') {
            rx = (x * cos - y * sin) * rescale;
            ry = (x * sin + y * cos) * rescale;
        }
        return new double[] {rx + e.rotationOrigin[0], ry + e.rotationOrigin[1],
            rz + e.rotationOrigin[2]};
    }

    /**
     * A model-space corner through the GUI transform, centred on the model: scale, then rotate X,
     * Y, Z as the client's {@code rotationXYZ} composes them, then translate.
     */
    static double[] display(double[] v, ModelResolver.Transform t) {
        double x = (v[0] - 8) * t.scale[0];
        double y = (v[1] - 8) * t.scale[1];
        double z = (v[2] - 8) * t.scale[2];
        // Rz first, then Ry, then Rx: q = qx * qy * qz applied to a vector.
        double a = Math.toRadians(t.rotation[2]);
        double nx = x * Math.cos(a) - y * Math.sin(a);
        double ny = x * Math.sin(a) + y * Math.cos(a);
        x = nx;
        y = ny;
        a = Math.toRadians(t.rotation[1]);
        nx = x * Math.cos(a) + z * Math.sin(a);
        double nz = -x * Math.sin(a) + z * Math.cos(a);
        x = nx;
        z = nz;
        a = Math.toRadians(t.rotation[0]);
        ny = y * Math.cos(a) - z * Math.sin(a);
        nz = y * Math.sin(a) + z * Math.cos(a);
        y = ny;
        z = nz;
        return new double[] {x + t.translation[0], y + t.translation[1], z + t.translation[2]};
    }

    // ── Colour ───────────────────────────────────────────────────────────────

    /** {@code texture} multiplied by {@code tint} (RGB, -1 for none) and {@code shade}; memoised. */
    private static BufferedImage shaded(Map<String, BufferedImage> memo, String key,
            BufferedImage texture, int tint, double shade) {
        if (tint == -1 && shade >= 1.0) {
            return texture;
        }
        String memoKey = key + "|" + tint + "|" + shade;
        BufferedImage cached = memo.get(memoKey);
        if (cached != null) {
            return cached;
        }
        double tr = tint == -1 ? 1 : ((tint >> 16) & 0xFF) / 255.0;
        double tg = tint == -1 ? 1 : ((tint >> 8) & 0xFF) / 255.0;
        double tb = tint == -1 ? 1 : (tint & 0xFF) / 255.0;
        BufferedImage out = new BufferedImage(texture.getWidth(), texture.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < texture.getHeight(); y++) {
            for (int x = 0; x < texture.getWidth(); x++) {
                int argb = texture.getRGB(x, y);
                int r = (int) Math.round(((argb >> 16) & 0xFF) * tr * shade);
                int g = (int) Math.round(((argb >> 8) & 0xFF) * tg * shade);
                int b = (int) Math.round((argb & 0xFF) * tb * shade);
                out.setRGB(x, y, (argb & 0xFF000000) | (clamp(r) << 16) | (clamp(g) << 8)
                        | clamp(b));
            }
        }
        memo.put(memoKey, out);
        return out;
    }

    /** Brightens opaque pixels with the glint colour, modulated by the glint texture if present. */
    private static void glint(BufferedImage icon, Textures textures) {
        BufferedImage pattern = textures.load(
                "assets/minecraft/textures/misc/enchanted_glint_item.png");
        if (pattern == null) {
            pattern = textures.load("assets/minecraft/textures/misc/enchanted_item_glint.png");
        }
        int w = icon.getWidth();
        int h = icon.getHeight();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = icon.getRGB(x, y);
                if (((argb >>> 24) & 0xFF) == 0) {
                    continue;
                }
                double strength = 0.35;
                if (pattern != null) {
                    int p = pattern.getRGB((x * 2 + y) % pattern.getWidth(),
                            (y * 2) % pattern.getHeight());
                    double luminance = (((p >> 16) & 0xFF) + ((p >> 8) & 0xFF) + (p & 0xFF))
                            / (3.0 * 255.0);
                    strength = 0.15 + 0.5 * luminance;
                }
                int r = (int) Math.round(((argb >> 16) & 0xFF) + ((GLINT_RGB >> 16) & 0xFF) * strength);
                int g = (int) Math.round(((argb >> 8) & 0xFF) + ((GLINT_RGB >> 8) & 0xFF) * strength);
                int b = (int) Math.round((argb & 0xFF) + (GLINT_RGB & 0xFF) * strength);
                icon.setRGB(x, y, (argb & 0xFF000000) | (clamp(r) << 16) | (clamp(g) << 8)
                        | clamp(b));
            }
        }
    }

    /** A neutral framed square: "an item, picture unavailable". */
    static void placeholder(BufferedImage out, int scale) {
        int size = out.getWidth();
        int border = 0xFF5A5A5A;
        int fill = 0xFF8B8B8B;
        int inset = 2 * scale;
        for (int y = inset; y < size - inset; y++) {
            for (int x = inset; x < size - inset; x++) {
                boolean edge = x < inset + scale || y < inset + scale
                        || x >= size - inset - scale || y >= size - inset - scale;
                out.setRGB(x, y, edge ? border : fill);
            }
        }
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : v > 255 ? 255 : v;
    }
}
