package br.com.caixasimples.vendas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.cadastro.TipoProduto;
import br.com.caixasimples.cadastro.application.ProdutoService;
import br.com.caixasimples.cadastro.application.ProdutoService.DadosDoProduto;
import br.com.caixasimples.cadastro.internal.ClienteService;
import br.com.caixasimples.cadastro.internal.ClienteService.DadosDoCliente;
import br.com.caixasimples.cadastro.internal.ProdutoRepository;
import br.com.caixasimples.caixa.StatusSessaoCaixa;
import br.com.caixasimples.caixa.TipoMovimentoCaixa;
import br.com.caixasimples.caixa.application.SessaoCaixaService;
import br.com.caixasimples.caixa.application.SessaoCaixaService.ResumoDeSessao;
import br.com.caixasimples.caixa.domain.MovimentoCaixa;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.pagamentos.domain.SolicitacaoPagamento;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.TenantContext;
import br.com.caixasimples.vendas.application.VendaService;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.CompletedEventPublications;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.core.EventPublicationRegistry;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * O cancelamento e o recebimento de fiado quando outra transação altera a mesma raiz ao mesmo
 * tempo: uma sangria ou o fechamento na SessaoCaixa, outra venda do mesmo Produto, um suprimento.
 *
 * <p>SessaoCaixa e Produto têm versão, e quem grava sobre uma versão que outra transação já mudou
 * é recusado. Os ouvintes do cancelamento e do recebimento rodam na transação de quem publica,
 * então a recusa derruba a operação inteira: nada dela fica, quem operou recebe o conflito e
 * repete. Se os ouvintes rodassem depois do commit, a mesma recusa derrubaria só o efeito, com a
 * venda já cancelada ou a dívida já quitada, e a publicação ficaria incompleta sem ninguém a
 * reprocessar.
 *
 * <h2>Como o conflito fica determinístico</h2>
 *
 * <p>A alteração concorrente é enviada ao banco numa transação que fica aberta, com a linha da
 * raiz presa. A operação sob teste começa em outra thread, e a transação só confirma quando alguma
 * conexão está parada esperando aquela linha. Quem espera já leu a raiz antes da confirmação, pela
 * versão sem a alteração, e grava sobre uma versão que deixa de existir. Nada depende de pausa
 * nem de sorte com o escalonador.
 */
class CancelamentoERecebimentoSobConcorrenciaTest extends TesteDeIntegracao {

    private static final String SENHA_DE_TESTE = "uma senha longa de teste";
    private static final Duration ESPERA = Duration.ofSeconds(10);

    @Autowired
    private CriadorDeContaDeTeste criador;

    @Autowired
    private VendaService vendas;

    @Autowired
    private SessaoCaixaService caixas;

    @Autowired
    private ProdutoService produtos;

    @Autowired
    private ProdutoRepository linhasDeProduto;

    @Autowired
    private ClienteService clientes;

    @Autowired
    private TransactionTemplate transacao;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private EventPublicationRegistry registroDePublicacoes;

    @Autowired
    private CompletedEventPublications publicacoesConcluidas;

    @AfterEach
    void limparContexto() {
        TenantContext.limpar();
    }

    @Test
    @DisplayName("sangria gravada enquanto o cancelamento estorna: o cancelamento falha inteiro, e repetido estorna sobre a sangria")
    void sangriaConcorrenteRecusaOCancelamentoInteiro() {
        ContaCriada conta = criador.criar("Cafeteria Aurora", SENHA_DE_TESTE);
        UUID sessaoId = conta.comoUsuario(() -> caixas.abrir(Money.de("50.00")));
        UUID cafeId = cadastrar(conta, "Cafe coado", Money.de("20.00"));
        UUID vendaId = vendaConcluida(conta, sessaoId, cafeId,
                SolicitacaoPagamento.emDinheiro(Money.de("20.00"), Money.de("20.00")));

        CompletableFuture<Void> cancelamento = segurandoARaiz(conta,
                () -> caixas.registrarSangria(sessaoId, Money.de("10.00"), "deposito no banco"),
                () -> conta.comoUsuario(() -> vendas.cancelar(vendaId)));

        assertThatThrownBy(cancelamento::join)
                .as("o cancelamento leu a sessão sem a sangria e foi recusado pela versão")
                .hasCauseInstanceOf(OptimisticLockingFailureException.class);
        conta.comoUsuario(() -> {
            assertThat(vendas.consultar(vendaId).status()).isEqualTo(StatusVenda.CONCLUIDA);
            assertThat(tiposNoExtrato(sessaoId))
                    .containsExactly(TipoMovimentoCaixa.VENDA, TipoMovimentoCaixa.SANGRIA);
            // 50,00 de abertura, 20,00 da venda e 10,00 de sangria: a venda continua valendo.
            assertThat(esperadoDe(sessaoId)).isEqualTo(Money.de("60.00"));
        });
        semPublicacaoDa(vendaId);

        conta.comoUsuario(() -> vendas.cancelar(vendaId));

        conta.comoUsuario(() -> {
            assertThat(vendas.consultar(vendaId).status()).isEqualTo(StatusVenda.CANCELADA);
            assertThat(tiposNoExtrato(sessaoId)).containsExactly(TipoMovimentoCaixa.VENDA,
                    TipoMovimentoCaixa.SANGRIA, TipoMovimentoCaixa.ESTORNO);
            assertThat(esperadoDe(sessaoId)).isEqualTo(Money.de("40.00"));
        });
    }

