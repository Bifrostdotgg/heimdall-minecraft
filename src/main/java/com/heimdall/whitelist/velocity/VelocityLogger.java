package com.heimdall.whitelist.velocity;

import com.heimdall.whitelist.core.PluginLogger;
import org.slf4j.Logger;

/**
 * Velocity implementation of PluginLogger
 */
public class VelocityLogger implements PluginLogger {

  private final Logger logger;
  private volatile boolean debugEnabled;

  public VelocityLogger(Logger logger, boolean debugEnabled) {
    this.logger = logger;
    this.debugEnabled = debugEnabled;
  }

  public void setDebugEnabled(boolean debugEnabled) {
    this.debugEnabled = debugEnabled;
  }

  @Override
  public void info(String message) {
    logger.info(message);
  }

  @Override
  public void warning(String message) {
    logger.warn(message);
  }

  @Override
  public void severe(String message) {
    logger.error(message);
  }

  @Override
  public void debug(String message) {
    if (debugEnabled) {
      logger.info("[DEBUG] " + message);
    }
  }
}
