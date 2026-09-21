package br.com.caixasimples.contas.web;

import br.com.caixasimples.contas.application.AutenticacaoService;
import br.com.caixasimples.contas.internal.CredenciaisInvalidasException;
import br.com.caixasimples.shared.TenantContext;
import br.com.caixasimples.shared.UsuarioAutenticado;
import br.com.caixasimples.shared.UsuarioContext;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

/**
 * Entrada HTTP da autenticação. Rotas sob {@code /api}, sem versão no caminho.
 */
@RestController
@RequestMapping("/api/auth")
@Validated
class AutenticacaoController {

    private final AutenticacaoService autenticacao;

    AutenticacaoController(AutenticacaoService autenticacao) {
        this.autenticacao = autenticacao;
    }

    @PostMapping("/login")
    RespostaDeLogin login(@RequestBody PedidoDeLogin pedido) {
        return new RespostaDeLogin(autenticacao.entrar(pedido.email(), pedido.senha()));
    }

    /**
     * Quem está autenticado agora.
     *
     * <p>Existe para que haja um endpoint protegido de verdade: é por ele que se verifica, por
     * HTTP, que requisição sem token é recusada, que o tenant do contexto vem do claim, não do
     * pedido, e que o perfil é o do banco, não o do claim, de modo que um usuário inativado deixa
     * de entrar na requisição seguinte.
     */
    @GetMapping("/eu")
    RespostaDeIdentidade eu() {
        UsuarioAutenticado usuario = UsuarioContext.exigirAtual();
        return new RespostaDeIdentidade(
                usuario.usuarioId(),
                TenantContext.exigirAtual().valor(),
                usuario.perfil().name());
    }

    /**
     * Login recusado vira 401 sem corpo detalhado. A mensagem é sempre a mesma, e o motivo real não
     * viaja para o cliente.
     */
    @ExceptionHandler(CredenciaisInvalidasException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    void credenciaisInvalidas() {
        // sem corpo de propósito
    }

    record PedidoDeLogin(@NotBlank String email, @NotBlank String senha) {
    }

    record RespostaDeLogin(String token) {
    }

    record RespostaDeIdentidade(UUID usuarioId, UUID contaId, String perfil) {
    }
}
