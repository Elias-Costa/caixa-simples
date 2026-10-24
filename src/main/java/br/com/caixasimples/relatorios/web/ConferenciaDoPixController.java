package br.com.caixasimples.relatorios.web;

import br.com.caixasimples.relatorios.application.ConferenciaDoPixService;
import br.com.caixasimples.relatorios.application.ConferenciaDoPixService.ConferenciaDoPix;
import br.com.caixasimples.relatorios.application.ConferenciaDoPixService.Lancamento;
import br.com.caixasimples.relatorios.application.OrigemDoLancamento;
import br.com.caixasimples.vendas.StatusVenda;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Entrada HTTP da conferência do Pix de um dia, de todos os operadores ou de um só (RF24); a
 * autorização de ADMIN e o plano ficam no caso de uso.
 *
 * <p>O dia é obrigatório e é um só: a lista não aceita período, porque não é soma.
 */
@RestController
@RequestMapping("/api/relatorios/conferencia-do-pix")
class ConferenciaDoPixController {

    private final ConferenciaDoPixService conferencia;

    ConferenciaDoPixController(ConferenciaDoPixService conferencia) {
        this.conferencia = conferencia;
    }

    @GetMapping
    ConferenciaNaResposta doDia(@RequestParam LocalDate dia,
            @RequestParam(required = false) UUID operadorId) {
        // O caso de uso tem uma assinatura para cada pergunta; sem operador, é a da Conta inteira.
        ConferenciaDoPix resultado;
        if (operadorId == null) {
            resultado = conferencia.doDia(dia);
        } else {
            resultado = conferencia.doDia(dia, operadorId);
        }
        return ConferenciaNaResposta.de(resultado);
    }

    record ConferenciaNaResposta(LocalDate dia, List<LancamentoNaResposta> lancamentos) {
        static ConferenciaNaResposta de(ConferenciaDoPix resultado) {
            return new ConferenciaNaResposta(resultado.dia(),
                    resultado.lancamentos().stream().map(LancamentoNaResposta::de).toList());
        }
    }

    /** O cliente sai da resposta quando a venda não tem, e a tela não mostra a linha dele. */
    record LancamentoNaResposta(UUID id, OrigemDoLancamento origem, UUID vendaId,
            Instant lancadoEm, BigDecimal valor, UUID operadorId, StatusVenda situacaoDaVenda,
            UUID clienteId, boolean integrado, boolean aguardandoConfirmacao) {
        static LancamentoNaResposta de(Lancamento lancamento) {
            return new LancamentoNaResposta(lancamento.id(), lancamento.origem(),
                    lancamento.vendaId(), lancamento.lancadoEm(), lancamento.valor().valor(),
                    lancamento.operadorId(), lancamento.situacaoDaVenda(), lancamento.clienteId(),
                    lancamento.integrado(), lancamento.aguardandoConfirmacao());
        }
    }
}
