package br.com.caixasimples.estoque;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.cadastro.TipoProduto;
import br.com.caixasimples.cadastro.application.ProdutoService;
import br.com.caixasimples.cadastro.application.ProdutoService.DadosDoProduto;
import br.com.caixasimples.cadastro.internal.ProdutoRepository;
import br.com.caixasimples.caixa.application.SessaoCaixaService;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.pagamentos.StatusPagamento;
import br.com.caixasimples.pagamentos.domain.SolicitacaoPagamento;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.TenantContext;
import br.com.caixasimples.vendas.CriadorDeVendaDeTeste;
import br.com.caixasimples.vendas.VendaConcluida;
import br.com.caixasimples.vendas.VendaConcluida.Item;
import br.com.caixasimples.vendas.VendaConcluida.Parcela;
import br.com.caixasimples.vendas.application.VendaService;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.CompletedEventPublications;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.core.TargetEventPublication;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * O estoque reagindo à venda concluída, com o registro de publicação de verdade no meio.
 *
 * <p>Publica o evento diretamente, sem passar por {@code VendaService}, para provar só o que é do
 * estoque: a conta com o controle desligado não baixa nada, a ligada baixa um movimento por item
 * de produto, a reentrega não duplica, e o ouvinte acha o produto da conta do evento numa thread
 * que não tem tenant nenhum. O último teste faz o caminho inteiro, da comanda ao saldo.
 *
 * <p>Todo evento é publicado dentro de uma transação, porque um listener transacional só é
 * chamado depois de um commit. O listener roda em outra thread, então cada asserção espera com
 * Awaitility. Há dois ouvintes do mesmo evento, o caixa e o estoque, então quem espera pela
 * publicação concluída filtra pelo ouvinte do estoque.
 */
class BaixaDeEstoqueListenerTest extends TesteDeIntegracao {

    private static final String SENHA_DE_TESTE = "uma senha longa de teste";
    private static final Duration ESPERA = Duration.ofSeconds(10);

    @Autowired
    private ApplicationEventPublisher publicador;

    @Autowired
    private TransactionTemplate transacao;

    @Autowired
    private SessaoCaixaService sessoesDeCaixa;

    @Autowired
    private ProdutoService produtoService;

    @Autowired
    private ProdutoRepository produtos;

    @Autowired
    private VendaService vendaService;

    @Autowired
    private CompletedEventPublications publicacoesConcluidas;

    @Autowired
    private CriadorDeContaDeTeste criador;

    @Autowired
    private CriadorDeVendaDeTeste vendas;

    @AfterEach
    void limparContexto() {
        TenantContext.limpar();
    }

    @Test
    @DisplayName("com o controle de estoque desligado, a venda não gera movimento nem muda saldo")
    void comEstoqueDesligadoNadaAcontece() {
        ContaCriada conta = criador.criar("Salao Vizinho", SENHA_DE_TESTE);
        UUID sessaoId = abrirCaixa(conta);
        UUID vendaId = vendas.criarAbertaEm(conta.contaId(), sessaoId, conta.usuarioId());
        UUID shampooId = cadastrar(conta, "Shampoo", TipoProduto.PRODUTO);

        VendaConcluida evento = evento(conta, vendaId, sessaoId,
                List.of(new Item(shampooId, new BigDecimal("2"))));

        publicar(evento);

        // Só dá para afirmar que nada aconteceu depois de o ouvinte do estoque ter terminado, e o
        // sinal de que terminou é a publicação dele concluída no registro.
        await().atMost(ESPERA).untilAsserted(() ->
                assertThat(publicacoesDoEstoque(evento)).hasSize(1));

        conta.comoUsuario(() -> {
            assertThat(saldoDe(shampooId)).isEqualByComparingTo("0");
            assertThat(produtoService.jaDeuBaixaPorVenda(shampooId, vendaId)).isFalse();
        });
    }

