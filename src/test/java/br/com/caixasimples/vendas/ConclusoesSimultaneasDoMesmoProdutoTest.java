package br.com.caixasimples.vendas;

import static br.com.caixasimples.sincronizacao.GestoDeTeste.conclusao;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.cadastro.TipoProduto;
import br.com.caixasimples.cadastro.application.ProdutoService;
import br.com.caixasimples.cadastro.application.ProdutoService.DadosDoProduto;
import br.com.caixasimples.cadastro.internal.ProdutoRepository;
import br.com.caixasimples.caixa.TipoMovimentoCaixa;
import br.com.caixasimples.caixa.application.SessaoCaixaService;
import br.com.caixasimples.caixa.domain.MovimentoCaixa;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.UsuarioCriado;
import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.pagamentos.domain.SolicitacaoPagamento;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.TenantContext;
import br.com.caixasimples.shared.UsuarioContext;
import br.com.caixasimples.sincronizacao.GestoDeTeste;
import br.com.caixasimples.sincronizacao.application.ResultadoDaOperacao;
import br.com.caixasimples.sincronizacao.application.ResultadoDaOperacao.Resultado;
import br.com.caixasimples.sincronizacao.application.SincronizacaoService;
import br.com.caixasimples.vendas.application.VendaService;
import br.com.caixasimples.vendas.application.VendaService.ParcelaParaTela;
import br.com.caixasimples.vendas.application.VendaService.VendaParaTela;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Duas vendas do mesmo produto concluídas ao mesmo tempo, cada uma no caixa do seu operador: o
 * balcão com dois caixas vendendo pão francês.
 *
 * <p>A conclusão dá baixa no estoque na própria transação. O Produto é lido sem trava e gravado
 * pela versão, então a conclusão que leu o produto antes de a outra confirmar grava sobre uma
 * versão que deixou de existir e é recusada inteira. Os caixas são de operadores diferentes e não
 * disputam nada; o produto é a única raiz com versão que as duas transações dividem, e com o
 * controle de estoque desligado nada é disputado.
 *
 * <p>Com rede, a conclusão repete sozinha a recusa, numa transação nova que lê o produto como a
 * outra o deixou, e as duas vendas valem sem ninguém tocar de novo em Concluir venda. Pelo lote, a
 * conclusão roda na transação do lote e não repete: o gesto volta como erro transitório, e o
 * dispositivo o reenvia.
 *
 * <h2>Como o conflito fica determinístico</h2>
 *
 * <p>A primeira conclusão envia a baixa ao banco numa transação que fica aberta, com a linha do
 * produto presa. A segunda começa em outra thread, lê o produto pela versão de antes e para ao
 * gravar; a primeira só confirma quando essa espera aparece em {@code pg_locks}. Nada depende de
 * pausa nem de sorte com o escalonador.
 */
class ConclusoesSimultaneasDoMesmoProdutoTest extends TesteDeIntegracao {

    private static final String SENHA_DE_TESTE = "uma senha longa de teste";
    private static final Duration ESPERA = Duration.ofSeconds(10);
    private static final Money PRECO = Money.de("10.00");
    private static final Money ABERTURA = Money.de("50.00");

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
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("com rede, a segunda conclusão do mesmo produto é recusada pela versão, repete sozinha e conclui, com as duas baixas e o dinheiro na gaveta")
    void segundaConclusaoComRedeRepeteSozinhaEConclui(CapturedOutput saida) {
        Balcao balcao = doisCaixasVendendo("Padaria Aurora", true);

        CompletableFuture<Object> conclusao = segurandoOProduto(balcao,
                () -> balcao.operador().comoUsuario(() -> {
                    vendas.concluir(balcao.vendaDoOperador());
                    return null;
                }));

        assertThat(conclusao).succeedsWithin(ESPERA);
        assertThat(saida.getOut())
                .as("a primeira tentativa leu o produto antes da baixa da outra e foi recusada")
                .contains("conclusao da venda " + balcao.vendaDoOperador()
                        + " recusada por conflito de versao na tentativa 1; repetindo")
                .doesNotContain("na tentativa 2");
        concluidaComBaixaEGaveta(balcao);
    }

