package com.vaultcore.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Local practice only: deliberately allows choosing an identity and role. Never enable on a server. */
@Configuration(proxyBeanMethods = false)
@Profile("dev")
public class DevAuthentication {
    public static final String ISSUER = "http://vaultcore.local";

    @Bean
    KeyPair developmentKeys(@Value("${VAULTCORE_DEV_KEY_FILE:}") String keyFile) throws Exception {
        if (!keyFile.isBlank()) {
            String pem = Files.readString(Path.of(keyFile)).replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
            KeyFactory factory = KeyFactory.getInstance("RSA");
            RSAPrivateCrtKey privateKey = (RSAPrivateCrtKey) factory.generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
            return new KeyPair(factory.generatePublic(new RSAPublicKeySpec(privateKey.getModulus(),
                    privateKey.getPublicExponent())), privateKey);
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    @Bean
    JwtEncoder developmentEncoder(KeyPair developmentKeys) {
        RSAKey key = new RSAKey.Builder((RSAPublicKey) developmentKeys.getPublic())
                .privateKey(developmentKeys.getPrivate()).keyID("local-practice").build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
    }

    @Bean
    JwtDecoder developmentDecoder(KeyPair developmentKeys) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) developmentKeys.getPublic()).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(ISSUER), jwt -> jwt.getAudience().contains("vaultcore-api")
                        ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"))));
        return decoder;
    }

    @RestController
    @Profile("dev")
    public static class Tokens {
        private final JwtEncoder encoder;
        public Tokens(JwtEncoder encoder) { this.encoder = encoder; }

        @PostMapping("/dev/token")
        public TokenResponse issue(@Valid @RequestBody TokenRequest request) {
            Instant now = Instant.now();
            JwtClaimsSet claims = JwtClaimsSet.builder().issuer(ISSUER).subject(request.subject())
                    .audience(List.of("vaultcore-api")).issuedAt(now).expiresAt(now.plusSeconds(900))
                    .claim("roles", request.roles() == null ? Set.of("CUSTOMER") : request.roles()).build();
            return new TokenResponse(encoder.encode(JwtEncoderParameters.from(claims)).getTokenValue(), 900);
        }
    }

    public record TokenRequest(@NotBlank @Size(max = 255) String subject,
            @Size(max = 4) Set<@Pattern(regexp = "CUSTOMER|TELLER|AUDITOR|ADMIN") String> roles) { }
    public record TokenResponse(String accessToken, long expiresIn) { }
}
