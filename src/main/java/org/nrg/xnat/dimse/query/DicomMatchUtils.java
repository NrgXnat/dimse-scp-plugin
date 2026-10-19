package org.nrg.xnat.dimse.query;

import org.apache.commons.lang.StringUtils;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * DICOM-standard matching utilities for C-FIND query filtering.
 *
 * <p>Implements the matching rules defined in PS3.4 C.2.2.2:
 * universal matching, single value matching, wildcard matching,
 * date range matching, and UID list matching.</p>
 */
public final class DicomMatchUtils {

    private DicomMatchUtils() {}

    /**
     * Build a predicate for whether a candidate Attributes object matches all
     * non-empty query keys.
     *
     * @param query     the DICOM query keys from the C-FIND request
     * @return Predicate that returns true if its argument matches all query keys
     */
    public static Predicate<Attributes> matchesQuery(final Attributes query) {
        return candidate -> null != candidate && IntStream.of(query.tags())
                .filter(tag -> tag != Tag.QueryRetrieveLevel)
                .filter(tag -> tag != Tag.SpecificCharacterSet)
                .allMatch(tag -> query.getVR(tag) == VR.UI
                        ? matchesUidList(query.getStrings(tag), candidate.getStrings(tag))
                        : matchesAnyValue(tag, query.getVR(tag),
                                query.getString(tag), candidate.getStrings(tag)));
    }

    /**
     * Tests whether a single query value matches a candidate Attribute
     * that may hold multiple values. The candidate matches if any one of
     * its values satisfies {@link #matchesValue}.
     *
     * <p>This makes single-value matching against a multi-valued
     * Attribute — notably Modalities in Study (0008,0061) — succeed when
     * the study includes the queried value, rather than only when the
     * first value matches. Matching multi-valued <em>query</em> keys with
     * logical-AND semantics (PS3.4 C.2.2.2.8) is not implemented; only
     * the first query value is considered.</p>
     *
     * @param tag             the DICOM tag
     * @param vr              the Value Representation
     * @param queryValue      the (single) query value
     * @param candidateValues the candidate's values, or null if absent
     * @return true if the candidate matches
     */
    static boolean matchesAnyValue(final int tag, final VR vr,
                                   final String queryValue,
                                   final String[] candidateValues) {
        if (StringUtils.isEmpty(queryValue)) {
            return true; // universal match
        }
        if (candidateValues == null || candidateValues.length == 0) {
            return false; // non-empty query cannot match an absent value
        }
        return Arrays.stream(candidateValues)
                .anyMatch(candidateValue ->
                        matchesValue(tag, vr, queryValue, candidateValue));
    }

    /**
     * Tests whether a single candidate value matches a query value
     * according to DICOM matching rules.
     *
     * @param tag            the DICOM tag
     * @param vr             the Value Representation
     * @param queryValue     the query value (non-null, non-empty)
     * @param candidateValue the candidate value (may be null)
     * @return true if the candidate matches
     */
    public static boolean matchesValue(final int tag, final VR vr,
                                       final String queryValue,
                                       final String candidateValue) {
        if (StringUtils.isEmpty(queryValue)) {
            return true; // universal match
        }
        if (StringUtils.isEmpty(candidateValue)) {
            return false; // non-empty query cannot match empty candidate
        }

        if (vr == VR.UI) {
            return queryValue.equals(candidateValue); // exact
        }
        if (vr == VR.DA || vr == VR.DT || vr == VR.TM) {
            if (isDateRange(queryValue)) {
                return matchesDateRange(queryValue, candidateValue);
            }
        }
        if (containsWildcard(queryValue)) {
            return matchesWildcard(queryValue, candidateValue);
        }
        // Single value exact match, case-insensitive (consistent with
        // the case-insensitive SQL push-down in DimseDataAccess).
        return candidateValue.equalsIgnoreCase(queryValue);
    }

    /**
     * Tests whether a query value contains DICOM wildcards.
     *
     * @param value the value to check
     * @return true if the value contains '*' or '?'
     */
    public static boolean containsWildcard(final String value) {
        return StringUtils.containsAny(value, "*?");
    }

    /**
     * Tests whether a query value is a DICOM date/time range.
     *
     * @param value the value to check
     * @return true if the value contains a '-' range separator
     */
    public static boolean isDateRange(final String value) {
        return StringUtils.contains(value, '-');
    }

