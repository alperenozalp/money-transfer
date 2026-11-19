package com.moneytransfer.security.local;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

import com.moneytransfer.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/demo")
@Profile("local & !prod")
public class LocalDemoSessionController {

    private static final Duration TOKEN_LIFETIME = Duration.ofMinutes(30);

    private final Clock clock;
    private final JwtEncoder jwtEncoder;
    private final PasswordEncoder passwordEncoder;
    private final Map<String, EncodedDemoUser> demoUsers;

    public LocalDemoSessionController(Clock clock, JwtEncoder jwtEncoder, PasswordEncoder passwordEncoder) {
        this.clock = clock;
        this.jwtEncoder = jwtEncoder;
        this.passwordEncoder = passwordEncoder;
        this.demoUsers = LocalDemoUsers.ALL.stream().collect(Collectors.toUnmodifiableMap(
                LocalDemoUsers.DemoUser::customerId,
                user -> new EncodedDemoUser(user, passwordEncoder.encode(user.password()))));
    }

    @PostMapping("/session")
    public ApiResponse<DemoSessionResponse> createDemoSession(@Valid @RequestBody DemoSessionRequest request) {
        EncodedDemoUser encodedUser = demoUsers.get(request.customerId());
        if (encodedUser == null || !passwordEncoder.matches(request.password(), encodedUser.passwordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid customer ID or password.");
        }

        LocalDemoUsers.DemoUser user = encodedUser.user();
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(TOKEN_LIFETIME);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(LocalDemoJwtConfiguration.ISSUER)
                .subject(user.subject())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim("scope", "demo")
                .claim("customerId", user.customerId())
                .claim("name", user.displayName())
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        String accessToken = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new ApiResponse<>(new DemoSessionResponse(
                accessToken,
                "Bearer",
                TOKEN_LIFETIME.toSeconds(),
                user.customerId(),
                user.displayName()));
    }

    private record EncodedDemoUser(LocalDemoUsers.DemoUser user, String passwordHash) {
    }

    public record DemoSessionRequest(
            @NotBlank String customerId,
            @NotBlank String password) {
    }

    public record DemoSessionResponse(
            String accessToken,
            String tokenType,
            long expiresIn,
            String customerId,
            String customerName) {
    }
}
