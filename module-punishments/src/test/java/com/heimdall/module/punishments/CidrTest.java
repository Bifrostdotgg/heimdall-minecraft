package com.heimdall.module.punishments;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CidrTest {

    @Test
    void ipv4Prefix() {
        assertTrue(Cidr.matches("203.0.113.0/24", "203.0.113.9"));
        assertFalse(Cidr.matches("203.0.113.0/24", "203.0.114.9"));
        assertTrue(Cidr.matches("10.0.0.1/32", "10.0.0.1"));
    }
}
