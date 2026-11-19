package com.moneytransfer.security.local;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
@Profile("local & !prod")
public class LocalDemoJwtConfiguration {

    static final String ISSUER = "money-transfer-local-demo";

    @Bean
    KeyPair localDemoSigningKeyPair() throws NoSuchAlgorithmException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    @Bean
    JwtEncoder localDemoJwtEncoder(KeyPair localDemoSigningKeyPair) {
        RSAKey signingKey = new RSAKey.Builder((RSAPublicKey) localDemoSigningKeyPair.getPublic())
                .privateKey((RSAPrivateKey) localDemoSigningKeyPair.getPrivate())
                .keyID(java.util.UUID.randomUUID().toString())
                .build();
        JWKSource<SecurityContext> jwkSource = new ImmutableJWKSet<>(new JWKSet(signingKey));
        return new NimbusJwtEncoder(jwkSource);
    }

    @Bean
    JwtDecoder localDemoJwtDecoder(KeyPair localDemoSigningKeyPair) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withPublicKey((RSAPublicKey) localDemoSigningKeyPair.getPublic())
                .build();
        OAuth2TokenValidator<org.springframework.security.oauth2.jwt.Jwt> validator =
                JwtValidators.createDefaultWithIssuer(ISSUER);
        decoder.setJwtValidator(validator);
        return decoder;
    }

    @Bean
    PasswordEncoder localDemoPasswordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
