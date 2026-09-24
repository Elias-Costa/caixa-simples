package br.com.caixasimples.vendas.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.tuple;

import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.pagamentos.StatusPagamento;
import br.com.caixasimples.pagamentos.domain.CobrancaPix;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.vendas.StatusVenda;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A montagem da comanda (RF07, RF08), o pagamento dividido entre formas e a conclusão (RF09), o
 * cancelamento (RF12) e as duas invariantes da raiz, em memória.
 *
 * <p>Teste de unidade puro, sem contexto Spring e sem banco, porque a raiz não conhece framework.
 * O que atravessa o banco, inclusive a cópia do preço do produto e o Strategy de pagamento de
 * verdade, fica em {@code VendaServiceTest}. Aqui o status de cada parcela é passado direto à
 * raiz, o que permite provar o que PENDENTE e RECUSADO fazem na conta antes de existir estratégia
 * que os produza.
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
    void pixPendenteReservaSaldoMasQrNaoConcluiVenda() {
        Venda venda = new Venda(SESSAO, OPERADOR);
        venda.adicionarItem(CAFE, BigDecimal.ONE, Money.de("10.00"), Money.ZERO);
        UUID tentativa = UUID.randomUUID();
        CobrancaPix cobranca = CobrancaPix.aguardando(tentativa, "chave-teste",
                Instant.now().plusSeconds(900));

        Pagamento primeira = venda.reservarPix(tentativa, Money.de("10.00"), cobranca);
        assertThat(venda.reservarPix(tentativa, Money.de("10.00"), cobranca))
                .isEqualTo(primeira);
        assertThat(venda.getPagamentos()).hasSize(1);
        assertThatIllegalStateException().isThrownBy(() -> venda.reservarPix(tentativa,
                Money.de("9.00"), cobranca));
        venda.atualizarCobrancaPix(tentativa, cobranca.disponivel("codigo-copia-e-cola"));
        venda.atualizarCobrancaPix(tentativa, cobranca.incerta());
        assertThat(venda.getPagamentos().get(0).cobrancaPix().estado())
                .isEqualTo(CobrancaPix.Estado.DISPONIVEL);
        assertThat(venda.getPagamentos().get(0).cobrancaPix().copiaECola())
                .isEqualTo("codigo-copia-e-cola");
        assertThat(venda.getPagamentos().get(0).status()).isEqualTo(StatusPagamento.PENDENTE);
        assertThatIllegalStateException().isThrownBy(venda::concluir);
    }

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
    @DisplayName("venda que não está ABERTA recusa item, remoção, desconto, pagamento e conclusão")
    void vendaForaDeAbertaNaoAceitaMontagem() {
        for (StatusVenda status : List.of(StatusVenda.CONCLUIDA, StatusVenda.CANCELADA)) {
            ItemVenda item = ItemVenda.novo(CAFE, DOIS, Money.de("4.50"), Money.ZERO);
            // A CONCLUIDA precisa da parcela que a fecha, senão reconstituir a recusa. A CANCELADA
            // não tem regra de pagamento, e vai com a mesma parcela por simplicidade.
            Pagamento parcela = Pagamento.novo(FormaPagamento.DINHEIRO, Money.de("9.00"),
                    StatusPagamento.CONFIRMADO, Money.ZERO);
            Venda venda = Venda.reconstituir(UUID.randomUUID(), SESSAO, OPERADOR, null, status,
                    Money.de("9.00"), Money.ZERO, Instant.now(), Instant.now(), List.of(item),
                    List.of(parcela));

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
            assertThatIllegalStateException()
                    .as("pagar em " + status)
                    .isThrownBy(() -> venda.registrarPagamento(FormaPagamento.PIX,
                            Money.de("1.00"), StatusPagamento.CONFIRMADO, Money.ZERO))
                    .withMessageContaining(status.name());
            assertThatIllegalStateException()
                    .as("concluir em " + status)
                    .isThrownBy(venda::concluir)
                    .withMessageContaining(status.name());

            assertThat(venda.getItens()).hasSize(1);
            assertThat(venda.getPagamentos()).hasSize(1);
            assertThat(venda.getValorTotal()).isEqualTo(Money.de("9.00"));
            assertThat(venda.getStatus()).isEqualTo(status);
        }
    }

    @Test
    @DisplayName("parcelas entram uma a uma, sem mudar o status, até o que falta pagar (RF09)")
    void parcelasEntramUmaAUmaSemConcluir() {
        Venda venda = new Venda(SESSAO, OPERADOR);
        venda.adicionarItem(CAFE, DOIS, Money.de("4.50"), Money.ZERO);
        venda.adicionarItem(QUEIJO, SETECENTOS_E_CINQUENTA_GRAMAS, Money.de("39.90"), Money.ZERO);

        venda.registrarPagamento(FormaPagamento.PIX, Money.de("20.00"), StatusPagamento.CONFIRMADO, Money.ZERO);
        // A parcela que fecha a conta não conclui: quem conclui é concluir.
        venda.registrarPagamento(FormaPagamento.DINHEIRO, Money.de("18.93"),
                StatusPagamento.CONFIRMADO, Money.ZERO);

        assertThat(venda.getStatus()).isEqualTo(StatusVenda.ABERTA);
        assertThat(venda.getPagamentos())
                .extracting(Pagamento::forma, Pagamento::valor, Pagamento::status)
                .containsExactly(
                        tuple(FormaPagamento.PIX, Money.de("20.00"), StatusPagamento.CONFIRMADO),
                        tuple(FormaPagamento.DINHEIRO, Money.de("18.93"),
                                StatusPagamento.CONFIRMADO));
        assertThat(venda.getPagamentos()).allSatisfy(parcela -> {
            assertThat(parcela.id()).isNotNull();
            assertThat(parcela.criadoEm()).isNotNull();
        });

        assertThatNullPointerException().isThrownBy(() ->
                venda.registrarPagamento(null, Money.de("1.00"), StatusPagamento.CONFIRMADO, Money.ZERO));
        assertThatNullPointerException().isThrownBy(() ->
                venda.registrarPagamento(FormaPagamento.PIX, null, StatusPagamento.CONFIRMADO, Money.ZERO));
        assertThatNullPointerException().isThrownBy(() ->
                venda.registrarPagamento(FormaPagamento.PIX, Money.de("1.00"), null, Money.ZERO));
    }

    @Test
    @DisplayName("parcela maior que o que falta pagar é recusada, sem deixar rastro")
    void parcelaAcimaDoSaldoERecusada() {
        Venda venda = new Venda(SESSAO, OPERADOR);
        venda.adicionarItem(CAFE, DOIS, Money.de("15.00"), Money.ZERO);
        venda.registrarPagamento(FormaPagamento.PIX, Money.de("20.00"), StatusPagamento.CONFIRMADO, Money.ZERO);

        // Total 30, 20 já lançados: 15 passa do que falta, que é 10.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> venda.registrarPagamento(FormaPagamento.CARTAO,
                        Money.de("15.00"), StatusPagamento.CONFIRMADO, Money.ZERO))
                .withMessageContaining("maior que o que falta pagar, 10.00");

        assertThat(venda.getPagamentos()).hasSize(1);

        // Exatamente o que falta passa: é a parcela que fecha a conta.
        venda.registrarPagamento(FormaPagamento.CARTAO, Money.de("10.00"),
                StatusPagamento.CONFIRMADO, Money.ZERO);
        assertThat(venda.getPagamentos()).hasSize(2);

        // Com a conta fechada, qualquer parcela positiva passa do que falta, que é zero.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> venda.registrarPagamento(FormaPagamento.DINHEIRO,
                        Money.de("0.01"), StatusPagamento.CONFIRMADO, Money.ZERO))
                .withMessageContaining("maior que o que falta pagar, 0.00");
    }

    @Test
    @DisplayName("parcela PENDENTE reserva lugar na conta; RECUSADO não ocupa lugar")
    void pendenteReservaLugarERecusadoNao() {
        Venda venda = new Venda(SESSAO, OPERADOR);
        venda.adicionarItem(CAFE, DOIS, Money.de("15.00"), Money.ZERO);

        // Uma cobrança Pix que espera o provedor: o dinheiro por baixo não pode cobrir o total
        // inteiro, senão a confirmação chegaria a uma venda já paga.
        venda.registrarPagamento(FormaPagamento.PIX, Money.de("20.00"), StatusPagamento.PENDENTE, Money.ZERO);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> venda.registrarPagamento(FormaPagamento.DINHEIRO,
                        Money.de("30.00"), StatusPagamento.CONFIRMADO, Money.ZERO))
                .withMessageContaining("maior que o que falta pagar, 10.00");

        // Um cartão negado é desfecho encerrado: os 10 dele continuam a pagar.
        venda.registrarPagamento(FormaPagamento.CARTAO, Money.de("10.00"),
                StatusPagamento.RECUSADO, Money.ZERO);
        venda.registrarPagamento(FormaPagamento.DINHEIRO, Money.de("10.00"),
                StatusPagamento.CONFIRMADO, Money.ZERO);
        assertThat(venda.getPagamentos()).hasSize(3);

        // E nem PENDENTE nem RECUSADO contam para concluir: só 10 dos 30 estão confirmados.
        assertThatIllegalStateException()
                .isThrownBy(venda::concluir)
                .withMessageContaining("10.00 em pagamentos confirmados")
                .withMessageContaining("faltam 20.00");
        assertThat(venda.getStatus()).isEqualTo(StatusVenda.ABERTA);
    }

    @Test
    @DisplayName("conclui a venda dividida entre dinheiro e Pix quando os confirmados batem com o total (RF09)")
    void concluiVendaDivididaEntreDinheiroEPix() {
        Venda venda = new Venda(SESSAO, OPERADOR);
        venda.adicionarItem(CAFE, DOIS, Money.de("4.50"), Money.ZERO);
        venda.adicionarItem(QUEIJO, SETECENTOS_E_CINQUENTA_GRAMAS, Money.de("39.90"), Money.ZERO);
        venda.aplicarDesconto(Money.de("3.93"));
        // 9,00 + 29,93 menos 3,93 = 35,00.
        assertThat(venda.getValorTotal()).isEqualTo(Money.de("35.00"));

        venda.registrarPagamento(FormaPagamento.PIX, Money.de("20.00"), StatusPagamento.CONFIRMADO, Money.ZERO);
        venda.registrarPagamento(FormaPagamento.DINHEIRO, Money.de("15.00"),
                StatusPagamento.CONFIRMADO, Money.ZERO);
        venda.concluir();

        assertThat(venda.getStatus()).isEqualTo(StatusVenda.CONCLUIDA);
        assertThatInvarianteVale(venda);
    }

    @Test
    @DisplayName("concluir com pagamentos confirmados a menos é recusado e a venda continua ABERTA")
    void concluirComPagamentoAMenosERecusado() {
        Venda venda = new Venda(SESSAO, OPERADOR);
        venda.adicionarItem(CAFE, DOIS, Money.de("4.50"), Money.ZERO);

        assertThatIllegalStateException()
                .as("sem parcela nenhuma")
                .isThrownBy(venda::concluir)
                .withMessageContaining("0.00 em pagamentos confirmados")
                .withMessageContaining("faltam 9.00");

        venda.registrarPagamento(FormaPagamento.PIX, Money.de("5.00"), StatusPagamento.CONFIRMADO, Money.ZERO);
        assertThatIllegalStateException()
                .as("com parte paga")
                .isThrownBy(venda::concluir)
                .withMessageContaining("faltam 4.00");

        assertThat(venda.getStatus()).isEqualTo(StatusVenda.ABERTA);
        assertThat(venda.getPagamentos()).hasSize(1);
    }

    @Test
    @DisplayName("venda sem item não conclui; brinde de preço zero conclui sem parcela")
    void vendaVaziaNaoConcluiEBrindeConclui() {
        Venda vazia = new Venda(SESSAO, OPERADOR);
        assertThatIllegalStateException()
                .isThrownBy(vazia::concluir)
                .withMessageContaining("nao tem item nenhum");
        assertThat(vazia.getStatus()).isEqualTo(StatusVenda.ABERTA);

        // Algo foi vendido, por zero: a conta fecha com zero em pagamentos.
        Venda brinde = new Venda(SESSAO, OPERADOR);
        brinde.adicionarItem(CAFE, BigDecimal.ONE, Money.ZERO, Money.ZERO);
        brinde.concluir();
        assertThat(brinde.getStatus()).isEqualTo(StatusVenda.CONCLUIDA);
        assertThat(brinde.getPagamentos()).isEmpty();
        assertThatInvarianteVale(brinde);
    }

    @Test
    @DisplayName("depois de uma parcela, a comanda continua aberta a montagem, mas o total não fica abaixo do já pago")
    void montagemDepoisDeParcelaNaoFicaAbaixoDoPago() {
        Venda venda = new Venda(SESSAO, OPERADOR);
        UUID itemDeQuinze = venda.adicionarItem(CAFE, BigDecimal.ONE, Money.de("15.00"),
                Money.ZERO);
        venda.adicionarItem(QUEIJO, BigDecimal.ONE, Money.de("15.00"), Money.ZERO);
        venda.registrarPagamento(FormaPagamento.PIX, Money.de("20.00"), StatusPagamento.CONFIRMADO, Money.ZERO);

        // Adicionar nunca reduz o total, então nunca é recusado por este motivo.
        UUID itemDeQuatroECinquenta = venda.adicionarItem(CAFE, BigDecimal.ONE, Money.de("4.50"),
                Money.ZERO);
        assertThat(venda.getValorTotal()).isEqualTo(Money.de("34.50"));

        // Remover um item de 15 deixaria 19,50, abaixo dos 20 pagos.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> venda.removerItem(itemDeQuinze))
                .withMessageContaining("abaixo dos 20.00 ja lancados");
        // Descontar 15 deixaria 19,50 também.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> venda.aplicarDesconto(Money.de("15.00")))
                .withMessageContaining("abaixo dos 20.00 ja lancados");
        assertThat(venda.getItens()).hasSize(3);
        assertThat(venda.getValorDesconto()).isEqualTo(Money.ZERO);

        // Remover o item de 4,50 deixa 30,00, que ainda cobre os 20; descontar 10 deixa exatamente
        // 20, que também cobre.
        venda.removerItem(itemDeQuatroECinquenta);
        venda.aplicarDesconto(Money.de("10.00"));
        assertThat(venda.getValorTotal()).isEqualTo(Money.de("20.00"));
        assertThatInvarianteVale(venda);
    }

    @Test
    @DisplayName("reconstituir recusa total divergente dos itens: o estado não vira agregado")
    void reconstituirRecusaTotalDivergente() {
        ItemVenda item = ItemVenda.novo(CAFE, DOIS, Money.de("4.50"), Money.ZERO);

        // 9,00 gravado como 10,00: a linha mente, e a raiz recusa em vez de remontar.
        assertThatIllegalStateException()
                .isThrownBy(() -> Venda.reconstituir(UUID.randomUUID(), SESSAO, OPERADOR, null,
                        StatusVenda.ABERTA, Money.de("10.00"), Money.ZERO, Instant.now(), null,
                        List.of(item), List.of()))
                .withMessageContaining("valorTotal 10.00")
                .withMessageContaining("viola a invariante do total");

        // O desconto entra na conta: 9,00 menos 1,00 é 8,00, não 9,00.
        assertThatIllegalStateException()
                .isThrownBy(() -> Venda.reconstituir(UUID.randomUUID(), SESSAO, OPERADOR, null,
                        StatusVenda.ABERTA, Money.de("9.00"), Money.de("1.00"), Instant.now(),
                        null, List.of(item), List.of()))
                .withMessageContaining("viola a invariante do total");

        // O estado coerente passa, inclusive com desconto.
        Venda coerente = Venda.reconstituir(UUID.randomUUID(), SESSAO, OPERADOR, null,
                StatusVenda.ABERTA, Money.de("8.00"), Money.de("1.00"), Instant.now(), null,
                List.of(item), List.of());
        assertThat(coerente.getValorTotal()).isEqualTo(Money.de("8.00"));
    }

    @Test
    @DisplayName("reconstituir recusa venda CONCLUIDA cujos confirmados não batem com o total")
    void reconstituirRecusaConcluidaSemPagamentoQueFeche() {
        ItemVenda item = ItemVenda.novo(CAFE, DOIS, Money.de("4.50"), Money.ZERO);
        Pagamento parcial = Pagamento.novo(FormaPagamento.PIX, Money.de("5.00"),
                StatusPagamento.CONFIRMADO, Money.ZERO);
        Pagamento pendente = Pagamento.novo(FormaPagamento.PIX, Money.de("9.00"),
                StatusPagamento.PENDENTE, Money.ZERO);

        assertThatIllegalStateException()
                .as("confirmados a menos")
                .isThrownBy(() -> Venda.reconstituir(UUID.randomUUID(), SESSAO, OPERADOR, null,
                        StatusVenda.CONCLUIDA, Money.de("9.00"), Money.ZERO, Instant.now(),
                        Instant.now(), List.of(item), List.of(parcial)))
                .withMessageContaining("viola a invariante da conclusao");
        assertThatIllegalStateException()
                .as("PENDENTE não conta como confirmado")
                .isThrownBy(() -> Venda.reconstituir(UUID.randomUUID(), SESSAO, OPERADOR, null,
                        StatusVenda.CONCLUIDA, Money.de("9.00"), Money.ZERO, Instant.now(),
                        Instant.now(), List.of(item), List.of(pendente)))
                .withMessageContaining("viola a invariante da conclusao");

        // ABERTA com parcela parcial ou pendente é o estado normal de uma comanda em pagamento.
        Venda aberta = Venda.reconstituir(UUID.randomUUID(), SESSAO, OPERADOR, null,
                StatusVenda.ABERTA, Money.de("9.00"), Money.ZERO, Instant.now(), null,
                List.of(item), List.of(parcial));
        assertThat(aberta.getStatus()).isEqualTo(StatusVenda.ABERTA);

        // CANCELADA com a conta pela metade também: é a comanda abandonada depois de uma parcela.
        Venda cancelada = Venda.reconstituir(UUID.randomUUID(), SESSAO, OPERADOR, null,
                StatusVenda.CANCELADA, Money.de("9.00"), Money.ZERO, Instant.now(), null,
                List.of(item), List.of(parcial));
        assertThat(cancelada.getStatus()).isEqualTo(StatusVenda.CANCELADA);
    }

    @Test
    @DisplayName("reconstituir recusa venda CONCLUIDA sem o instante da conclusão; nas outras ele é livre")
    void reconstituirRecusaConcluidaSemInstante() {
        ItemVenda item = ItemVenda.novo(CAFE, DOIS, Money.de("4.50"), Money.ZERO);
        Pagamento parcela = Pagamento.novo(FormaPagamento.PIX, Money.de("9.00"),
                StatusPagamento.CONFIRMADO, Money.ZERO);

        // A conta fecha, mas a linha não sabe quando: é estado que a raiz nunca produz.
        assertThatIllegalStateException()
                .isThrownBy(() -> Venda.reconstituir(UUID.randomUUID(), SESSAO, OPERADOR, null,
                        StatusVenda.CONCLUIDA, Money.de("9.00"), Money.ZERO, Instant.now(), null,
                        List.of(item), List.of(parcela)))
                .withMessageContaining("sem o instante da conclusao");

        Instant instante = Instant.now();
        Venda concluida = Venda.reconstituir(UUID.randomUUID(), SESSAO, OPERADOR, null,
                StatusVenda.CONCLUIDA, Money.de("9.00"), Money.ZERO, Instant.now(), instante,
                List.of(item), List.of(parcela));
        assertThat(concluida.getConcluidoEm()).isEqualTo(instante);

        // CANCELADA vem das duas origens: da comanda abandonada, sem instante, e da venda
        // concluída e depois devolvida, com ele.
        Venda abandonada = Venda.reconstituir(UUID.randomUUID(), SESSAO, OPERADOR, null,
                StatusVenda.CANCELADA, Money.de("9.00"), Money.ZERO, Instant.now(), null,
                List.of(item), List.of());
        assertThat(abandonada.getConcluidoEm()).isNull();
        Venda devolvida = Venda.reconstituir(UUID.randomUUID(), SESSAO, OPERADOR, null,
                StatusVenda.CANCELADA, Money.de("9.00"), Money.ZERO, Instant.now(), instante,
                List.of(item), List.of(parcela));
        assertThat(devolvida.getConcluidoEm()).isEqualTo(instante);
    }

    @Test
    @DisplayName("concluir grava o instante da conclusão, que fica depois do cancelamento; a parcela guarda o troco")
    void concluirGravaOInstanteEAParcelaGuardaOTroco() {
        Venda venda = new Venda(SESSAO, OPERADOR);
        venda.adicionarItem(CAFE, DOIS, Money.de("4.50"), Money.ZERO);
        assertThat(venda.getConcluidoEm()).as("comanda aberta nao tem instante").isNull();

        // O cliente entregou 10,00 por 9,00: o troco chega pronto do módulo de pagamentos e fica
        // na parcela, para o comprovante sair igual numa reimpressão.
        venda.registrarPagamento(FormaPagamento.DINHEIRO, Money.de("9.00"),
                StatusPagamento.CONFIRMADO, Money.de("1.00"));
        assertThat(venda.getConcluidoEm()).as("parcela nao conclui").isNull();
        assertThat(venda.getPagamentos())
                .extracting(Pagamento::forma, Pagamento::valor, Pagamento::troco)
                .containsExactly(tuple(FormaPagamento.DINHEIRO, Money.de("9.00"),
                        Money.de("1.00")));
        // O troco não entra na conta: a venda está paga com 9,00, não com 10,00.
        assertThat(venda.getValorTotal()).isEqualTo(Money.de("9.00"));

        Instant antes = Instant.now();
        venda.concluir();
        Instant concluidoEm = venda.getConcluidoEm();
        assertThat(concluidoEm).isNotNull();
        assertThat(concluidoEm).isAfterOrEqualTo(antes);
        assertThat(concluidoEm).isAfterOrEqualTo(venda.getCriadoEm());
        assertThatInvarianteVale(venda);

        venda.cancelar();
        assertThat(venda.getConcluidoEm())
                .as("a conclusao aconteceu, e o cancelamento nao apaga isso")
                .isEqualTo(concluidoEm);

        // Troco nulo é recusado antes de a parcela existir, como os outros nulos.
        Venda outra = new Venda(SESSAO, OPERADOR);
        outra.adicionarItem(CAFE, DOIS, Money.de("4.50"), Money.ZERO);
        assertThatNullPointerException().isThrownBy(() ->
                outra.registrarPagamento(FormaPagamento.DINHEIRO, Money.de("9.00"),
                        StatusPagamento.CONFIRMADO, null));
        assertThat(outra.getPagamentos()).isEmpty();
    }

    @Test
    @DisplayName("cancelar desfaz a comanda ABERTA, com ou sem parcela, e a venda CONCLUIDA; itens e parcelas ficam (RF12)")
    void cancelaAbertaEConcluida() {
        Venda vazia = new Venda(SESSAO, OPERADOR);
        vazia.cancelar();
        assertThat(vazia.getStatus()).isEqualTo(StatusVenda.CANCELADA);

        Venda pelaMetade = new Venda(SESSAO, OPERADOR);
        pelaMetade.adicionarItem(CAFE, DOIS, Money.de("4.50"), Money.ZERO);
        pelaMetade.registrarPagamento(FormaPagamento.PIX, Money.de("5.00"),
                StatusPagamento.CONFIRMADO, Money.ZERO);
        pelaMetade.cancelar();
        assertThat(pelaMetade.getStatus()).isEqualTo(StatusVenda.CANCELADA);
        assertThat(pelaMetade.getItens()).hasSize(1);
        assertThat(pelaMetade.getPagamentos())
                .extracting(Pagamento::status)
                .containsExactly(StatusPagamento.CONFIRMADO);
        assertThat(pelaMetade.getValorTotal()).isEqualTo(Money.de("9.00"));

        Venda concluida = new Venda(SESSAO, OPERADOR);
        concluida.adicionarItem(CAFE, DOIS, Money.de("4.50"), Money.ZERO);
        concluida.registrarPagamento(FormaPagamento.DINHEIRO, Money.de("9.00"),
                StatusPagamento.CONFIRMADO, Money.ZERO);
        concluida.concluir();
        concluida.cancelar();
        assertThat(concluida.getStatus()).isEqualTo(StatusVenda.CANCELADA);
        // O que tinha sido vendido e como tinha sido pago continua contado: é o que permite ao
        // caixa e ao estoque desfazerem exatamente o que a conclusão fez.
        assertThat(concluida.getItens()).hasSize(1);
        assertThat(concluida.getPagamentos())
                .extracting(Pagamento::forma, Pagamento::valor, Pagamento::status)
                .containsExactly(tuple(FormaPagamento.DINHEIRO, Money.de("9.00"),
                        StatusPagamento.CONFIRMADO));
        assertThatInvarianteVale(concluida);
    }

    @Test
    @DisplayName("CANCELADA é final: não cancela de novo, e o resto continua recusado")
    void canceladaEFinal() {
        Venda venda = new Venda(SESSAO, OPERADOR);
        venda.adicionarItem(CAFE, DOIS, Money.de("4.50"), Money.ZERO);
        venda.cancelar();

        assertThatIllegalStateException()
                .isThrownBy(venda::cancelar)
                .withMessageContaining("ja esta CANCELADA");
        assertThatIllegalStateException()
                .isThrownBy(venda::concluir)
                .withMessageContaining("CANCELADA");
        assertThatIllegalStateException()
                .isThrownBy(() -> venda.registrarPagamento(FormaPagamento.PIX, Money.de("9.00"),
                        StatusPagamento.CONFIRMADO, Money.ZERO))
                .withMessageContaining("CANCELADA");
        assertThat(venda.getStatus()).isEqualTo(StatusVenda.CANCELADA);
    }

    /**
     * As invariantes da raiz, conferidas do jeito que um leitor conferiria: somando as listas. Se
     * a raiz esquecer de recalcular em alguma operação, ou concluir sem a conta fechada, é aqui
     * que o teste denuncia.
     */
    private static void assertThatInvarianteVale(Venda venda) {
        Money somaDosItens = venda.getItens().stream()
                .map(ItemVenda::subtotal)
                .reduce(Money.ZERO, Money::somar);

        assertThat(venda.getValorTotal())
                .as("valorTotal = soma dos subtotais menos o desconto da venda")
                .isEqualTo(somaDosItens.subtrair(venda.getValorDesconto()));
        assertThat(venda.getValorTotal().isNegativo()).isFalse();

        if (venda.getStatus() == StatusVenda.CONCLUIDA) {
            Money confirmados = venda.getPagamentos().stream()
                    .filter(parcela -> parcela.status() == StatusPagamento.CONFIRMADO)
                    .map(Pagamento::valor)
                    .reduce(Money.ZERO, Money::somar);
            assertThat(confirmados)
                    .as("numa venda CONCLUIDA, a soma dos CONFIRMADO e o total")
                    .isEqualTo(venda.getValorTotal());
            assertThat(venda.getConcluidoEm())
                    .as("venda CONCLUIDA sabe quando concluiu")
                    .isNotNull();
        }
    }
}
