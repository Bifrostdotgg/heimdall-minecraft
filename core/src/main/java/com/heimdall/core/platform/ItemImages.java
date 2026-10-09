package com.heimdall.core.platform;

import com.heimdall.core.items.ChatItem;
import com.heimdall.core.items.ItemTranslations;
import java.util.concurrent.CompletableFuture;

/**
 * Draws the tooltip card for an item a player showed in chat, as a PNG. Departure D86.
 *
 * <p>Only a Bukkit backend can answer: it is the only place with items, with the vanilla client
 * assets it can fetch, with the resource packs the server sends, and with {@code java.awt}. Both
 * proxies get {@link #NONE}, through the default {@link Integrations#itemImages()}, and so does a
 * backend whose JVM has no usable {@code java.awt}.
 *
 * <h2>Contract</h2>
 *
 * <ul>
 *   <li>{@link #render} never blocks its caller and never throws: the work runs on the
 *       implementation's own bounded executor, and every failure (no assets yet, a broken pack, a
 *       time budget exceeded, a full queue) completes the future exceptionally or with {@code null}.
 *       The caller decides how long it waits; a slow render costs an image, never a chat line.
 *   <li>{@link #translate} answers from language data already loaded and never fetches.
 *   <li>Nothing is stored beyond a bounded cache of rendered images keyed by what was drawn. No
 *       name or lore text is ever logged.
 * </ul>
 */
public interface ItemImages extends ItemTranslations {

    /** Renders nothing, translates nothing. What proxies and image-less servers answer. */
    ItemImages NONE = new ItemImages() {
        @Override
        public boolean available() {
            return false;
        }

        @Override
        public void prepare() {
        }

        @Override
        public String translate(String key) {
            return null;
        }

        @Override
        public CompletableFuture<byte[]> render(ChatItem item) {
            return CompletableFuture.completedFuture(null);
        }
    };

    /** Whether this platform can produce images at all. Cheap; safe to call per line. */
    boolean available();

    /**
     * Starts preparing assets (vanilla download, pack index) in the background, if they are not
     * ready yet. Idempotent and non-blocking; called when image rendering is switched on, so the
     * first item shown does not pay for the download.
     */
    void prepare();

    /**
     * Starts drawing {@code item}.
     *
     * @return a future completing with PNG bytes, or with {@code null} / exceptionally when no image
     *     can be produced
     */
    CompletableFuture<byte[]> render(ChatItem item);
}
