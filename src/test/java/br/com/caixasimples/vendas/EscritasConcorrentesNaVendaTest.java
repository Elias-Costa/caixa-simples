package br.com.caixasimples.vendas;

import static br.com.caixasimples.sincronizacao.GestoDeTeste.conclusao;
import static br.com.caixasimples.sincronizacao.GestoDeTeste.item;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.cadastro.TipoProduto;
import br.com.caixasimples.cadastro.application.ProdutoService;
import br.com.caixasimples.cadastro.application.ProdutoService.DadosDoProduto;
import br.com.caixasimples.cadastro.internal.ClienteService;
import br.com.caixasimples.cadastro.internal.ClienteService.DadosDoCliente;
import br.com.caixasimples.cadastro.internal.ProdutoRepository;
import br.com.caixasimples.caixa.TipoMovimentoCaixa;
import br.com.caixasimples.caixa.application.SessaoCaixaService;
import br.com.caixasimples.caixa.domain.MovimentoCaixa;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.pagamentos.domain.SolicitacaoPagamento;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.TenantContext;
import br.com.caixasimples.shared.UsuarioContext;
import br.com.caixasimples.sincronizacao.GestoDeTeste;
import br.com.caixasimples.sincronizacao.application.ResultadoDaOperacao;
import br.com.caixasimples.sincronizacao.application.ResultadoDaOperacao.Resultado;
import br.com.caixasimples.sincronizacao.application.SincronizacaoService;
import br.com.caixasimples.vendas.application.VendaNaoEncontradaException;
import br.com.caixasimples.vendas.application.VendaService;
import br.com.caixasimples.vendas.application.VendaService.ItemParaTela;
import br.com.caixasimples.vendas.application.VendaService.VendaParaTela;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Escritas que alteram a mesma Venda ao mesmo tempo, com rede ou pelo lote do dispositivo.
 *
 * <p>A linha da Venda não tem versão, e quem grava copia o estado inteiro da raiz: status, totais
 * e membros. Duas escritas que leram a mesma comanda gravariam uma por cima da outra, e a que
 * confirmasse por último apagaria o efeito da outra: o total de um item só com os dois itens
 * gravados, ou a comanda de volta a ABERTA depois de concluída, com o dinheiro já na gaveta e a
 * baixa já feita. Toda escrita lê a raiz com a trava da linha, então a segunda espera a primeira
 * confirmar e decide sobre o que ela deixou.
 *
 * <h2>Como a disputa fica determinística</h2>
 *
 * <p>Uma transação prende a linha da Venda sem alterá-la, e as operações começam em threads
 * próprias, uma depois da outra: cada uma só começa quando as anteriores estão paradas numa trava
 * ou já terminaram. A transação confirma quando todas estão assim, e as paradas seguem na ordem em
 * que pararam. A trava é {@code for update}, mais forte que a do serviço, porque barra também a
 * conferência de chave estrangeira de quem insere item ou parcela: sem a trava na leitura, toda
 * escrita leria a comanda antes de qualquer outra gravar; com ela, cada uma para na própria
 * leitura. Nada depende de pausa nem de sorte com o escalonador.
 *
 * <p>A mesma técnica prende um Produto para provar a ordem das travas. A conclusão e o
 * cancelamento gravam os produtos em ordem de id, então a segunda transação para atrás da
 * primeira no primeiro produto em comum e recebe o conflito de versão, em vez de cada uma segurar
 * um produto e esperar pelo outro até o banco derrubar uma delas. O produto preso é o de maior
 * id, para que a segunda espere pela primeira, e não pela barreira: cada uma já conferiu a chave
 * do produto ao inserir o movimento, e duas transações paradas na mesma barreira seriam soltas
 * juntas quando ela confirma, sem fila, e a primeira a gravar seria sorte do escalonador.
 */
class EscritasConcorrentesNaVendaTest extends TesteDeIntegracao {

    private static final String SENHA_DE_TESTE = "uma senha longa de teste";
    private static final Duration ESPERA = Duration.ofSeconds(10);

    private static final String PRENDE_A_VENDA =
            "select id from venda where id = :id and conta_id = :conta for update";

    /** A trava que a gravação de um Produto disputa, sem barrar quem só confere a chave dele. */
    private static final String PRENDE_O_PRODUTO =
            "select id from produto where id = :id and conta_id = :conta for no key update";

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
    private SincronizacaoService sincronizacao;

