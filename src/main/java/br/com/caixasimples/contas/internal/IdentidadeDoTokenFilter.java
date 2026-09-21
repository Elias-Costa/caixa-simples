package br.com.caixasimples.contas.internal;

import br.com.caixasimples.shared.ContaId;
import br.com.caixasimples.shared.TenantContext;
import br.com.caixasimples.shared.UsuarioAutenticado;
import br.com.caixasimples.shared.UsuarioContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Lê a conta e o usuário do token autenticado e os coloca em {@link TenantContext} e
 * {@link UsuarioContext}.
 *
 * <p>Este é o único ponto do sistema onde o tenant entra em cena numa requisição. O
 * {@code contaId} vem <strong>do token</strong>, nunca de corpo, path, query ou header, porque
 * qualquer um desses seria um valor que o cliente pode forjar para alcançar dado de outra conta
 * (RNF05).
 *
 * <p>O usuário também vem do token, mas o que se guarda dele, o perfil e o fato de estar ativo,
 * <strong>vem do banco</strong>, lido pela chave a cada requisição, já sob o tenant. O token dura
 * um dia e se renova a cada resposta, então o claim de perfil sobreviveria à mudança no banco;
 * com a leitura aqui, inativar um usuário ou trocar o perfil dele vale na requisição seguinte.
 * Usuário inativo, ou que já não existe, recebe 401 e a cadeia não segue. A leitura funciona
 * porque cada consulta de repositório abre a própria sessão do Hibernate, e a sessão resolve o
 * tenant na abertura: o tenant já está definido quando a consulta roda.
 *
 * <p>Roda depois da autenticação do Spring Security e sempre limpa os dois contextos no
 * {@code finally}, porque thread reaproveitada não pode herdar a identidade da requisição
 * anterior.
 */
@Component
class IdentidadeDoTokenFilter extends OncePerRequestFilter {

    private final UsuarioRepository usuarios;

    IdentidadeDoTokenFilter(UsuarioRepository usuarios) {
        this.usuarios = usuarios;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest requisicao, HttpServletResponse resposta,
            FilterChain corrente) throws ServletException, IOException {

        Authentication autenticacao = SecurityContextHolder.getContext().getAuthentication();

        if (!(autenticacao != null && autenticacao.getPrincipal() instanceof Jwt token)) {
            corrente.doFilter(requisicao, resposta);
            return;
        }

        String conta = token.getClaimAsString(EmissorDeToken.CLAIM_CONTA);
        String sujeito = token.getSubject();
        if (conta == null || sujeito == null) {
            corrente.doFilter(requisicao, resposta);
            return;
        }

        try {
            TenantContext.definir(ContaId.de(conta));

            Optional<Usuario> usuario = usuarios.findById(UUID.fromString(sujeito))
                    .filter(Usuario::isAtivo);
            if (usuario.isEmpty()) {
                resposta.setStatus(HttpStatus.UNAUTHORIZED.value());
                return;
            }

            UsuarioContext.definir(
                    new UsuarioAutenticado(usuario.get().getId(), usuario.get().getPerfil()));
            corrente.doFilter(requisicao, resposta);
        } finally {
            UsuarioContext.limpar();
            TenantContext.limpar();
        }
    }
}
