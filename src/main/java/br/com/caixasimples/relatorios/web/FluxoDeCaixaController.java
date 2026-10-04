package br.com.caixasimples.relatorios.web;

import br.com.caixasimples.relatorios.application.FluxoDeCaixaService;
import br.com.caixasimples.relatorios.application.FluxoDeCaixaService.FluxoDeCaixa;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Entrada HTTP do fluxo de caixa de um período (RF23); a autorização de ADMIN e o plano ficam no
 * caso de uso.
 *
 * <p>Sem filtro além do período: é a gaveta da Conta inteira, só dinheiro em espécie, e o operador
 * da gaveta é o da sessão, que o histórico do caixa já responde.
 */
@RestController
@RequestMapping("/api/relatorios/fluxo-de-caixa")
class FluxoDeCaixaController {

    private final FluxoDeCaixaService fluxo;

    FluxoDeCaixaController(FluxoDeCaixaService fluxo) {
        this.fluxo = fluxo;
    }

    @GetMapping
    FluxoDeCaixaNaResposta doPeriodo(@RequestParam LocalDate inicio, @RequestParam LocalDate fim) {
        return FluxoDeCaixaNaResposta.de(fluxo.doPeriodo(inicio, fim));
    }

    record FluxoDeCaixaNaResposta(LocalDate inicio, LocalDate fim, BigDecimal vendas,
            BigDecimal suprimentos, BigDecimal sangrias, BigDecimal estornos,
            BigDecimal recebimentos, BigDecimal entradas, BigDecimal saidas, BigDecimal saldo) {
        static FluxoDeCaixaNaResposta de(FluxoDeCaixa resultado) {
            return new FluxoDeCaixaNaResposta(resultado.inicio(), resultado.fim(),
                    resultado.vendas().valor(), resultado.suprimentos().valor(),
                    resultado.sangrias().valor(), resultado.estornos().valor(),
                    resultado.recebimentos().valor(), resultado.entradas().valor(),
                    resultado.saidas().valor(), resultado.saldo().valor());
        }
    }
}
