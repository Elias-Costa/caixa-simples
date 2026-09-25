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
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.TenantContext;
import br.com.caixasimples.vendas.CriadorDeVendaDeTeste;
import br.com.caixasimples.vendas.VendaCancelada;
import br.com.caixasimples.vendas.VendaConcluida;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
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
 * O estoque reagindo à venda cancelada, com o registro de publicação de verdade no meio: o
 * oposto de {@code BaixaDeEstoqueListenerTest}, no mesmo molde.
 *
 * <p>Publica os eventos diretamente, sem passar por {@code VendaService}, para provar só o que é
 * do estoque: a conta com o controle desligado não devolve nada, a ligada devolve um movimento
 * por item de produto que tinha baixado, a conta que ligou o controle depois da venda não devolve
 * o que não saiu, a reentrega não duplica, e o ouvinte acha o produto da conta do evento numa
 * thread que não tem tenant nenhum. O caminho inteiro, do cancelamento pelo caso de uso ao saldo,
 * está em {@code VendaServiceTest}.
 *
 * <p>Para haver o que devolver, cada cenário publica antes a venda concluída, com a conta no
 * contexto: a baixa roda dentro dessa publicação, na transação de quem publica. Os eventos vão
 * pagos em Pix, para o ouvinte do caixa não ter o que fazer e sair do caminho. O cancelamento é
 * publicado dentro de uma transação, porque o ouvinte dele só é chamado depois de um commit, em
 * outra thread, então cada asserção sobre o estorno espera com Awaitility. Há dois ouvintes do
 * cancelamento, então quem espera pela publicação concluída filtra pelo ouvinte do estoque.
 */
class EstornoDeEstoqueListenerTest extends TesteDeIntegracao {

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
    @DisplayName("com o controle de estoque desligado, o cancelamento não gera movimento nem muda saldo")
    void comEstoqueDesligadoNadaAcontece() {
        ContaCriada conta = criador.criar("Salao Vizinho", SENHA_DE_TESTE);
        UUID sessaoId = abrirCaixa(conta);
        UUID vendaId = vendas.criarAbertaEm(conta.contaId(), sessaoId, conta.usuarioId());
        UUID shampooId = cadastrar(conta, "Shampoo", TipoProduto.PRODUTO);

        VendaCancelada evento = cancelamento(conta, vendaId, sessaoId,
                List.of(new VendaCancelada.Item(shampooId, new BigDecimal("2"))));

        publicar(evento);

        await().atMost(ESPERA).untilAsserted(() ->
                assertThat(publicacoesDoEstoque(evento)).hasSize(1));

        conta.comoUsuario(() -> {
            assertThat(saldoDe(shampooId)).isEqualByComparingTo("0");
            assertThat(produtoService.jaEstornouPorCancelamento(shampooId, vendaId)).isFalse();
        });
    }

    @Test
    @DisplayName("com o controle ligado, cada item que baixou volta; serviço no meio não gera nada")
    void comEstoqueLigadoDevolveUmMovimentoPorItem() {
        ContaCriada conta = criador.criar("Cafeteria Aurora", SENHA_DE_TESTE);
        criador.habilitarEstoque(conta.contaId());
        UUID sessaoId = abrirCaixa(conta);
        UUID vendaId = vendas.criarAbertaEm(conta.contaId(), sessaoId, conta.usuarioId());
        UUID cafeId = cadastrar(conta, "Cafe coado", TipoProduto.PRODUTO);
        UUID queijoId = cadastrar(conta, "Queijo minas", TipoProduto.PRODUTO);
        UUID entregaId = cadastrar(conta, "Entrega", TipoProduto.SERVICO);

        concluir(conta, conclusao(conta, vendaId, sessaoId, List.of(
                new VendaConcluida.Item(cafeId, new BigDecimal("2")),
                new VendaConcluida.Item(entregaId, BigDecimal.ONE),
                new VendaConcluida.Item(queijoId, new BigDecimal("0.750")))));
        await().atMost(ESPERA).untilAsserted(() ->
                conta.comoUsuario(() -> {
                    assertThat(saldoDe(cafeId)).isEqualByComparingTo("-2");
                    assertThat(saldoDe(queijoId)).isEqualByComparingTo("-0.750");
                }));

        publicar(cancelamento(conta, vendaId, sessaoId, List.of(
                new VendaCancelada.Item(cafeId, new BigDecimal("2")),
                new VendaCancelada.Item(entregaId, BigDecimal.ONE),
                new VendaCancelada.Item(queijoId, new BigDecimal("0.750")))));

        await().atMost(ESPERA).untilAsserted(() ->
                conta.comoUsuario(() -> {
                    assertThat(saldoDe(cafeId)).isEqualByComparingTo("0");
                    assertThat(saldoDe(queijoId)).isEqualByComparingTo("0");
                }));

        conta.comoUsuario(() -> {
            assertThat(produtoService.jaEstornouPorCancelamento(cafeId, vendaId)).isTrue();
            assertThat(produtoService.jaEstornouPorCancelamento(queijoId, vendaId)).isTrue();
            assertThat(produtoService.jaDeuBaixaPorVenda(cafeId, vendaId))
                    .as("a baixa fica no histórico; o estorno é outro movimento")
                    .isTrue();
            assertThat(saldoDe(entregaId)).as("serviço não tem estoque").isEqualByComparingTo("0");
            assertThat(produtoService.jaEstornouPorCancelamento(entregaId, vendaId)).isFalse();
        });
    }

