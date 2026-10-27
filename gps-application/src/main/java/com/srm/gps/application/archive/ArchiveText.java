package com.srm.gps.application.archive;

/**
 * Makes any text storable in PostgreSQL without failing: a record that broke a consumer may hold anything, and the
 * archive must never refuse it (that would block the archive behind it).
 */
final class ArchiveText {

    private ArchiveText() {
    }

    /** PostgreSQL text cannot hold the NUL character: it is written as the visible escape \u0000 instead. */
    static String text(String value) {
        return value == null ? null : value.replace("\u0000", "\\u0000");
    }

    /** For varchar columns: cut to the column's length (the full value is never needed to find a row). */
    static String limited(String value, int maxLength) {
        String clean = text(value);
        return clean == null || clean.length() <= maxLength ? clean : clean.substring(0, maxLength);
    }
}
