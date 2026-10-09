package org.mineacademy.chatcontrol.settings;

/** Stand-in for ChatControl's settings holder; only the field Heimdall reads. */
public class Settings {

    /** {@code Settings$Channels}, as ChatControl names it. */
    public static class Channels {

        /** Off by default in ChatControl's shipped config; null until settings load. */
        public static Boolean ENABLED;
    }
}
