package br.com.caixasimples.contas.internal;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Chave e beans de emissao/validacao de JWT (decisao D14a).
 *
 * <p>HMAC-SHA256 com chave simetrica: emissor e validador sao a mesma aplicacao, entao par de
 * chaves assimetricas seria maquinario sem uso. A chave vem de {@code CAIXA_SIMPLES_JWT_SECRET} e
 * <strong>nao tem valor padrao</strong> — a aplicacao nao sobe sem ela (D14e). Um padrao em codigo
 * seria o mesmo em toda instalacao, o que equivale a nao ter chave.
 */
@Configuration(proxyBeanMethods = false)
class JwtConfiguration {

    /** HMAC-SHA256 exige chave de ao menos 256 bits. */
    private static final int MINIMO_DE_BYTES = 32;

    private final SecretKey chave;

    JwtConfiguration(@Value("${caixa-simples.jwt.secret}") String segredo) {
        byte[] bytes = segredo.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MINIMO_DE_BYTES) {
            throw new IllegalStateException(
                    "CAIXA_SIMPLES_JWT_SECRET precisa de ao menos " + MINIMO_DE_BYTES
                            + " bytes para HMAC-SHA256; recebeu " + bytes.length);
        }
        this.chave = new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(chave));
    }

    @Bean
    JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder.withSecretKey(chave).build();
    }
}
