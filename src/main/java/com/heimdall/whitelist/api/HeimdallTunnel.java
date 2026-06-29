package com.heimdall.whitelist.api;

import com.google.gson.JsonObject;

import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Inter-plugin bridge over the Heimdall Whitelist plugin's single outbound
 * WebSocket connection. Other server plugins (e.g. Trace) can ride this one
 * socket instead of opening their own — publish events to the dashboard, make
 * request/response RPC calls, and subscribe to dashboard→server messages.
 *
 * <p>Obtain it from the Bukkit {@link org.bukkit.plugin.ServicesManager}:
 * <pre>{@code
 * var reg = Bukkit.getServicesManager().getRegistration(HeimdallTunnel.class);
 * HeimdallTunnel tunnel = reg != null ? reg.getProvider() : null;
 * }</pre>
 *
 * <p>Plugins that depend on this one can reference this interface directly;
 * plugins that only soft-depend should look it up reflectively so they keep
 * working when the Whitelist plugin is absent.
 */
public interface HeimdallTunnel {

  /** Whether the underlying socket is currently connected. */
  boolean isConnected();

  /** Fire-and-forget event to the dashboard side. */
  void publish(String type, JsonObject payload);

  /** Request/response RPC; the returned future completes with the reply payload. */
  CompletableFuture<JsonObject> request(String type, JsonObject payload, long timeoutMs);

  /**
   * Subscribe to an inbound (dashboard→server) message type. The handler
   * receives the message payload and a reply callback used to answer the
   * request (correlated by id automatically).
   */
  void on(String type, BiConsumer<JsonObject, Consumer<JsonObject>> handler);
}
