package com.heimdall.whitelist.core;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Config-driven whitelist bypass — issue #796 / MC-2.
 *
 * Pins the cross-platform UUID allowlist used by both the Paper and Velocity
 * login listeners now that the {@code heimdall.bypass} permission can't gate
 * login (permissions aren't attached at pre-login).
 */
class BypassListTest {

  private static final String UUID = "11111111-2222-3333-4444-555555555555";
  private static final String OTHER = "99999999-8888-7777-6666-555555555555";

  @Test
  void exactMatchIsBypassed() {
    assertTrue(BypassList.isBypassed(Collections.singletonList(UUID), UUID));
  }

  @Test
  void nonMemberIsNotBypassed() {
    assertFalse(BypassList.isBypassed(Collections.singletonList(UUID), OTHER));
  }

  @Test
  void matchingIsCaseInsensitive() {
    assertTrue(BypassList.isBypassed(
        Collections.singletonList(UUID.toUpperCase()), UUID.toLowerCase()));
  }

  @Test
  void surroundingWhitespaceIsTrimmed() {
    assertTrue(BypassList.isBypassed(
        Collections.singletonList("  " + UUID + "  "), UUID));
  }

  @Test
  void matchesAnyEntryInTheList() {
    List<String> list = Arrays.asList(OTHER, UUID);
    assertTrue(BypassList.isBypassed(list, UUID));
  }

  @Test
  void emptyOrNullListBypassesNobody() {
    assertFalse(BypassList.isBypassed(Collections.emptyList(), UUID));
    assertFalse(BypassList.isBypassed(null, UUID));
  }

  @Test
  void nullPlayerUuidIsNeverBypassed() {
    assertFalse(BypassList.isBypassed(Collections.singletonList(UUID), null));
  }

  @Test
  void nullEntriesInListAreSkipped() {
    List<String> list = Arrays.asList(null, UUID);
    assertTrue(BypassList.isBypassed(list, UUID));
    assertFalse(BypassList.isBypassed(Collections.singletonList((String) null), UUID));
  }
}
