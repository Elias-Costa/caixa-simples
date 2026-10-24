package br.com.caixasimples.contas.internal;

import br.com.caixasimples.shared.ContaId;
import br.com.caixasimples.shared.EventoDeSeguranca;
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
 * Usuário inativo, ou que já não existe, recebe 401 e a cadeia não segue.
 *
 * <p>A mesma leitura confere a versão de acesso. O token que não leva a versão gravada no Usuário foi
 * emitido antes de uma troca de senha, de uma redefinição ou da saída de todos os aparelhos, e recebe
 * 401 como o do usuário inativo. O token sem versão nenhuma também, porque nada garante que ele não
 * seja anterior a uma revogação. As duas recusas vão ao log como evento de segurança, com a Conta e
 * o Usuário do token e a origem: o token revogado que continua chegando é o sinal de um aparelho
 * furtado ainda em uso. A leitura funciona
 * porque cada consulta de repositório abre a própria sessão do Hibernate, e a sessão resolve o
 * tenant na abertura: o tenant já está definido quando a consulta roda.
 *
 * <p>Antes do Usuário, a Conta: com o acesso dela suspenso por uso ilegal ou ataque ao serviço, todo
 * token dela recebe 401, inclusive o que leva a versão de acesso atual. A suspensão sobe a versão de
 * todos os Usuários, mas um login que leu a Conta antes dela e a versão depois emitiria um token com a
 * versão nova, que só esta conferência barra. Vem primeiro para o log dizer o motivo real, e não a
 * versão antiga que a suspensão também produz. O 401, e não o 403, leva o aplicativo ao login, que
 * explica a suspensão, e devolve à fila do aparelho o lote recusado.
 *
 * <p><strong>Com a senha provisória, só três rotas respondem.</strong> A senha escolhida por outra
 * pessoa, o administrador na do operador ou o mantenedor na do primeiro acesso, é conhecida por quem a
 * escolheu. O aplicativo só mostra a troca enquanto ela vale, mas pela API quem a conhece usaria a
 * conta da outra pessoa sem trocar nada, e o que fizesse sairia no nome dela. Por isso, lida a
 * credencial do banco, como o perfil e o ativo, toda rota da API responde 403 enquanto a senha for
 * provisória, menos a identidade, a troca da própria senha e a saída de todos os aparelhos, as únicas
 * de que a tela de troca precisa. A comparação é exata, pelo método e pelo caminho: uma variante do
 * caminho cai na recusa, que é o lado seguro. A recusa vai ao log como as outras de acesso, com o
 * motivo, e conta no alerta de rajada. O 403, e não o 401, mantém o aparelho na sessão: ele mostra a
 * troca, e o lote recusado volta à fila até ela acontecer.
 *
 * <p>Roda depois da autenticação do Spring Security e sempre limpa os dois contextos no
 * {@code finally}, porque thread reaproveitada não pode herdar a identidade da requisição
 * anterior.
 */
@Component
class IdentidadeDoTokenFilter extends OncePerRequestFilter {

    /**
     * O corpo da recusa pela senha provisória, fixo e sem dado de ninguém. Escrito à mão porque o
     * filtro responde antes de qualquer controller, e o tratador de erros não chega aqui.
     */
    private static final String CORPO_DA_SENHA_PROVISORIA = "{\"type\":\"about:blank\","
            + "\"title\":\"Forbidden\",\"status\":403,"
            + "\"detail\":\"Troque a senha provisória para continuar.\"}";

    private final UsuarioRepository usuarios;
    private final ContaRepository contas;
    private final CredencialRepository credenciais;

    IdentidadeDoTokenFilter(UsuarioRepository usuarios, ContaRepository contas,
            CredencialRepository credenciais) {
        this.usuarios = usuarios;
        this.contas = contas;
        this.credenciais = credenciais;
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

            Optional<Usuario> lido = Optional.empty();
            String recusa = null;
            if (contas.estaSuspensa(TenantContext.exigirAtual().valor())) {
                recusa = "conta_suspensa";
            } else {
                lido = usuarios.findById(UUID.fromString(sujeito));
                if (lido.isEmpty() || !lido.get().isAtivo()) {
                    recusa = "usuario_inativo";
                } else if (!levaAVersaoDoBanco(token, lido.get())) {
                    recusa = "versao_antiga";
                }
            }
            if (recusa != null) {
                EventoDeSeguranca.de("token.recusado")
                        .com("conta", conta)
                        .com("usuario", sujeito)
                        .com("motivo", recusa)
                        .com("metodo", requisicao.getMethod())
                        .com("caminho", requisicao.getRequestURI())
                        .daOrigem(requisicao.getRemoteAddr())
                        .registrarRecusa();
                resposta.setStatus(HttpStatus.UNAUTHORIZED.value());
                return;
            }

            Usuario usuario = lido.get();
            if (!liberadaComSenhaProvisoria(requisicao) && temSenhaProvisoria(usuario)) {
                EventoDeSeguranca.de("acesso.recusado")
                        .com("conta", conta)
                        .com("usuario", sujeito)
                        .com("motivo", "senha_provisoria")
                        .com("metodo", requisicao.getMethod())
                        .com("caminho", requisicao.getRequestURI())
                        .daOrigem(requisicao.getRemoteAddr())
                        .registrarRecusa();
                resposta.setStatus(HttpStatus.FORBIDDEN.value());
                resposta.setContentType("application/problem+json");
                resposta.setCharacterEncoding("UTF-8");
                resposta.getWriter().write(CORPO_DA_SENHA_PROVISORIA);
                return;
            }

            UsuarioContext.definir(new UsuarioAutenticado(usuario.getId(), usuario.getPerfil()));
            corrente.doFilter(requisicao, resposta);
        } finally {
            UsuarioContext.limpar();
            TenantContext.limpar();
        }
    }

    /**
     * As rotas de que a tela de troca da senha precisa, e fora da API, onde só há o aplicativo, que
     * não leva token. Fica aqui, à vista, e não numa configuração: é a regra inteira.
     */
    private static boolean liberadaComSenhaProvisoria(HttpServletRequest requisicao) {
        String metodo = requisicao.getMethod();
        String caminho = requisicao.getRequestURI();
        if (!caminho.startsWith("/api/")) {
            return true;
        }
        return ("GET".equals(metodo) && "/api/auth/eu".equals(caminho))
                || ("POST".equals(metodo) && "/api/auth/senha".equals(caminho))
                || ("POST".equals(metodo) && "/api/auth/sair-de-todos".equals(caminho));
    }

    /**
     * A credencial não tem tenant, porque é ela que o descobre no login; a busca pelo Usuário, já
     * conferido nesta Conta, alcança a linha certa pelo índice único de {@code usuario_id}. Sem
     * credencial o Usuário não entra, e o filtro já o teria recusado como inativo.
     */
    private boolean temSenhaProvisoria(Usuario usuario) {
        return credenciais.findByUsuarioId(usuario.getId())
                .map(Credencial::isSenhaProvisoria)
                .orElse(false);
    }

    /** O claim numérico chega como {@code Long} na leitura do token, daí a comparação por valor. */
    private static boolean levaAVersaoDoBanco(Jwt token, Usuario usuario) {
        Object versao = token.getClaim(EmissorDeToken.CLAIM_VERSAO_DO_ACESSO);
        return versao instanceof Number numero
                && numero.longValue() == usuario.getVersaoDoAcesso();
    }
}
