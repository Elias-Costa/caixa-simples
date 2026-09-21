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
 *
 * <p>O perfil do token novo é o que {@link IdentidadeDoTokenFilter} acabou de ler do banco, e não
 * o claim do token velho: assim uma troca de perfil chega ao cliente na primeira renovação. Por
 * isso este filtro roda depois daquele, e só emite quando os dois contextos estão preenchidos.
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

        Optional<ContaId> conta = TenantContext.atual();
        Optional<UsuarioAutenticado> usuario = UsuarioContext.atual();

        if (conta.isPresent() && usuario.isPresent()) {
            resposta.setHeader(HEADER, emissor.emitir(
                    usuario.get().usuarioId(), conta.get(), usuario.get().perfil()));
        }

        corrente.doFilter(requisicao, resposta);
    }
}
