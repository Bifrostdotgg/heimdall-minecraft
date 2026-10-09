package com.heimdall.platform.bukkit.itemimage;

import com.heimdall.core.items.ChatItem;
import com.heimdall.core.items.ItemText;
import com.heimdall.core.items.ItemTranslations;
import com.heimdall.core.items.Snbt;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;

/**
 * Draws the tooltip card: the item's icon on the left, its tooltip box on the right, as one PNG.
 *
 * <h2>What the box says, in vanilla's order</h2>
 *
 * <ol>
 *   <li>The name: the custom name (italic unless it says otherwise), else the item name, else the
 *       type's English name; in the rarity colour unless the text carries its own. Rarity is the
 *       hover's {@code rarity}, else the server's default for the type, raised one step by
 *       enchantments as in game.
 *   <li>Enchantments, then an enchanted book's stored enchantments: grey, curses red, each with its
 *       level in Roman numerals unless it is a single-level enchantment at level one. An enchantment
 *       with no English name (a plugin's own, like {@code vane_enchantments:unbreakable}) shows its
 *       id made readable.
 *   <li>Lore, dark purple italic by default; at most {@value #MAX_LORE_LINES} lines.
 *   <li>{@code Unbreakable}, in blue.
 *   <li>{@code Durability: x / y} whenever the item can take damage and is not unbreakable. Not a
 *       vanilla line without advanced tooltips; it is Adam's product choice, because the number is
 *       what a player showing a tool usually means to show. Registry ids and component counts are
 *       not shown.
 * </ol>
 *
 * <p>Anything the item hides ({@code tooltip_display}, {@code show_in_tooltip:false},
 * {@code HideFlags}) stays hidden; a hidden tooltip is the icon alone.
 *
 * <h2>Metrics</h2>
 *
 * <p>1.21.2+: 3 pixels of padding, 10 per line, 2 extra under the name, each glyph shadowed in
 * {@code (colour & 0xFCFCFC) >> 2} one pixel down and right. The box is the nine-sliced
 * {@code gui/sprites/tooltip/background} and {@code frame} sprites when the assets have them, and the
 * classic gradient (background {@code 0xF0100010}, border {@code 0x505000FF} to {@code 0x5028007F})
 * before 1.21.2 or when no assets are present. Lines wider than {@value #MAX_LINE_WIDTH} GUI pixels
 * are cut with an ellipsis. Everything is drawn at an integer scale, nearest-neighbour.
 *
 * <p>Render thread only. Never logs text.
 */
final class CardRenderer {

    static final int MAX_LORE_LINES = 24;
    static final int MAX_LINE_WIDTH = 320;
    static final int LINE_HEIGHT = 10;
    static final int ICON = 16;
    static final int GAP = 4;
    static final int MARGIN = 1;

    static final int COMMON = 0xFFFFFF;
    static final int UNCOMMON = 0xFFFF55;
    static final int RARE = 0x55FFFF;
    static final int EPIC = 0xFF55FF;
    static final int GRAY = 0xAAAAAA;
    static final int RED = 0xFF5555;
    static final int BLUE = 0x5555FF;
    static final int DARK_PURPLE = 0xAA00AA;
    static final int WHITE = 0xFFFFFF;

    /** Enchantments whose only level is I, so the numeral is left off. */
    private static final Set<String> SINGLE_LEVEL = new HashSet<String>(Arrays.asList(
            "minecraft:aqua_affinity", "minecraft:binding_curse", "minecraft:channeling",
            "minecraft:flame", "minecraft:infinity", "minecraft:mending", "minecraft:multishot",
            "minecraft:silk_touch", "minecraft:vanishing_curse"));

    private final PackStack stack;
    private final Textures textures;
    private final ModelResolver models;
    private BitmapFont font;

    CardRenderer(PackStack stack) {
        this.stack = stack;
        this.textures = new Textures(stack);
        this.models = new ModelResolver(stack);
    }

    /** One tooltip line with the defaults its runs fall back to. */
    static final class Line {
        final ItemText text;
        final int color;
        final boolean italic;

        Line(ItemText text, int color, boolean italic) {
            this.text = text;
            this.color = color;
            this.italic = italic;
        }
    }

