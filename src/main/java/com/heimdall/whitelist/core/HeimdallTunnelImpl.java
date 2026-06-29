package com.heimdall.whitelist.core;

import com.google.gson.JsonObject;
import com.heimdall.whitelist.api.HeimdallTunnel;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Default {@link HeimdallTunnel} backed by the plugin's {@link WebSocketClient}.
 * Inbound messages are dispatched via {@link #dispatchInbound} from the plugin's
 * existing message handler (for types it doesn't handle natively).
 */
public final class HeimdallTunnelImpl implements HeimdallTunnel {

  private final WebSocketClient ws;
  private final Map<String, BiConsumer<JsonObject, Consumer<JsonObject>>> handlers = new ConcurrentHashMap<>();

  public HeimdallTunnelImpl(WebSocketClient ws) {
    this.ws = ws;
  }

  @Override
  public boolean isConnected() {
    return ws.isConnected();
  }

  @Override
  public void publish(String type, JsonObject payload) {
    ws.send(type, payload);
  }

  @Override
  public CompletableFuture<JsonObject> request(String type, JsonObject payload, long timeoutMs) {
    return ws.sendAndWait(type, payload, timeoutMs);
  }

  @Override
  public void on(String type, BiConsumer<JsonObject, Consumer<JsonObject>> handler) {
    handlers.put(type, handler);
  }

  /**
   * Dispatch an inbound message to a registered handler, if any.
   *
   * @return true if a handler consumed it; false if the type is unregistered.
   */
  public boolean dispatchInbound(String type, String id, JsonObject payload) {
    BiConsumer<JsonObject, Consumer<JsonObject>> handler = handlers.get(type);
    if (handler == null) {
      return false;
    }
    handler.accept(payload, reply -> ws.reply(id, type + ".result", reply != null ? reply : new JsonObject()));
    return true;
  }
}