    @Autowired
    private ObjectMapper json;

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
    @DisplayName("dois itens lançados ao mesmo tempo: os dois valem, e o total soma os dois")
    void duasAdicoesSimultaneasValemAsDuas() {
        ContaCriada conta = criador.criar("Cafeteria Aurora", SENHA_DE_TESTE);
        UUID sessaoId = conta.comoUsuario(() -> caixas.abrir(Money.ZERO));
        UUID cafeId = cadastrar(conta, "Cafe coado", Money.de("8.00"));
        UUID paoId = cadastrar(conta, "Pao de queijo", Money.de("5.00"));
        UUID vendaId = conta.comoUsuario(() -> vendas.iniciar(sessaoId));

        List<CompletableFuture<UUID>> disputa = comAVendaPresa(conta, vendaId,
                () -> vendas.adicionarItem(vendaId, cafeId, BigDecimal.ONE, Money.ZERO),
                () -> vendas.adicionarItem(vendaId, paoId, BigDecimal.ONE, Money.ZERO));

        aguardar(disputa);
        conta.comoUsuario(() -> {
            VendaParaTela venda = vendas.consultar(vendaId);
            assertThat(venda.itens()).extracting(ItemParaTela::produtoId)
                    .containsExactly(cafeId, paoId);
            assertThat(venda.total()).isEqualTo(Money.de("13.00"));
        });
        assertThat(disputa).allSatisfy(adicao -> assertThat(adicao).succeedsWithin(ESPERA));
    }