    @Test
    @DisplayName("conta que ligou o controle depois da venda não devolve o que não saiu")
    void semBaixaNaoHaEstorno() {
        ContaCriada conta = criador.criar("Barbearia Central", SENHA_DE_TESTE);
        UUID sessaoId = abrirCaixa(conta);
        UUID vendaId = vendas.criarAbertaEm(conta.contaId(), sessaoId, conta.usuarioId());
        UUID pomadaId = cadastrar(conta, "Pomada", TipoProduto.PRODUTO);

        // A venda aconteceu com o controle desligado: nada saiu. O ouvinte da conclusão roda
        // dentro da publicação, então ao voltar dela a decisão de não baixar já foi tomada.
        concluir(conta, conclusao(conta, vendaId, sessaoId,
                List.of(new VendaConcluida.Item(pomadaId, BigDecimal.ONE))));

        criador.habilitarEstoque(conta.contaId());
        VendaCancelada cancelamento = cancelamento(conta, vendaId, sessaoId,
                List.of(new VendaCancelada.Item(pomadaId, BigDecimal.ONE)));
        publicar(cancelamento);
        await().atMost(ESPERA).untilAsserted(() ->
                assertThat(publicacoesDoEstoque(cancelamento))
                        .as("a entrega terminou sem erro: pular não é falhar")
                        .hasSize(1));

        conta.comoUsuario(() -> {
            assertThat(saldoDe(pomadaId))
                    .as("devolver o que nunca saiu inventaria estoque")
                    .isEqualByComparingTo("0");
            assertThat(produtoService.jaDeuBaixaPorVenda(pomadaId, vendaId)).isFalse();
            assertThat(produtoService.jaEstornouPorCancelamento(pomadaId, vendaId)).isFalse();
        });
    }

    @Test
    @DisplayName("o mesmo cancelamento entregue duas vezes devolve uma vez só, e as duas entregas terminam")
    void reentregaNaoDuplicaOEstorno() {
        ContaCriada conta = criador.criar("Mercearia Aurora", SENHA_DE_TESTE);
        criador.habilitarEstoque(conta.contaId());
        UUID sessaoId = abrirCaixa(conta);
        UUID vendaId = vendas.criarAbertaEm(conta.contaId(), sessaoId, conta.usuarioId());
        UUID arrozId = cadastrar(conta, "Arroz", TipoProduto.PRODUTO);

        concluir(conta, conclusao(conta, vendaId, sessaoId,
                List.of(new VendaConcluida.Item(arrozId, new BigDecimal("2")))));
        await().atMost(ESPERA).untilAsserted(() ->
                conta.comoUsuario(() ->
                        assertThat(saldoDe(arrozId)).isEqualByComparingTo("-2")));

        VendaCancelada cancelamento = cancelamento(conta, vendaId, sessaoId,
                List.of(new VendaCancelada.Item(arrozId, new BigDecimal("2"))));
        publicar(cancelamento);
        await().atMost(ESPERA).untilAsserted(() ->
                conta.comoUsuario(() ->
                        assertThat(saldoDe(arrozId)).isEqualByComparingTo("0")));

        // A segunda publicação do mesmo fato é o que uma reentrega do registro faz. Ela também
        // termina sem erro: fica concluída no registro em vez de presa como falha.
        publicar(cancelamento);
        await().atMost(ESPERA).untilAsserted(() ->
                assertThat(publicacoesDoEstoque(cancelamento))
                        .as("as duas entregas terminaram, nenhuma delas com erro")
                        .hasSize(2));

        conta.comoUsuario(() ->
                assertThat(saldoDe(arrozId))
                        .as("devolvido uma vez, não duas")
                        .isEqualByComparingTo("0"));
    }

