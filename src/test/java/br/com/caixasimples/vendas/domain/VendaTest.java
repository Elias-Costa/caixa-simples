package br.com.caixasimples.vendas.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import br.com.caixasimples.shared.Money;
import br.com.caixasimples.vendas.StatusVenda;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A montagem da comanda (RF07, RF08) e a invariante do total, em memória.
 *
 * <p>Teste de unidade puro, sem contexto Spring e sem banco, porque a raiz não conhece framework.
 * O que atravessa o banco, inclusive a cópia do preço do produto, fica em
 * {@code VendaServiceTest}.
 *
 * <p>Os valores seguem o exemplo da venda por peso: {@code 0,750 kg} a {@code R$ 39,90} dá
 * {@code R$ 29,925}, que o item arredonda para {@code R$ 29,93} antes de somar.
 */
class VendaTest {

    private static final UUID SESSAO = UUID.randomUUID();
    private static final UUID OPERADOR = UUID.randomUUID();
    private static final UUID CAFE = UUID.randomUUID();
    private static final UUID QUEIJO = UUID.randomUUID();

    private static final BigDecimal DOIS = new BigDecimal("2");
    private static final BigDecimal SETECENTOS_E_CINQUENTA_GRAMAS = new BigDecimal("0.750");

    @Test
    @DisplayName("nasce ABERTA, vazia, com total e desconto zero e sem cliente")
    void nasceAbertaEVazia() {
        Venda venda = new Venda(SESSAO, OPERADOR);

        assertThat(venda.getId()).isNotNull();
        assertThat(venda.getSessaoCaixaId()).isEqualTo(SESSAO);
        assertThat(venda.getUsuarioId()).isEqualTo(OPERADOR);
        assertThat(venda.getClienteId()).isNull();
        assertThat(venda.getStatus()).isEqualTo(StatusVenda.ABERTA);
        assertThat(venda.getValorTotal()).isEqualTo(Money.ZERO);
        assertThat(venda.getValorDesconto()).isEqualTo(Money.ZERO);
        assertThat(venda.getItens()).isEmpty();
        assertThat(venda.getPagamentos()).isEmpty();
        assertThat(venda.getCriadoEm()).isNotNull();

        assertThatNullPointerException().isThrownBy(() -> new Venda(null, OPERADOR));
        assertThatNullPointerException().isThrownBy(() -> new Venda(SESSAO, null));
    }

    @Test
    @DisplayName("o total é a soma dos itens, cada um arredondado antes de somar")
    void totalSomaOsItensArredondadosUmAUm() {
        Venda venda = new Venda(SESSAO, OPERADOR);

        UUID itemDoCafe = venda.adicionarItem(CAFE, DOIS, Money.de("4.50"), Money.ZERO);
        venda.adicionarItem(QUEIJO, SETECENTOS_E_CINQUENTA_GRAMAS, Money.de("39.90"), Money.ZERO);

        // 9,00 + 29,93. Se o arredondamento fosse no total, daria 38,925 e não haveria como
        // fechar as linhas do comprovante com o valor impresso.
        assertThat(venda.getValorTotal()).isEqualTo(Money.de("38.93"));
        assertThat(venda.getItens()).hasSize(2);
        assertThat(venda.getItens().get(0).id()).isEqualTo(itemDoCafe);
        assertThat(venda.getItens().get(0).precoUnitario()).isEqualTo(Money.de("4.50"));
        assertThat(venda.getItens().get(1).subtotal()).isEqualTo(Money.de("29.93"));
        assertThatInvarianteVale(venda);
    }

    @Test
    @DisplayName("desconto do item entra no subtotal, e igual ao bruto zera o item (RF08)")
    void descontoDoItemEntraNoSubtotal() {
        Venda venda = new Venda(SESSAO, OPERADOR);

        venda.adicionarItem(CAFE, DOIS, Money.de("4.50"), Money.de("1.00"));
        assertThat(venda.getValorTotal()).isEqualTo(Money.de("8.00"));

        // Cortesia: desconto exatamente igual ao bruto vale, e o item passa a valer zero.
        venda.adicionarItem(QUEIJO, BigDecimal.ONE, Money.de("10.00"), Money.de("10.00"));
        assertThat(venda.getValorTotal()).isEqualTo(Money.de("8.00"));
        assertThat(venda.getItens().get(1).subtotal()).isEqualTo(Money.ZERO);
        assertThatInvarianteVale(venda);
    }