    @Test
    @DisplayName("sem a repetição, a forma que o lote usa, a mesma disputa recusa a segunda conclusão pela versão: a venda fica aberta e paga, fora da gaveta e sem baixa")
    void semRepeticaoASegundaConclusaoERecusadaPelaVersao() {
        Balcao balcao = doisCaixasVendendo("Padaria do Porto", true);

        CompletableFuture<Object> conclusao = segurandoOProduto(balcao,
                () -> balcao.operador().comoUsuario(() -> {
                    vendas.concluir(balcao.vendaDoOperador(), Instant.now());
                    return null;
                }));

        assertThatThrownBy(conclusao::join)
                .as("leu o produto antes da baixa da outra e foi recusada pela versão")
                .hasCauseInstanceOf(OptimisticLockingFailureException.class);
        balcao.operador().comoUsuario(() -> {
            VendaParaTela venda = vendas.consultar(balcao.vendaDoOperador());
            assertThat(venda.status()).isEqualTo(StatusVenda.ABERTA);
            assertThat(venda.faltaPagar()).isEqualTo(Money.ZERO);
            assertThat(venda.parcelas()).extracting(ParcelaParaTela::forma)
                    .containsExactly(FormaPagamento.DINHEIRO);
            assertThat(tiposNoExtrato(balcao.caixaDoOperador())).isEmpty();
            assertThat(esperadoDe(balcao.caixaDoOperador())).isEqualTo(ABERTURA);
            assertThat(produtos.jaDeuBaixaPorVenda(balcao.pao(), balcao.vendaDoOperador()))
                    .isFalse();
        });
        assertThat(saldoDe(balcao)).as("só a venda do administrador baixou")
                .isEqualByComparingTo("9");
    }

    @Test
    @DisplayName("pelo lote, a mesma disputa responde erro transitório, nada fica gravado, e o reenvio do mesmo gesto é aplicado")
    void segundaConclusaoPeloLoteVoltaComoErroTransitorio() {
        Balcao balcao = doisCaixasVendendo("Padaria do Largo", true);
        GestoDeTeste gesto = conclusao(balcao.vendaDoOperador());

        CompletableFuture<List<ResultadoDaOperacao>> envio = segurandoOProduto(balcao,
                () -> balcao.operador().comoUsuario(() -> enviar(gesto)));

        assertThat(envio.join()).singleElement().satisfies(resultado -> {
            assertThat(resultado.resultado()).isEqualTo(Resultado.ERRO_TRANSITORIO);
            assertThat(resultado.detalhe())
                    .isEqualTo("Falha temporária no servidor; o aparelho envia de novo.");
        });
        balcao.operador().comoUsuario(() -> {
            assertThat(vendas.consultar(balcao.vendaDoOperador()).status())
                    .isEqualTo(StatusVenda.ABERTA);
            assertThat(tiposNoExtrato(balcao.caixaDoOperador())).isEmpty();
        });
        assertThat(saldoDe(balcao)).isEqualByComparingTo("9");

        List<ResultadoDaOperacao> reenvio = balcao.operador().comoUsuario(() -> enviar(gesto));

        assertThat(reenvio).extracting(ResultadoDaOperacao::resultado)
                .containsExactly(Resultado.APLICADA);
        concluidaComBaixaEGaveta(balcao);
    }

    @Test
    @DisplayName("com o controle de estoque desligado, as duas conclusões valem: os caixas de operadores diferentes não disputam nada")
    void semEstoqueAsDuasConclusoesValem() {
        Balcao balcao = doisCaixasVendendo("Padaria da Esquina", false);

        CompletableFuture<Object> conclusao = segurandoOProduto(balcao,
                () -> balcao.operador().comoUsuario(() -> {
                    vendas.concluir(balcao.vendaDoOperador());
                    return null;
                }));

        assertThat(conclusao).succeedsWithin(ESPERA);
        balcao.admin().comoUsuario(() -> {
            assertThat(vendas.consultar(balcao.vendaDoAdmin()).status())
                    .isEqualTo(StatusVenda.CONCLUIDA);
            assertThat(vendas.consultar(balcao.vendaDoOperador()).status())
                    .isEqualTo(StatusVenda.CONCLUIDA);
        });
        assertThat(saldoDe(balcao)).as("sem controle de estoque, o saldo não se move")
                .isEqualByComparingTo("10");
    }

    /**
     * Uma Conta com o administrador e um operador, cada um com o próprio caixa aberto e uma venda
     * paga de um pão francês, que ainda falta concluir.
     */
    private Balcao doisCaixasVendendo(String nomeDoNegocio, boolean comEstoque) {
        ContaCriada admin = criador.criar(nomeDoNegocio, SENHA_DE_TESTE);
        if (comEstoque) {
            criador.habilitarEstoque(admin.contaId());
        }
        UsuarioCriado operador = criador.criarOperadorEm(admin.contaId(), "Atendente da tarde");
        UUID pao = admin.comoUsuario(() -> {
            UUID id = produtos.cadastrar(TipoProduto.PRODUTO,
                    new DadosDoProduto("Pao frances", PRECO, null, null, "un", null));
            // Com saldo, a baixa não deixa o estoque negativo, e a conclusão do lote não vai
            // para revisão por isso.
            produtos.ajustarEstoque(id, new BigDecimal("10"), "contagem inicial");
            return id;
        });
        UUID caixaDoAdmin = admin.comoUsuario(() -> caixas.abrir(ABERTURA));
        UUID caixaDoOperador = operador.comoUsuario(() -> caixas.abrir(ABERTURA));
        UUID vendaDoAdmin = admin.comoUsuario(() -> vendaPaga(caixaDoAdmin, pao));
        UUID vendaDoOperador = operador.comoUsuario(() -> vendaPaga(caixaDoOperador, pao));
        return new Balcao(admin, operador, pao, caixaDoOperador, vendaDoAdmin, vendaDoOperador);
    }

