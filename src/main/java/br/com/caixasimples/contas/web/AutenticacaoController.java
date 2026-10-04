package br.com.caixasimples.contas.web;

import br.com.caixasimples.contas.RecursoDoPlano;
import br.com.caixasimples.contas.application.AutenticacaoService;
import br.com.caixasimples.contas.application.AutenticacaoService.Identidade;
import br.com.caixasimples.contas.internal.CredenciaisInvalidasException;
import br.com.caixasimples.contas.internal.LoginContidoException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
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

    /**
     * A origem é o endereço que o servidor já leu da direita da cadeia de proxies confiáveis, antes
     * do Spring: o que o cliente escreve à esquerda do cabeçalho nunca chega aqui.
     */
    @PostMapping("/login")
    RespostaDeLogin login(@Valid @RequestBody PedidoDeLogin pedido, HttpServletRequest requisicao) {
        return new RespostaDeLogin(
                autenticacao.entrar(pedido.email(), pedido.senha(), requisicao.getRemoteAddr()));
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
                identidade.estoqueHabilitado(),
                identidade.plano().name(),
                identidade.situacaoDoPlano().name(),
                identidade.vencimentoDoPlano(),
                identidade.inicioDaSuspensao(),
                identidade.recursos().stream().map(RecursoDoPlano::name).toList());
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

    /**
     * Tentativas demais da mesma origem, ou do mesmo e-mail vindo dela, viram 429 com a espera até a
     * janela acabar: em segundos no Retry-After e em minutos na mensagem, que a tela de login mostra.
     * Os dois arredondam para cima, para quem esperar o que foi dito não ser recusado de novo.
     */
    @ExceptionHandler(LoginContidoException.class)
    ResponseEntity<ProblemDetail> loginContido(LoginContidoException contido) {
        long segundos = (contido.espera().toMillis() + 999) / 1000;
        long minutos = (segundos + 59) / 60;
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                "Muitas tentativas de entrar. Tente de novo em " + minutos
                        + (minutos == 1 ? " minuto." : " minutos."));
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(segundos))
                .body(problema);
    }

    record PedidoDeLogin(@NotBlank String email, @NotBlank String senha) {
    }

    record RespostaDeLogin(String token) {
    }

    /**
     * @param tipoNegocio       omitido no JSON quando a conta não tem tipo
     * @param vencimentoDoPlano omitido no plano gratuito
     * @param inicioDaSuspensao omitido no plano gratuito
     */
    record RespostaDeIdentidade(UUID usuarioId, String nome, String perfil, UUID contaId,
            String nomeNegocio, String tipoNegocio, boolean estoqueHabilitado, String plano,
            String situacaoDoPlano, LocalDate vencimentoDoPlano, LocalDate inicioDaSuspensao,
            List<String> recursos) {
    }
}
