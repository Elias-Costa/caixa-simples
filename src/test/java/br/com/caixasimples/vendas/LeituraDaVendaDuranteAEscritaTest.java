package br.com.caixasimples.vendas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.cadastro.TipoProduto;
import br.com.caixasimples.cadastro.application.ProdutoService;
import br.com.caixasimples.cadastro.application.ProdutoService.DadosDoProduto;
import br.com.caixasimples.cadastro.internal.ClienteService;
import br.com.caixasimples.cadastro.internal.ClienteService.DadosDoCliente;
import br.com.caixasimples.caixa.application.SessaoCaixaService;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.pagamentos.domain.SolicitacaoPagamento;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.TenantContext;
import br.com.caixasimples.shared.UsuarioContext;
import br.com.caixasimples.vendas.application.VendaService;
import br.com.caixasimples.vendas.application.VendaService.DividaParaTela;
import br.com.caixasimples.vendas.application.VendaService.ResumoDaVenda;
import br.com.caixasimples.vendas.application.VendaService.VendaParaTela;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Uma consulta da Venda lida enquanto outra transação altera a mesma Venda.
 *
 * <p>A raiz e cada lista de membros vêm em consultas separadas, e a consulta não trava a linha. Sem
 * uma fotografia única, a linha da Venda vem de antes da escrita e os membros, de depois: o total
 * zero com o item já lançado, ou o fiado já quitado sem o recebimento que o quitou. O dado gravado
 * está certo e a remontagem recusa o que leu, então uma leitura correta vira erro na tela do
 * balcão, como acontece quando o lote do dispositivo monta uma venda logo depois do login.
 *
 * <h2>Como a disputa fica determinística</h2>
 *
 * <p>Uma transação prende a tabela do membro, e a leitura começa numa thread própria: passa pela
 * linha da Venda, que não está presa, e para na consulta dos membros, o que {@code pg_locks}
 * mostra. Só então a escrita roda, nessa mesma transação, que confirma a escrita e solta a tabela
 * no mesmo instante. A trava de linha que os testes de escrita usam não serviria aqui: consulta
 * sem trava não espera por linha presa. Nada depende de pausa nem de sorte com o escalonador.
 */
class LeituraDaVendaDuranteAEscritaTest extends TesteDeIntegracao {

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
    private ClienteService clientes;

    @Autowired
    private TransactionTemplate transacao;

    @Autowired
    private JdbcClient jdbc;

    @AfterEach
    void limparContexto() {
        TenantContext.limpar();
        UsuarioContext.limpar();
    }

    @Test
    @DisplayName("lista das vendas do caixa lida enquanto um item confirma: sai a venda como estava antes dele, e a leitura seguinte já o vê")
    void listaLidaEnquantoUmItemConfirma() {
        ContaCriada conta = criador.criar("Cafeteria Aurora", SENHA_DE_TESTE);
        UUID sessaoId = conta.comoUsuario(() -> caixas.abrir(Money.ZERO));
        UUID cafeId = cadastrar(conta, "Cafe coado", Money.de("4.90"));
        UUID vendaId = conta.comoUsuario(() -> vendas.iniciar(sessaoId));

        CompletableFuture<List<ResumoDaVenda>> lista = lerEnquantoConfirma(conta, "item_venda",
                () -> vendas.vendasDaSessao(sessaoId),
                () -> vendas.adicionarItem(vendaId, cafeId, BigDecimal.ONE, Money.ZERO));

        assertThat(lista).succeedsWithin(ESPERA).satisfies(resumos ->
                assertThat(resumos).singleElement().satisfies(resumo ->
                        assertThat(resumo.total()).isEqualTo(Money.ZERO)));
        conta.comoUsuario(() -> assertThat(vendas.vendasDaSessao(sessaoId)).singleElement()
                .satisfies(resumo -> assertThat(resumo.total()).isEqualTo(Money.de("4.90"))));
    }

    @Test
    @DisplayName("detalhe da venda lido enquanto o item do aparelho confirma: sai a venda sem o item e com o total de antes, e a leitura seguinte o vê")
    void detalheLidoEnquantoOItemDoAparelhoConfirma() {
        ContaCriada conta = criador.criar("Padaria Central", SENHA_DE_TESTE);
        UUID sessaoId = conta.comoUsuario(() -> caixas.abrir(Money.ZERO));
        UUID paoId = cadastrar(conta, "Pao de queijo", Money.de("4.90"));
        UUID vendaId = conta.comoUsuario(() -> vendas.iniciar(sessaoId));

        CompletableFuture<VendaParaTela> detalhe = lerEnquantoConfirma(conta, "item_venda",
                () -> vendas.consultar(vendaId),
                () -> vendas.adicionarItemComPrecoVisto(vendaId, UUID.randomUUID(), paoId,
                        BigDecimal.ONE, Money.de("4.90"), Money.ZERO, Instant.now()));

        assertThat(detalhe).succeedsWithin(ESPERA).satisfies(venda -> {
            assertThat(venda.total()).isEqualTo(Money.ZERO);
            assertThat(venda.itens()).isEmpty();
        });
        conta.comoUsuario(() -> {
            VendaParaTela venda = vendas.consultar(vendaId);
            assertThat(venda.total()).isEqualTo(Money.de("4.90"));
            assertThat(venda.itens()).hasSize(1);
        });
    }