    @Test
    @DisplayName("com o controle ligado, cada item de produto vira uma baixa; serviço no meio não gera nada")
    void comEstoqueLigadoBaixaUmMovimentoPorItem() {
        ContaCriada conta = criador.criar("Cafeteria Aurora", SENHA_DE_TESTE);
        criador.habilitarEstoque(conta.contaId());
        UUID sessaoId = abrirCaixa(conta);
        UUID vendaId = vendas.criarAbertaEm(conta.contaId(), sessaoId, conta.usuarioId());
        UUID cafeId = cadastrar(conta, "Cafe coado", TipoProduto.PRODUTO);
        UUID queijoId = cadastrar(conta, "Queijo minas", TipoProduto.PRODUTO);
        UUID entregaId = cadastrar(conta, "Entrega", TipoProduto.SERVICO);

        VendaConcluida evento = evento(conta, vendaId, sessaoId, List.of(
                new Item(cafeId, new BigDecimal("2")),
                new Item(entregaId, BigDecimal.ONE),
                new Item(queijoId, new BigDecimal("0.750"))));

        publicar(evento);

        await().atMost(ESPERA).untilAsserted(() ->
                conta.comoUsuario(() -> {
                    assertThat(saldoDe(cafeId)).isEqualByComparingTo("-2");
                    assertThat(saldoDe(queijoId)).isEqualByComparingTo("-0.750");
                }));

        conta.comoUsuario(() -> {
            assertThat(produtoService.jaDeuBaixaPorVenda(cafeId, vendaId)).isTrue();
            assertThat(produtoService.jaDeuBaixaPorVenda(queijoId, vendaId)).isTrue();
            assertThat(saldoDe(entregaId)).as("serviço não tem estoque").isEqualByComparingTo("0");
            assertThat(produtoService.jaDeuBaixaPorVenda(entregaId, vendaId)).isFalse();
        });
    }

    @Test
    @DisplayName("o mesmo evento entregue duas vezes baixa uma vez só, e as duas entregas terminam")
    void reentregaNaoDuplicaABaixa() {
        ContaCriada conta = criador.criar("Mercearia da Rua", SENHA_DE_TESTE);
        criador.habilitarEstoque(conta.contaId());
        UUID sessaoId = abrirCaixa(conta);
        UUID vendaId = vendas.criarAbertaEm(conta.contaId(), sessaoId, conta.usuarioId());
        UUID arrozId = cadastrar(conta, "Arroz", TipoProduto.PRODUTO);

        VendaConcluida evento = evento(conta, vendaId, sessaoId,
                List.of(new Item(arrozId, new BigDecimal("5"))));

        publicar(evento);
        await().atMost(ESPERA).untilAsserted(() ->
                conta.comoUsuario(() ->
                        assertThat(saldoDe(arrozId)).isEqualByComparingTo("-5")));

        // A segunda publicação do mesmo fato, depois de a primeira ter baixado, é o que uma
        // reentrega do registro de publicação faz. Ela também termina sem erro: fica concluída
        // no registro em vez de presa como falha.
        publicar(evento);
        await().atMost(ESPERA).untilAsserted(() ->
                assertThat(publicacoesDoEstoque(evento))
                        .as("as duas entregas terminaram, nenhuma delas com erro")
                        .hasSize(2));

        conta.comoUsuario(() ->
                assertThat(saldoDe(arrozId)).isEqualByComparingTo("-5"));
    }

    @Test
    @DisplayName("o ouvinte baixa na conta do evento, e a conta B não vê o produto nem o movimento (RNF05)")
    void baixaNaContaDoEventoENaoVazaParaOutra() {
        ContaCriada contaA = criador.criar("Loja A", SENHA_DE_TESTE);
        ContaCriada contaB = criador.criar("Loja B", SENHA_DE_TESTE);
        criador.habilitarEstoque(contaA.contaId());
        criador.habilitarEstoque(contaB.contaId());
        UUID sessaoDaContaA = abrirCaixa(contaA);
        UUID vendaDaContaA = vendas.criarAbertaEm(contaA.contaId(), sessaoDaContaA,
                contaA.usuarioId());
        UUID produtoDaContaA = cadastrar(contaA, "Camiseta", TipoProduto.PRODUTO);

        VendaConcluida evento = evento(contaA, vendaDaContaA, sessaoDaContaA,
                List.of(new Item(produtoDaContaA, BigDecimal.ONE)));

        // Publicado como conta B de propósito: o listener roda em outra thread, sem tenant, e
        // tem de usar a conta que está dentro do evento, não a de quem publicou.
        contaB.comoUsuario(() -> publicar(evento));

        await().atMost(ESPERA).untilAsserted(() ->
                contaA.comoUsuario(() ->
                        assertThat(saldoDe(produtoDaContaA)).isEqualByComparingTo("-1")));

        contaB.comoUsuario(() -> {
            assertThat(produtos.findById(produtoDaContaA)).isEmpty();
            assertThat(produtoService.jaDeuBaixaPorVenda(produtoDaContaA, vendaDaContaA))
                    .isFalse();
        });
    }

