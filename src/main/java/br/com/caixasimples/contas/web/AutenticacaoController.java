package br.com.caixasimples.contas.web;

import br.com.caixasimples.contas.application.AutenticacaoService;
import br.com.caixasimples.contas.internal.CredenciaisInvalidasException;
import br.com.caixasimples.shared.TenantContext;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

/**
 * Entrada HTTP da autenticacao. Rotas em {@code /api}, sem versao (decisao D14c).
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
     * Quem esta autenticado agora.
     *
     * <p>Existe para que haja um endpoint protegido de verdade: e por ele que se verifica, por
     * HTTP, que requisicao sem token e recusada e que o tenant do contexto vem do claim, nao do
     * pedido.
     */
    @GetMapping("/eu")
    RespostaDeIdentidade eu(@AuthenticationPrincipal Jwt token) {
        return new RespostaDeIdentidade(
                UUID.fromString(token.getSubject()),
                TenantContext.exigirAtual().valor(),
                token.getClaimAsString("perfil"));
    }

    /**
     * Login recusado vira 401 sem corpo detalhado. A mensagem e sempre a mesma, e o motivo real
     * nao viaja para o cliente.
     */
    @ExceptionHandler(CredenciaisInvalidasException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    void credenciaisInvalidas() {
        // sem corpo de proposito
    }

    record PedidoDeLogin(@NotBlank String email, @NotBlank String senha) {
    }

    record RespostaDeLogin(String token) {
    }

    record RespostaDeIdentidade(UUID usuarioId, UUID contaId, String perfil) {
    }
}
