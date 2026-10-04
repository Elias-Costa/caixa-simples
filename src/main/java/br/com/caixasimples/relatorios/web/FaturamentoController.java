package br.com.caixasimples.relatorios.web;

import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.relatorios.application.FaturamentoService;
import br.com.caixasimples.relatorios.application.FaturamentoService.Faturamento;
import br.com.caixasimples.relatorios.application.FaturamentoService.Filtros;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Entrada HTTP do faturamento (RF21, RF24); a autorização de ADMIN e o plano ficam no caso de uso.
 *
 * <p>Os filtros por forma de pagamento e por operador existem só na rota do período, porque é por
 * ela que a tela pede tudo, inclusive o dia de hoje, como um período de um dia. Filtro ausente é o
 * faturamento inteiro naquela dimensão, e os dois se combinam. A tela monta a quebra por forma
 * pedindo esta rota uma vez por forma: as formas somam o total sem filtro, porque o que se soma é
 * a parcela, e não a venda.
 */
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
    FaturamentoNaResposta doPeriodo(@RequestParam LocalDate inicio, @RequestParam LocalDate fim,
            @RequestParam(required = false) FormaPagamento forma,
            @RequestParam(required = false) UUID operadorId) {
        return FaturamentoNaResposta.de(
                faturamento.doPeriodo(inicio, fim, new Filtros(forma, operadorId)));
    }

    record FaturamentoNaResposta(LocalDate inicio, LocalDate fim, BigDecimal total,
            long quantidadeDeVendas) {
        static FaturamentoNaResposta de(Faturamento resultado) {
            return new FaturamentoNaResposta(resultado.inicio(), resultado.fim(),
                    resultado.total().valor(), resultado.quantidadeDeVendas());
        }
    }
}