    @Test
    @DisplayName("fechamento gravado enquanto o cancelamento estorna: o cancelamento falha inteiro, e repetido esbarra no caixa fechado")
    void fechamentoConcorrenteRecusaOCancelamentoInteiro() {
        ContaCriada conta = criador.criar("Empório do Bairro", SENHA_DE_TESTE);
        UUID sessaoId = conta.comoUsuario(() -> caixas.abrir(Money.de("50.00")));
        UUID cafeId = cadastrar(conta, "Cafe coado", Money.de("20.00"));
        UUID vendaId = vendaConcluida(conta, sessaoId, cafeId,
                SolicitacaoPagamento.emDinheiro(Money.de("20.00"), Money.de("20.00")));

        CompletableFuture<Void> cancelamento = segurandoARaiz(conta,
                () -> caixas.fechar(sessaoId, Money.de("70.00")),
                () -> conta.comoUsuario(() -> vendas.cancelar(vendaId)));

        assertThatThrownBy(cancelamento::join)
                .as("o cancelamento leu a sessão aberta e foi recusado pela versão do fechamento")
                .hasCauseInstanceOf(OptimisticLockingFailureException.class);
        conta.comoUsuario(() -> {
            assertThat(vendas.consultar(vendaId).status()).isEqualTo(StatusVenda.CONCLUIDA);
            ResumoDeSessao sessao = caixas.consultar(sessaoId);
            assertThat(sessao.status()).isEqualTo(StatusSessaoCaixa.FECHADA);
            // A conferência contou os 20,00 de uma venda que continua concluída: o caixa e a
            // venda dizem a mesma coisa.
            assertThat(sessao.diferenca()).isEqualTo(Money.ZERO);
            assertThat(tiposNoExtrato(sessaoId)).containsExactly(TipoMovimentoCaixa.VENDA);
        });
        semPublicacaoDa(vendaId);

        // Repetir esbarra na regra de só cancelar com o caixa aberto, e a recusa é visível a
        // quem opera, em vez de um estorno perdido depois do commit.
        assertThatIllegalStateException()
                .isThrownBy(() -> conta.comoUsuario(() -> vendas.cancelar(vendaId)))
                .withMessageContaining("nao esta ABERTA");
    }

