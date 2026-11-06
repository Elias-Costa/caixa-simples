package br.com.caixasimples.sincronizacao.web;

import br.com.caixasimples.sincronizacao.OperacaoRecebida;
import br.com.caixasimples.sincronizacao.application.ResultadoDaOperacao;
import br.com.caixasimples.sincronizacao.application.SincronizacaoService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Entrada HTTP do lote que o dispositivo envia quando a rede volta.
 *
 * <p>Responde 200 com um resultado por operação, na ordem do pedido, mesmo quando algumas foram
 * recusadas: cada resultado diz o que fazer com o gesto correspondente na fila local. O lote
 * inteiro só é recusado quando não pode ser lido: sem token, 401; com mais de
 * {@link SincronizacaoService#LIMITE_DO_LOTE} operações, com id repetido ou dependências em ciclo,
 * 400. A conta e o usuário vêm do token, nunca do corpo (RNF05).
 */
@RestController
@RequestMapping("/api/sincronizacao")
class SincronizacaoController {

    private final SincronizacaoService sincronizacao;

    SincronizacaoController(SincronizacaoService sincronizacao) {
        this.sincronizacao = sincronizacao;
    }

    @PostMapping
    RespostaDoLote sincronizar(@Valid @RequestBody PedidoDoLote pedido) {
        List<OperacaoRecebida> operacoes = pedido.operacoes().stream()
                .map(OperacaoNoPedido::recebida)
                .toList();
        return new RespostaDoLote(sincronizacao.sincronizar(operacoes).stream()
                .map(ResultadoNaResposta::de)
                .toList());
    }

    record PedidoDoLote(
            @NotNull @Size(max = SincronizacaoService.LIMITE_DO_LOTE)
            List<@Valid @NotNull OperacaoNoPedido> operacoes) {
    }

    /** Os campos do gesto como o dispositivo o guarda na fila local. */
    record OperacaoNoPedido(@NotNull UUID operacaoId, @NotNull UUID registroId,
            @NotBlank @Size(max = 60) String tipo, @NotNull JsonNode payload, Long versaoBase,
            List<@NotNull UUID> dependeDe, @NotNull Instant criadoEm) {

        OperacaoRecebida recebida() {
            return new OperacaoRecebida(operacaoId, registroId, tipo, payload, versaoBase,
                    dependeDe, criadoEm);
        }
    }

    record RespostaDoLote(List<ResultadoNaResposta> resultados) {
    }

    /**
     * O resultado de uma operação. Os tipos da revisão vêm só na revisão que os tem, e faltam nos
     * outros resultados: o aparelho que não os conhece lê os mesmos campos de sempre.
     */
    record ResultadoNaResposta(UUID operacaoId, String resultado, Long versao, String detalhe,
            List<String> tiposDaRevisao) {

        static ResultadoNaResposta de(ResultadoDaOperacao resultado) {
            // Nulo sai da resposta, porque a aplicação escreve JSON sem os campos nulos.
            List<String> tipos = resultado.tiposDaRevisao().isEmpty() ? null
                    : resultado.tiposDaRevisao().stream().map(Enum::name).toList();
            return new ResultadoNaResposta(resultado.operacaoId(), resultado.resultado().name(),
                    resultado.versao(), resultado.detalhe(), tipos);
        }
    }
}
