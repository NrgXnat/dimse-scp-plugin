package org.nrg.xnat.dimse.query;

import org.apache.commons.lang.StringUtils;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.ElementDictionary;
import org.dcm4che3.data.VR;
import org.dcm4che3.net.Status;
import org.dcm4che3.net.service.DicomServiceException;

import java.util.List;
import java.util.OptionalInt;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Validation of DA (Date) and TM (Time) query key values in a C-FIND
 * Identifier. A malformed value is rejected rather than ignored, so the
 * SCU learns that its query was not applied as written.
 *
 * <p>A valid value is an exact value or a range (PS3.4 C.2.2.2.5):
 * {@code <v1>-<v2>}, {@code -<v1>} or {@code <v1>-}, with
 * {@code <v1>} less than or equal to {@code <v2>}. Each value must have
 * the PS3.5 format for its VR: DA is {@code YYYYMMDD} naming a real
 * calendar date; TM is {@code HH[MM[SS[.F{1,6}]]]}. Wildcards, a lone
 * {@code -}, and the Empty Value Matching value {@code ""} (which needs
 * Extended Negotiation, PS3.4 C.2.2.2.7) are malformed.</p>
 */
public final class DateTimeKeys {

    private DateTimeKeys() {}

    /**
     * A PS3.5 TM value: hours, then optionally minutes, seconds and a
     * fraction of one to six digits. A component may be omitted only if
     * every component to its right is omitted.
     *
     * <p>PS3.5 allows SS to be 60 "only for a leap second". Leap seconds
     * are inserted at 23:59:60 UTC, and a whole-hour offset from UTC
     * changes only the hour, so second 60 is accepted at any hour but
     * only in minute 59 (the same rule as the XNAT DICOMweb plugin). A
     * fractional-hour zone (e.g. +05:30, rendered {@code HH:29:60}) is
     * rejected, and whether a leap second fell on the queried date is
     * not checked.</p>
     */
    private static final Pattern TIME = Pattern.compile(
            "([01]\\d|2[0-3])(?:[0-5]\\d(?:[0-5]\\d(?:\\.\\d{1,6})?)?|5960(?:\\.\\d{1,6})?)?");

    /**
     * Rejects an Identifier containing a malformed DA or TM key value.
     * Every key with VR DA or TM and a non-empty value is checked; each
     * value of a multi-valued key must be valid. Zero-length values are
     * universal matching and are not checked.
     *
     * <p>Reported as C000H Unable to Process with the Offending Element
     * (0000,0901) naming the key, as for other values this SCP cannot
     * process (see {@link QueryLevel#requireSingleUidKeysAbove}).</p>
     *
     * @param keys the C-FIND Identifier
     * @throws DicomServiceException with status C000H (Unable to
     *         Process) if a DA or TM key value is malformed
     */
    public static void requireValid(final Attributes keys) throws DicomServiceException {
        final OptionalInt malformed = findMalformed(keys);
        if (malformed.isPresent()) {
            final int tag = malformed.getAsInt();
            // Error Comment is LO: dcm4che truncates it to 64 characters.
            throw new DicomServiceException(Status.UnableToProcess,
                    ElementDictionary.keywordOf(tag, null) + " is not a valid "
                    + keys.getVR(tag) + " query value")
                    .setOffendingElements(tag);
        }
    }

    /**
     * Finds the first DA or TM key with a malformed value.
     *
     * @param keys the C-FIND Identifier
     * @return the tag of the malformed key, or empty if there is none
     */
    static OptionalInt findMalformed(final Attributes keys) {
        return IntStream.of(keys.tags())
                .filter(tag -> !isValid(keys.getVR(tag),
                        DicomMatchUtils.nonEmptyValues(keys.getStrings(tag))))
                .findFirst();
    }

    /**
     * Tests whether every value of a key is a valid query value for its
     * VR. Only DA and TM are checked; other VRs are always valid here.
     *
     * @param vr     the key's Value Representation
     * @param values the key's non-empty values
     * @return false if the key is DA or TM and any value is malformed
     */
    static boolean isValid(final VR vr, final List<String> values) {
        if (vr == VR.DA) {
            return values.stream().allMatch(value ->
                    DicomMatchUtils.parseDateRange(value).isPresent());
        }
        if (vr == VR.TM) {
            return values.stream().allMatch(DateTimeKeys::isValidTimeQueryValue);
        }
        return true;
    }

    /**
     * Tests whether a value is a valid TM query value: an exact time or
     * a range with at least one bound, every bound a valid TM, and the
     * lower bound not after the upper.
     *
     * @param value a non-empty TM query value
     * @return true if the value is well formed
     */
    public static boolean isValidTimeQueryValue(final String value) {
        if (!DicomMatchUtils.isDateRange(value)) {
            return isValidTime(value);
        }
        final int dash = value.indexOf('-');
        final String lower = value.substring(0, dash);
        final String upper = value.substring(dash + 1);
        return !(lower.isEmpty() && upper.isEmpty())
                && Stream.of(lower, upper)
                        .filter(StringUtils::isNotEmpty)
                        .allMatch(DateTimeKeys::isValidTime)
                && (lower.isEmpty() || upper.isEmpty()
                        || fullPrecision(lower).compareTo(fullPrecision(upper)) <= 0);
    }

    /**
     * Tests whether a value is a single PS3.5 TM value.
     *
     * @param value the value to check
     * @return true if the value matches {@code HH[MM[SS[.F{1,6}]]]}
     */
    static boolean isValidTime(final String value) {
        return TIME.matcher(value).matches();
    }

    /**
     * Pads a valid TM value to {@code HHMMSS.FFFFFF} with zeros, so
     * values of different precision compare in time order as strings.
     * An omitted component is taken as its earliest value.
     */
    private static String fullPrecision(final String time) {
        final int dot = time.indexOf('.');
        final String whole = dot < 0 ? time : time.substring(0, dot);
        final String fraction = dot < 0 ? "" : time.substring(dot + 1);
        return StringUtils.rightPad(whole, 6, '0') + "."
                + StringUtils.rightPad(fraction, 6, '0');
    }
}
