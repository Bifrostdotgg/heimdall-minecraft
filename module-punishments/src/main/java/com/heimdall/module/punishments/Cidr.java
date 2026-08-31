package com.heimdall.module.punishments;

import java.net.InetAddress;

/**
 * Canonical CIDR match. Never log or print the CIDR from a staff-facing screen.
 */
final class Cidr {

    private Cidr() {
    }

    static String canonical(String cidr) {
        if (cidr == null) return "";
        return cidr.trim().toLowerCase();
    }

    static boolean matches(String cidr, String ip) {
        if (cidr == null || ip == null || cidr.isEmpty() || ip.isEmpty()) return false;
        String spec = cidr.trim();
        int slash = spec.indexOf('/');
        if (slash <= 0) return spec.equalsIgnoreCase(ip.trim());
        String network = spec.substring(0, slash);
        int prefix;
        try {
            prefix = Integer.parseInt(spec.substring(slash + 1).trim());
        } catch (NumberFormatException e) {
            return false;
        }
        try {
            byte[] net = InetAddress.getByName(network).getAddress();
            byte[] addr = InetAddress.getByName(ip.trim()).getAddress();
            if (net.length != addr.length) return false;
            int max = net.length * 8;
            if (prefix < 0 || prefix > max) return false;
            int full = prefix / 8;
            for (int i = 0; i < full; i++) {
                if (net[i] != addr[i]) return false;
            }
            int rem = prefix % 8;
            if (rem == 0) return true;
            int mask = 0xFF << (8 - rem);
            return (net[full] & mask) == (addr[full] & mask);
        } catch (Exception e) {
            return false;
        }
    }
}
