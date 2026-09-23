package br.com.caixasimples.vendas.web;

import br.com.caixasimples.vendas.application.VendaService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Consultas operacionais de fiado para seleção do Cliente e cobrança no balcão. */
@RestController
@RequestMapping("/api/fiado")
class FiadoController {

    private final VendaService vendas;

    FiadoController(VendaService vendas) {
        this.vendas = vendas;
    }

    @GetMapping("/clientes/{clienteId}/saldo")
    SaldoNaResposta saldo(@PathVariable UUID clienteId) {
        return new SaldoNaResposta(clienteId, vendas.saldoDevedorDoCliente(clienteId).valor());
    }

    @GetMapping("/dividas")
    List<DividaNaResposta> dividas() {
        return vendas.dividasEmAberto().stream().map(DividaNaResposta::de).toList();
    }

    record SaldoNaResposta(UUID clienteId, BigDecimal saldoDevedor) {
    }

    record DividaNaResposta(UUID vendaId, UUID clienteId, String nomeCliente,
            Instant concluidoEm, BigDecimal saldoDevedor) {
        static DividaNaResposta de(VendaService.DividaParaTela divida) {
            return new DividaNaResposta(divida.vendaId(), divida.clienteId(),
                    divida.nomeCliente(), divida.concluidoEm(), divida.saldoDevedor().valor());
        }
    }
}
