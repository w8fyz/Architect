package sh.fyz.architect.migration;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Version parsed out of a migration filename.
 *
 * <p>Files follow {@code V<version>__<description>.sql} — for example
 * {@code V1__initial_schema.sql} or {@code V2026.07.31__add_orders.sql}. The
 * version is one or more numeric segments separated by {@code .} or {@code _},
 * which lets a project number migrations sequentially ({@code V1}, {@code V2})
 * or by date ({@code V2026_07_31_1200}) without the runner caring which.</p>
 *
 * <p>Ordering is numeric per segment, not lexicographic: {@code V10} sorts after
 * {@code V9}, which a plain filename sort would get wrong. When one version is a
 * prefix of another the shorter one comes first ({@code V1} before {@code V1.1}).</p>
 */
public record MigrationVersion(String raw, List<Long> segments, String description)
        implements Comparable<MigrationVersion> {

    private static final Pattern FILENAME = Pattern.compile(
            "^V(?<version>\\d+(?:[._]\\d+)*)__(?<description>.+)\\.sql$"
    );

    /**
     * Parses a migration filename.
     *
     * @return the parsed version, or {@code null} when the name does not follow
     *         the convention — callers surface those as ignored rather than
     *         failing the whole run, so an unrelated {@code .sql} file dropped in
     *         the directory cannot block a deployment.
     */
    public static MigrationVersion parse(String filename) {
        if (filename == null) {
            return null;
        }
        Matcher m = FILENAME.matcher(filename);
        if (!m.matches()) {
            return null;
        }
        String version = m.group("version");
        List<Long> segments = new ArrayList<>();
        for (String part : version.split("[._]")) {
            try {
                segments.add(Long.parseLong(part));
            } catch (NumberFormatException e) {
                // Unreachable while the pattern only admits digits, but a
                // segment longer than a long would land here.
                return null;
            }
        }
        return new MigrationVersion(version, List.copyOf(segments), m.group("description"));
    }

    /** Filename this version came from, reconstructed. */
    public String filename() {
        return "V" + raw + "__" + description + ".sql";
    }

    @Override
    public int compareTo(MigrationVersion other) {
        int size = Math.max(segments.size(), other.segments.size());
        for (int i = 0; i < size; i++) {
            long a = i < segments.size() ? segments.get(i) : 0L;
            long b = i < other.segments.size() ? other.segments.get(i) : 0L;
            int cmp = Long.compare(a, b);
            if (cmp != 0) {
                return cmp;
            }
        }
        // Same numeric value written differently (V1 vs V1.0): fall back to the
        // literal so ordering stays total and stable.
        return raw.compareTo(other.raw);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MigrationVersion other)) return false;
        return Objects.equals(raw, other.raw) && Objects.equals(description, other.description);
    }

    @Override
    public int hashCode() {
        return Objects.hash(raw, description);
    }

    @Override
    public String toString() {
        return raw;
    }
}
