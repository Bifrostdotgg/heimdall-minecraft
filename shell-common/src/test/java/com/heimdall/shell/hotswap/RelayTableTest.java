package com.heimdall.shell.hotswap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.util.Registration;
import com.heimdall.shell.contract.CommandBinding;
import com.heimdall.shell.contract.CommandTarget;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Command relays: the one object the platform holds for a Heimdall command, and what it says in
 * every state the shell can be in (departure D87).
 */
class RelayTableTest {

    private final FixturePlatform.FakeCommands platform = new FixturePlatform.FakeCommands();
    private final FixturePlatform.RecordingAudience audience = new FixturePlatform.RecordingAudience();
    private final RecordingShellLog log = new RecordingShellLog();
    private final State state = new State();
    private final RelayTable relays = new RelayTable(platform, audience, log, state);
    private final List<String> calls = new ArrayList<String>();
    private final List<String> adminCalls = new ArrayList<String>();

    /** The shell's state, settable per test. */
    private static final class State implements RelayTable.State {

        volatile boolean swapping;
        volatile boolean hasCore = true;

        @Override
        public boolean isSwapping() {
            return swapping;
        }

        @Override
        public boolean hasCore() {
            return hasCore;
        }

        @Override
        public String adminLabel() {
            return "hd";
        }
    }

    private CommandBinding binding(final String name, final String tag, String... aliases) {
        return CommandBinding.named(name)
                .aliases(Arrays.asList(aliases))
                .permission("heimdall.test")
                .target(new CommandTarget() {
                    @Override
                    public void execute(Object sender, String label, String[] args) {
                        calls.add(tag + " " + label + " " + Arrays.toString(args));
                    }

                    @Override
                    public List<String> complete(Object sender, String alias, String[] args) {
                        return Collections.singletonList(tag);
                    }
                })
                .build();
    }

    private void withAdmin() {
        relays.admin(new RelayTable.AdminVerbs() {
            @Override
            public boolean handle(Object sender, String label, String[] args, boolean coreBound) {
                if (args.length > 0 && "swap".equals(args[0])) {
                    adminCalls.add("swap");
                    return true;
                }
                return false;
            }

            @Override
            public List<String> verbs() {
                return Arrays.asList("status", "swap");
            }
        });
    }

    @Test
    @DisplayName("a bound relay forwards sender, label and arguments to the core's target")
    void forwards() {
        relays.bind(binding("ban", "core-1", "tempban"));

        relays.find("ban").execute("Steve", "tempban", new String[] {"Alex", "1d"});

        assertEquals(Arrays.asList("core-1 tempban [Alex, 1d]"), calls);
        assertEquals(Arrays.asList("ban[tempban]"), platform.registered);
        assertEquals(Collections.singletonList("core-1"),
                relays.find("ban").complete("Steve", "ban", new String[] {""}));
    }

    @Test
    @DisplayName("outside a swap, unbinding a runtime command gives the name back to the platform")
    void unbindOutsideASwapUnregisters() {
        Registration bound = relays.bind(binding("ban", "core-1"));

        bound.close();

        assertEquals(Arrays.asList("ban"), platform.unregistered);
        assertNull(relays.find("ban"), "a module switched off must give its verb back (LiteBans)");
    }

    @Test
    @DisplayName("during a swap, unbinding keeps the command and it answers 'updating'")
    void unbindDuringASwapKeepsTheCommand() {
        Registration bound = relays.bind(binding("ban", "core-1"));
        state.swapping = true;

        bound.close();
        relays.find("ban").execute("Steve", "ban", new String[0]);

        assertTrue(platform.unregistered.isEmpty(), "a command must never vanish mid-swap");
        assertEquals("Steve: " + ShellMessages.UPDATING, audience.last());
    }

    @Test
    @DisplayName("the new core rebinds the same relay; prune removes only what nobody rebound")
    void rebindAndPrune() {
        Registration ban = relays.bind(binding("ban", "core-1"));
        Registration mute = relays.bind(binding("mute", "core-1"));
        Relay banRelay = relays.find("ban");
        state.swapping = true;
        ban.close();
        mute.close();

        relays.bind(binding("ban", "core-2"));
        state.swapping = false;
        int pruned = relays.prune();

        assertEquals(1, pruned);
        assertSame(banRelay, relays.find("ban"), "the platform object must be the same one");
        assertNull(relays.find("mute"));
        assertEquals(Arrays.asList("mute"), platform.unregistered);
        banRelay.execute("Steve", "ban", new String[0]);
        assertEquals("core-2 ban []", calls.get(0));
    }

