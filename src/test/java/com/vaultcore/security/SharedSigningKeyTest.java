package com.vaultcore.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;

class SharedSigningKeyTest {
    @TempDir Path temporary;

    @Test
    void replicasUsingOneKeyAcceptEachOthersTokensAndRejectTampering() throws Exception {
        DevAuthentication configuration = new DevAuthentication();
        var original = configuration.developmentKeys("");
        Path keyFile = temporary.resolve("private-key.pem");
        Files.writeString(keyFile, "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(original.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n");
        var firstReplica = configuration.developmentKeys(keyFile.toString());
        var secondReplica = configuration.developmentKeys(keyFile.toString());
        var claims = JwtClaimsSet.builder().subject("alice").issuer(DevAuthentication.ISSUER)
                .audience(List.of("vaultcore-api")).issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60)).build();
        String token = configuration.developmentEncoder(firstReplica)
                .encode(JwtEncoderParameters.from(claims)).getTokenValue();
        var decoder = configuration.developmentDecoder(secondReplica);
        assertThat(decoder.decode(token).getSubject()).isEqualTo("alice");
        int signature = token.lastIndexOf('.') + 1;
        String tampered = token.substring(0, signature) + (token.charAt(signature) == 'A' ? 'B' : 'A')
                + token.substring(signature + 1);
        assertThatThrownBy(() -> decoder.decode(tampered)).isInstanceOf(JwtException.class);
    }
}