    @Test
    @DisplayName("desconto do item maior que o bruto é recusado, sem deixar rastro")
    void descontoDoItemAcimaDoBrutoERecusado() {
        Venda venda = new Venda(SESSAO, OPERADOR);
        venda.adicionarItem(CAFE, DOIS, Money.de("4.50"), Money.ZERO);

        // 0,750 × 39,90 = 29,93 depois de arredondar; 29,94 passa por um centavo.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> venda.adicionarItem(QUEIJO, SETECENTOS_E_CINQUENTA_GRAMAS,
                        Money.de("39.90"), Money.de("29.94")))
                .withMessageContaining("maior que o valor do item");

        assertThat(venda.getItens()).hasSize(1);
        assertThat(venda.getValorTotal()).isEqualTo(Money.de("9.00"));
        assertThatInvarianteVale(venda);
    }

    @Test
    @DisplayName("as guardas do item valem na entrada pela raiz")
    void guardasDoItemValemPelaRaiz() {
        Venda venda = new Venda(SESSAO, OPERADOR);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> venda.adicionarItem(CAFE, BigDecimal.ZERO, Money.de("4.50"),
                        Money.ZERO))
                .withMessageContaining("quantidade");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> venda.adicionarItem(CAFE, new BigDecimal("0.7505"),
                        Money.de("4.50"), Money.ZERO))
                .withMessageContaining("casas decimais");
        assertThatNullPointerException()
                .isThrownBy(() -> venda.adicionarItem(CAFE, BigDecimal.ONE, Money.de("4.50"),
                        null))
                .withMessageContaining("Money.ZERO");

        assertThat(venda.getItens()).isEmpty();
        assertThat(venda.getValorTotal()).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("desconto da venda recalcula o total e substitui o anterior, sem acumular (RF08)")
    void descontoDaVendaSubstituiOAnterior() {
        Venda venda = new Venda(SESSAO, OPERADOR);
        venda.adicionarItem(CAFE, DOIS, Money.de("4.50"), Money.ZERO);
        venda.adicionarItem(QUEIJO, SETECENTOS_E_CINQUENTA_GRAMAS, Money.de("39.90"), Money.ZERO);

        venda.aplicarDesconto(Money.de("5.00"));
        assertThat(venda.getValorDesconto()).isEqualTo(Money.de("5.00"));
        assertThat(venda.getValorTotal()).isEqualTo(Money.de("33.93"));

        venda.aplicarDesconto(Money.de("3.00"));
        assertThat(venda.getValorDesconto()).isEqualTo(Money.de("3.00"));
        assertThat(venda.getValorTotal()).isEqualTo(Money.de("35.93"));

        // Tirar o desconto é aplicar zero.
        venda.aplicarDesconto(Money.ZERO);
        assertThat(venda.getValorTotal()).isEqualTo(Money.de("38.93"));
        assertThatInvarianteVale(venda);
    }

    @Test
    @DisplayName("desconto da venda igual à soma zera o total; acima dela ou negativo é recusado")
    void descontoDaVendaNaoPassaDaSoma() {
        Venda venda = new Venda(SESSAO, OPERADOR);
        venda.adicionarItem(CAFE, DOIS, Money.de("4.50"), Money.ZERO);

        venda.aplicarDesconto(Money.de("9.00"));
        assertThat(venda.getValorTotal()).isEqualTo(Money.ZERO);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> venda.aplicarDesconto(Money.de("9.01")))
                .withMessageContaining("maior que a soma dos itens");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> venda.aplicarDesconto(Money.de("-0.01")))
                .withMessageContaining("negativo");
        assertThatNullPointerException()
                .isThrownBy(() -> venda.aplicarDesconto(null));

        // A recusa não mexe no que estava aplicado.
        assertThat(venda.getValorDesconto()).isEqualTo(Money.de("9.00"));
        assertThatInvarianteVale(venda);
    }

    @Test
    @DisplayName("venda vazia não aceita desconto: não há de onde descontar")
    void vendaVaziaNaoAceitaDesconto() {
        Venda venda = new Venda(SESSAO, OPERADOR);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> venda.aplicarDesconto(Money.de("0.01")))
                .withMessageContaining("maior que a soma dos itens");

        // Zero em venda vazia vale: é o mesmo estado pedido de novo.
        venda.aplicarDesconto(Money.ZERO);
        assertThat(venda.getValorTotal()).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("remover item recalcula o total, e o item some da lista")
    void removerItemRecalculaOTotal() {
        Venda venda = new Venda(SESSAO, OPERADOR);
        UUID itemDoCafe = venda.adicionarItem(CAFE, DOIS, Money.de("4.50"), Money.ZERO);
        venda.adicionarItem(QUEIJO, SETECENTOS_E_CINQUENTA_GRAMAS, Money.de("39.90"), Money.ZERO);

        venda.removerItem(itemDoCafe);

        assertThat(venda.getItens()).extracting(ItemVenda::produtoId).containsExactly(QUEIJO);
        assertThat(venda.getValorTotal()).isEqualTo(Money.de("29.93"));
        assertThatInvarianteVale(venda);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> venda.removerItem(itemDoCafe))
                .withMessageContaining("nao esta na venda");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> venda.removerItem(UUID.randomUUID()))
                .withMessageContaining("nao esta na venda");
    }

    @Test
    @DisplayName("remover item que deixaria o desconto da venda maior que a soma é recusado")
    void removerItemNaoDeixaOTotalNegativo() {
        Venda venda = new Venda(SESSAO, OPERADOR);
        UUID itemDoCafe = venda.adicionarItem(CAFE, DOIS, Money.de("4.50"), Money.ZERO);
        UUID itemDoQueijo = venda.adicionarItem(QUEIJO, SETECENTOS_E_CINQUENTA_GRAMAS,
                Money.de("39.90"), Money.ZERO);
        venda.aplicarDesconto(Money.de("20.00"));

        // Sem o queijo sobram 9,00, que não cobrem os 20,00 de desconto.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> venda.removerItem(itemDoQueijo))
                .withMessageContaining("Reduza o desconto antes");

        assertThat(venda.getItens()).hasSize(2);
        assertThat(venda.getValorTotal()).isEqualTo(Money.de("18.93"));

        // Sem o café sobram 29,93, que cobrem. E o total continua batendo.
        venda.removerItem(itemDoCafe);
        assertThat(venda.getValorTotal()).isEqualTo(Money.de("9.93"));
        assertThatInvarianteVale(venda);
    }

    @Test
    @DisplayName("o mesmo produto pode aparecer em duas linhas, sem mesclar")
    void mesmoProdutoEmDuasLinhas() {
        Venda venda = new Venda(SESSAO, OPERADOR);

        UUID primeira = venda.adicionarItem(CAFE, BigDecimal.ONE, Money.de("4.50"), Money.ZERO);
        UUID segunda = venda.adicionarItem(CAFE, BigDecimal.ONE, Money.de("4.50"),
                Money.de("0.50"));

        assertThat(primeira).isNotEqualTo(segunda);
        assertThat(venda.getItens()).hasSize(2);
        assertThat(venda.getItens()).extracting(ItemVenda::produtoId).containsOnly(CAFE);
        assertThat(venda.getValorTotal()).isEqualTo(Money.de("8.50"));
        assertThatInvarianteVale(venda);
    }

    @Test
    @DisplayName("venda que não está ABERTA recusa item, remoção e desconto")
    void vendaForaDeAbertaNaoAceitaMontagem() {
        for (StatusVenda status : List.of(StatusVenda.CONCLUIDA, StatusVenda.CANCELADA)) {
            ItemVenda item = ItemVenda.novo(CAFE, DOIS, Money.de("4.50"), Money.ZERO);
            Venda venda = Venda.reconstituir(UUID.randomUUID(), SESSAO, OPERADOR, null, status,
                    Money.de("9.00"), Money.ZERO, Instant.now(), List.of(item), List.of());

            assertThatIllegalStateException()
                    .as("adicionar em " + status)
                    .isThrownBy(() -> venda.adicionarItem(QUEIJO, BigDecimal.ONE,
                            Money.de("10.00"), Money.ZERO))
                    .withMessageContaining(status.name());
            assertThatIllegalStateException()
                    .as("remover em " + status)
                    .isThrownBy(() -> venda.removerItem(item.id()))
                    .withMessageContaining(status.name());
            assertThatIllegalStateException()
                    .as("descontar em " + status)
                    .isThrownBy(() -> venda.aplicarDesconto(Money.de("1.00")))
                    .withMessageContaining(status.name());

            assertThat(venda.getItens()).hasSize(1);
            assertThat(venda.getValorTotal()).isEqualTo(Money.de("9.00"));
        }
    }

    /**
     * A invariante da raiz, conferida do jeito que um leitor conferiria: somando a lista e
     * subtraindo o desconto. Se a raiz esquecer de recalcular em alguma operação, é aqui que o
     * teste denuncia.
     */
    private static void assertThatInvarianteVale(Venda venda) {
        Money somaDosItens = venda.getItens().stream()
                .map(ItemVenda::subtotal)
                .reduce(Money.ZERO, Money::somar);

        assertThat(venda.getValorTotal())
                .as("valorTotal = soma dos subtotais menos o desconto da venda")
                .isEqualTo(somaDosItens.subtrair(venda.getValorDesconto()));
        assertThat(venda.getValorTotal().isNegativo()).isFalse();
    }
}
