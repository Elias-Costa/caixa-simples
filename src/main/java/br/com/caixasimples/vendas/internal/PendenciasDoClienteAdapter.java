package br.com.caixasimples.vendas.internal;

import br.com.caixasimples.cadastro.PendenciasDoCliente;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.vendas.StatusVenda;
import br.com.caixasimples.vendas.domain.Venda;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** A Venda responde sobre seu próprio saldo e seu próprio estado antes da remoção do Cliente. */
@Component
class PendenciasDoClienteAdapter implements PendenciasDoCliente {

    private final VendaRepository vendas;

    PendenciasDoClienteAdapter(VendaRepository vendas) {
        this.vendas = vendas;
    }

    @Override
    public Money saldoDevedor(UUID clienteId) {
        return vendas.findByClienteIdAndStatus(clienteId, StatusVenda.CONCLUIDA).stream()
                .map(VendaEntity::paraDominio)
                .map(Venda::saldoDevedor)
                .reduce(Money.ZERO, Money::somar);
    }

    @Override
    public boolean temComandaAberta(UUID clienteId) {
        return vendas.existsByClienteIdAndStatus(clienteId, StatusVenda.ABERTA);
    }
}