    @Test
    @DisplayName("a rebind with different aliases re-registers, because only then does a proxy learn them")
    void changedAliasesReRegister() {
        Registration first = relays.bind(binding("ban", "core-1", "b"));
        state.swapping = true;
        first.close();

        relays.bind(binding("ban", "core-2", "b", "tempban"));

        assertEquals(Arrays.asList("ban[b]", "ban[b, tempban]"), platform.registered);
        assertEquals(Arrays.asList("ban"), platform.unregistered);
    }

    @Test
    @DisplayName("an old generation's late unbind does not unbind its successor")
    void staleUnbindIsIgnored() {
        Registration first = relays.bind(binding("ban", "core-1"));
        relays.bind(binding("ban", "core-2"));

        first.close();

        assertNotNull(relays.find("ban").bound());
        relays.find("ban").execute("Steve", "ban", new String[0]);
        assertEquals("core-2 ban []", calls.get(0));
    }

    @Test
    @DisplayName("a permanent relay with nothing bound says 'switched off' with a core, 'not running' without")
    void permanentRelayAnswers() {
        relays.installPermanent("offend", Collections.<String>emptyList(), "heimdall.offend",
                "", "", false);
        Relay offend = relays.find("offend");

        offend.execute("Steve", "offend", new String[0]);
        assertTrue(audience.last().contains("switched off"), audience.last());

        state.hasCore = false;
        offend.execute("Steve", "offend", new String[0]);
        assertTrue(audience.last().contains("not running"), audience.last());
        assertTrue(audience.last().contains("/hd status"), audience.last());

        Registration bound = relays.bind(binding("offend", "core-1"));
        bound.close();
        assertNotNull(relays.find("offend"), "a permanent relay outlives every binding");
        assertTrue(platform.unregistered.isEmpty());
    }

    @Test
    @DisplayName("the admin verb 'swap' is the shell's, with or without a core bound")
    void adminSwapIsIntercepted() {
        withAdmin();
        relays.installPermanent("hd", Collections.singletonList("heimdall"), "heimdall.admin",
                "", "", true);
        relays.bind(binding("hd", "core-1", "heimdall"));

        relays.find("hd").execute("console", "hd", new String[] {"swap"});
        relays.find("hd").execute("console", "hd", new String[] {"status"});

        assertEquals(Arrays.asList("swap"), adminCalls);
        assertEquals(Arrays.asList("core-1 hd [status]"), calls, "status is the core's when it runs");
    }

    @Test
    @DisplayName("a target that throws is contained: the sender is told, the server log has it")
    void throwingTargetIsContained() {
        relays.bind(CommandBinding.named("boom").target(new CommandTarget() {
            @Override
            public void execute(Object sender, String label, String[] args) {
                throw new NoClassDefFoundError("a closed classloader");
            }

            @Override
            public List<String> complete(Object sender, String alias, String[] args) {
                throw new IllegalStateException("broken");
            }
        }).build());

        relays.find("boom").execute("Steve", "boom", new String[0]);

        assertEquals("Steve: " + ShellMessages.COMMAND_FAILED, audience.last());
        assertTrue(log.errors().get(0).contains("/boom failed"), log.lines().toString());
        assertTrue(relays.find("boom").complete("Steve", "boom", new String[0]).isEmpty());
    }

    @Test
    @DisplayName("visibility follows the bound permission, and the installed one while unbound")
    void visibility() {
        relays.installPermanent("hd", Collections.<String>emptyList(), "heimdall.admin", "", "", true);
        audience.grantAll = false;

        assertFalse(relays.find("hd").visibleTo("Steve"));
        audience.grantAll = true;
        assertTrue(relays.find("hd").visibleTo("Steve"));
    }

    @Test
    @DisplayName("a platform that refuses a name leaves no relay and a warning")
    void refusedRegistration() {
        platform.refuse = true;

        Registration bound = relays.bind(binding("ban", "core-1"));

        assertSame(Registration.NONE, bound);
        assertNull(relays.find("ban"));
        assertFalse(log.warnings().isEmpty());
    }
}