    @Test
    @DisplayName("outra venda do mesmo produto concluída enquanto o cancelamento devolve: o cancelamento falha inteiro, e repetido devolve o produto")
    void outraVendaConcorrenteRecusaOCancelamentoInteiro() {
        ContaCriada conta = criador.criar("Mercearia da Esquina", SENHA_DE_TESTE);
        criador.habilitarEstoque(conta.contaId());
        UUID sessaoId = conta.comoUsuario(() -> caixas.abrir(Money.ZERO));
        UUID arrozId = cadastrar(conta, "Arroz", Money.de("20.00"));
        UUID vendaId = vendaConcluida(conta, sessaoId, arrozId,
                SolicitacaoPagamento.de(FormaPagamento.CARTAO, Money.de("20.00")));
        UUID outraVendaId = vendaPaga(conta, sessaoId, arrozId,
                SolicitacaoPagamento.de(FormaPagamento.CARTAO, Money.de("20.00")));

        CompletableFuture<Void> cancelamento = segurandoARaiz(conta,
                () -> vendas.concluir(outraVendaId),
                () -> conta.comoUsuario(() -> vendas.cancelar(vendaId)));

        assertThatThrownBy(cancelamento::join)
                .as("o cancelamento leu o produto sem a outra baixa e foi recusado pela versão")
                .hasCauseInstanceOf(OptimisticLockingFailureException.class);
        conta.comoUsuario(() -> {
            assertThat(vendas.consultar(vendaId).status()).isEqualTo(StatusVenda.CONCLUIDA);
            assertThat(produtos.jaEstornouPorCancelamento(arrozId, vendaId)).isFalse();
            // As duas vendas baixaram um arroz cada, e as duas continuam valendo.
            assertThat(saldoDe(arrozId)).isEqualByComparingTo("-2");
        });
        semPublicacaoDa(vendaId);

        conta.comoUsuario(() -> vendas.cancelar(vendaId));

        conta.comoUsuario(() -> {
            assertThat(vendas.consultar(vendaId).status()).isEqualTo(StatusVenda.CANCELADA);
            assertThat(produtos.jaEstornouPorCancelamento(arrozId, vendaId)).isTrue();
            assertThat(saldoDe(arrozId)).isEqualByComparingTo("-1");
        });
    }

    @Test
    @DisplayName("suprimento gravado enquanto o recebimento de fiado entra na gaveta: o recebimento falha inteiro, e repetido entra sobre o suprimento")
    void suprimentoConcorrenteRecusaORecebimentoInteiro() {
        ContaCriada conta = criador.criar("Padaria Central", SENHA_DE_TESTE);
        UUID sessaoId = conta.comoUsuario(() -> caixas.abrir(Money.ZERO));
        UUID paoId = cadastrar(conta, "Pao de forma", Money.de("20.00"));
        UUID clienteId = conta.comoUsuario(() ->
                clientes.cadastrar(new DadosDoCliente("Lia", null)));
        UUID vendaId = vendaFiada(conta, sessaoId, clienteId, paoId);

        CompletableFuture<Void> recebimento = segurandoARaiz(conta,
                () -> caixas.registrarSuprimento(sessaoId, Money.de("30.00"), "troco do dia"),
                () -> conta.comoUsuario(() ->
                        vendas.receber(vendaId, Money.de("20.00"), FormaPagamento.DINHEIRO)));

        assertThatThrownBy(recebimento::join)
                .as("o recebimento leu a sessão sem o suprimento e foi recusado pela versão")
                .hasCauseInstanceOf(OptimisticLockingFailureException.class);
        conta.comoUsuario(() -> {
            assertThat(vendas.saldoDevedorDoCliente(clienteId))
                    .as("a dívida continua inteira")
                    .isEqualTo(Money.de("20.00"));
            assertThat(tiposNoExtrato(sessaoId)).containsExactly(TipoMovimentoCaixa.SUPRIMENTO);
            assertThat(esperadoDe(sessaoId)).isEqualTo(Money.de("30.00"));
        });
        semPublicacaoDa(vendaId);

        conta.comoUsuario(() ->
                vendas.receber(vendaId, Money.de("20.00"), FormaPagamento.DINHEIRO));

        conta.comoUsuario(() -> {
            assertThat(vendas.saldoDevedorDoCliente(clienteId)).isEqualTo(Money.ZERO);
            assertThat(tiposNoExtrato(sessaoId)).containsExactly(TipoMovimentoCaixa.SUPRIMENTO,
                    TipoMovimentoCaixa.RECEBIMENTO);
            assertThat(esperadoDe(sessaoId)).isEqualTo(Money.de("50.00"));
        });
    }

    /**
     * Roda a {@code alteracao} numa transação que envia a escrita ao banco e fica aberta, com a
     * linha da raiz presa, enquanto a {@code operacao} começa em outra thread. Só confirma quando
     * alguma conexão está parada esperando uma trava desta transação, ou quando a operação já
     * terminou sem precisar dela; no segundo caso, as asserções de quem chamou dizem o porquê.
     *
     * @return a operação, que só termina depois desta confirmação quando esperava pela linha
     */
    private CompletableFuture<Void> segurandoARaiz(ContaCriada quemAltera, Runnable alteracao,
            Runnable operacao) {
        return quemAltera.comoUsuario(() -> transacao.execute(status -> {
            alteracao.run();
            // O UPDATE vai ao banco agora, e a linha fica presa até esta transação terminar.
            status.flush();
            int estaConexao = jdbc.sql("select pg_backend_pid()").query(Integer.class).single();

            CompletableFuture<Void> emAndamento = CompletableFuture.runAsync(operacao);

            await().atMost(ESPERA)
                    .until(() -> quantasEsperamPor(estaConexao) > 0 || emAndamento.isDone());
            return emAndamento;
        }));
    }

