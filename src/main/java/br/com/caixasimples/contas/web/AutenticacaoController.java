package br.com.caixasimples.contas.web;

import br.com.caixasimples.contas.application.AutenticacaoService;
import br.com.caixasimples.contas.application.AutenticacaoService.Identidade;
import br.com.caixasimples.contas.internal.CredenciaisInvalidasException;
import jakarta.validation.Valid;
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

/**
 * Entrada HTTP da autenticação. Rotas sob {@code /api}, sem versão no caminho.
 *
 * <p>É o molde dos controllers do projeto: pedido e resposta são records aninhados, o corpo do
 * pedido é validado com {@code @Valid} (campo em branco vira 400 antes de chegar ao caso de uso),
 * o controller só traduz e delega, e a exceção que só este módulo lança é traduzida aqui mesmo.
 * O que é transversal, como a recusa por perfil virar 403, fica no tratador de {@code shared}.
 */
@RestController
@RequestMapping("/api/auth")
class AutenticacaoController {

    private final AutenticacaoService autenticacao;

    AutenticacaoController(AutenticacaoService autenticacao) {
        this.autenticacao = autenticacao;
    }

    @PostMapping("/login")
    RespostaDeLogin login(@Valid @RequestBody PedidoDeLogin pedido) {
        return new RespostaDeLogin(autenticacao.entrar(pedido.email(), pedido.senha()));
    }

    /**
     * Quem está autenticado agora, e em que negócio.
     *
     * <p>É o que o aplicativo mostra no cabeçalho de toda tela, e é também o endpoint protegido
     * pelo qual se verifica, por HTTP, que requisição sem token é recusada, que o tenant do
     * contexto vem do claim, não do pedido, e que o perfil é o do banco, não o do claim, de modo
     * que um usuário inativado deixa de entrar na requisição seguinte.
     */
    @GetMapping("/eu")
    RespostaDeIdentidade eu() {
        Identidade identidade = autenticacao.identidade();
        return new RespostaDeIdentidade(
                identidade.usuarioId(),
                identidade.nome(),
                identidade.perfil().name(),
                identidade.contaId(),
                identidade.nomeNegocio(),
                identidade.tipoNegocio(),
                identidade.estoqueHabilitado());
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

    /**
     * @param tipoNegocio omitido no JSON quando a conta não tem tipo
     */
    record RespostaDeIdentidade(UUID usuarioId, String nome, String perfil, UUID contaId,
            String nomeNegocio, String tipoNegocio, boolean estoqueHabilitado) {
    }
}
