package litebans.api;

/**
 * Compile-only stub of LiteBans 2.x {@code litebans.api.Events}. Not shipped.
 */
public abstract class Events {

    public static Events get() {
        throw new UnsupportedOperationException("stub");
    }

    public abstract void register(Listener listener);

    public abstract void unregister(Listener listener);

    public static abstract class Listener {
        public void entryAdded(Entry entry) {
        }

        public void entryRemoved(Entry entry) {
        }
    }
}
