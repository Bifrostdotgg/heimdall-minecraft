package com.heimdall.module.whitelist;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.heimdall.core.config.ServerRole;
import com.heimdall.core.json.Payload;
import com.heimdall.core.roles.RoleSyncLoginSource.Coverage;
import java.nio.file.Path;
import java.util.Collections;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code coverageFor}: the answer role sync uses to decide whether to ask for a snapshot itself.
 *
 * <p>{@code NOT_COVERED} and {@code NO_LOGIN_CHECK} are joins role sync may have to cover on its
 * own; {@code DELIVERS} and {@code DEFERS_TO_GATEKEEPER} are ones where asking would be a second
 * bot call for the same login on the network. The cases are the whitelist's
 * reasons to skip the bot, which is why they are read from the same place the login decision is.
 */
class WhitelistRoleSyncSourceTest {

    private static final UUID STEVE = UUID.fromString(WhitelistHarness.ALLOWED);

    private static Payload settings() {
        return Payload.builder().put("prewarmEnabled", false).build();
    }

    @Test
    @DisplayName("an enforcing whitelist sends the login to the bot, so role sync need not ask")
    void enabledDelivers(@TempDir Path dir) {
        try (WhitelistHarness h = WhitelistHarness.standalone(dir)) {
            h.enableWith(settings());

            assertEquals(Coverage.DELIVERS, h.module.coverageFor(STEVE));
        }
    }

    @Test
    @DisplayName("a module that is not running delivers nothing, whatever the config once said")
    void notRunningDeliversNothing(@TempDir Path dir) {
        try (WhitelistHarness h = WhitelistHarness.standalone(dir)) {
            assertEquals(Coverage.NO_LOGIN_CHECK, h.module.coverageFor(STEVE), "never enabled");

            h.enableWith(settings());
            h.disableModule();

            assertEquals(Coverage.NO_LOGIN_CHECK, h.module.coverageFor(STEVE), "switched off");
        }
    }

    @Test
    @DisplayName("a bypassed player is admitted without asking, so role sync here has to")
    void bypassedDeliversNothing(@TempDir Path dir) {
        try (WhitelistHarness h = WhitelistHarness.standalone(dir)) {
            h.enableWith(Payload.builder()
                    .put("prewarmEnabled", false)
                    .putStrings("bypassUuids", Collections.singletonList(STEVE.toString()))
                    .build());

            assertEquals(Coverage.NOT_COVERED, h.module.coverageFor(STEVE));
        }
    }

    @Test
    @DisplayName("a backend with enforceOnBackend off defers to the proxy, which makes the call")
    void backendThatAbstainsDeliversNothing(@TempDir Path dir) {
        try (WhitelistHarness h = WhitelistHarness.withRole(dir, ServerRole.ENFORCER)) {
            h.enableWith(Payload.builder()
                    .put("prewarmEnabled", false)
                    .put("enforceOnBackend", false)
                    .build());

            assertEquals(Coverage.DEFERS_TO_GATEKEEPER, h.module.coverageFor(STEVE),
                    "the proxy makes the network's one call, so the backend must not ask again");
        }
    }

    @Test
    @DisplayName("a backend that re-checks logins does deliver")
    void enforcingBackendDelivers(@TempDir Path dir) {
        try (WhitelistHarness h = WhitelistHarness.withRole(dir, ServerRole.ENFORCER)) {
            h.enableWith(settings());

            assertEquals(Coverage.DELIVERS, h.module.coverageFor(STEVE));
        }
    }
}
