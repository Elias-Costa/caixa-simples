package br.com.caixasimples.contas.internal;

import br.com.caixasimples.shared.Perfil;
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
 * Emite o token de acesso.
 *
 * <p>A validade é configurável e não há refresh token. Ela conta <strong>do último contato com o
 * servidor</strong>, e não do login, porque toda resposta autenticada traz um token novo, emitido
 * por {@link RenovacaoDeTokenFilter}. Na prática, a validade é a janela de resistência offline
 * exigida pelo RNF01: qualquer reconexão zera o contador, e o aplicativo só trava depois de uma
 * janela inteira sem rede.
 *
 * <p>O token leva a versão de acesso do usuário, e {@link IdentidadeDoTokenFilter} recusa o que não
 * leva a versão gravada no banco. É a revogação: sem ela, um aparelho furtado que continuasse com
 * rede renovaria o token indefinidamente, e subir a versão derruba todos os aparelhos de uma vez.
 */
@Component
public class EmissorDeToken {

    static final String CLAIM_CONTA = "contaId";
    static final String CLAIM_PERFIL = "perfil";
    static final String CLAIM_VERSAO_DO_ACESSO = "versaoDoAcesso";

    private final JwtEncoder encoder;
    private final Duration validade;

    EmissorDeToken(JwtEncoder encoder, @Value("${caixa-simples.jwt.validade}") Duration validade) {
        this.encoder = encoder;
        this.validade = validade;
    }

    /**
     * @param versaoDoAcesso a versão de acesso do usuário que vale agora no banco
     */
    public String emitir(UUID usuarioId, ContaId contaId, Perfil perfil, int versaoDoAcesso) {
        Instant agora = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(usuarioId.toString())
                .claim(CLAIM_CONTA, contaId.valor().toString())
                .claim(CLAIM_PERFIL, perfil.name())
                .claim(CLAIM_VERSAO_DO_ACESSO, versaoDoAcesso)
                .issuedAt(agora)
                .expiresAt(agora.plus(validade))
                .build();

        return encoder
                .encode(JwtEncoderParameters.from(JwsHeader.with(() -> "HS256").build(), claims))
                .getTokenValue();
    }
}
