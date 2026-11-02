package com.rsreach.security.jwt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rsreach.common.RsReachConstants;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    @Test
    void customerAndStaffTokensUseSeparateRealms() {
        JwtProperties properties = new JwtProperties();
        properties.getCustomer().setSecret("customer-secret-at-least-32-bytes!!");
        properties.getCustomer().setIssuer("rsreach-customer");
        properties.getCustomer().setAudience("rsreach-app");
        properties.getStaff().setSecret("staff-secret-at-least-32-bytes!!!!!");
        properties.getStaff().setIssuer("rsreach-staff");
        properties.getStaff().setAudience("rsreach-platform");

        JwtService jwtService = new JwtService(properties);
        UUID userId = UUID.randomUUID();
        String customerToken = jwtService.issueAccessToken(RsReachConstants.REALM_CUSTOMER, userId, false);
        String staffToken = jwtService.issueAccessToken(RsReachConstants.REALM_STAFF, userId, true);

        var customer = jwtService.parse(customerToken, RsReachConstants.REALM_CUSTOMER);
        assertEquals(userId, customer.subjectId());
        assertEquals(RsReachConstants.REALM_CUSTOMER, customer.realm());

        var staff = jwtService.parse(staffToken, RsReachConstants.REALM_STAFF);
        assertTrue(staff.mfaVerified());

        assertThrows(Exception.class, () -> jwtService.parse(customerToken, RsReachConstants.REALM_STAFF));
        assertThrows(Exception.class, () -> jwtService.parse(staffToken, RsReachConstants.REALM_CUSTOMER));
    }
}