    @Test
    @DisplayName("duas parcelas do total lançadas ao mesmo tempo: uma vale, e a outra é recusada por passar do que falta pagar")
    void duasParcelasSimultaneasNaoPassamDoTotal() {
        ContaCriada conta = criador.criar("Padaria Central", SENHA_DE_TESTE);
        UUID sessaoId = conta.comoUsuario(() -> caixas.abrir(Money.ZERO));
        UUID cafeId = cadastrar(conta, "Cafe coado", Money.de("8.00"));
        UUID vendaId = conta.comoUsuario(() -> {
            UUID id = vendas.iniciar(sessaoId);
            vendas.adicionarItem(id, cafeId, BigDecimal.ONE, Money.ZERO);
            return id;
        });

        List<CompletableFuture<Money>> disputa = comAVendaPresa(conta, vendaId,
                () -> vendas.registrarPagamento(vendaId, UUID.randomUUID(),
                        SolicitacaoPagamento.de(FormaPagamento.CARTAO, Money.de("8.00")),
                        Instant.now(), null),
                () -> vendas.registrarPagamento(vendaId, UUID.randomUUID(),
                        SolicitacaoPagamento.de(FormaPagamento.CARTAO, Money.de("8.00")),
                        Instant.now(), null));

        aguardar(disputa);
        conta.comoUsuario(() -> {
            VendaParaTela venda = vendas.consultar(vendaId);
            assertThat(venda.parcelas()).hasSize(1);
            assertThat(venda.pago()).isEqualTo(Money.de("8.00"));
        });
        assertThat(disputa.get(0)).succeedsWithin(ESPERA);
        assertThat(disputa.get(1)).failsWithin(ESPERA)
                .withThrowableOfType(ExecutionException.class)
                .withCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("item removido enquanto outro é lançado: as duas mudanças valem, e o total fecha com os itens que ficaram")
    void remocaoEAdicaoSimultaneasValemAsDuas() {
        ContaCriada conta = criador.criar("Lanchonete do Largo", SENHA_DE_TESTE);
        UUID sessaoId = conta.comoUsuario(() -> caixas.abrir(Money.ZERO));
        UUID paoId = cadastrar(conta, "Pao na chapa", Money.de("10.00"));
        UUID bolinhoId = cadastrar(conta, "Bolinho", Money.de("5.00"));
        UUID sucoId = cadastrar(conta, "Suco de laranja", Money.de("7.00"));
        UUID vendaId = conta.comoUsuario(() -> vendas.iniciar(sessaoId));
        conta.comoUsuario(() -> vendas.adicionarItem(vendaId, paoId, BigDecimal.ONE, Money.ZERO));
        UUID itemDoBolinho = conta.comoUsuario(() ->
                vendas.adicionarItem(vendaId, bolinhoId, BigDecimal.ONE, Money.ZERO));

        List<CompletableFuture<Object>> disputa = comAVendaPresa(conta, vendaId,
                () -> vendas.adicionarItem(vendaId, sucoId, BigDecimal.ONE, Money.ZERO),
                semResultado(() -> vendas.removerItem(vendaId, itemDoBolinho)));

        aguardar(disputa);
        conta.comoUsuario(() -> {
            VendaParaTela venda = vendas.consultar(vendaId);
            assertThat(venda.itens()).extracting(ItemParaTela::produtoId)
                    .containsExactly(paoId, sucoId);
            assertThat(venda.total()).isEqualTo(Money.de("17.00"));
        });
        assertThat(disputa).allSatisfy(mudanca -> assertThat(mudanca).succeedsWithin(ESPERA));
    }

    @Test
    @DisplayName("item lançado enquanto a conclusão grava: a conclusão vale, o item é recusado, e caixa e estoque contam só a venda concluída")
    void itemDepoisDaConclusaoERecusado() {
        ContaCriada conta = criador.criar("Mercearia da Esquina", SENHA_DE_TESTE);
        criador.habilitarEstoque(conta.contaId());
        UUID sessaoId = conta.comoUsuario(() -> caixas.abrir(Money.de("50.00")));
        UUID arrozId = cadastrar(conta, "Arroz", Money.de("20.00"));
        UUID feijaoId = cadastrar(conta, "Feijao", Money.de("9.00"));
        UUID vendaId = vendaPagaEmDinheiro(conta, sessaoId, arrozId, Money.de("20.00"));

        List<CompletableFuture<Object>> disputa = comAVendaPresa(conta, vendaId,
                semResultado(() -> vendas.concluir(vendaId)),
                () -> vendas.adicionarItem(vendaId, feijaoId, BigDecimal.ONE, Money.ZERO));

        aguardar(disputa);
        continuaConcluida(conta, vendaId, sessaoId, List.of(arrozId), Money.de("20.00"),
                Money.de("70.00"));
        conta.comoUsuario(() -> {
            assertThat(saldoDe(arrozId)).isEqualByComparingTo("-1");
            assertThat(saldoDe(feijaoId)).isEqualByComparingTo("0");
        });
        assertThat(disputa.get(0)).succeedsWithin(ESPERA);
        recusadaPorEstarConcluida(disputa.get(1));
    }

    @Test
    @DisplayName("item do aparelho lançado enquanto a conclusão grava: a conclusão vale, o item é recusado, e caixa e estoque contam só a venda concluída")
    void itemDoAparelhoDepoisDaConclusaoERecusado() {
        ContaCriada conta = criador.criar("Armazém Aurora", SENHA_DE_TESTE);
        criador.habilitarEstoque(conta.contaId());
        UUID sessaoId = conta.comoUsuario(() -> caixas.abrir(Money.de("50.00")));
        UUID arrozId = cadastrar(conta, "Arroz", Money.de("20.00"));
        UUID feijaoId = cadastrar(conta, "Feijao", Money.de("9.00"));
        UUID vendaId = vendaPagaEmDinheiro(conta, sessaoId, arrozId, Money.de("20.00"));

        List<CompletableFuture<Object>> disputa = comAVendaPresa(conta, vendaId,
                semResultado(() -> vendas.concluir(vendaId)),
                () -> vendas.adicionarItemComPrecoVisto(vendaId, UUID.randomUUID(), feijaoId,
                        BigDecimal.ONE, Money.de("9.00"), Money.ZERO, Instant.now()));

        aguardar(disputa);
        continuaConcluida(conta, vendaId, sessaoId, List.of(arrozId), Money.de("20.00"),
                Money.de("70.00"));
        conta.comoUsuario(() -> {
            assertThat(saldoDe(arrozId)).isEqualByComparingTo("-1");
            assertThat(saldoDe(feijaoId)).isEqualByComparingTo("0");
        });
        assertThat(disputa.get(0)).succeedsWithin(ESPERA);
        recusadaPorEstarConcluida(disputa.get(1));
    }

    @Test
    @DisplayName("desconto retirado enquanto a conclusão grava: a conclusão vale com o desconto, e a retirada é recusada")
    void descontoDepoisDaConclusaoERecusado() {
        ContaCriada conta = criador.criar("Empório do Bairro", SENHA_DE_TESTE);
        UUID sessaoId = conta.comoUsuario(() -> caixas.abrir(Money.de("50.00")));
        UUID queijoId = cadastrar(conta, "Queijo minas", Money.de("20.00"));
        UUID vendaId = conta.comoUsuario(() -> {
            UUID id = vendas.iniciar(sessaoId);
            vendas.adicionarItem(id, queijoId, BigDecimal.ONE, Money.ZERO);
            vendas.aplicarDesconto(id, Money.de("5.00"));
            vendas.registrarPagamento(id,
                    SolicitacaoPagamento.emDinheiro(Money.de("15.00"), Money.de("15.00")));
            return id;
        });

        List<CompletableFuture<Object>> disputa = comAVendaPresa(conta, vendaId,
                semResultado(() -> vendas.concluir(vendaId)),
                semResultado(() -> vendas.aplicarDesconto(vendaId, Money.ZERO)));

        aguardar(disputa);
        continuaConcluida(conta, vendaId, sessaoId, List.of(queijoId), Money.de("15.00"),
                Money.de("65.00"));
        conta.comoUsuario(() -> assertThat(vendas.consultar(vendaId).descontoDaVenda())
                .isEqualTo(Money.de("5.00")));
        assertThat(disputa.get(0)).succeedsWithin(ESPERA);
        recusadaPorEstarConcluida(disputa.get(1));
    }

    @Test
    @DisplayName("Cliente vinculado enquanto a conclusão grava: a conclusão vale sem Cliente, e o vínculo é recusado")
    void vinculoDepoisDaConclusaoERecusado() {
        ContaCriada conta = criador.criar("Quitanda Aurora", SENHA_DE_TESTE);
        UUID sessaoId = conta.comoUsuario(() -> caixas.abrir(Money.de("50.00")));
        UUID bananaId = cadastrar(conta, "Banana", Money.de("6.00"));
        UUID clienteId = conta.comoUsuario(() ->
                clientes.cadastrar(new DadosDoCliente("Lia", null)));
        UUID vendaId = vendaPagaEmDinheiro(conta, sessaoId, bananaId, Money.de("6.00"));

        List<CompletableFuture<Object>> disputa = comAVendaPresa(conta, vendaId,
                semResultado(() -> vendas.concluir(vendaId)),
                semResultado(() -> vendas.vincularCliente(vendaId, clienteId)));

        aguardar(disputa);
        continuaConcluida(conta, vendaId, sessaoId, List.of(bananaId), Money.de("6.00"),
                Money.de("56.00"));
        conta.comoUsuario(() -> assertThat(vendas.consultar(vendaId).clienteId()).isNull());
        assertThat(disputa.get(0)).succeedsWithin(ESPERA);
        recusadaPorEstarConcluida(disputa.get(1));
    }

    @Test
    @DisplayName("item lançado antes da conclusão que esperava: o item vale, a conclusão é recusada por faltar pagamento, e nada vai ao caixa nem ao estoque")
    void conclusaoDepoisDoItemERecusada() {
        ContaCriada conta = criador.criar("Mercadinho Aurora", SENHA_DE_TESTE);
        criador.habilitarEstoque(conta.contaId());
        UUID sessaoId = conta.comoUsuario(() -> caixas.abrir(Money.de("50.00")));
        UUID arrozId = cadastrar(conta, "Arroz", Money.de("20.00"));
        UUID feijaoId = cadastrar(conta, "Feijao", Money.de("9.00"));
        UUID vendaId = vendaPagaEmDinheiro(conta, sessaoId, arrozId, Money.de("20.00"));

        List<CompletableFuture<Object>> disputa = comAVendaPresa(conta, vendaId,
                () -> vendas.adicionarItem(vendaId, feijaoId, BigDecimal.ONE, Money.ZERO),
                semResultado(() -> vendas.concluir(vendaId)));

        aguardar(disputa);
        conta.comoUsuario(() -> {
            VendaParaTela venda = vendas.consultar(vendaId);
            assertThat(venda.status()).isEqualTo(StatusVenda.ABERTA);
            assertThat(venda.itens()).extracting(ItemParaTela::produtoId)
                    .containsExactly(arrozId, feijaoId);
            assertThat(venda.total()).isEqualTo(Money.de("29.00"));
            assertThat(tiposNoExtrato(sessaoId)).isEmpty();
            assertThat(esperadoDe(sessaoId)).isEqualTo(Money.de("50.00"));
            assertThat(saldoDe(arrozId)).isEqualByComparingTo("0");
            assertThat(saldoDe(feijaoId)).isEqualByComparingTo("0");
        });
        assertThat(disputa.get(0)).succeedsWithin(ESPERA);
        assertThat(disputa.get(1)).failsWithin(ESPERA)
                .withThrowableOfType(ExecutionException.class)
                .withCauseInstanceOf(IllegalStateException.class)
                .withMessageContaining("faltam");
    }

    @Test
    @DisplayName("dois itens do aparelho em envios simultâneos do lote: os dois aplicados, e a Venda fecha com os dois")
    void doisItensDoLoteSimultaneosValemOsDois() {
        ContaCriada conta = criador.criar("Cafeteria do Porto", SENHA_DE_TESTE);
        UUID sessaoId = conta.comoUsuario(() -> caixas.abrir(Money.ZERO));
        UUID cafeId = cadastrar(conta, "Cafe coado", Money.de("8.00"));
        UUID paoId = cadastrar(conta, "Pao de queijo", Money.de("5.00"));
        UUID vendaId = conta.comoUsuario(() -> vendas.iniciar(sessaoId));
        GestoDeTeste itemDoCafe = item(vendaId, UUID.randomUUID(), cafeId, "1", "8.00");
        GestoDeTeste itemDoPao = item(vendaId, UUID.randomUUID(), paoId, "1", "5.00");

        List<CompletableFuture<List<ResultadoDaOperacao>>> disputa = comAVendaPresa(conta,
                vendaId, () -> enviar(itemDoCafe), () -> enviar(itemDoPao));

        aguardar(disputa);
        conta.comoUsuario(() -> {
            VendaParaTela venda = vendas.consultar(vendaId);
            assertThat(venda.itens()).extracting(ItemParaTela::produtoId)
                    .containsExactly(cafeId, paoId);
            assertThat(venda.total()).isEqualTo(Money.de("13.00"));
        });
        assertThat(disputa).allSatisfy(envio -> assertThat(envio)
                .succeedsWithin(ESPERA)
                .asInstanceOf(InstanceOfAssertFactories.list(ResultadoDaOperacao.class))
                .extracting(ResultadoDaOperacao::resultado)
                .containsExactly(Resultado.APLICADA));
    }

    @Test
    @DisplayName("conclusão e item do aparelho em envios simultâneos do lote: a conclusão aplicada, o item não aplicado, e caixa e estoque contam só a venda concluída")
    void conclusaoEItemDoLoteSimultaneos() {
        ContaCriada conta = criador.criar("Mercearia do Porto", SENHA_DE_TESTE);
        criador.habilitarEstoque(conta.contaId());
        UUID sessaoId = conta.comoUsuario(() -> caixas.abrir(Money.de("50.00")));
        UUID arrozId = cadastrar(conta, "Arroz", Money.de("20.00"));
        UUID feijaoId = cadastrar(conta, "Feijao", Money.de("9.00"));
        conta.comoUsuario(() -> {
            // Com saldo, a baixa não deixa o estoque negativo, e a conclusão não vai para revisão.
            produtos.ajustarEstoque(arrozId, new BigDecimal("10"), "contagem inicial");
            produtos.ajustarEstoque(feijaoId, new BigDecimal("10"), "contagem inicial");
        });
        UUID vendaId = vendaPagaEmDinheiro(conta, sessaoId, arrozId, Money.de("20.00"));

        List<CompletableFuture<List<ResultadoDaOperacao>>> disputa = comAVendaPresa(conta,
                vendaId, () -> enviar(conclusao(vendaId)),
                () -> enviar(item(vendaId, UUID.randomUUID(), feijaoId, "1", "9.00")));

        aguardar(disputa);
        continuaConcluida(conta, vendaId, sessaoId, List.of(arrozId), Money.de("20.00"),
                Money.de("70.00"));
        conta.comoUsuario(() -> {
            assertThat(saldoDe(arrozId)).isEqualByComparingTo("9");
            assertThat(saldoDe(feijaoId)).isEqualByComparingTo("10");
        });
        assertThat(disputa.get(0))
                .succeedsWithin(ESPERA)
                .asInstanceOf(InstanceOfAssertFactories.list(ResultadoDaOperacao.class))
                .extracting(ResultadoDaOperacao::resultado)
                .containsExactly(Resultado.APLICADA);
        assertThat(disputa.get(1))
                .succeedsWithin(ESPERA)
                .asInstanceOf(InstanceOfAssertFactories.list(ResultadoDaOperacao.class))
                .singleElement()
                .satisfies(recusa -> {
                    assertThat(recusa.resultado()).isEqualTo(Resultado.NAO_APLICADA);
                    assertThat(recusa.detalhe()).contains("nao aceita montagem");
                });
    }

    @Test
    @DisplayName("duas conclusões com os mesmos produtos em ordens diferentes e um deles preso: uma conclui, a outra recebe conflito de versão em vez de impasse, e repetida conclui")
    void conclusoesComProdutosEmOrdensDiferentesNaoTravamUmaAOutra() {
        ContaCriada conta = criador.criar("Mercearia Central", SENHA_DE_TESTE);
        criador.habilitarEstoque(conta.contaId());
        UUID sessaoId = conta.comoUsuario(() -> caixas.abrir(Money.ZERO));
        TresProdutos produto = tresProdutosEmOrdemDeId(conta);
        // O produto preso é o de maior id, e cada venda o lança entre os outros dois, em ordens
        // opostas. Na ordem dos itens, cada conclusão gravaria um produto diferente antes de parar
        // no preso, e depois uma esperaria pelo que a outra gravou. Na ordem do id, a primeira
        // grava os outros dois antes de parar no preso, e a segunda para atrás dela no primeiro.
        UUID primeiraVenda = vendaPagaEmCartao(conta, sessaoId, produto.primeiro(),
                produto.ultimo(), produto.segundo());
        UUID segundaVenda = vendaPagaEmCartao(conta, sessaoId, produto.segundo(),
                produto.ultimo(), produto.primeiro());

        List<CompletableFuture<Object>> disputa = comOProdutoPreso(conta, produto.ultimo(),
                semResultado(() -> vendas.concluir(primeiraVenda)),
                semResultado(() -> vendas.concluir(segundaVenda)));

        aguardar(disputa);
        assertThat(disputa.get(0)).succeedsWithin(ESPERA);
        assertThat(disputa.get(1)).failsWithin(ESPERA)
                .withThrowableOfType(ExecutionException.class)
                .as("a segunda leu o produto antes da baixa da primeira e foi recusada pela versão")
                .withCauseInstanceOf(OptimisticLockingFailureException.class);
        conta.comoUsuario(() -> {
            assertThat(vendas.consultar(primeiraVenda).status()).isEqualTo(StatusVenda.CONCLUIDA);
            assertThat(vendas.consultar(segundaVenda).status()).isEqualTo(StatusVenda.ABERTA);
            assertThat(produto.todos()).allSatisfy(id ->
                    assertThat(saldoDe(id)).isEqualByComparingTo("-1"));
        });

        conta.comoUsuario(() -> vendas.concluir(segundaVenda));

        conta.comoUsuario(() -> {
            assertThat(vendas.consultar(segundaVenda).status()).isEqualTo(StatusVenda.CONCLUIDA);
            assertThat(produto.todos()).allSatisfy(id ->
                    assertThat(saldoDe(id)).isEqualByComparingTo("-2"));
        });
    }

    @Test
    @DisplayName("dois cancelamentos com os mesmos produtos em ordens diferentes e um deles preso: um cancela, o outro recebe conflito de versão em vez de impasse, e repetido cancela")
    void cancelamentosComProdutosEmOrdensDiferentesNaoTravamUmAOutro() {
        ContaCriada conta = criador.criar("Mercearia do Largo", SENHA_DE_TESTE);
        criador.habilitarEstoque(conta.contaId());
        UUID sessaoId = conta.comoUsuario(() -> caixas.abrir(Money.ZERO));
        TresProdutos produto = tresProdutosEmOrdemDeId(conta);
        // As mesmas ordens do teste das conclusões, pelo mesmo motivo.
        UUID primeiraVenda = vendaPagaEmCartao(conta, sessaoId, produto.primeiro(),
                produto.ultimo(), produto.segundo());
        UUID segundaVenda = vendaPagaEmCartao(conta, sessaoId, produto.segundo(),
                produto.ultimo(), produto.primeiro());
        conta.comoUsuario(() -> {
            vendas.concluir(primeiraVenda);
            vendas.concluir(segundaVenda);
        });

        List<CompletableFuture<Object>> disputa = comOProdutoPreso(conta, produto.ultimo(),
                semResultado(() -> vendas.cancelar(primeiraVenda)),
                semResultado(() -> vendas.cancelar(segundaVenda)));

        aguardar(disputa);
        assertThat(disputa.get(0)).succeedsWithin(ESPERA);
        assertThat(disputa.get(1)).failsWithin(ESPERA)
                .withThrowableOfType(ExecutionException.class)
                .as("o segundo leu o produto antes da devolução do primeiro e foi recusado pela versão")
                .withCauseInstanceOf(OptimisticLockingFailureException.class);
        conta.comoUsuario(() -> {
            assertThat(vendas.consultar(primeiraVenda).status()).isEqualTo(StatusVenda.CANCELADA);
            assertThat(vendas.consultar(segundaVenda).status()).isEqualTo(StatusVenda.CONCLUIDA);
            assertThat(produto.todos()).allSatisfy(id ->
                    assertThat(saldoDe(id)).isEqualByComparingTo("-1"));
        });

        conta.comoUsuario(() -> vendas.cancelar(segundaVenda));

        conta.comoUsuario(() -> {
            assertThat(vendas.consultar(segundaVenda).status()).isEqualTo(StatusVenda.CANCELADA);
            assertThat(produto.todos()).allSatisfy(id ->
                    assertThat(saldoDe(id)).isEqualByComparingTo("0"));
        });
    }

    @Test
    @DisplayName("com a Venda de uma Conta presa, a escrita de outra Conta com o mesmo id termina sem esperar, como Venda inexistente")
    void travaNaoAlcancaAVendaDeOutraConta() {
        ContaCriada contaA = criador.criar("Cafeteria Aurora", SENHA_DE_TESTE);
        ContaCriada contaB = criador.criar("Loja da Esquina", SENHA_DE_TESTE);
        UUID sessaoA = contaA.comoUsuario(() -> caixas.abrir(Money.ZERO));
        UUID cafeA = cadastrar(contaA, "Cafe coado", Money.de("8.00"));
        UUID vendaDeA = contaA.comoUsuario(() -> {
            UUID id = vendas.iniciar(sessaoA);
            vendas.adicionarItem(id, cafeA, BigDecimal.ONE, Money.ZERO);
            return id;
        });
        UUID paoB = cadastrar(contaB, "Pao de queijo", Money.de("5.00"));

        ExecutorService threadDaOutraConta = Executors.newSingleThreadExecutor();
        try {
            contaA.comoUsuario(() -> transacao.executeWithoutResult(status -> {
                prender(PRENDE_A_VENDA, vendaDeA, contaA);

                CompletableFuture<UUID> adicao = CompletableFuture.supplyAsync(() ->
                        contaB.comoUsuario(() -> vendas.adicionarItem(vendaDeA, paoB,
                                BigDecimal.ONE, Money.ZERO)), threadDaOutraConta);
                CompletableFuture<Object> conclusao = CompletableFuture.supplyAsync(
                        () -> contaB.comoUsuario(semResultado(() -> vendas.concluir(vendaDeA))),
                        threadDaOutraConta);

                // As duas terminam com a linha ainda presa: nenhuma esperou por ela.
                assertThat(adicao).failsWithin(ESPERA)
                        .withThrowableOfType(ExecutionException.class)
                        .withCauseInstanceOf(VendaNaoEncontradaException.class);
                assertThat(conclusao).failsWithin(ESPERA)
                        .withThrowableOfType(ExecutionException.class)
                        .withCauseInstanceOf(VendaNaoEncontradaException.class);
                assertThat(quantasEsperam()).isZero();
            }));
        } finally {
            threadDaOutraConta.shutdown();
        }

        contaA.comoUsuario(() -> {
            VendaParaTela venda = vendas.consultar(vendaDeA);
            assertThat(venda.status()).isEqualTo(StatusVenda.ABERTA);
            assertThat(venda.itens()).extracting(ItemParaTela::produtoId).containsExactly(cafeA);
        });
    }

    /**
     * Começa as operações com a linha da Venda presa, como descrito no topo da classe.
     *
     * @return as operações, na ordem dada; cada uma termina depois que a linha é solta
     */
    @SafeVarargs
    private <T> List<CompletableFuture<T>> comAVendaPresa(ContaCriada conta, UUID vendaId,
            Supplier<T>... operacoes) {
        return segurando(conta, PRENDE_A_VENDA, vendaId, List.of(operacoes));
    }

    /** O mesmo, com a linha de um Produto presa no lugar da Venda. */
    @SafeVarargs
    private <T> List<CompletableFuture<T>> comOProdutoPreso(ContaCriada conta, UUID produtoId,
            Supplier<T>... operacoes) {
        return segurando(conta, PRENDE_O_PRODUTO, produtoId, List.of(operacoes));
    }

    /**
     * Prende a linha numa transação que não a altera e começa cada operação numa thread própria,
     * como quem a conta autenticou. A seguinte só começa quando as anteriores estão paradas numa
     * trava ou terminaram, e a transação só confirma quando todas estão assim.
     */
    private <T> List<CompletableFuture<T>> segurando(ContaCriada conta, String trava, UUID id,
            List<Supplier<T>> operacoes) {
        ExecutorService threads = Executors.newFixedThreadPool(operacoes.size());
        try {
            return conta.comoUsuario(() -> transacao.execute(status -> {
                prender(trava, id, conta);
                List<CompletableFuture<T>> iniciadas = new ArrayList<>();
                for (Supplier<T> operacao : operacoes) {
                    iniciadas.add(CompletableFuture.supplyAsync(
                            () -> conta.comoUsuario(operacao), threads));
                    await().atMost(ESPERA).until(() ->
                            quantasEsperam() + terminadas(iniciadas) == iniciadas.size());
                }
                return iniciadas;
            }));
        } finally {
            // As threads terminam o que começaram e só então se encerram.
            threads.shutdown();
        }
    }

    private void prender(String trava, UUID id, ContaCriada conta) {
        jdbc.sql(trava)
                .param("id", id)
                .param("conta", conta.contaId().valor())
                .query(UUID.class)
                .single();
    }

    /**
     * Quantas conexões estão paradas esperando uma trava, de quem for. A segunda escrita na mesma
     * linha espera a primeira, e não a transação que prendeu a linha, então contar só quem espera
     * por esta deixaria a segunda de fora. Lê {@code pg_locks}, que o PostgreSQL monta na hora da
     * consulta.
     */
    private long quantasEsperam() {
        return jdbc.sql("select count(*) from pg_locks where not granted")
                .query(Long.class)
                .single();
    }

    private static long terminadas(List<? extends CompletableFuture<?>> operacoes) {
        return operacoes.stream().filter(CompletableFuture::isDone).count();
    }

    private static void aguardar(List<? extends CompletableFuture<?>> operacoes) {
        await().atMost(ESPERA).until(() -> terminadas(operacoes) == operacoes.size());
    }

    /** Uma operação sem retorno, no formato que a disputa recebe. */
    private static Supplier<Object> semResultado(Runnable operacao) {
        return () -> {
            operacao.run();
            return null;
        };
    }

    private List<ResultadoDaOperacao> enviar(GestoDeTeste gesto) {
        return sincronizacao.sincronizar(List.of(gesto.recebida(json)));
    }

    /**
     * A conclusão valeu inteira, sem a edição que chegou depois: a Venda CONCLUIDA com os itens e o
     * total de antes, e um lançamento de VENDA só, com o dinheiro dela, no caixa.
     */
    private void continuaConcluida(ContaCriada conta, UUID vendaId, UUID sessaoId,
            List<UUID> produtosVendidos, Money total, Money esperadoNaGaveta) {
        conta.comoUsuario(() -> {
            VendaParaTela venda = vendas.consultar(vendaId);
            assertThat(venda.status()).isEqualTo(StatusVenda.CONCLUIDA);
            assertThat(venda.itens()).extracting(ItemParaTela::produtoId)
                    .containsExactlyElementsOf(produtosVendidos);
            assertThat(venda.total()).isEqualTo(total);
            assertThat(tiposNoExtrato(sessaoId)).containsExactly(TipoMovimentoCaixa.VENDA);
            assertThat(esperadoDe(sessaoId)).isEqualTo(esperadoNaGaveta);
        });
    }

    /** A edição esperou a conclusão e foi recusada pela raiz, que já não estava ABERTA. */
    private static void recusadaPorEstarConcluida(CompletableFuture<?> edicao) {
        assertThat(edicao).failsWithin(ESPERA)
                .withThrowableOfType(ExecutionException.class)
                .withCauseInstanceOf(IllegalStateException.class)
                .withMessageContaining("nao aceita montagem");
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

    /** Uma unidade do produto, paga em dinheiro sem troco e ainda ABERTA: falta só concluir. */
    private UUID vendaPagaEmDinheiro(ContaCriada conta, UUID sessaoId, UUID produtoId,
            Money preco) {
        return conta.comoUsuario(() -> {
            UUID vendaId = vendas.iniciar(sessaoId);
            vendas.adicionarItem(vendaId, produtoId, BigDecimal.ONE, Money.ZERO);
            vendas.registrarPagamento(vendaId, SolicitacaoPagamento.emDinheiro(preco, preco));
            return vendaId;
        });
    }

    /**
     * Uma unidade de cada produto, lançados na ordem dada, paga em cartão e ainda ABERTA. O cartão
     * não passa pela gaveta: a disputa fica só entre os produtos.
     */
    private UUID vendaPagaEmCartao(ContaCriada conta, UUID sessaoId, UUID... produtosNaOrdem) {
        return conta.comoUsuario(() -> {
            UUID vendaId = vendas.iniciar(sessaoId);
            for (UUID produtoId : produtosNaOrdem) {
                vendas.adicionarItem(vendaId, produtoId, BigDecimal.ONE, Money.ZERO);
            }
            Money total = vendas.consultar(vendaId).total();
            vendas.registrarPagamento(vendaId,
                    SolicitacaoPagamento.de(FormaPagamento.CARTAO, total));
            return vendaId;
        });
    }

    /** Três produtos da Conta na ordem do id, a do UUID, que é a que os ouvintes do estoque usam. */
    private record TresProdutos(UUID primeiro, UUID segundo, UUID ultimo) {

        List<UUID> todos() {
            return List.of(primeiro, segundo, ultimo);
        }
    }

    private TresProdutos tresProdutosEmOrdemDeId(ContaCriada conta) {
        List<UUID> ids = new ArrayList<>(List.of(
                cadastrar(conta, "Arroz", Money.de("20.00")),
                cadastrar(conta, "Feijao", Money.de("9.00")),
                cadastrar(conta, "Farinha", Money.de("6.00"))));
        Collections.sort(ids);
        return new TresProdutos(ids.get(0), ids.get(1), ids.get(2));
    }

    /** O saldo como o domínio o vê. */
    private BigDecimal saldoDe(UUID produtoId) {
        return linhasDeProduto.findById(produtoId).orElseThrow().paraDominio().getEstoqueAtual();
    }
}
