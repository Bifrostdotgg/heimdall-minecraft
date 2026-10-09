package com.heimdall.core.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.http.BedrockIdentityProvider;
import com.heimdall.core.json.Payload;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The platform-free half of chat-channel awareness: the null object and the default seam. */
class ChatChannelsTest {

    @Test
    @DisplayName("a platform that says nothing about channels gets NONE, not null")
    void integrationsDefaultToNone() {
        // The proxies implement Integrations without knowing this method exists. That is the whole
        // reason it is a default method, and the bridge reads it on every inbound frame.
        Integrations bare = new Integrations() {
            @Override
            public Optional<LuckPermsBridge> luckPerms() {
                return Optional.empty();
            }

            @Override
            public BedrockIdentityProvider floodgate() {
                return BedrockIdentityProvider.NONE;
            }

            @Override
            public CompletableFuture<Payload> traceProbe(UUID playerUuid) {
                return CompletableFuture.completedFuture(Payload.empty());
            }
        };

        assertSame(ChatChannels.NONE, bare.chatChannels());
    }

    @Test
    @DisplayName("NONE is one public room: no channels, no members, state none")
    void noneIsOnePublicRoom() {
        assertEquals(ChatChannels.State.NONE, ChatChannels.NONE.state());
        assertTrue(ChatChannels.NONE.channelNames().isEmpty());
        assertFalse(ChatChannels.NONE.members("staff").isPresent(),
                "with no channel plugin there is no such channel, which is not the same answer as "
                        + "a channel with nobody in it");
    }

    @Test
    @DisplayName("the state names are a wire contract with the bot")
    void wireNames() {
        assertEquals("none", ChatChannels.State.NONE.wireName());
        assertEquals("active", ChatChannels.State.ACTIVE.wireName());
        assertEquals("broken", ChatChannels.State.BROKEN.wireName());
    }
}