    private record Balcao(ContaCriada admin, UsuarioCriado operador, UUID pao,
            UUID caixaDoOperador, UUID vendaDoAdmin, UUID vendaDoOperador) {
    }

    private UUID vendaPaga(UUID sessaoId, UUID produtoId) {
        UUID vendaId = vendas.iniciar(sessaoId);
        vendas.adicionarItem(vendaId, produtoId, BigDecimal.ONE, Money.ZERO);
        vendas.registrarPagamento(vendaId, SolicitacaoPagamento.emDinheiro(PRECO, PRECO));
        return vendaId;
    }

    /**
     * Conclui a venda do administrador numa transação que envia a baixa ao banco e fica aberta,
     * com a linha do produto presa, enquanto a {@code operacao} começa em outra thread. Só
     * confirma quando alguma conexão está parada esperando uma trava desta transação, ou quando a
     * operação já terminou sem precisar dela; no segundo caso, as asserções de quem chamou dizem o
     * porquê.
     */
    private <T> CompletableFuture<T> segurandoOProduto(Balcao balcao, Supplier<T> operacao) {
        return balcao.admin().comoUsuario(() -> transacao.execute(status -> {
            // A forma sem repetição, que entra na transação aberta aqui; a com rede abriria as
            // próprias.
            vendas.concluir(balcao.vendaDoAdmin(), Instant.now());
            // O UPDATE do produto vai ao banco agora, e a linha fica presa até esta transação
            // terminar.
            status.flush();
            int estaConexao = jdbc.sql("select pg_backend_pid()").query(Integer.class).single();

            CompletableFuture<T> emAndamento = CompletableFuture.supplyAsync(operacao);

            await().atMost(ESPERA)
                    .until(() -> quantasEsperamPor(estaConexao) > 0 || emAndamento.isDone());
            return emAndamento;
        }));
    }

    /**
     * Quantas conexões estão paradas esperando uma trava que a conexão indicada segura. Lê
     * {@code pg_locks}, que o PostgreSQL monta na hora da consulta.
     */
    private long quantasEsperamPor(int conexao) {
        return jdbc.sql("select count(*) from pg_locks"
                        + " where not granted and :conexao = any(pg_blocking_pids(pid))")
                .param("conexao", conexao)
                .query(Long.class)
                .single();
    }

    private List<ResultadoDaOperacao> enviar(GestoDeTeste gesto) {
        return sincronizacao.sincronizar(List.of(gesto.recebida(json)));
    }

    /** A venda do operador valeu inteira: concluída, com o dinheiro na gaveta e a baixa feita. */
    private void concluidaComBaixaEGaveta(Balcao balcao) {
        balcao.operador().comoUsuario(() -> {
            assertThat(vendas.consultar(balcao.vendaDoOperador()).status())
                    .isEqualTo(StatusVenda.CONCLUIDA);
            assertThat(tiposNoExtrato(balcao.caixaDoOperador()))
                    .containsExactly(TipoMovimentoCaixa.VENDA);
            assertThat(esperadoDe(balcao.caixaDoOperador())).isEqualTo(ABERTURA.somar(PRECO));
            assertThat(produtos.jaDeuBaixaPorVenda(balcao.pao(), balcao.vendaDoOperador()))
                    .isTrue();
        });
        assertThat(saldoDe(balcao)).isEqualByComparingTo("8");
    }

    private List<TipoMovimentoCaixa> tiposNoExtrato(UUID sessaoId) {
        return caixas.consultarExtrato(sessaoId).movimentos().stream()
                .map(MovimentoCaixa::tipo)
                .toList();
    }

    private Money esperadoDe(UUID sessaoId) {
        return caixas.consultar(sessaoId).valorFechamentoEsperado();
    }

    /** O saldo como o domínio o vê. */
    private BigDecimal saldoDe(Balcao balcao) {
        return balcao.admin().comoUsuario(() ->
                linhasDeProduto.findById(balcao.pao()).orElseThrow().paraDominio()
                        .getEstoqueAtual());
    }
}
