package br.com.caixasimples.vendas.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.pagamentos.StatusPagamento;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.vendas.StatusVenda;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FiadoTest {

    private Venda comanda() {
        Venda venda = new Venda(UUID.randomUUID(), UUID.randomUUID());
        venda.adicionarItem(UUID.randomUUID(), BigDecimal.ONE, Money.de("20.00"), Money.ZERO);
        return venda;
    }

    @Test
    void fiadoExigeClienteEPermiteUmaParcelaPendente() {
        Venda venda = comanda();
        venda.registrarPagamento(FormaPagamento.FIADO, Money.de("19.00"),
                StatusPagamento.PENDENTE, Money.ZERO);
        assertThatIllegalStateException().isThrownBy(venda::concluir)
                .withMessageContaining("cliente");
        assertThatIllegalStateException().isThrownBy(() -> venda.registrarPagamento(
                FormaPagamento.FIADO, Money.de("1.00"), StatusPagamento.PENDENTE, Money.ZERO));
        venda.registrarPagamento(FormaPagamento.DINHEIRO, Money.de("1.00"),
                StatusPagamento.CONFIRMADO, Money.ZERO);
        venda.vincularCliente(UUID.randomUUID());
        venda.concluir();
        assertThat(venda.getStatus()).isEqualTo(StatusVenda.CONCLUIDA);
        assertThat(venda.saldoDevedor()).isEqualTo(Money.de("19.00"));
    }

    @Test
    void comandaSemFiadoFicaSemCliente() {
        Venda venda = comanda();
        venda.vincularCliente(UUID.randomUUID());
        venda.registrarPagamento(FormaPagamento.DINHEIRO, Money.de("20.00"),
                StatusPagamento.CONFIRMADO, Money.ZERO);
        venda.desvincularCliente();
        assertThat(venda.getClienteId()).isNull();
        venda.concluir();
        assertThat(venda.getStatus()).isEqualTo(StatusVenda.CONCLUIDA);
    }

    @Test
    void parcelaFiadoLancadaNaoDeixaTirarOClienteSoTrocar() {
        Venda venda = comanda();
        UUID primeiro = UUID.randomUUID();
        venda.vincularCliente(primeiro);
        venda.registrarPagamento(FormaPagamento.FIADO, Money.de("20.00"),
                StatusPagamento.PENDENTE, Money.ZERO);
        assertThatIllegalStateException().isThrownBy(venda::desvincularCliente)
                .withMessageContaining("pagamento no fiado");
        assertThat(venda.getClienteId()).isEqualTo(primeiro);
        UUID outro = UUID.randomUUID();
        venda.vincularCliente(outro);
        assertThat(venda.getClienteId()).isEqualTo(outro);
    }

    @Test
    void vendaConcluidaNaoPerdeOCliente() {
        Venda venda = comanda();
        UUID cliente = UUID.randomUUID();
        venda.vincularCliente(cliente);
        venda.registrarPagamento(FormaPagamento.CARTAO, Money.de("20.00"),
                StatusPagamento.CONFIRMADO, Money.ZERO);
        venda.concluir();
        assertThatIllegalStateException().isThrownBy(venda::desvincularCliente)
                .withMessageContaining("já está concluída");
        assertThat(venda.getClienteId()).isEqualTo(cliente);
    }

    @Test
    void doisRecebimentosParciaisQuitamSemPerderHistorico() {
        Venda venda = comanda();
        venda.vincularCliente(UUID.randomUUID());
        venda.registrarPagamento(FormaPagamento.FIADO, Money.de("20.00"),
                StatusPagamento.PENDENTE, Money.ZERO);
        venda.concluir();
        UUID sessaoDeQuemRecebe = UUID.randomUUID();
        UUID recebimentoId = UUID.randomUUID();
        venda.receber(recebimentoId, sessaoDeQuemRecebe, Money.de("7.00"),
                FormaPagamento.DINHEIRO);
        assertThat(venda.saldoDevedor()).isEqualTo(Money.de("13.00"));
        assertThatIllegalStateException().isThrownBy(() -> venda.receber(recebimentoId,
                sessaoDeQuemRecebe, Money.de("7.00"), FormaPagamento.DINHEIRO));
        assertThat(venda.saldoDevedor()).isEqualTo(Money.de("13.00"));
        assertThat(venda.getPagamentos().getFirst().status()).isEqualTo(StatusPagamento.PENDENTE);
        assertThatIllegalArgumentException().isThrownBy(() ->
                venda.receber(sessaoDeQuemRecebe, Money.de("13.01"), FormaPagamento.PIX));
        venda.receber(sessaoDeQuemRecebe, Money.de("13.00"), FormaPagamento.PIX);
        assertThat(venda.getRecebimentos()).hasSize(2);
        assertThat(venda.saldoDevedor()).isEqualTo(Money.ZERO);
        assertThat(venda.getPagamentos().getFirst().status()).isEqualTo(StatusPagamento.CONFIRMADO);
    }

    @Test
    void cancelamentoRetiraDividaSemApagarRecebimentos() {
        Venda venda = comanda();
        venda.vincularCliente(UUID.randomUUID());
        venda.registrarPagamento(FormaPagamento.FIADO, Money.de("20.00"),
                StatusPagamento.PENDENTE, Money.ZERO);
        venda.concluir();
        venda.receber(UUID.randomUUID(), Money.de("4.00"), FormaPagamento.CARTAO);
        venda.cancelar();
        assertThat(venda.saldoDevedor()).isEqualTo(Money.ZERO);
        assertThat(venda.getRecebimentos()).hasSize(1);
        assertThatIllegalStateException().isThrownBy(() ->
                venda.receber(UUID.randomUUID(), Money.de("1.00"), FormaPagamento.PIX));
    }
}
