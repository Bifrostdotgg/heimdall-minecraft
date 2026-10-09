package com.heimdall.core.items;

/**
 * The language data an item tooltip needs: translation keys to English text.
 *
 * <p>Platform-free code cannot read a client jar, so it asks through this. On a Bukkit backend the
 * answer comes from the vanilla {@code en_us} language file (and any resource pack's), once it has
 * been fetched and indexed; everywhere else, and before that, it is {@link #NONE} and callers fall
 * back to something readable they can build themselves (a humanised id, the raw key).
 *
 * <p>Implementations must answer from what is already loaded: never block, never fetch, never throw.
 * The caller can be a chat thread.
 */
public interface ItemTranslations {

    /** Knows nothing. */
    ItemTranslations NONE = new ItemTranslations() {
        @Override
        public String translate(String key) {
            return null;
        }
    };

    /** The English text for {@code key}, or {@code null} if it is not known. */
    String translate(String key);
}
