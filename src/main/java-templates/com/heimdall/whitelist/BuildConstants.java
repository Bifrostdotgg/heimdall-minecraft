package com.heimdall.whitelist;

/**
 * Build-time constants generated from the Maven project metadata.
 *
 * <p>This file is a template under {@code src/main/java-templates}; the
 * templating-maven-plugin filters {@code ${project.version}} from the POM into
 * the generated source at build time. It is the single source of truth for the
 * plugin version — do not hardcode the version anywhere else (plugin.yml reads
 * it via resource filtering, and the Velocity {@code @Plugin} annotation,
 * User-Agent header, and update checker all reference {@link #VERSION}).
 */
public final class BuildConstants {

  /** The plugin version, e.g. {@code "2.0.0"}, sourced from the Maven POM. */
  public static final String VERSION = "${project.version}";

  private BuildConstants() {}
}