    @Test
    @DisplayName("dívidas lidas enquanto o recebimento que quita confirma: sai a dívida como estava, e a leitura seguinte já não a traz")
    void dividasLidasEnquantoORecebimentoQuita() {
        ContaCriada conta = criador.criar("Mercearia da Esquina", SENHA_DE_TESTE);
        UUID sessaoId = conta.comoUsuario(() -> caixas.abrir(Money.ZERO));
        UUID arrozId = cadastrar(conta, "Arroz", Money.de("20.00"));
        UUID clienteId = conta.comoUsuario(() ->
                clientes.cadastrar(new DadosDoCliente("Lia", null)));
        UUID vendaId = conta.comoUsuario(() -> {
            UUID id = vendas.iniciar(sessaoId);
            vendas.adicionarItem(id, arrozId, BigDecimal.ONE, Money.ZERO);
            vendas.vincularCliente(id, clienteId);
            vendas.registrarPagamento(id,
                    SolicitacaoPagamento.de(FormaPagamento.FIADO, Money.de("20.00")));
            vendas.concluir(id);
            return id;
        });

        // Os recebimentos são lidos antes das parcelas: prender as parcelas é o que mistura os dois.
        CompletableFuture<List<DividaParaTela>> dividas = lerEnquantoConfirma(conta, "pagamento",
                () -> vendas.dividasEmAberto(),
                () -> vendas.receber(vendaId, Money.de("20.00"), FormaPagamento.DINHEIRO));

        assertThat(dividas).succeedsWithin(ESPERA).satisfies(lista ->
                assertThat(lista).singleElement().satisfies(divida ->
                        assertThat(divida.saldoDevedor()).isEqualTo(Money.de("20.00"))));
        conta.comoUsuario(() -> assertThat(vendas.dividasEmAberto()).isEmpty());
    }

    /**
     * As escritas dependem de {@code READ COMMITTED}: com a trava, a segunda lê o que a primeira
     * confirmou. O isolamento da consulta que ficasse na conexão faria a escrita seguinte receber
     * erro de serialização. A mesma thread tende a receber a mesma conexão de volta do pool.
     */
    @Test
    @DisplayName("depois de uma consulta, a conexão volta ao isolamento de que as escritas dependem")
    void consultaNaoDeixaOIsolamentoNaConexao() {
        ContaCriada conta = criador.criar("Quitanda Aurora", SENHA_DE_TESTE);
        UUID sessaoId = conta.comoUsuario(() -> caixas.abrir(Money.ZERO));

        conta.comoUsuario(() -> {
            vendas.vendasDaSessao(sessaoId);

            String isolamento = transacao.execute(status ->
                    jdbc.sql("show transaction_isolation").query(String.class).single());
            assertThat(isolamento).isEqualTo("read committed");
        });
    }

    /**
     * Prende a tabela, começa a leitura e, quando ela está parada na tabela presa, roda a escrita na
     * mesma transação, que confirma a escrita e solta a tabela de uma vez.
     *
     * @return a leitura, que termina depois que a transação confirma
     */
    private <T> CompletableFuture<T> lerEnquantoConfirma(ContaCriada conta, String tabelaDoMembro,
            Supplier<T> leitura, Runnable escrita) {
        ExecutorService thread = Executors.newSingleThreadExecutor();
        try {
            return conta.comoUsuario(() -> transacao.execute(status -> {
                jdbc.sql("lock table " + tabelaDoMembro + " in access exclusive mode").update();
                CompletableFuture<T> lida = CompletableFuture.supplyAsync(
                        () -> conta.comoUsuario(leitura), thread);
                await().atMost(ESPERA).until(() -> quantasEsperam() == 1 || lida.isDone());
                assertThat(lida).as("a leitura tem de estar parada na tabela do membro").isNotDone();
                escrita.run();
                return lida;
            }));
        } finally {
            thread.shutdown();
        }
    }

    /** Quantas conexões estão paradas esperando uma trava. */
    private long quantasEsperam() {
        return jdbc.sql("select count(*) from pg_locks where not granted")
                .query(Long.class)
                .single();
    }

    private UUID cadastrar(ContaCriada conta, String nome, Money preco) {
        return conta.comoUsuario(() ->
                produtos.cadastrar(TipoProduto.PRODUTO,
                        new DadosDoProduto(nome, preco, null, null, "un", null)));
    }
}
