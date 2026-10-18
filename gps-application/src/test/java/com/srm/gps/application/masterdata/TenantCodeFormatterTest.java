package com.srm.gps.application.masterdata;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TenantCodeFormatterTest {

    @Test
    void companyNameBecomesLowerCaseCode() {
        assertEquals("demo-transport-jsr", TenantCodeFormatter.toTenantCode("DEMO-TRANSPORT-JSR"));
    }

    @Test
    void spacesAndSymbolsBecomeSingleDashes() {
        assertEquals("m-s-roy-transport", TenantCodeFormatter.toTenantCode("  M/S Roy & Transport. "));
    }

    @Test
    void missingNameGetsAPlaceholderCode() {
        assertEquals(TenantCodeFormatter.CODE_FOR_UNKNOWN_COMPANY, TenantCodeFormatter.toTenantCode(null));
        assertEquals(TenantCodeFormatter.CODE_FOR_UNKNOWN_COMPANY, TenantCodeFormatter.toTenantCode("***"));
    }
}