    /**
     * Matches using DICOM wildcard rules: '*' matches any sequence
     * of characters, '?' matches any single character. Matching is
     * case-insensitive.
     */
    static boolean matchesWildcard(final String pattern, final String value) {
        final String regex = wildcardToRegex(pattern);
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE)
                .matcher(value).matches();
    }

    /**
     * Converts a DICOM wildcard pattern to a Java regex.
     *
     * @param pattern the DICOM wildcard pattern
     * @return a Java regex string
     */
    static String wildcardToRegex(final String pattern) {
        final StringBuilder sb = new StringBuilder(pattern.length() * 2);
        for (int i = 0; i < pattern.length(); i++) {
            final char c = pattern.charAt(i);
            switch (c) {
                case '*':
                    sb.append(".*");
                    break;
                case '?':
                    sb.append('.');
                    break;
                // Escape regex metacharacters
                case '.': case '(': case ')': case '[': case ']':
                case '{': case '}': case '\\': case '^': case '$':
                case '|': case '+':
                    sb.append('\\').append(c);
                    break;
                default:
                    sb.append(c);
                    break;
            }
        }
        return sb.toString();
    }

    /**
     * Matches a DICOM date/time range. Ranges use the format
     * {@code start-end}, where either bound may be omitted for an
     * open-ended range.
     */
    static boolean matchesDateRange(final String range, final String value) {
        final int dashIndex = range.indexOf('-');
        final String start = range.substring(0, dashIndex);
        final String end = range.substring(dashIndex + 1);

        if (!start.isEmpty() && value.compareTo(start) < 0) {
            return false;
        }
        return end.isEmpty() || value.compareTo(end) <= 0;
    }

    /**
     * List of UID Matching (PS3.4 C.2.2.2.2): each UID in the query
     * may generate a match, so the candidate matches if any of its
     * values exactly equals any query UID. A single UID is the
     * one-element case. An absent or zero-length query value is
     * universal matching.
     *
     * @param queryUids       the query UIDs (dcm4che splits the
     *                        backslash-delimited list), or null
     * @param candidateValues the candidate's values, or null if absent
     * @return true if the candidate matches
     */
    static boolean matchesUidList(final String[] queryUids,
                                  final String[] candidateValues) {
        final Set<String> uids = queryUids == null
                ? Collections.emptySet()
                : Arrays.stream(queryUids)
                        .filter(StringUtils::isNotEmpty)
                        .collect(Collectors.toSet());
        if (uids.isEmpty()) {
            return true; // universal match
        }
        return candidateValues != null
                && Arrays.stream(candidateValues).anyMatch(uids::contains);
    }

    /**
     * Translates a DICOM wildcard pattern to a SQL LIKE pattern.
     * DICOM '*' becomes SQL '%', DICOM '?' becomes SQL '_'.
     * Existing SQL wildcards in the input are escaped.
     *
     * @param dicomPattern the DICOM wildcard pattern
     * @return a SQL LIKE pattern
     */
    public static String toSqlLike(final String dicomPattern) {
        final StringBuilder sb = new StringBuilder(dicomPattern.length() + 4);
        for (int i = 0; i < dicomPattern.length(); i++) {
            final char c = dicomPattern.charAt(i);
            switch (c) {
                case '%':
                    sb.append("\\%");
                    break;
                case '_':
                    sb.append("\\_");
                    break;
                case '*':
                    sb.append('%');
                    break;
                case '?':
                    sb.append('_');
                    break;
                default:
                    sb.append(c);
                    break;
            }
        }
        return sb.toString();
    }

    private static final DateTimeFormatter DICOM_DATE =
            DateTimeFormatter.ofPattern("uuuuMMdd")
                    .withResolverStyle(ResolverStyle.STRICT);

    /**
     * Tests whether a value is a valid DICOM DA date
     * ({@code YYYYMMDD} naming a real calendar date). Range and
     * partial-precision values are not accepted.
     *
     * @param dicomDate the value to check
     * @return true if the value is a valid 8-digit calendar date
     */
    public static boolean isValidDicomDate(final String dicomDate) {
        if (dicomDate == null || dicomDate.length() != 8) {
            return false;
        }
        try {
            LocalDate.parse(dicomDate, DICOM_DATE);
            return true;
        } catch (final DateTimeParseException e) {
            return false;
        }
    }

    /**
     * Converts a DICOM DA value (YYYYMMDD) to ISO date format
     * (YYYY-MM-DD) for SQL queries.
     *
     * @param dicomDate a DICOM DA string
     * @return an ISO date string, or the original value if it
     *         cannot be parsed
     */
    public static String dicomDateToIso(final String dicomDate) {
        if (dicomDate == null || dicomDate.length() != 8) {
            return dicomDate;
        }
        return dicomDate.substring(0, 4) + "-"
                + dicomDate.substring(4, 6) + "-"
                + dicomDate.substring(6, 8);
    }
}
