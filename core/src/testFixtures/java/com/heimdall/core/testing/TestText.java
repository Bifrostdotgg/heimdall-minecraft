package com.heimdall.core.testing;

import com.heimdall.core.text.Msg;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;

/**
 * Flattens a {@link Component} to the plain text a test wants to assert about.
 *
 * <p>Built on {@link Msg#toLegacy} and a §-code strip rather than on Adventure's own
 * {@code PlainTextComponentSerializer}, because that class ships in a separate artifact that core
 * does not depend on — and adding one so a fixture can read a string would put a library on the
 * shipped classpath for the sake of the tests.
 *
 * <p>The pattern removes {@code §} and whatever follows it, which covers ordinary colour codes and
 * the repeated-character hex form {@code Msg} emits (departure: {@code §x§f§f§0§0§0§0}) in one rule,
 * since that form is just several such pairs in a row.
 */
public final class TestText {

    private static final Pattern SECTION_CODE = Pattern.compile("§.", Pattern.DOTALL);

    private TestText() {
    }

    /** The component's text, with every colour code removed. */
    public static String plain(Component component) {
        if (component == null) {
            return "";
        }
        return SECTION_CODE.matcher(Msg.toLegacy(component)).replaceAll("");
    }
}