    @Test
    @DisplayName("o ouvinte devolve na conta do evento, e a conta B não vê o produto nem o movimento (RNF05)")
    void devolveNaContaDoEventoENaoVazaParaOutra() {
        ContaCriada contaA = criador.criar("Loja A", SENHA_DE_TESTE);
        ContaCriada contaB = criador.criar("Loja B", SENHA_DE_TESTE);
        criador.habilitarEstoque(contaA.contaId());
        UUID sessaoDaContaA = abrirCaixa(contaA);
        UUID vendaDaContaA = vendas.criarAbertaEm(contaA.contaId(), sessaoDaContaA,
                contaA.usuarioId());
        UUID escovaDaContaA = cadastrar(contaA, "Escova", TipoProduto.PRODUTO);

        concluir(contaA, conclusao(contaA, vendaDaContaA, sessaoDaContaA,
                List.of(new VendaConcluida.Item(escovaDaContaA, BigDecimal.ONE))));
        await().atMost(ESPERA).untilAsserted(() ->
                contaA.comoUsuario(() ->
                        assertThat(saldoDe(escovaDaContaA)).isEqualByComparingTo("-1")));

        // Publicado como conta B de propósito: o ouvinte roda em outra thread, sem tenant, e tem
        // de usar a conta que está dentro do evento, não a de quem publicou.
        contaB.comoUsuario(() ->
                publicar(cancelamento(contaA, vendaDaContaA, sessaoDaContaA,
                        List.of(new VendaCancelada.Item(escovaDaContaA, BigDecimal.ONE)))));

        await().atMost(ESPERA).untilAsserted(() ->
                contaA.comoUsuario(() ->
                        assertThat(saldoDe(escovaDaContaA)).isEqualByComparingTo("0")));

        contaB.comoUsuario(() -> {
            assertThat(produtos.findById(escovaDaContaA)).isEmpty();
            assertThat(produtoService.jaEstornouPorCancelamento(escovaDaContaA, vendaDaContaA))
                    .isFalse();
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

    /** O saldo como o domínio o vê. */
    private BigDecimal saldoDe(UUID produtoId) {
        return produtos.findById(produtoId).orElseThrow().paraDominio().getEstoqueAtual();
    }

    /** Uma venda paga em Pix, para o ouvinte do caixa não ter o que fazer e sair do caminho. */
    private static VendaConcluida conclusao(ContaCriada conta, UUID vendaId, UUID sessaoId,
            List<VendaConcluida.Item> itens) {
        return new VendaConcluida(conta.contaId(), vendaId, sessaoId, conta.usuarioId(), itens,
                List.of(new VendaConcluida.Parcela(FormaPagamento.PIX, Money.de("10.00"),
                        StatusPagamento.CONFIRMADO)),
                Instant.now());
    }

    /** O cancelamento da mesma venda, também paga em Pix. */
    private static VendaCancelada cancelamento(ContaCriada conta, UUID vendaId, UUID sessaoId,
            List<VendaCancelada.Item> itens) {
        return new VendaCancelada(conta.contaId(), vendaId, sessaoId, conta.usuarioId(), itens,
                List.of(new VendaCancelada.Parcela(FormaPagamento.PIX, Money.de("10.00"),
                        StatusPagamento.CONFIRMADO)));
    }

    /**
     * A conclusão é ouvida dentro da transação de quem publica, então a conta vai no contexto
     * antes de a transação abrir, como numa requisição.
     */
    private void concluir(ContaCriada conta, VendaConcluida evento) {
        conta.comoUsuario(() -> publicar(evento));
    }

    private void publicar(Object evento) {
        transacao.executeWithoutResult(status -> publicador.publishEvent(evento));
    }

    private List<? extends EventPublication> publicacoesDoEstoque(VendaCancelada evento) {
        return publicacoesEntreguesA(evento, "EstornoDeEstoqueListener");
    }

    /**
     * As publicações deste evento entregues ao ouvinte nomeado. O identificador do alvo é a
     * assinatura do método do listener, então o nome da classe basta para separar do caixa.
     */
    private List<? extends EventPublication> publicacoesEntreguesA(Object evento,
            String ouvinte) {
        return publicacoesConcluidas.findAll().stream()
                .filter(publicacao -> publicacao.getEvent().equals(evento))
                .filter(publicacao -> publicacao instanceof TargetEventPublication alvo
                        && alvo.getTargetIdentifier().getValue().contains(ouvinte))
                .toList();
    }
}
