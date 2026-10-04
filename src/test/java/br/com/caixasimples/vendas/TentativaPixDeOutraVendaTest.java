package br.com.caixasimples.vendas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.cadastro.TipoProduto;
import br.com.caixasimples.cadastro.application.ProdutoService;
import br.com.caixasimples.cadastro.application.ProdutoService.DadosDoProduto;
import br.com.caixasimples.caixa.application.SessaoCaixaService;
import br.com.caixasimples.contas.AutenticadorDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.pagamentos.StatusPagamento;
import br.com.caixasimples.pagamentos.domain.CobrancaPix;
import br.com.caixasimples.pagamentos.domain.PixGateway;
import br.com.caixasimples.pagamentos.domain.SolicitacaoPagamento;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.vendas.application.VendaPixService;
import br.com.caixasimples.vendas.application.VendaService;
import br.com.caixasimples.vendas.application.VendaService.VendaParaTela;
import br.com.caixasimples.vendas.domain.Pagamento;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * O id de uma tentativa Pix que já é o id de uma parcela de outra Venda.
 *
 * <p>O aparelho gera o id da tentativa antes de pedir a cobrança, e o servidor o usa como id da
 * parcela: é assim que o reenvio depois de uma resposta perdida encontra a mesma cobrança. A linha
 * da parcela não tem versão, e gravar uma parcela nova com um id já usado mesclaria a nova sobre a
 * existente, que trocaria de conteúdo e de comanda. O id já usado em outra Venda desta conta é
 * recusado antes de gravar, como os demais ids que vêm do aparelho, e cada cenário confere as duas
 * Vendas inteiras depois da tentativa: a linha gravada da parcela e a leitura da raiz.
 *
 * <p>Em outra conta, a consulta não enxerga a parcela, e quem recusa é a chave primária, sem ler
 * nem alterar a outra conta. Duas reservas simultâneas com o mesmo id novo passam as duas pela
 * consulta antes de qualquer uma confirmar, e a chave primária também decide: uma vale, a outra
 * falha.
 *
 * <h2>Como a disputa fica simultânea</h2>
 *
 * <p>Uma transação prende as linhas das contas com {@code for update}. A primeira reserva consulta
 * o id, insere a parcela e para na conferência da chave estrangeira da conta, que espera a trava.
 * A segunda só começa quando a primeira está parada: a consulta dela também não enxerga a parcela,
 * que ainda não foi confirmada, e a inserção espera pela primeira na chave primária. Solta a
 * trava, a primeira confirma e a segunda recebe a violação da chave. O teste não depende da ordem
 * e exige só o que importa: uma reserva vale, a outra falha, e a parcela fica numa Venda só.
 */
class TentativaPixDeOutraVendaTest extends TesteDeIntegracao {

    private static final String SENHA_DE_TESTE = "uma senha longa de teste";
    private static final Duration ESPERA = Duration.ofSeconds(10);
    private static final Money VALOR = Money.de("12.50");

    /** A trava que a conferência da chave estrangeira da conta disputa ao inserir a parcela. */
    private static final String PRENDE_AS_CONTAS =
            "select id from conta where id in (:contas) for update";

    /**
     * A linha da parcela lida por SQL, fora do filtro de conta, de propósito: o que se confere é a
     * que Venda e a que conta ela pertence depois da tentativa.
     */
    private static final String LE_AS_PARCELAS = "select id, venda_id, conta_id, forma, valor,"
            + " status, troco, criado_em, pix_txid, pix_chave_recebedora, pix_expira_em,"
            + " pix_copia_e_cola, pix_estado from pagamento";

    @Autowired MockMvc http;
    @Autowired CriadorDeContaDeTeste criador;
    @Autowired AutenticadorDeTeste autenticador;
    @Autowired ProdutoService produtos;
    @Autowired SessaoCaixaService caixas;
    @Autowired VendaService vendas;
    @Autowired VendaPixService pix;
    @Autowired TransactionTemplate transacao;
    @Autowired JdbcClient jdbc;
    @MockitoBean PixGateway gateway;

