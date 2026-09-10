package br.com.caixasimples.vendas.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.pagamentos.StatusPagamento;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.vendas.StatusVenda;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * As guardas dos membros do agregado Venda e a imutabilidade do que a raiz expõe.
 *
 * <p>Teste de unidade puro, sem contexto Spring e sem banco, porque nada aqui conhece framework.
 *
 * <p>O que este arquivo <strong>não</strong> cobre, de propósito: a invariante do total e a regra
 * dos pagamentos. As duas ainda não existem em código, já que a raiz só remonta o que está gravado;
 * cada uma ganha teste junto do caso de uso que a traz.
 */
class MembrosDaVendaTest {

    private static final UUID PRODUTO = UUID.randomUUID();

    @Test
    @DisplayName("item aceita quantidade fracionada, preço zero e desconto zero")
    void itemAceitaOsLimitesValidos() {
        ItemVenda item = new ItemVenda(UUID.randomUUID(), PRODUTO, new BigDecimal("0.750"),
                Money.ZERO, Money.ZERO, Instant.now());

        assertThat(item.quantidade()).isEqualByComparingTo("0.750");
        assertThat(item.precoUnitario()).isEqualTo(Money.ZERO);
        assertThat(item.desconto()).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("item recusa quantidade zero ou negativa: nada foi vendido")
    void itemRecusaQuantidadeNaoPositiva() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> item(BigDecimal.ZERO, Money.de("10.00"), Money.ZERO))
                .withMessageContaining("quantidade");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> item(new BigDecimal("-1"), Money.de("10.00"), Money.ZERO))
                .withMessageContaining("quantidade");
    }

    @Test
    @DisplayName("item recusa preço unitário negativo e desconto negativo")
    void itemRecusaValoresNegativos() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> item(BigDecimal.ONE, Money.de("-0.01"), Money.ZERO))
                .withMessageContaining("preco unitario");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> item(BigDecimal.ONE, Money.de("10.00"), Money.de("-0.01")))
                .withMessageContaining("desconto");
    }

    @Test
    @DisplayName("item recusa nulo em todo campo: desconto ausente é zero, nunca nulo")
    void itemRecusaNulos() {
        Instant agora = Instant.now();

        assertThatNullPointerException().isThrownBy(() ->
                new ItemVenda(null, PRODUTO, BigDecimal.ONE, Money.ZERO, Money.ZERO, agora));
        assertThatNullPointerException().isThrownBy(() ->
                new ItemVenda(UUID.randomUUID(), null, BigDecimal.ONE, Money.ZERO, Money.ZERO,
                        agora));
        assertThatNullPointerException().isThrownBy(() ->
                new ItemVenda(UUID.randomUUID(), PRODUTO, null, Money.ZERO, Money.ZERO, agora));
        assertThatNullPointerException().isThrownBy(() ->
                new ItemVenda(UUID.randomUUID(), PRODUTO, BigDecimal.ONE, null, Money.ZERO, agora));
        assertThatNullPointerException()
                .isThrownBy(() -> new ItemVenda(UUID.randomUUID(), PRODUTO, BigDecimal.ONE,
                        Money.ZERO, null, agora))
                .withMessageContaining("Money.ZERO");
        assertThatNullPointerException().isThrownBy(() ->
                new ItemVenda(UUID.randomUUID(), PRODUTO, BigDecimal.ONE, Money.ZERO, Money.ZERO,
                        null));
    }

    @Test
    @DisplayName("pagamento aceita valor zero e recusa valor negativo")
    void pagamentoGuardaOSinalDoValor() {
        Pagamento zero = new Pagamento(UUID.randomUUID(), FormaPagamento.DINHEIRO, Money.ZERO,
                StatusPagamento.CONFIRMADO, Instant.now());
        assertThat(zero.valor()).isEqualTo(Money.ZERO);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Pagamento(UUID.randomUUID(), FormaPagamento.PIX,
                        Money.de("-5.00"), StatusPagamento.CONFIRMADO, Instant.now()))
                .withMessageContaining("negativo");
    }

    @Test
    @DisplayName("pagamento recusa nulo em todo campo")
    void pagamentoRecusaNulos() {
        Instant agora = Instant.now();

        assertThatNullPointerException().isThrownBy(() ->
                new Pagamento(null, FormaPagamento.PIX, Money.ZERO, StatusPagamento.CONFIRMADO,
                        agora));
        assertThatNullPointerException().isThrownBy(() ->
                new Pagamento(UUID.randomUUID(), null, Money.ZERO, StatusPagamento.CONFIRMADO,
                        agora));
        assertThatNullPointerException().isThrownBy(() ->
                new Pagamento(UUID.randomUUID(), FormaPagamento.PIX, null,
                        StatusPagamento.CONFIRMADO, agora));
        assertThatNullPointerException().isThrownBy(() ->
                new Pagamento(UUID.randomUUID(), FormaPagamento.PIX, Money.ZERO, null, agora));
        assertThatNullPointerException().isThrownBy(() ->
                new Pagamento(UUID.randomUUID(), FormaPagamento.PIX, Money.ZERO,
                        StatusPagamento.CONFIRMADO, null));
    }

    @Test
    @DisplayName("a raiz copia as listas que recebe e devolve cópias imutáveis")
    void raizNaoCompartilhaAsListas() {
        List<ItemVenda> itens = new ArrayList<>();
        itens.add(item(BigDecimal.ONE, Money.de("10.00"), Money.ZERO));
        List<Pagamento> pagamentos = new ArrayList<>();

        Venda venda = Venda.reconstituir(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                null, StatusVenda.ABERTA, Money.de("10.00"), Money.ZERO, Instant.now(), itens,
                pagamentos);

        // Alterar a lista original depois de montar a venda não pode alcançar o agregado: senão
        // qualquer chamador teria uma porta lateral para inserir item sem passar pela raiz.
        itens.add(item(BigDecimal.ONE, Money.de("99.00"), Money.ZERO));
        assertThat(venda.getItens()).hasSize(1);

        assertThatThrownBy(() -> venda.getItens().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> venda.getPagamentos().add(new Pagamento(UUID.randomUUID(),
                FormaPagamento.PIX, Money.ZERO, StatusPagamento.CONFIRMADO, Instant.now())))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static ItemVenda item(BigDecimal quantidade, Money precoUnitario, Money desconto) {
        return new ItemVenda(UUID.randomUUID(), PRODUTO, quantidade, precoUnitario, desconto,
                Instant.now());
    }
}
