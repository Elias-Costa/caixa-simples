package br.com.caixasimples.sincronizacao;

import static br.com.caixasimples.sincronizacao.GestoDeTeste.abertura;
import static br.com.caixasimples.sincronizacao.GestoDeTeste.conclusao;
import static br.com.caixasimples.sincronizacao.GestoDeTeste.conteudo;
import static br.com.caixasimples.sincronizacao.GestoDeTeste.inicio;
import static br.com.caixasimples.sincronizacao.GestoDeTeste.item;
import static br.com.caixasimples.sincronizacao.GestoDeTeste.parcelaNoCartao;
import static br.com.caixasimples.sincronizacao.GestoDeTeste.sangria;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.cadastro.TipoProduto;
import br.com.caixasimples.cadastro.application.ProdutoService;
import br.com.caixasimples.cadastro.application.ProdutoService.DadosDoProduto;
import br.com.caixasimples.cadastro.internal.ProdutoRepository;
import br.com.caixasimples.caixa.TipoMovimentoCaixa;
import br.com.caixasimples.caixa.application.SessaoCaixaService;
import br.com.caixasimples.caixa.domain.MovimentoCaixa;
import br.com.caixasimples.caixa.internal.SessaoCaixaRepository;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.UsuarioCriado;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.TenantContext;
import br.com.caixasimples.shared.UsuarioContext;
import br.com.caixasimples.sincronizacao.application.ResultadoDaOperacao;
import br.com.caixasimples.sincronizacao.application.ResultadoDaOperacao.Resultado;
import br.com.caixasimples.sincronizacao.application.SincronizacaoService;
import br.com.caixasimples.sincronizacao.internal.OperacaoSincronizadaRepository;
import br.com.caixasimples.vendas.StatusVenda;
import br.com.caixasimples.vendas.application.VendaService;
import br.com.caixasimples.vendas.domain.ItemVenda;
import br.com.caixasimples.vendas.domain.Venda;
import br.com.caixasimples.vendas.internal.VendaEntity;
import br.com.caixasimples.vendas.internal.VendaRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

/**
 * As regras do lote que não dependem de HTTP: reenvio simultâneo, dependências, revisão e
 * recusa, com a conta e o usuário no contexto como o filtro de autenticação deixaria.
 */
class SincronizacaoServiceTest extends TesteDeIntegracao {

    private static final String SENHA = "uma senha longa de teste";

    @Autowired SincronizacaoService sincronizacao;
    @Autowired OperacaoSincronizadaRepository registros;
    @Autowired ObjectMapper json;
    @Autowired CriadorDeContaDeTeste criador;
    @Autowired ProdutoService produtos;
    @Autowired ProdutoRepository linhasDeProduto;
    @Autowired SessaoCaixaService sessoes;
    @Autowired SessaoCaixaRepository linhasDeSessao;
    @Autowired VendaService vendas;
    @Autowired VendaRepository linhasDeVenda;

    @AfterEach
    void limparContexto() {
        TenantContext.limpar();
        UsuarioContext.limpar();
    }