    @Test
    @DisplayName("da comanda ao saldo: concluir a venda pelo caso de uso baixa o estoque dos produtos")
    void vendaConcluidaPeloCasoDeUsoBaixaOEstoque() {
        ContaCriada conta = criador.criar("Padaria Central", SENHA_DE_TESTE);
        criador.habilitarEstoque(conta.contaId());
        UUID sessaoId = abrirCaixa(conta);
        UUID paoId = cadastrar(conta, "Pao frances", TipoProduto.PRODUTO);
        UUID encomendaId = cadastrar(conta, "Encomenda", TipoProduto.SERVICO);

        UUID vendaId = conta.comoUsuario(() ->
                vendaService.iniciar(sessaoId));
        conta.comoUsuario(() -> {
            vendaService.adicionarItem(vendaId, paoId, new BigDecimal("12"), Money.ZERO);
            vendaService.adicionarItem(vendaId, encomendaId, BigDecimal.ONE, Money.ZERO);
            // 12 x 5,00 + 1 x 5,00 = 65,00.
            vendaService.registrarPagamento(vendaId,
                    SolicitacaoPagamento.de(FormaPagamento.PIX, Money.de("65.00")));
            vendaService.concluir(vendaId);
        });

        await().atMost(ESPERA).untilAsserted(() ->
                conta.comoUsuario(() ->
                        assertThat(saldoDe(paoId)).isEqualByComparingTo("-12")));

        conta.comoUsuario(() -> {
            assertThat(saldoDe(encomendaId)).isEqualByComparingTo("0");
            assertThat(produtoService.jaDeuBaixaPorVenda(paoId, vendaId)).isTrue();
        });
    }

    private UUID abrirCaixa(ContaCriada conta) {
        return conta.comoUsuario(() ->
                sessoesDeCaixa.abrir(Money.ZERO));
    }

    private UUID cadastrar(ContaCriada conta, String nome, TipoProduto tipo) {
        return conta.comoUsuario(() ->
                produtoService.cadastrar(tipo,
                        new DadosDoProduto(nome, Money.de("5.00"), null, null, "un", null)));
    }

    /** O saldo como o domínio o vê: a única leitura pública de {@code estoque_atual} hoje. */
    private BigDecimal saldoDe(UUID produtoId) {
        return produtos.findById(produtoId).orElseThrow().paraDominio().getEstoqueAtual();
    }

    /** Um evento pago em Pix, para o ouvinte do caixa não ter o que fazer e sair do caminho. */
    private static VendaConcluida evento(ContaCriada conta, UUID vendaId, UUID sessaoId,
            List<Item> itens) {
        return new VendaConcluida(conta.contaId(), vendaId, sessaoId, conta.usuarioId(), itens,
                List.of(new Parcela(FormaPagamento.PIX, Money.de("10.00"),
                        StatusPagamento.CONFIRMADO)));
    }

    private void publicar(VendaConcluida evento) {
        transacao.executeWithoutResult(status -> publicador.publishEvent(evento));
    }

    /**
     * As publicações deste evento entregues ao ouvinte do estoque. O identificador do alvo é a
     * assinatura do método do listener, então o nome da classe basta para separar do caixa.
     */
    private List<? extends EventPublication> publicacoesDoEstoque(VendaConcluida evento) {
        return publicacoesConcluidas.findAll().stream()
                .filter(publicacao -> publicacao.getEvent().equals(evento))
                .filter(publicacao -> publicacao instanceof TargetEventPublication alvo
                        && alvo.getTargetIdentifier().getValue()
                                .contains("BaixaDeEstoqueListener"))
                .toList();
    }
}
