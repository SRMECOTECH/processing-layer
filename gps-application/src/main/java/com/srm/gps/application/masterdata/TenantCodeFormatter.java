package com.srm.gps.application.masterdata;

import java.util.Locale;

/** Turns a company name such as "DEMO-TRANSPORT-JSR" into a tenant code such as "demo-transport-jsr". */
public final class TenantCodeFormatter {

    private static final int MAXIMUM_CODE_LENGTH = 40;
    static final String CODE_FOR_UNKNOWN_COMPANY = "unknown-company";

    private TenantCodeFormatter() {
    }

    public static String toTenantCode(String companyName) {
        if (companyName == null) {
            return CODE_FOR_UNKNOWN_COMPANY;
        }
        String code = companyName.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")   // anything else becomes a single dash
                .replaceAll("^-+|-+$", "");      // no dash at the start or end
        if (code.length() > MAXIMUM_CODE_LENGTH) {
            code = code.substring(0, MAXIMUM_CODE_LENGTH).replaceAll("-+$", "");
        }
        return code.isEmpty() ? CODE_FOR_UNKNOWN_COMPANY : code;
    }
}
