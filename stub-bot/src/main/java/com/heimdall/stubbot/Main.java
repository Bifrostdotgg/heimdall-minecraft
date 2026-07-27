package com.heimdall.stubbot;

import java.util.concurrent.CountDownLatch;

/**
 * Standalone entry point.
 *
 * <pre>
 * ./gradlew :stub-bot:run --args="--port=8080 --guild-id=123456789012345678"
 * stub-bot/build/install/stub-bot/bin/stub-bot          # after ./gradlew build
 * </pre>
 *
 * <p>Configuration comes from {@code STUB_BOT_*} environment variables and {@code --key=value}
 * arguments (arguments win). See {@code stub-bot/README.md} for the full list.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        StubBotConfig config = StubBotConfig.fromEnvironment(System.getenv(), args);
        StubBot bot = StubBot.start(config);

        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            bot.close();
            stopped.countDown();
        }, "stub-bot-shutdown"));

        try {
            stopped.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
