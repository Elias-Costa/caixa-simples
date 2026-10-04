package br.com.caixasimples.contas.web;

import br.com.caixasimples.contas.Plano;
import br.com.caixasimples.contas.RecursoDoPlano;
import br.com.caixasimples.contas.application.PedidoDePlanoNaoEncontradoException;
import br.com.caixasimples.contas.application.PlanoService;
import br.com.caixasimples.contas.application.PlanoService.EstadoDoPlano;
import br.com.caixasimples.contas.application.PlanoService.PedidoNaConta;
import br.com.caixasimples.contas.application.PlanoService.Proposta;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * O plano da Conta autenticada: consultar, pedir e aplicar o código de ativação (RF31).
 *
 * <p>Sem id de Conta na rota ou no pedido (RNF05). O id do pedido na rota de aplicação é procurado
 * só na Conta do token, e o de outra Conta responde 404, como um id que nunca existiu.
 */
@RestController
@RequestMapping("/api/conta/plano")
class PlanoController {

    private final PlanoService planos;

    PlanoController(PlanoService planos) {
        this.planos = planos;
    }

    @GetMapping
    EstadoNaResposta consultar() {
        return EstadoNaResposta.de(planos.consultar());
    }

    @PostMapping("/pedidos")
    ResponseEntity<PedidoNaResposta> pedir(@Valid @RequestBody PedidoDeTroca pedido) {
        PedidoNaConta criado = planos.pedir(pedido.plano());
        return ResponseEntity.created(URI.create("/api/conta/plano/pedidos/" + criado.id()))
                .body(PedidoNaResposta.de(criado));
    }

    @PostMapping("/pedidos/{id}/codigo")
    EstadoNaResposta aplicarCodigo(@PathVariable UUID id,
            @Valid @RequestBody CodigoDeAtivacao corpo) {
        return EstadoNaResposta.de(planos.aplicarCodigo(id, corpo.codigo()));
    }

    @ExceptionHandler(PedidoDePlanoNaoEncontradoException.class)
    ProblemDetail naoEncontrado(PedidoDePlanoNaoEncontradoException excecao) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, excecao.getMessage());
    }

    record PedidoDeTroca(@NotNull Plano plano) {
    }

    record CodigoDeAtivacao(@NotBlank String codigo) {
    }

    /**
     * @param vencimento        omitido no plano gratuito
     * @param inicioDaSuspensao omitido no plano gratuito
     * @param pedidoAberto      omitido sem pedido à espera do código
     */
    record EstadoNaResposta(String plano, String situacao, LocalDate vencimento,
            LocalDate inicioDaSuspensao, List<String> recursos, BigDecimal mensalidadeCaixaSimples,
            BigDecimal mensalidadeCompleto, List<PropostaNaResposta> propostas,
            PedidoNaResposta pedidoAberto) {

        static EstadoNaResposta de(EstadoDoPlano estado) {
            return new EstadoNaResposta(estado.plano().name(), estado.situacao().name(),
                    estado.vencimento(), estado.inicioDaSuspensao(),
                    estado.recursos().stream().map(RecursoDoPlano::name).toList(),
                    estado.mensalidadeCaixaSimples().valor(), estado.mensalidadeCompleto().valor(),
                    estado.propostas().stream().map(PropostaNaResposta::de).toList(),
                    estado.pedidoAberto() == null ? null : PedidoNaResposta.de(estado.pedidoAberto()));
        }
    }

    /** @param periodoInicio omitido na adesão, cujo período começa na aplicação do código */
    record PropostaNaResposta(String tipo, String plano, BigDecimal valor, LocalDate periodoInicio,
            LocalDate periodoFim) {

        static PropostaNaResposta de(Proposta proposta) {
            return new PropostaNaResposta(proposta.tipo().name(), proposta.plano().name(),
                    proposta.valor().valor(), proposta.periodoInicio(), proposta.periodoFim());
        }
    }

    record PedidoNaResposta(UUID id, String tipo, String plano, BigDecimal valor,
            LocalDate periodoInicio, LocalDate periodoFim, String situacao, Instant criadoEm) {

        static PedidoNaResposta de(PedidoNaConta pedido) {
            return new PedidoNaResposta(pedido.id(), pedido.tipo().name(), pedido.plano().name(),
                    pedido.valor().valor(), pedido.periodoInicio(), pedido.periodoFim(),
                    pedido.situacao().name(), pedido.criadoEm());
        }
    }
}