    @Test
    @DisplayName("tentativa Pix de outra Venda da conta: 409, e as duas Vendas ficam como estavam")
    void tentativaPixDeOutraVendaDaContaERecusada() throws Exception {
        Balcao balcao = abrirBalcao("Cafeteria Aurora");
        UUID vendaA = vendaComUmItem(balcao);
        UUID vendaB = vendaComUmItem(balcao);
        UUID tentativa = UUID.randomUUID();
        cobrancasDevolvem("codigo-pix-da-venda-a");
        balcao.conta().comoUsuario(() -> pix.cobrar(vendaA, tentativa, VALOR));
        VendaParaTela aAntes = consultar(balcao.conta(), vendaA);
        VendaParaTela bAntes = consultar(balcao.conta(), vendaB);
        LinhaDaParcela parcelaAntes = linhaDaParcela(tentativa).orElseThrow();

        MvcResult naOutraVenda = pedirPix(balcao.conta(), vendaB, tentativa);
        String corpo = naOutraVenda.getResponse().getContentAsString();

        assertSoftly(s -> {
            s.assertThat(naOutraVenda.getResponse().getStatus()).isEqualTo(409);
            s.assertThat(corpo).doesNotContain(vendaA.toString());
            s.assertThat(linhaDaParcela(tentativa)).contains(parcelaAntes);
            s.assertThat(parcelasDa(vendaB)).isEmpty();
            s.check(() -> verify(gateway, times(1)).criarCobranca(any(), any()));
        });
        assertThat(consultar(balcao.conta(), vendaA)).isEqualTo(aAntes);
        assertThat(consultar(balcao.conta(), vendaB)).isEqualTo(bAntes);

        // O reenvio legítimo, na Venda dona da tentativa, devolve a mesma parcela sem nova cobrança.
        http.perform(post("/api/vendas/{id}/pagamentos/pix", vendaA)
                        .with(autenticador.como(balcao.conta()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pedidoDePix(tentativa)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(tentativa.toString()))
                .andExpect(jsonPath("$.pix.copiaECola").value("codigo-pix-da-venda-a"));
        assertThat(consultar(balcao.conta(), vendaA)).isEqualTo(aAntes);
        verify(gateway, times(1)).criarCobranca(any(), any());
    }

    @Test
    @DisplayName("parcela em dinheiro de Venda concluída usada como tentativa Pix em outra: 409,"
            + " e a concluída segue com a parcela dela")
    void parcelaDeVendaConcluidaNaoViraPixDeOutraVenda() throws Exception {
        Balcao balcao = abrirBalcao("Loja da Esquina");
        UUID vendaA = vendaComUmItem(balcao);
        UUID vendaB = vendaComUmItem(balcao);
        UUID parcelaEmDinheiro = UUID.randomUUID();
        balcao.conta().comoUsuario(() -> {
            vendas.registrarPagamentoOnline(vendaA, parcelaEmDinheiro,
                    SolicitacaoPagamento.emDinheiro(VALOR, Money.de("20.00")), null);
            vendas.concluir(vendaA);
        });
        cobrancasDevolvem("codigo-pix-da-venda-b");
        VendaParaTela aAntes = consultar(balcao.conta(), vendaA);
        VendaParaTela bAntes = consultar(balcao.conta(), vendaB);
        LinhaDaParcela parcelaAntes = linhaDaParcela(parcelaEmDinheiro).orElseThrow();
        assertThat(aAntes.status()).isEqualTo(StatusVenda.CONCLUIDA);

        MvcResult naOutraVenda = pedirPix(balcao.conta(), vendaB, parcelaEmDinheiro);
        String corpo = naOutraVenda.getResponse().getContentAsString();

        assertSoftly(s -> {
            s.assertThat(naOutraVenda.getResponse().getStatus()).isEqualTo(409);
            s.assertThat(corpo).doesNotContain(vendaA.toString());
            s.assertThat(linhaDaParcela(parcelaEmDinheiro)).contains(parcelaAntes);
            s.assertThat(parcelasDa(vendaB)).isEmpty();
            s.assertThatCode(() -> consultar(balcao.conta(), vendaA))
                    .doesNotThrowAnyException();
            s.check(() -> verify(gateway, never()).criarCobranca(any(), any()));
        });
        assertThat(consultar(balcao.conta(), vendaA)).isEqualTo(aAntes);
        assertThat(consultar(balcao.conta(), vendaB)).isEqualTo(bAntes);
    }

    @Test
    @DisplayName("tentativa Pix com o id de uma parcela de outra conta: recusada sem ler nem alterar"
            + " a outra conta")
    void tentativaComIdDeOutraContaERecusadaSemTocarNela() throws Exception {
        Balcao daConta = abrirBalcao("Cafeteria Aurora");
        Balcao daOutra = abrirBalcao("Loja da Esquina");
        UUID vendaA = vendaComUmItem(daConta);
        UUID vendaB = vendaComUmItem(daOutra);
        UUID tentativa = UUID.randomUUID();
        cobrancasDevolvem("codigo-pix-da-conta-a");
        daConta.conta().comoUsuario(() -> pix.cobrar(vendaA, tentativa, VALOR));
        VendaParaTela aAntes = consultar(daConta.conta(), vendaA);
        VendaParaTela bAntes = consultar(daOutra.conta(), vendaB);
        LinhaDaParcela parcelaAntes = linhaDaParcela(tentativa).orElseThrow();

        MvcResult naOutraConta = pedirPix(daOutra.conta(), vendaB, tentativa);
        String corpo = naOutraConta.getResponse().getContentAsString();

        assertSoftly(s -> {
            // Quem recusa é a chave primária, e a resposta genérica não diz nada sobre a outra conta.
            s.assertThat(naOutraConta.getResponse().getStatus()).isEqualTo(500);
            s.assertThat(corpo).doesNotContain(vendaA.toString())
                    .doesNotContain(tentativa.toString());
            s.assertThat(linhaDaParcela(tentativa)).contains(parcelaAntes);
            s.assertThat(parcelasDa(vendaB)).isEmpty();
            s.check(() -> verify(gateway, times(1)).criarCobranca(any(), any()));
        });
        assertThat(consultar(daConta.conta(), vendaA)).isEqualTo(aAntes);
        assertThat(consultar(daOutra.conta(), vendaB)).isEqualTo(bAntes);
    }

    @Test
    @DisplayName("duas reservas simultâneas com o mesmo id novo, na mesma conta: uma vale, a outra"
            + " falha")
    void reservasSimultaneasNaMesmaConta() {
        Balcao balcao = abrirBalcao("Cafeteria Aurora");
        disputar(new Reserva(balcao.conta(), vendaComUmItem(balcao)),
                new Reserva(balcao.conta(), vendaComUmItem(balcao)));
    }

    @Test
    @DisplayName("duas reservas simultâneas com o mesmo id novo, em contas diferentes: uma vale, a"
            + " outra falha")
    void reservasSimultaneasEmContasDiferentes() {
        Balcao daConta = abrirBalcao("Cafeteria Aurora");
        Balcao daOutra = abrirBalcao("Loja da Esquina");
        disputar(new Reserva(daConta.conta(), vendaComUmItem(daConta)),
                new Reserva(daOutra.conta(), vendaComUmItem(daOutra)));
    }

    /**
     * As duas reservas com o mesmo id novo, presas como descrito no topo da classe, e a conferência
     * do que sobrou: exatamente uma vale, a parcela é só da Venda dela, e a outra Venda fica como
     * estava, sem cobrança pedida por ela.
     */
    private void disputar(Reserva primeira, Reserva segunda) {
        UUID tentativa = UUID.randomUUID();
        cobrancasDevolvem("codigo-pix-da-disputa");
        List<Reserva> reservas = List.of(primeira, segunda);
        List<VendaParaTela> antes = reservas.stream()
                .map(reserva -> consultar(reserva.conta(), reserva.vendaId()))
                .toList();

        List<CompletableFuture<Pagamento>> disputa = comAsContasPresas(reservas, tentativa);
        aguardar(disputa);

        List<Integer> valeram = new ArrayList<>();
        for (int i = 0; i < disputa.size(); i++) {
            if (!disputa.get(i).isCompletedExceptionally()) {
                valeram.add(i);
            }
        }
        assertThat(valeram).hasSize(1);
        int vencedora = valeram.get(0);
        int perdedora = 1 - vencedora;
        Reserva venceu = reservas.get(vencedora);
        Reserva perdeu = reservas.get(perdedora);

        assertThat(disputa.get(perdedora)).failsWithin(ESPERA)
                .withThrowableOfType(ExecutionException.class)
                .withCauseInstanceOf(DataIntegrityViolationException.class);
        assertThat(linhaDaParcela(tentativa)).hasValueSatisfying(linha -> {
            assertThat(linha.vendaId()).isEqualTo(venceu.vendaId());
            assertThat(linha.contaId()).isEqualTo(venceu.conta().contaId().valor());
        });
        assertThat(consultar(venceu.conta(), venceu.vendaId()).parcelas()).singleElement()
                .satisfies(parcela -> {
                    assertThat(parcela.id()).isEqualTo(tentativa);
                    assertThat(parcela.status()).isEqualTo(StatusPagamento.PENDENTE);
                    assertThat(parcela.cobrancaPix().estado())
                            .isEqualTo(CobrancaPix.Estado.DISPONIVEL);
                });
        assertThat(parcelasDa(perdeu.vendaId())).isEmpty();
        assertThat(consultar(perdeu.conta(), perdeu.vendaId())).isEqualTo(antes.get(perdedora));
        verify(gateway, times(1)).criarCobranca(any(), any());
    }

    /**
     * Prende as linhas das contas numa transação que não as altera e começa cada reserva numa
     * thread própria, como quem a conta autenticou. A seguinte só começa quando as anteriores estão
     * paradas numa trava ou terminaram, e a transação só confirma quando todas estão assim.
     */
    private List<CompletableFuture<Pagamento>> comAsContasPresas(List<Reserva> reservas,
            UUID tentativa) {
        List<UUID> contas = reservas.stream()
                .map(reserva -> reserva.conta().contaId().valor())
                .distinct()
                .toList();
        ExecutorService threads = Executors.newFixedThreadPool(reservas.size());
        try {
            return reservas.get(0).conta().comoUsuario(() -> transacao.execute(status -> {
                jdbc.sql(PRENDE_AS_CONTAS).param("contas", contas).query(UUID.class).list();
                List<CompletableFuture<Pagamento>> iniciadas = new ArrayList<>();
                for (Reserva reserva : reservas) {
                    iniciadas.add(CompletableFuture.supplyAsync(() -> reserva.conta().comoUsuario(
                            () -> pix.cobrar(reserva.vendaId(), tentativa, VALOR)), threads));
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

    /**
     * Quantas conexões estão paradas esperando uma trava, de quem for: a segunda reserva espera a
     * primeira, e não a transação que prendeu as contas.
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

    private MvcResult pedirPix(ContaCriada conta, UUID vendaId, UUID tentativa) throws Exception {
        return http.perform(post("/api/vendas/{id}/pagamentos/pix", vendaId)
                        .with(autenticador.como(conta))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pedidoDePix(tentativa)))
                .andReturn();
    }

    private static String pedidoDePix(UUID tentativa) {
        return "{\"tentativaId\":\"" + tentativa + "\",\"valor\":12.50}";
    }

    /** O provedor simulado aceita a cobrança do valor da comanda e devolve este copia e cola. */
    private void cobrancasDevolvem(String copiaECola) {
        when(gateway.chaveRecebedora()).thenReturn("chave-de-teste");
        when(gateway.criarCobranca(any(), eq(VALOR))).thenReturn(copiaECola);
    }

    private VendaParaTela consultar(ContaCriada conta, UUID vendaId) {
        return conta.comoUsuario(() -> vendas.consultar(vendaId));
    }

    private Optional<LinhaDaParcela> linhaDaParcela(UUID parcelaId) {
        return jdbc.sql(LE_AS_PARCELAS + " where id = :id")
                .param("id", parcelaId)
                .query(TentativaPixDeOutraVendaTest::paraLinha)
                .optional();
    }

    private List<LinhaDaParcela> parcelasDa(UUID vendaId) {
        return jdbc.sql(LE_AS_PARCELAS + " where venda_id = :venda")
                .param("venda", vendaId)
                .query(TentativaPixDeOutraVendaTest::paraLinha)
                .list();
    }

    private static LinhaDaParcela paraLinha(ResultSet linha, int numero) throws SQLException {
        return new LinhaDaParcela(linha.getObject("id", UUID.class),
                linha.getObject("venda_id", UUID.class),
                linha.getObject("conta_id", UUID.class),
                linha.getString("forma"),
                linha.getBigDecimal("valor"),
                linha.getString("status"),
                linha.getBigDecimal("troco"),
                linha.getObject("criado_em", OffsetDateTime.class),
                linha.getString("pix_txid"),
                linha.getString("pix_chave_recebedora"),
                linha.getObject("pix_expira_em", OffsetDateTime.class),
                linha.getString("pix_copia_e_cola"),
                linha.getString("pix_estado"));
    }

    /** Uma conta com o caixa aberto e um produto de R$ 12,50, onde nascem as Vendas do cenário. */
    private record Balcao(ContaCriada conta, UUID sessaoId, UUID produtoId) {
    }

    /** Uma reserva da disputa: quem pede, e em que Venda. */
    private record Reserva(ContaCriada conta, UUID vendaId) {
    }

    /** A linha gravada da parcela, com a Venda e a conta a que ela pertence. */
    private record LinhaDaParcela(UUID id, UUID vendaId, UUID contaId, String forma,
            BigDecimal valor, String status, BigDecimal troco, OffsetDateTime criadoEm,
            String pixTxid, String pixChaveRecebedora, OffsetDateTime pixExpiraEm,
            String pixCopiaECola, String pixEstado) {
    }

    private Balcao abrirBalcao(String negocio) {
        ContaCriada conta = criador.criar(negocio, SENHA_DE_TESTE);
        return conta.comoUsuario(() -> {
            UUID produtoId = produtos.cadastrar(TipoProduto.PRODUTO,
                    new DadosDoProduto("Item Pix", VALOR, null, null, "un", null));
            return new Balcao(conta, caixas.abrir(Money.ZERO), produtoId);
        });
    }

    private UUID vendaComUmItem(Balcao balcao) {
        return balcao.conta().comoUsuario(() -> {
            UUID vendaId = vendas.iniciar(balcao.sessaoId());
            vendas.adicionarItem(vendaId, balcao.produtoId(), BigDecimal.ONE, Money.ZERO);
            return vendaId;
        });
    }
}
