package br.com.caixasimples.sincronizacao.web;

import br.com.caixasimples.sincronizacao.application.RevisaoService;
import br.com.caixasimples.sincronizacao.application.RevisaoService.RevisaoDeOperacao;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Entrada HTTP da lista de revisões do administrador e da conferência de cada uma.
 *
 * <p>A lista traz as pendências de qualquer dia, porque uma revisão esquecida não pode sumir da
 * vista só porque o dia virou, e as conferidas no dia pedido, para quem quer rever o que já olhou.
 * Operador recebe 403; operação de outra Conta, 404 (RNF05).
 */
@RestController
@RequestMapping("/api/sincronizacao/revisoes")
class RevisaoController {

    private final RevisaoService revisoes;

    RevisaoController(RevisaoService revisoes) {
        this.revisoes = revisoes;
    }

    @GetMapping
    RevisoesNaResposta listar(@RequestParam LocalDate dia) {
        return new RevisoesNaResposta(
                revisoes.pendentes().stream().map(RevisaoNaResposta::de).toList(),
                revisoes.conferidasNoDia(dia).stream().map(RevisaoNaResposta::de).toList());
    }

    @PostMapping("/{operacaoId}/conferencia")
    ResponseEntity<Void> conferir(@PathVariable UUID operacaoId) {
        revisoes.conferir(operacaoId);
        return ResponseEntity.noContent().build();
    }

    record RevisoesNaResposta(List<RevisaoNaResposta> pendentes,
            List<RevisaoNaResposta> conferidas) {
    }

    record RevisaoNaResposta(UUID operacaoId, UUID usuarioId, String tipo, UUID registroId,
            JsonNode payload, Instant criadaEm, Instant recebidaEm, String resultado,
            String detalhe, Instant conferidaEm, UUID conferidaPor) {

        static RevisaoNaResposta de(RevisaoDeOperacao revisao) {
            return new RevisaoNaResposta(revisao.operacaoId(), revisao.usuarioId(),
                    revisao.tipo(), revisao.registroId(), revisao.payload(), revisao.criadaEm(),
                    revisao.recebidaEm(), revisao.resultado().name(), revisao.detalhe(),
                    revisao.conferidaEm(), revisao.conferidaPor());
        }
    }
}