    /**
     * Quantas conexões estão paradas esperando uma trava que a conexão indicada segura. Lê
     * {@code pg_locks}, que o PostgreSQL monta na hora da consulta, e não
     * {@code pg_stat_activity}, que ele congela no primeiro acesso de cada transação.
     */
    private long quantasEsperamPor(int conexao) {
        return jdbc.sql("select count(*) from pg_locks"
                        + " where not granted and :conexao = any(pg_blocking_pids(pid))")
                .param("conexao", conexao)
                .query(Long.class)
                .single();
    }

    /**
     * Nenhuma publicação, concluída ou não, fala desta venda: o cancelamento e o recebimento não
     * passam pelo registro de publicação, e não há entrega para ficar incompleta.
     */
    private void semPublicacaoDa(UUID vendaId) {
        Predicate<Object> daVenda = evento ->
                evento instanceof VendaCancelada cancelada && cancelada.vendaId().equals(vendaId)
                        || evento instanceof FiadoRecebido recebido
                                && recebido.vendaId().equals(vendaId);
        assertThat(registroDePublicacoes.findIncompletePublications())
                .extracting(EventPublication::getEvent)
                .noneMatch(daVenda);
        assertThat(publicacoesConcluidas.findAll())
                .extracting(EventPublication::getEvent)
                .noneMatch(daVenda);
    }

    private List<TipoMovimentoCaixa> tiposNoExtrato(UUID sessaoId) {
        return caixas.consultarExtrato(sessaoId).movimentos().stream()
                .map(MovimentoCaixa::tipo)
                .toList();
    }

    private Money esperadoDe(UUID sessaoId) {
        return caixas.consultar(sessaoId).valorFechamentoEsperado();
    }

    private UUID cadastrar(ContaCriada conta, String nome, Money preco) {
        return conta.comoUsuario(() ->
                produtos.cadastrar(TipoProduto.PRODUTO,
                        new DadosDoProduto(nome, preco, null, null, "un", null)));
    }

    /** Uma venda de uma unidade, paga e ainda ABERTA: falta só concluir. */
    private UUID vendaPaga(ContaCriada conta, UUID sessaoId, UUID produtoId,
            SolicitacaoPagamento pagamento) {
        return conta.comoUsuario(() -> {
            UUID vendaId = vendas.iniciar(sessaoId);
            vendas.adicionarItem(vendaId, produtoId, BigDecimal.ONE, Money.ZERO);
            vendas.registrarPagamento(vendaId, pagamento);
            return vendaId;
        });
    }

    private UUID vendaConcluida(ContaCriada conta, UUID sessaoId, UUID produtoId,
            SolicitacaoPagamento pagamento) {
        UUID vendaId = vendaPaga(conta, sessaoId, produtoId, pagamento);
        conta.comoUsuario(() -> vendas.concluir(vendaId));
        return vendaId;
    }

    /** Uma unidade a 20,00, vendida fiado a um Cliente: a dívida inteira fica em aberto. */
    private UUID vendaFiada(ContaCriada conta, UUID sessaoId, UUID clienteId, UUID produtoId) {
        return conta.comoUsuario(() -> {
            UUID vendaId = vendas.iniciar(sessaoId);
            vendas.adicionarItem(vendaId, produtoId, BigDecimal.ONE, Money.ZERO);
            vendas.vincularCliente(vendaId, clienteId);
            vendas.registrarPagamento(vendaId,
                    SolicitacaoPagamento.de(FormaPagamento.FIADO, Money.de("20.00")));
            vendas.concluir(vendaId);
            return vendaId;
        });
    }

    /** O saldo como o domínio o vê. */
    private BigDecimal saldoDe(UUID produtoId) {
        return linhasDeProduto.findById(produtoId).orElseThrow().paraDominio().getEstoqueAtual();
    }
}
