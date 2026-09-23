package br.com.caixasimples.relatorios.web;

import br.com.caixasimples.relatorios.application.FaturamentoService;
import br.com.caixasimples.relatorios.application.FaturamentoService.Faturamento;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Entrada HTTP do faturamento (RF21); a autorização de ADMIN fica no caso de uso. */
@RestController
@RequestMapping("/api/relatorios/faturamento")
class FaturamentoController {

    private final FaturamentoService faturamento;

    FaturamentoController(FaturamentoService faturamento) {
        this.faturamento = faturamento;
    }

    @GetMapping("/dia")
    FaturamentoNaResposta doDia(@RequestParam LocalDate dia) {
        return FaturamentoNaResposta.de(faturamento.doDia(dia));
    }

    @GetMapping
    FaturamentoNaResposta doPeriodo(@RequestParam LocalDate inicio, @RequestParam LocalDate fim) {
        return FaturamentoNaResposta.de(faturamento.doPeriodo(inicio, fim));
    }

    record FaturamentoNaResposta(LocalDate inicio, LocalDate fim, BigDecimal total,
            long quantidadeDeVendas) {
        static FaturamentoNaResposta de(Faturamento resultado) {
            return new FaturamentoNaResposta(resultado.inicio(), resultado.fim(),
                    resultado.total().valor(), resultado.quantidadeDeVendas());
        }
    }
}
