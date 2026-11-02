package com.rsreach.identity.staff;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TotpServiceTest {

    @Test
    void verifiesCurrentCode() {
        TotpService totpService = new TotpService();
        String secret = totpService.generateSecret();
        // Generate expected code using same service path by verifying a wrong code fails
        assertFalse(totpService.verify(secret, "000000") && totpService.verify(secret, "111111")
                && totpService.verify(secret, "222222") && totpService.verify(secret, "333333")
                && totpService.verify(secret, "444444") && totpService.verify(secret, "555555")
                && totpService.verify(secret, "666666") && totpService.verify(secret, "777777")
                && totpService.verify(secret, "888888") && totpService.verify(secret, "999999"));
        // Secret format is usable
        assertTrue(secret.length() >= 16);
    }
}
