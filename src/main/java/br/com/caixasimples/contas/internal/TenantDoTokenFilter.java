package br.com.caixasimples.contas.internal;

import br.com.caixasimples.shared.ContaId;
import br.com.caixasimples.shared.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Le a conta do claim do token autenticado e a coloca no {@link TenantContext}.
 *
 * <p>Este e o unico ponto do sistema onde o tenant entra em cena numa requisicao. O
 * {@code contaId} vem <strong>do token</strong> — nunca de corpo, path, query ou header, o que
 * seria IDOR direto (RNF05, regra em {@code .claude/rules/multi-tenancy.md}).
 *
 * <p>Roda depois da autenticacao do Spring Security e sempre limpa o contexto no {@code finally}:
 * thread reaproveitada nao pode herdar o tenant da requisicao anterior.
 */
@Component
class TenantDoTokenFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest requisicao, HttpServletResponse resposta,
            FilterChain corrente) throws ServletException, IOException {

        Authentication autenticacao = SecurityContextHolder.getContext().getAuthentication();
        boolean definiu = false;

        if (autenticacao != null && autenticacao.getPrincipal() instanceof Jwt token) {
            String conta = token.getClaimAsString(EmissorDeToken.CLAIM_CONTA);
            if (conta != null) {
                TenantContext.definir(ContaId.de(conta));
                definiu = true;
            }
        }

        try {
            corrente.doFilter(requisicao, resposta);
        } finally {
            if (definiu) {
                TenantContext.limpar();
            }
        }
    }
}
