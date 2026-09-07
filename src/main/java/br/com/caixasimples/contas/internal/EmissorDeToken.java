package br.com.caixasimples.contas.internal;

import br.com.caixasimples.contas.Perfil;
import br.com.caixasimples.shared.ContaId;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/**
 * Emite o token de acesso (decisao A4).
 *
 * <p>Vale 24 horas e nao ha refresh token. As 24 horas contam <strong>do ultimo contato com o
 * servidor</strong>, nao do login: toda resposta autenticada traz um token novo
 * ({@link RenovacaoDeTokenFilter}). Na pratica, a validade e a janela de resistencia offline
 * exigida pelo RNF01 — qualquer reconexao zera o contador, e o app so trava depois de 24h
 * ininterruptas sem rede.
 *
 * <p>Custo aceito e registrado em A4: sem revogacao, um dispositivo furtado que continue online
 * renova o token indefinidamente.
 */
@Component
public class EmissorDeToken {

    static final String CLAIM_CONTA = "contaId";
    static final String CLAIM_PERFIL = "perfil";

    private final JwtEncoder encoder;
    private final Duration validade;

    EmissorDeToken(JwtEncoder encoder, @Value("${caixa-simples.jwt.validade}") Duration validade) {
        this.encoder = encoder;
        this.validade = validade;
    }

    public String emitir(UUID usuarioId, ContaId contaId, Perfil perfil) {
        Instant agora = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(usuarioId.toString())
                .claim(CLAIM_CONTA, contaId.valor().toString())
                .claim(CLAIM_PERFIL, perfil.name())
                .issuedAt(agora)
                .expiresAt(agora.plus(validade))
                .build();

        return encoder
                .encode(JwtEncoderParameters.from(JwsHeader.with(() -> "HS256").build(), claims))
                .getTokenValue();
    }
}
