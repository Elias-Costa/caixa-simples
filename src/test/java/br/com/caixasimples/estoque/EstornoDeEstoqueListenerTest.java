package br.com.caixasimples.estoque;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

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
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * O estoque reagindo à venda cancelada, dentro da transação de quem cancela: o oposto de
 * {@code BaixaDeEstoqueListenerTest}, no mesmo molde.
 *
 * <p>Publica os eventos diretamente, sem passar por {@code VendaService}, para provar só o que é
 * do estoque: a conta com o controle desligado não devolve nada, a ligada devolve um movimento
 * por item de produto que tinha baixado, a conta que ligou o controle depois da venda não devolve
 * o que não saiu, o mesmo cancelamento não devolve duas vezes, e o evento de outra conta é
 * recusado. O caminho inteiro, do cancelamento pelo caso de uso ao saldo, está em
 * {@code VendaServiceTest}; a disputa com outra transação pelo mesmo produto, em
 * {@code CancelamentoERecebimentoSobConcorrenciaTest}.
 *
 * <p>Para haver o que devolver, cada cenário publica antes a venda concluída. Os eventos vão pagos
 * em Pix, para o ouvinte do caixa não ter o que fazer e sair do caminho, e são publicados com a
 * conta no contexto, como numa requisição: a devolução acontece antes de a transação de quem
 * publica terminar, então cada asserção lê logo em seguida.
 */
class EstornoDeEstoqueListenerTest extends TesteDeIntegracao {

    private static final String SENHA_DE_TESTE = "uma senha longa de teste";

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

        publicar(conta, cancelamento(conta, vendaId, sessaoId,
                List.of(new VendaCancelada.Item(shampooId, new BigDecimal("2")))));

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

        publicar(conta, conclusao(conta, vendaId, sessaoId, List.of(
                new VendaConcluida.Item(cafeId, new BigDecimal("2")),
                new VendaConcluida.Item(entregaId, BigDecimal.ONE),
                new VendaConcluida.Item(queijoId, new BigDecimal("0.750")))));
        conta.comoUsuario(() -> {
            assertThat(saldoDe(cafeId)).isEqualByComparingTo("-2");
            assertThat(saldoDe(queijoId)).isEqualByComparingTo("-0.750");
        });

        publicar(conta, cancelamento(conta, vendaId, sessaoId, List.of(
                new VendaCancelada.Item(cafeId, new BigDecimal("2")),
                new VendaCancelada.Item(entregaId, BigDecimal.ONE),
                new VendaCancelada.Item(queijoId, new BigDecimal("0.750")))));

        conta.comoUsuario(() -> {
            assertThat(saldoDe(cafeId)).isEqualByComparingTo("0");
            assertThat(saldoDe(queijoId)).isEqualByComparingTo("0");
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

        // A venda aconteceu com o controle desligado: nada saiu.
        publicar(conta, conclusao(conta, vendaId, sessaoId,
                List.of(new VendaConcluida.Item(pomadaId, BigDecimal.ONE))));

        criador.habilitarEstoque(conta.contaId());
        publicar(conta, cancelamento(conta, vendaId, sessaoId,
                List.of(new VendaCancelada.Item(pomadaId, BigDecimal.ONE))));

        conta.comoUsuario(() -> {
            assertThat(saldoDe(pomadaId))
                    .as("devolver o que nunca saiu inventaria estoque")
                    .isEqualByComparingTo("0");
            assertThat(produtoService.jaDeuBaixaPorVenda(pomadaId, vendaId)).isFalse();
            assertThat(produtoService.jaEstornouPorCancelamento(pomadaId, vendaId)).isFalse();
        });
    }

    @Test
    @DisplayName("o mesmo cancelamento publicado de novo é recusado pelo cadastro, e o estoque volta uma vez")
    void mesmoCancelamentoNaoDevolveDuasVezes() {
        ContaCriada conta = criador.criar("Mercearia Aurora", SENHA_DE_TESTE);
        criador.habilitarEstoque(conta.contaId());
        UUID sessaoId = abrirCaixa(conta);
        UUID vendaId = vendas.criarAbertaEm(conta.contaId(), sessaoId, conta.usuarioId());
        UUID arrozId = cadastrar(conta, "Arroz", TipoProduto.PRODUTO);

        publicar(conta, conclusao(conta, vendaId, sessaoId,
                List.of(new VendaConcluida.Item(arrozId, new BigDecimal("2")))));
        VendaCancelada evento = cancelamento(conta, vendaId, sessaoId,
                List.of(new VendaCancelada.Item(arrozId, new BigDecimal("2"))));
        publicar(conta, evento);

        // Sem registro de publicação não há reentrega; publicar de novo é defeito de quem
        // publica, e o cadastro recusa alto, desfazendo a transação que tentou.
        assertThatIllegalStateException()
                .isThrownBy(() -> publicar(conta, evento))
                .withMessageContaining("nao estorna de novo");

        conta.comoUsuario(() ->
                assertThat(saldoDe(arrozId))
                        .as("devolvido uma vez, não duas")
                        .isEqualByComparingTo("0"));
    }

    @Test
    @DisplayName("o evento de outra conta é recusado, e o produto dele não se move (RNF05)")
    void eventoDeOutraContaNaoDevolve() {
        ContaCriada contaA = criador.criar("Loja A", SENHA_DE_TESTE);
        ContaCriada contaB = criador.criar("Loja B", SENHA_DE_TESTE);
        criador.habilitarEstoque(contaA.contaId());
        criador.habilitarEstoque(contaB.contaId());
        UUID sessaoDaContaA = abrirCaixa(contaA);
        UUID vendaDaContaA = vendas.criarAbertaEm(contaA.contaId(), sessaoDaContaA,
                contaA.usuarioId());
        UUID escovaDaContaA = cadastrar(contaA, "Escova", TipoProduto.PRODUTO);
        publicar(contaA, conclusao(contaA, vendaDaContaA, sessaoDaContaA,
                List.of(new VendaConcluida.Item(escovaDaContaA, BigDecimal.ONE))));

        // A transação de quem publica está na conta B: o ouvinte não troca de conta no meio
        // dela, recusa.
        assertThatIllegalStateException()
                .isThrownBy(() -> publicar(contaB, cancelamento(contaA, vendaDaContaA,
                        sessaoDaContaA,
                        List.of(new VendaCancelada.Item(escovaDaContaA, BigDecimal.ONE)))))
                .withMessageContaining("outra conta");

        contaA.comoUsuario(() -> {
            assertThat(saldoDe(escovaDaContaA)).isEqualByComparingTo("-1");
            assertThat(produtoService.jaEstornouPorCancelamento(escovaDaContaA, vendaDaContaA))
                    .isFalse();
        });
        contaB.comoUsuario(() -> assertThat(produtos.findById(escovaDaContaA)).isEmpty());
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

    /** Como numa requisição: a conta no contexto antes de a transação abrir. */
    private void publicar(ContaCriada conta, Object evento) {
        conta.comoUsuario(() ->
                transacao.executeWithoutResult(status -> publicador.publishEvent(evento)));
    }
}
