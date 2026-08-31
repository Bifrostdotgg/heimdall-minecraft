package com.heimdall.module.punishments;

import com.heimdall.core.log.HeimdallLogger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Country of a connecting address. No live HTTP at login.
 *
 * <p>Looks for {@code geo-countries.tsv} (prefix, tab, ISO code) in the plugin data directory.
 * A GeoLite2 {@code geolite2-country.mmdb} file is recognised so the operator can drop one, but
 * this jar does not vendor a MaxMind reader; a present MMDB without a TSV fails open with a log.
 */
final class GeoCountryLookup {

    private final HeimdallLogger logger;
    private final List<String> prefixes = new ArrayList<String>();
    private final List<String> countries = new ArrayList<String>();
    private boolean warned;

    GeoCountryLookup(HeimdallLogger logger, Path dataDirectory) {
        this.logger = logger;
        Path tsv = dataDirectory.resolve("geo-countries.tsv");
        Path mmdb = dataDirectory.resolve("geolite2-country.mmdb");
        if (Files.isRegularFile(tsv)) {
            loadTsv(tsv);
        } else if (Files.isRegularFile(mmdb)) {
            logger.warn("geolite2-country.mmdb is present but this build has no MMDB parser; "
                    + "geo login checks fail open. Add geo-countries.tsv (CIDR<TAB>ISO) or wait "
                    + "for a reader.");
            warned = true;
        } else {
            logger.warn("no geo-countries.tsv or geolite2-country.mmdb in the data directory; "
                    + "geo punishments will not deny anyone");
            warned = true;
        }
    }

    String countryOf(String ip) {
        if (ip == null || ip.isEmpty() || prefixes.isEmpty()) return null;
        for (int i = 0; i < prefixes.size(); i++) {
            if (Cidr.matches(prefixes.get(i), ip)) {
                return countries.get(i);
            }
        }
        return null;
    }

    boolean ready() {
        return !prefixes.isEmpty();
    }

    private void loadTsv(Path tsv) {
        try {
            List<String> lines = Files.readAllLines(tsv, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                int tab = line.indexOf('\t');
                if (tab < 0) tab = line.indexOf(' ');
                if (tab <= 0) continue;
                prefixes.add(line.substring(0, tab).trim());
                countries.add(line.substring(tab + 1).trim().toUpperCase());
            }
            logger.info("geo-countries.tsv loaded (" + prefixes.size() + " prefixes)");
        } catch (Exception e) {
            if (!warned) {
                logger.warn("could not read geo-countries.tsv; geo checks fail open: " + e);
                warned = true;
            }
        }
    }
}