    @Test
    @DisplayName("o mesmo lote enviado junto por duas requisições aplica cada efeito uma vez só")
    void envioSimultaneoNaoDuplica() throws Exception {
        ContaCriada conta = criador.criar("Cafeteria Aurora", SENHA);
        UUID sessaoId = conta.comoUsuario(() -> sessoes.abrir(Money.de("50.00")));
        GestoDeTeste produto = GestoDeTeste.de("produto.criar", UUID.randomUUID(),
                SincronizacaoHttpTest.produto("Pao de mel", "6.00", null));
        GestoDeTeste cliente = GestoDeTeste.de("cliente.criar", UUID.randomUUID(),
                conteudo("nome", "Joana", "contato", null));
        GestoDeTeste suprimento = GestoDeTeste.de("caixa.suprir", sessaoId,
                conteudo("valor", new BigDecimal("15.00"), "motivo", "Troco",
                        "criadoEm", Instant.now().toString())).comVersaoBase(0);
        List<GestoDeTeste> lote = List.of(produto, cliente, suprimento);

        CountDownLatch largada = new CountDownLatch(1);
        ExecutorService duasRequisicoes = Executors.newFixedThreadPool(2);
        try {
            Supplier<List<ResultadoDaOperacao>> envio = () -> {
                try {
                    largada.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return conta.comoUsuario(() -> sincronizacao.sincronizar(recebidas(lote)));
            };
            Future<List<ResultadoDaOperacao>> primeira = duasRequisicoes.submit(envio::get);
            Future<List<ResultadoDaOperacao>> segunda = duasRequisicoes.submit(envio::get);
            largada.countDown();

            for (Future<List<ResultadoDaOperacao>> resposta : List.of(primeira, segunda)) {
                // Cada uma aplica, devolve a gravação da outra ou pede reenvio; nunca recusa.
                assertThat(resposta.get(30, TimeUnit.SECONDS))
                        .extracting(ResultadoDaOperacao::resultado)
                        .allMatch(resultado -> resultado == Resultado.APLICADA
                                || resultado == Resultado.ERRO_TRANSITORIO);
            }
        } finally {
            duasRequisicoes.shutdownNow();
        }

        // O reenvio de depois, que o dispositivo faz para os transitórios, encontra tudo aplicado.
        assertThat(conta.comoUsuario(() -> sincronizacao.sincronizar(recebidas(lote))))
                .extracting(ResultadoDaOperacao::resultado)
                .containsOnly(Resultado.APLICADA);
        conta.comoUsuario(() -> {
            assertThat(linhasDeProduto.findAll()).hasSize(1);
            assertThat(linhasDeSessao.findById(sessaoId).orElseThrow().paraDominio()
                    .getMovimentos()).extracting(MovimentoCaixa::tipo)
                    .containsExactly(TipoMovimentoCaixa.SUPRIMENTO);
            assertThat(registros.findAll()).hasSize(3);
        });
    }

    @Test
    @DisplayName("sessão fechada pelo ADMIN antes da Venda chegar: a Venda fica visível e não aplicada")
    void vendaQueChegaDepoisDoFechamento() {
        ContaCriada conta = criador.criar("Mercearia Aurora", SENHA);
        UsuarioCriado operador = criador.criarOperadorEm(conta.contaId(), "Operador da manha");
        UUID produtoId = cadastrar(conta, "Arroz", "7.00");
        UUID sessaoId = operador.comoUsuario(() -> sessoes.abrir(Money.ZERO));
        // O ADMIN fecha com rede o caixa em que o dispositivo do operador vendeu sem rede.
        conta.comoUsuario(() -> sessoes.fechar(sessaoId, Money.ZERO));

        UUID vendaId = UUID.randomUUID();
        GestoDeTeste inicio = inicio(vendaId, sessaoId);
        GestoDeTeste item = item(vendaId, UUID.randomUUID(), produtoId, "1", "7.00", inicio);
        GestoDeTeste parcela = parcelaNoCartao(vendaId, "7.00", item);
        GestoDeTeste conclusao = conclusao(vendaId, parcela);

        List<ResultadoDaOperacao> resultados = operador.comoUsuario(() ->
                sincronizacao.sincronizar(recebidas(List.of(inicio, item, parcela, conclusao))));

        assertThat(resultados).extracting(ResultadoDaOperacao::resultado)
                .containsOnly(Resultado.NAO_APLICADA);
        assertThat(resultados.get(0).detalhe()).contains("nao esta ABERTA");
        assertThat(resultados.get(1).detalhe()).contains("gesto anterior deste registro");
        conta.comoUsuario(() -> assertThat(linhasDeVenda.findById(vendaId)).isEmpty());
    }

    @Test
    @DisplayName("Venda recusada não prende a sessão: a sangria de outro registro é tentada e o fechamento entra")
    void outroRegistroSoOrdena() {
        ContaCriada conta = criador.criar("Armazém da Praça", SENHA);
        UUID sessaoId = UUID.randomUUID();
        UUID vendaId = UUID.randomUUID();
        UUID produtoQueNaoExiste = UUID.randomUUID();
        GestoDeTeste abertura = abertura(sessaoId, "50.00");
        GestoDeTeste inicio = inicio(vendaId, sessaoId, abertura);
        GestoDeTeste item = item(vendaId, UUID.randomUUID(), produtoQueNaoExiste, "1", "5.00",
                inicio);
        GestoDeTeste parcela = parcelaNoCartao(vendaId, "5.00", item);
        GestoDeTeste conclusao = conclusao(vendaId, parcela, abertura);
        GestoDeTeste sangria = sangria(sessaoId, "10.00", conclusao).comVersaoBase(0);
        GestoDeTeste fechamento = GestoDeTeste.de("caixa.fechar", sessaoId,
                conteudo("valorContado", new BigDecimal("40.00"), "fechadaEm",
                        Instant.now().toString()),
                abertura, inicio, item, parcela, conclusao, sangria).comVersaoBase(1);

        List<ResultadoDaOperacao> resultados = conta.comoUsuario(() ->
                sincronizacao.sincronizar(recebidas(List.of(abertura, inicio, item, parcela,
                        conclusao, sangria, fechamento))));

        assertThat(resultados).extracting(ResultadoDaOperacao::resultado).containsExactly(
                Resultado.APLICADA, Resultado.APLICADA, Resultado.NAO_APLICADA,
                Resultado.NAO_APLICADA, Resultado.NAO_APLICADA, Resultado.APLICADA,
                Resultado.APLICADA);
        assertThat(resultados.get(2).detalhe()).contains("produto nao encontrado");
        conta.comoUsuario(() -> {
            var sessao = linhasDeSessao.findById(sessaoId).orElseThrow().paraDominio();
            assertThat(sessao.getValorFechamentoEsperado()).isEqualTo(Money.de("40.00"));
            assertThat(sessao.getDiferenca()).isEqualTo(Money.ZERO);
            assertThat(linhasDeVenda.findById(vendaId).orElseThrow().paraDominio().getStatus())
                    .as("a comanda fica ABERTA no servidor, visível para o ADMIN resolver")
                    .isEqualTo(StatusVenda.ABERTA);
        });
    }

    @Test
    @DisplayName("dependência que ainda não chegou deixa o gesto para depois, sem gravar nada")
    void dependenciaAusenteETransitoria() {
        ContaCriada conta = criador.criar("Loja da Esquina", SENHA);
        UUID sessaoId = UUID.randomUUID();
        GestoDeTeste abertura = abertura(sessaoId, "0");
        GestoDeTeste inicio = inicio(UUID.randomUUID(), sessaoId, abertura);

        List<ResultadoDaOperacao> sozinho = conta.comoUsuario(() ->
                sincronizacao.sincronizar(recebidas(List.of(inicio))));

        assertThat(sozinho.get(0).resultado()).isEqualTo(Resultado.ERRO_TRANSITORIO);
        conta.comoUsuario(() ->
                assertThat(registros.findByOperacaoId(inicio.operacaoId())).isEmpty());

        List<ResultadoDaOperacao> juntos = conta.comoUsuario(() ->
                sincronizacao.sincronizar(recebidas(List.of(abertura, inicio))));
        assertThat(juntos).extracting(ResultadoDaOperacao::resultado)
                .containsOnly(Resultado.APLICADA);
    }

    @Test
    @DisplayName("preço visto diferente do vigente: o item fica com o que foi cobrado e vai para revisão")
    void precoDivergenteVaiParaRevisao() {
        ContaCriada conta = criador.criar("Cafe do Porto", SENHA);
        UUID produtoId = cadastrar(conta, "Cafe", "10.00");
        UUID sessaoId = conta.comoUsuario(() -> sessoes.abrir(Money.ZERO));
        UUID vendaId = UUID.randomUUID();
        GestoDeTeste inicio = inicio(vendaId, sessaoId);
        GestoDeTeste item = item(vendaId, UUID.randomUUID(), produtoId, "2", "8.50", inicio);

        List<ResultadoDaOperacao> resultados = conta.comoUsuario(() ->
                sincronizacao.sincronizar(recebidas(List.of(inicio, item))));

        assertThat(resultados.get(1).resultado()).isEqualTo(Resultado.APLICADA_COM_REVISAO);
        assertThat(resultados.get(1).detalhe()).contains("8.50").contains("10.00");
        conta.comoUsuario(() -> {
            Venda venda = linhasDeVenda.findById(vendaId).orElseThrow().paraDominio();
            assertThat(venda.getItens()).extracting(ItemVenda::precoUnitario)
                    .containsExactly(Money.de("8.50"));
            assertThat(venda.getValorTotal()).isEqualTo(Money.de("17.00"));
        });
    }

    @Test
    @DisplayName("produto inativado antes da sincronização: o item não entra sem o ADMIN")
    void produtoInativoRecusaOItem() {
        ContaCriada conta = criador.criar("Bazar Aurora", SENHA);
        UUID produtoId = cadastrar(conta, "Vela", "3.00");
        conta.comoUsuario(() -> produtos.inativar(produtoId));
        UUID sessaoId = conta.comoUsuario(() -> sessoes.abrir(Money.ZERO));
        UUID vendaId = UUID.randomUUID();
        GestoDeTeste inicio = inicio(vendaId, sessaoId);
        GestoDeTeste item = item(vendaId, UUID.randomUUID(), produtoId, "1", "3.00", inicio);

        List<ResultadoDaOperacao> resultados = conta.comoUsuario(() ->
                sincronizacao.sincronizar(recebidas(List.of(inicio, item))));

        assertThat(resultados.get(1).resultado()).isEqualTo(Resultado.NAO_APLICADA);
        assertThat(resultados.get(1).detalhe()).contains("inativado");
        conta.comoUsuario(() -> assertThat(linhasDeVenda.findById(vendaId).orElseThrow()
                .paraDominio().getItens()).isEmpty());
    }

    @Test
    @DisplayName("relógio do dispositivo adiantado além da tolerância: aplicado com revisão e o instante mantido")
    void relogioAdiantadoVaiParaRevisao() {
        ContaCriada conta = criador.criar("Papelaria Aurora", SENHA);
        Instant adiantado = Instant.now().plus(10, ChronoUnit.MINUTES)
                .truncatedTo(ChronoUnit.MILLIS);
        GestoDeTeste longe = GestoDeTeste.de("cliente.criar", UUID.randomUUID(),
                conteudo("nome", "Rita", "contato", null)).registradoEm(adiantado);
        GestoDeTeste perto = GestoDeTeste.de("cliente.criar", UUID.randomUUID(),
                conteudo("nome", "Rui", "contato", null))
                .registradoEm(Instant.now().plus(2, ChronoUnit.MINUTES));

        List<ResultadoDaOperacao> resultados = conta.comoUsuario(() ->
                sincronizacao.sincronizar(recebidas(List.of(longe, perto))));

        assertThat(resultados.get(0).resultado()).isEqualTo(Resultado.APLICADA_COM_REVISAO);
        assertThat(resultados.get(0).detalhe()).contains("relogio do dispositivo");
        assertThat(resultados.get(1).resultado())
                .as("dentro dos cinco minutos, o erro normal de relógio não vira revisão")
                .isEqualTo(Resultado.APLICADA);
        conta.comoUsuario(() -> assertThat(registros.findByOperacaoId(longe.operacaoId())
                .orElseThrow().getCriadaEm()).isEqualTo(adiantado));
    }

    @Test
    @DisplayName("duas Vendas sem rede do último item: as duas entram, o saldo fica negativo e a segunda vai para revisão")
    void duasVendasDoUltimoItem() {
        ContaCriada conta = criador.criar("Mercadinho do Bairro", SENHA);
        criador.habilitarEstoque(conta.contaId());
        UsuarioCriado operador = criador.criarOperadorEm(conta.contaId(), "Caixa dois");
        UUID produtoId = cadastrar(conta, "Queijo", "30.00");
        conta.comoUsuario(() -> produtos.ajustarEstoque(produtoId, BigDecimal.ONE, "Contagem"));
        List<GestoDeTeste> doOperador = vendaDeUmItem(produtoId);
        List<GestoDeTeste> doAdmin = vendaDeUmItem(produtoId);

        List<ResultadoDaOperacao> primeira = operador.comoUsuario(() ->
                sincronizacao.sincronizar(recebidas(doOperador)));
        List<ResultadoDaOperacao> segunda = conta.comoUsuario(() ->
                sincronizacao.sincronizar(recebidas(doAdmin)));

        assertThat(primeira).extracting(ResultadoDaOperacao::resultado)
                .containsOnly(Resultado.APLICADA);
        assertThat(segunda.get(4).resultado()).isEqualTo(Resultado.APLICADA_COM_REVISAO);
        assertThat(segunda.get(4).detalhe()).contains("estoque negativo").contains("Queijo");
        conta.comoUsuario(() -> assertThat(saldoDe(produtoId)).isEqualByComparingTo("-1"));

        // O reenvio devolve a mesma revisão e não baixa de novo.
        assertThat(conta.comoUsuario(() -> sincronizacao.sincronizar(recebidas(doAdmin))))
                .isEqualTo(segunda);
        conta.comoUsuario(() -> assertThat(saldoDe(produtoId)).isEqualByComparingTo("-1"));
    }

    @Test
    @DisplayName("id de Venda ou de item já usado nesta Conta é recusado e o registro existente não muda")
    void idJaUsadoNaoSobrescreve() {
        ContaCriada conta = criador.criar("Quitanda Aurora", SENHA);
        UUID produtoId = cadastrar(conta, "Banana", "5.00");
        UUID sessaoId = conta.comoUsuario(() -> sessoes.abrir(Money.ZERO));
        UUID vendaComRede = conta.comoUsuario(() -> vendas.iniciar(sessaoId));
        UUID itemComRede = conta.comoUsuario(() -> vendas.adicionarItem(vendaComRede, produtoId,
                new BigDecimal("3"), Money.ZERO));

        GestoDeTeste mesmaVenda = inicio(vendaComRede, sessaoId);
        UUID outraVenda = UUID.randomUUID();
        GestoDeTeste outroInicio = inicio(outraVenda, sessaoId);
        GestoDeTeste mesmoItem = item(outraVenda, itemComRede, produtoId, "1", "5.00",
                outroInicio);

        List<ResultadoDaOperacao> resultados = conta.comoUsuario(() ->
                sincronizacao.sincronizar(recebidas(List.of(mesmaVenda, outroInicio, mesmoItem))));

        assertThat(resultados).extracting(ResultadoDaOperacao::resultado).containsExactly(
                Resultado.NAO_APLICADA, Resultado.APLICADA, Resultado.NAO_APLICADA);
        assertThat(resultados.get(0).detalhe()).contains("ja existe venda");
        assertThat(resultados.get(2).detalhe()).contains("ja existe item");
        conta.comoUsuario(() -> {
            Venda original = linhasDeVenda.findById(vendaComRede).map(VendaEntity::paraDominio)
                    .orElseThrow();
            assertThat(original.getItens()).extracting(ItemVenda::id)
                    .containsExactly(itemComRede);
            assertThat(original.getValorTotal()).isEqualTo(Money.de("15.00"));
        });
    }

    /** Uma Venda de uma unidade, paga no cartão, na sessão aberta pelo próprio dispositivo. */
    private static List<GestoDeTeste> vendaDeUmItem(UUID produtoId) {
        UUID sessaoId = UUID.randomUUID();
        UUID vendaId = UUID.randomUUID();
        GestoDeTeste abertura = abertura(sessaoId, "0");
        GestoDeTeste inicio = inicio(vendaId, sessaoId, abertura);
        GestoDeTeste item = item(vendaId, UUID.randomUUID(), produtoId, "1", "30.00", inicio);
        GestoDeTeste parcela = parcelaNoCartao(vendaId, "30.00", item);
        return List.of(abertura, inicio, item, parcela, conclusao(vendaId, parcela, abertura));
    }

    private List<OperacaoRecebida> recebidas(List<GestoDeTeste> gestos) {
        return gestos.stream().map(gesto -> gesto.recebida(json)).toList();
    }

    private UUID cadastrar(ContaCriada conta, String nome, String preco) {
        return conta.comoUsuario(() -> produtos.cadastrar(TipoProduto.PRODUTO,
                new DadosDoProduto(nome, Money.de(preco), null, null, "un", Map.of())));
    }

    private BigDecimal saldoDe(UUID produtoId) {
        return linhasDeProduto.findById(produtoId).orElseThrow().paraDominio().getEstoqueAtual();
    }
}
