package com.moneytransfer.security.local;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LocalDemoJwtConfigurationTest {

    @Test
    void signsAndValidatesShortLivedJwtForTheDemoCustomer() throws Exception {
        LocalDemoJwtConfiguration configuration = new LocalDemoJwtConfiguration();
        var keyPair = configuration.localDemoSigningKeyPair();
        JwtEncoder encoder = configuration.localDemoJwtEncoder(keyPair);
        JwtDecoder decoder = configuration.localDemoJwtDecoder(keyPair);
        PasswordEncoder passwordEncoder = configuration.localDemoPasswordEncoder();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        LocalDemoSessionController controller = new LocalDemoSessionController(
                Clock.fixed(now, ZoneOffset.UTC),
                encoder,
                passwordEncoder);

        LocalDemoSessionController.DemoSessionResponse response = controller.createDemoSession(
                new LocalDemoSessionController.DemoSessionRequest("demo-customer", "demo1234")).data();
        var decodedToken = decoder.decode(response.accessToken());

        assertEquals("Bearer", response.tokenType());
        assertEquals(1800, response.expiresIn());
        assertEquals("demo-customer", response.customerId());
        assertEquals("Demo Kullanıcısı 1", response.customerName());
        assertEquals("demo-customer", decodedToken.getSubject());
        assertEquals("demo-customer", decodedToken.getClaimAsString("customerId"));
        assertEquals("money-transfer-local-demo", decodedToken.getClaimAsString("iss"));
        assertEquals(now.plusSeconds(1800), decodedToken.getExpiresAt());

        assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> controller.createDemoSession(
                        new LocalDemoSessionController.DemoSessionRequest("demo-customer", "wrong-password")));
    }
}