    /** The PNG for {@code item} at {@code scale}. */
    byte[] render(ChatItem item, ItemTranslations translations, ItemDefaults defaults, int scale,
            Deadline deadline) throws IOException {
        BufferedImage card = draw(item, translations, defaults, scale, deadline);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(card, "png", out)) {
            throw new IOException("no PNG writer");
        }
        return out.toByteArray();
    }

    BufferedImage draw(ChatItem item, ItemTranslations translations, ItemDefaults defaults,
            int scale, Deadline deadline) {
        int maxDamage = item.maxDamage() != null ? item.maxDamage()
                : Math.max(0, defaults.maxDurability(item.id()));
        List<Line> lines = lines(item, translations, defaults, maxDamage);
        BitmapFont f = font(deadline);

        ModelResolver.Icon icon = models.resolve(item, maxDamage, deadline);
        boolean glint = item.glintOverride() != null ? item.glintOverride()
                : !item.enchantments().isEmpty() || !item.storedEnchantments().isEmpty()
                        || intrinsicGlint(item.id());
        BufferedImage iconImage = IconPainter.paint(icon, textures, scale, glint, deadline);

        if (lines.isEmpty()) {
            Canvas canvas = new Canvas(ICON + 2 * MARGIN, ICON + 2 * MARGIN, scale);
            canvas.drawImage(iconImage, MARGIN * scale, MARGIN * scale);
            count(canvas, f, item, MARGIN, MARGIN);
            return canvas.image;
        }

        List<Line> fitted = new ArrayList<Line>(lines.size());
        double width = 0;
        for (Line line : lines) {
            deadline.check();
            Line cut = truncate(line, f);
            fitted.add(cut);
            width = Math.max(width, width(cut, f));
        }
        int w = (int) Math.ceil(width);
        int h = 8 + (fitted.size() - 1) * LINE_HEIGHT + (fitted.size() > 1 ? 2 : 0);

        BufferedImage background = textures.load(
                "assets/minecraft/textures/gui/sprites/tooltip/background.png");
        BufferedImage frame = textures.load("assets/minecraft/textures/gui/sprites/tooltip/frame.png");
        boolean sprites = background != null && frame != null;
        int extent = sprites ? 3 + 9 : 4;

        int tx = MARGIN + ICON + GAP + extent;
        int ty = MARGIN + extent;
        int canvasW = tx + w + extent + MARGIN;
        int canvasH = Math.max(ty + h + extent + MARGIN, ICON + 2 * MARGIN);
        Canvas canvas = new Canvas(canvasW, canvasH, scale);

        if (sprites) {
            nineSlice(canvas, background, spriteMeta("background"), tx - extent, ty - extent,
                    w + 2 * extent, h + 2 * extent);
            nineSlice(canvas, frame, spriteMeta("frame"), tx - extent, ty - extent,
                    w + 2 * extent, h + 2 * extent);
        } else {
            classicBox(canvas, tx, ty, w, h);
        }

        int iconY = Math.max(MARGIN, ty - 4);
        canvas.drawImage(iconImage, MARGIN * scale, iconY * scale);
        count(canvas, f, item, MARGIN, iconY);

        double y = ty;
        for (int i = 0; i < fitted.size(); i++) {
            deadline.check();
            drawLine(canvas, f, fitted.get(i), tx, y);
            y += LINE_HEIGHT + (i == 0 ? 2 : 0);
        }
        return canvas.image;
    }

    // ── Lines ────────────────────────────────────────────────────────────────

    static List<Line> lines(ChatItem item, ItemTranslations tr, ItemDefaults defaults,
            int maxDamage) {
        List<Line> out = new ArrayList<Line>();
        if (item.hideTooltip()) {
            return out;
        }
        out.add(new Line(item.nameText(tr), rarityColor(rarity(item, defaults)),
                item.hasCustomName()));
        if (!item.isHidden("enchantments")) {
            enchantments(out, item.enchantments(), tr);
        }
        if (!item.isHidden("stored_enchantments")) {
            enchantments(out, item.storedEnchantments(), tr);
        }
        if (!item.isHidden("lore")) {
            List<ItemText> lore = item.lore();
            for (int i = 0; i < lore.size() && i < MAX_LORE_LINES; i++) {
                out.add(new Line(lore.get(i), DARK_PURPLE, true));
            }
            if (lore.size() > MAX_LORE_LINES) {
                out.add(new Line(ItemText.plain("..."), DARK_PURPLE, true));
            }
        }
        if (item.unbreakable() && !item.isHidden("unbreakable")) {
            out.add(new Line(ItemText.plain(text(tr, "item.unbreakable", "Unbreakable")), BLUE,
                    false));
        }
        if (!item.unbreakable() && maxDamage > 0) {
            int damage = item.damage() == null ? 0 : item.damage();
            int left = Math.max(0, maxDamage - damage);
            out.add(new Line(ItemText.plain("Durability: " + left + " / " + maxDamage), WHITE,
                    false));
        }
        return out;
    }

    private static void enchantments(List<Line> out, Map<String, Integer> enchantments,
            ItemTranslations tr) {
        for (Map.Entry<String, Integer> entry : enchantments.entrySet()) {
            String id = entry.getKey();
            int level = entry.getValue();
            int colon = id.indexOf(':');
            String ns = colon < 0 ? "minecraft" : id.substring(0, colon);
            String path = colon < 0 ? id : id.substring(colon + 1);
            String name = text(tr, "enchantment." + ns + "." + path.replace('/', '.'),
                    ChatItem.humanise(path));
            boolean known = "minecraft".equals(ns);
            boolean showLevel = level != 1 || (known && !SINGLE_LEVEL.contains(id));
            if (showLevel) {
                name = name + " " + text(tr, "enchantment.level." + level, roman(level));
            }
            boolean curse = path.endsWith("_curse");
            out.add(new Line(ItemText.plain(name), curse ? RED : GRAY, false));
        }
    }

    static String roman(int n) {
        if (n <= 0 || n > 3999) {
            return Integer.toString(n);
        }
        int[] values = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] symbols = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder out = new StringBuilder();
        int rest = n;
        for (int i = 0; i < values.length; i++) {
            while (rest >= values[i]) {
                out.append(symbols[i]);
                rest -= values[i];
            }
        }
        return out.toString();
    }

    private static String text(ItemTranslations tr, String key, String fallback) {
        try {
            String found = tr == null ? null : tr.translate(key);
            return found == null || found.isEmpty() ? fallback : found;
        } catch (RuntimeException failed) {
            return fallback;
        }
    }

    /** The displayed rarity: the hover's, else the type's, raised a step by enchantments. */
    static String rarity(ChatItem item, ItemDefaults defaults) {
        String base = item.rarity() != null ? item.rarity() : defaults.rarity(item.id());
        if (base == null) {
            base = "common";
        }
        base = base.toLowerCase(Locale.ROOT);
        if (item.enchantments().isEmpty()) {
            return base;
        }
        if ("common".equals(base) || "uncommon".equals(base)) {
            return "rare";
        }
        if ("rare".equals(base)) {
            return "epic";
        }
        return base;
    }

    static int rarityColor(String rarity) {
        if ("uncommon".equals(rarity)) {
            return UNCOMMON;
        }
        if ("rare".equals(rarity)) {
            return RARE;
        }
        if ("epic".equals(rarity)) {
            return EPIC;
        }
        return COMMON;
    }

    private static boolean intrinsicGlint(String id) {
        return "minecraft:enchanted_golden_apple".equals(id)
                || "minecraft:experience_bottle".equals(id) || "minecraft:nether_star".equals(id)
                || "minecraft:enchanted_book".equals(id) || "minecraft:end_crystal".equals(id)
                || "minecraft:written_book".equals(id) || "minecraft:debug_stick".equals(id);
    }

    // ── Text ─────────────────────────────────────────────────────────────────

    private BitmapFont font(Deadline deadline) {
        if (font == null) {
            font = BitmapFont.load(stack, textures, deadline);
        }
        return font;
    }

    static double width(Line line, BitmapFont font) {
        double width = 0;
        for (ItemText.Span span : line.text.spans()) {
            boolean bold = Boolean.TRUE.equals(span.bold());
            String text = span.text();
            for (int i = 0; i < text.length(); ) {
                int cp = text.codePointAt(i);
                width += font.advance(cp, bold);
                i += Character.charCount(cp);
            }
        }
        return width;
    }

    /** The line, cut with "..." if it is wider than {@link #MAX_LINE_WIDTH}. */
    static Line truncate(Line line, BitmapFont font) {
        if (width(line, font) <= MAX_LINE_WIDTH) {
            return line;
        }
        double ellipsis = 3 * font.advance('.', false);
        double budget = MAX_LINE_WIDTH - ellipsis;
        List<ItemText.Span> kept = new ArrayList<ItemText.Span>();
        double used = 0;
        ItemText.Span last = null;
        outer:
        for (ItemText.Span span : line.text.spans()) {
            boolean bold = Boolean.TRUE.equals(span.bold());
            StringBuilder part = new StringBuilder();
            String text = span.text();
            for (int i = 0; i < text.length(); ) {
                int cp = text.codePointAt(i);
                double advance = font.advance(cp, bold);
                if (used + advance > budget) {
                    if (part.length() > 0) {
                        kept.add(copy(span, part.toString()));
                    }
                    last = span;
                    break outer;
                }
                used += advance;
                part.appendCodePoint(cp);
                i += Character.charCount(cp);
            }
            kept.add(span);
            last = span;
        }
        if (last != null) {
            kept.add(copy(last, "..."));
        }
        return new Line(ItemText.of(kept), line.color, line.italic);
    }

    private static ItemText.Span copy(ItemText.Span span, String text) {
        return new ItemText.Span(text, span.color(), span.bold(), span.italic(),
                span.underlined(), span.strikethrough(), span.obfuscated());
    }

    /** One line: the shadow pass, then the text pass, decorations included in both. */
    static void drawLine(Canvas canvas, BitmapFont font, Line line, double x, double y) {
        for (int pass = 0; pass < 2; pass++) {
            boolean shadow = pass == 0;
            double cursor = x + (shadow ? 1 : 0);
            double top = y + (shadow ? 1 : 0);
            for (ItemText.Span span : line.text.spans()) {
                int rgb = span.color() != null ? span.color() : line.color;
                if (shadow) {
                    rgb = (rgb & 0xFCFCFC) >> 2;
                }
                int argb = 0xFF000000 | rgb;
                boolean bold = Boolean.TRUE.equals(span.bold());
                boolean italic = span.italic() != null ? span.italic() : line.italic;
                double start = cursor;
                String text = span.text();
                for (int i = 0; i < text.length(); ) {
                    int cp = text.codePointAt(i);
                    cursor += font.draw(canvas, cp, cursor, top, argb, bold, italic);
                    i += Character.charCount(cp);
                }
                if (Boolean.TRUE.equals(span.strikethrough())) {
                    canvas.fill(start - 1, top + 3.5, cursor - start + 1, 1, argb);
                }
                if (Boolean.TRUE.equals(span.underlined())) {
                    canvas.fill(start - 1, top + 9, cursor - start + 1, 1, argb);
                }
            }
        }
    }

    /** The stack size in the icon's lower right corner, as the inventory shows it. */
    private static void count(Canvas canvas, BitmapFont font, ChatItem item, int iconX, int iconY) {
        if (item.count() <= 1) {
            return;
        }
        String text = Integer.toString(item.count());
        double width = 0;
        for (int i = 0; i < text.length(); i++) {
            width += font.advance(text.charAt(i), false);
        }
        Line line = new Line(ItemText.plain(text), WHITE, false);
        drawLine(canvas, font, line, iconX + 17 - width, iconY + 9);
    }

    // ── Box ──────────────────────────────────────────────────────────────────

    /** The pre-1.21.2 tooltip box, as vanilla's fill calls draw it around a text block. */
    static void classicBox(Canvas c, int x, int y, int w, int h) {
        int bg = 0xF0100010;
        int borderTop = 0x505000FF;
        int borderBottom = 0x5028007F;
        c.fill(x - 3, y - 4, w + 6, 1, bg);
        c.fill(x - 3, y + h + 3, w + 6, 1, bg);
        c.fill(x - 3, y - 3, w + 6, h + 6, bg);
        c.fill(x - 4, y - 3, 1, h + 6, bg);
        c.fill(x + w + 3, y - 3, 1, h + 6, bg);
        c.gradient(x - 3, y - 2, 1, h + 4, borderTop, borderBottom);
        c.gradient(x + w + 2, y - 2, 1, h + 4, borderTop, borderBottom);
        c.fill(x - 3, y - 3, w + 6, 1, borderTop);
        c.fill(x - 3, y + h + 2, w + 6, 1, borderBottom);
    }

    /** A tooltip sprite's {@code gui.scaling} from its {@code .mcmeta}, or {@code null}. */
    private Map<String, Object> spriteMeta(String name) {
        byte[] bytes = stack.read("assets/minecraft/textures/gui/sprites/tooltip/" + name
                + ".png.mcmeta", 64 * 1024);
        if (bytes == null) {
            return null;
        }
        try {
            Map<String, Object> meta = Snbt.asMap(Snbt.parse(AssetRoot.stripBom(
                    new String(bytes, StandardCharsets.UTF_8))));
            Map<String, Object> gui = Snbt.asMap(meta == null ? null : meta.get("gui"));
            return Snbt.asMap(gui == null ? null : gui.get("scaling"));
        } catch (Snbt.SyntaxException malformed) {
            return null;
        }
    }

    /**
     * Draws a sprite into a GUI rectangle: nine-sliced when its scaling says so (corners fixed,
     * edges and centre tiled, or stretched with {@code stretch_inner}), stretched otherwise.
     */
    static void nineSlice(Canvas c, BufferedImage sprite, Map<String, Object> scaling, int x,
            int y, int w, int h) {
        int s = c.scale;
        boolean nine = scaling != null
                && "nine_slice".equals(ModelResolver.strip(Snbt.asString(scaling.get("type"))));
        if (!nine) {
            blit(c, sprite, 0, 0, sprite.getWidth(), sprite.getHeight(), x * s, y * s, w * s,
                    h * s, false, 1);
            return;
        }
        double logicalWidth = Snbt.asDouble(scaling.get("width"), (double) sprite.getWidth());
        double perGui = sprite.getWidth() / Math.max(1.0, logicalWidth);
        int left;
        int top;
        int right;
        int bottom;
        Object border = scaling.get("border");
        Map<String, Object> sides = Snbt.asMap(border);
        if (sides != null) {
            left = Snbt.asInt(sides.get("left"), 0);
            top = Snbt.asInt(sides.get("top"), 0);
            right = Snbt.asInt(sides.get("right"), 0);
            bottom = Snbt.asInt(sides.get("bottom"), 0);
        } else {
            left = top = right = bottom = Snbt.asInt(border, 0);
        }
        boolean tile = !Boolean.TRUE.equals(Snbt.asBoolean(scaling.get("stretch_inner"), false));
        int sw = sprite.getWidth();
        int sh = sprite.getHeight();
        int[] srcX = {0, (int) Math.round(left * perGui), sw - (int) Math.round(right * perGui), sw};
        int[] srcY = {0, (int) Math.round(top * perGui), sh - (int) Math.round(bottom * perGui), sh};
        int[] dstX = {x * s, (x + left) * s, (x + w - right) * s, (x + w) * s};
        int[] dstY = {y * s, (y + top) * s, (y + h - bottom) * s, (y + h) * s};
        double srcPerOut = perGui / s;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                boolean inner = row == 1 || col == 1;
                blit(c, sprite, srcX[col], srcY[row], srcX[col + 1] - srcX[col],
                        srcY[row + 1] - srcY[row], dstX[col], dstY[row],
                        dstX[col + 1] - dstX[col], dstY[row + 1] - dstY[row],
                        inner && tile, srcPerOut);
            }
        }
    }

    /** Copies a source region into an output-pixel rectangle, stretched or tiled, nearest-neighbour. */
    private static void blit(Canvas c, BufferedImage src, int sx, int sy, int sw, int sh, int dx,
            int dy, int dw, int dh, boolean tile, double srcPerOut) {
        if (sw <= 0 || sh <= 0 || dw <= 0 || dh <= 0) {
            return;
        }
        for (int oy = 0; oy < dh; oy++) {
            int py = tile ? (int) (oy * srcPerOut) % sh : (int) ((long) oy * sh / dh);
            for (int ox = 0; ox < dw; ox++) {
                int px = tile ? (int) (ox * srcPerOut) % sw : (int) ((long) ox * sw / dw);
                c.blend(dx + ox, dy + oy, src.getRGB(sx + px, sy + py));
            }
        }
    }
}
