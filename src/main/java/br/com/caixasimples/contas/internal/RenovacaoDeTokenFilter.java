package br.com.caixasimples.contas.internal;

import br.com.caixasimples.contas.Perfil;
import br.com.caixasimples.shared.ContaId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Devolve um token novo em toda resposta autenticada.
 *
 * <p>É o que faz a validade contar do <strong>último contato com o servidor</strong> em vez do
 * login: qualquer requisição bem-sucedida renova a janela. Sem isso, um negócio que ficasse mais
 * tempo sem rede do que a validade do token não conseguiria nem abrir o aplicativo, o que
 * contraria a exigência de operação offline (RNF01).
 *
 * <p>Não é refresh token: é o mesmo token de acesso, reemitido. Renova em toda resposta, sem
 * limiar de idade, que é a versão sem número para explicar e sem configuração para errar.
 */
@Component
class RenovacaoDeTokenFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Caixa-Simples-Token";

    private final EmissorDeToken emissor;

    RenovacaoDeTokenFilter(EmissorDeToken emissor) {
        this.emissor = emissor;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest requisicao, HttpServletResponse resposta,
            FilterChain corrente) throws ServletException, IOException {

        Authentication autenticacao = SecurityContextHolder.getContext().getAuthentication();

        if (autenticacao != null && autenticacao.getPrincipal() instanceof Jwt token) {
            String conta = token.getClaimAsString(EmissorDeToken.CLAIM_CONTA);
            String perfil = token.getClaimAsString(EmissorDeToken.CLAIM_PERFIL);
            if (conta != null && perfil != null && token.getSubject() != null) {
                resposta.setHeader(HEADER, emissor.emitir(
                        UUID.fromString(token.getSubject()),
                        ContaId.de(conta),
                        Perfil.valueOf(perfil)));
            }
        }

        corrente.doFilter(requisicao, resposta);
    }
}
