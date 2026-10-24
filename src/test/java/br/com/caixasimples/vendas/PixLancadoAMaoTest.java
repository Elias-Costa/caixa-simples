package br.com.caixasimples.vendas;

import static br.com.caixasimples.sincronizacao.GestoDeTeste.conteudo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.verifyNoInteractions;
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
import br.com.caixasimples.contas.CriadorDeContaDeTeste.UsuarioCriado;
import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.pagamentos.StatusPagamento;
import br.com.caixasimples.pagamentos.domain.PixGateway;
import br.com.caixasimples.pagamentos.domain.SolicitacaoPagamento;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.TenantContext;
import br.com.caixasimples.sincronizacao.GestoDeTeste;
import br.com.caixasimples.sincronizacao.application.ResultadoDaOperacao;
import br.com.caixasimples.sincronizacao.application.ResultadoDaOperacao.Resultado;
import br.com.caixasimples.sincronizacao.application.SincronizacaoService;
import br.com.caixasimples.vendas.application.VendaService;
import br.com.caixasimples.vendas.application.VendaService.ParcelaParaTela;
import br.com.caixasimples.vendas.application.VendaService.VendaParaTela;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * O Pix que o operador lança à mão, depois de ver o dinheiro no extrato: confirmado na hora, sem
 * cobrança e sem o provedor, no plano gratuito, com rede e pelo lote do aparelho sem rede.
 *
 * <p>O provedor é um dublê sem resposta nenhuma: qualquer chamada a ele aparece na verificação, e
 * nenhum destes caminhos pode chegar lá.
 */
class PixLancadoAMaoTest extends TesteDeIntegracao {

    private static final String SENHA = "uma senha longa de teste";

    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired CriadorDeContaDeTeste criador;
    @Autowired AutenticadorDeTeste autenticador;
    @Autowired ProdutoService produtos;
    @Autowired SessaoCaixaService caixas;
    @Autowired VendaService vendas;
    @Autowired SincronizacaoService sincronizacao;
    @MockitoBean PixGateway gateway;

    @AfterEach
    void limparContexto() {
        TenantContext.limpar();
    }

    private record Balcao(ContaCriada conta, UUID sessaoId, UUID produtoId) {
    }

    /** Uma Conta no plano gratuito, como nasce, com o caixa aberto e um produto de R$ 20,00. */
    private Balcao abrirBalcao(String nome) {
        ContaCriada conta = criador.criar(nome, SENHA);
        UUID sessaoId = conta.comoUsuario(() -> caixas.abrir(Money.ZERO));
        UUID produtoId = conta.comoUsuario(() -> produtos.cadastrar(TipoProduto.PRODUTO,
                new DadosDoProduto("Café", Money.de("20.00"), null, null, "un", null)));
        return new Balcao(conta, sessaoId, produtoId);
    }

    private UUID comanda(Balcao balcao) {
        return balcao.conta().comoUsuario(() -> {
            UUID vendaId = vendas.iniciar(balcao.sessaoId());
            vendas.adicionarItem(vendaId, balcao.produtoId(), BigDecimal.ONE, Money.ZERO);
            return vendaId;
        });
    }

    private static SolicitacaoPagamento pix(String valor) {
        return SolicitacaoPagamento.de(FormaPagamento.PIX, Money.de(valor));
    }

    private VendaParaTela consultar(Balcao balcao, UUID vendaId) {
        return balcao.conta().comoUsuario(() -> vendas.consultar(vendaId));
    }

    @Test
    @DisplayName("pela rota, no plano gratuito, o Pix nasce confirmado, sem cobrança, e conclui a Venda com outra forma")
    void rotaConcluiNoGratuito() throws Exception {
        Balcao balcao = abrirBalcao("Padaria Aurora");
        UUID vendaId = comanda(balcao);
        UUID parcelaPix = UUID.randomUUID();

        http.perform(post("/api/vendas/{id}/pagamentos", vendaId)
                        .with(autenticador.como(balcao.conta()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pagamentoId\":\"" + parcelaPix
                                + "\",\"forma\":\"PIX\",\"valor\":12.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.troco").value(0.0));
        balcao.conta().comoUsuario(() -> {
            vendas.registrarPagamentoOnline(vendaId, UUID.randomUUID(),
                    SolicitacaoPagamento.emDinheiro(Money.de("8.00"), Money.de("10.00")), null);
            vendas.concluir(vendaId);
        });

        VendaParaTela venda = consultar(balcao, vendaId);
        assertThat(venda.status()).isEqualTo(StatusVenda.CONCLUIDA);
        ParcelaParaTela parcela = venda.parcelas().stream()
                .filter(p -> p.id().equals(parcelaPix)).findFirst().orElseThrow();
        assertThat(parcela.forma()).isEqualTo(FormaPagamento.PIX);
        assertThat(parcela.status()).isEqualTo(StatusPagamento.CONFIRMADO);
        assertThat(parcela.cobrancaPix()).isNull();
        assertThat(parcela.troco()).isEqualTo(Money.ZERO);
        verifyNoInteractions(gateway);
    }

    @Test
    @DisplayName("o mesmo id com o mesmo valor responde como da primeira vez; com outro valor, ou com valor recebido, é recusado")
    void repeticaoIdempotenteEConteudoDiferenteRecusado() {
        Balcao balcao = abrirBalcao("Mercearia Aurora");
        UUID vendaId = comanda(balcao);
        UUID parcelaPix = UUID.randomUUID();

        balcao.conta().comoUsuario(() -> {
            vendas.registrarPagamentoOnline(vendaId, parcelaPix, pix("10.00"), null);
            vendas.registrarPagamentoOnline(vendaId, parcelaPix, pix("10.00"), null);
        });
        assertThatIllegalStateException()
                .isThrownBy(() -> balcao.conta().comoUsuario(() ->
                        vendas.registrarPagamentoOnline(vendaId, parcelaPix, pix("9.00"), null)))
                .withMessageContaining("outro valor ou forma");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> balcao.conta().comoUsuario(() ->
                        vendas.registrarPagamentoOnline(vendaId, UUID.randomUUID(),
                                new SolicitacaoPagamento(FormaPagamento.PIX, Money.de("1.00"),
                                        Money.de("1.00")), null)))
                .withMessageContaining("troco");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> balcao.conta().comoUsuario(() ->
                        vendas.registrarPagamentoOnline(vendaId, UUID.randomUUID(),
                                pix("1.00"), "004512")))
                .withMessageContaining("O NSU só existe no");

        assertThat(consultar(balcao, vendaId).parcelas())
                .extracting(ParcelaParaTela::id).containsExactly(parcelaPix);
        verifyNoInteractions(gateway);
    }

    @Test
    @DisplayName("o Pix à mão e a cobrança integrada não se confundem pelo id, em nenhum dos dois sentidos")
    void pixAMaoECobrancaNaoSeMisturam() {
        Balcao balcao = abrirBalcao("Empório Aurora");
        UUID vendaId = comanda(balcao);
        UUID aMao = UUID.randomUUID();
        UUID reservada = UUID.randomUUID();
        balcao.conta().comoUsuario(() -> {
            vendas.registrarPagamentoOnline(vendaId, aMao, pix("5.00"), null);
            vendas.reservarPix(vendaId, reservada, Money.de("5.00"), "chave-teste");
        });

        // A raiz recusa reservar uma cobrança sobre a parcela que o operador lançou.
        assertThatIllegalStateException()
                .isThrownBy(() -> balcao.conta().comoUsuario(() ->
                        vendas.reservarPix(vendaId, aMao, Money.de("5.00"), "chave-teste")))
                .withMessageContaining("já foi pedida com outro valor");
        // E a cobrança reservada não vira, pelo mesmo id e valor, um Pix visto no extrato.
        assertThatIllegalStateException()
                .isThrownBy(() -> balcao.conta().comoUsuario(() ->
                        vendas.registrarPagamentoOnline(vendaId, reservada, pix("5.00"), null)))
                .withMessageContaining("outro valor ou forma");

        assertThat(consultar(balcao, vendaId).parcelas())
                .extracting(ParcelaParaTela::id, ParcelaParaTela::status)
                .containsExactlyInAnyOrder(
                        tuple(aMao, StatusPagamento.CONFIRMADO),
                        tuple(reservada, StatusPagamento.PENDENTE));
    }

    @Test
    @DisplayName("cancelar a Venda concluída com Pix à mão só marca a Venda, sem chamar o provedor")
    void cancelamentoSoMarca() {
        Balcao balcao = abrirBalcao("Quitanda Aurora");
        UUID vendaId = comanda(balcao);
        balcao.conta().comoUsuario(() -> {
            vendas.registrarPagamentoOnline(vendaId, UUID.randomUUID(), pix("20.00"), null);
            vendas.concluir(vendaId);
            vendas.cancelar(vendaId);
        });

        VendaParaTela venda = consultar(balcao, vendaId);
        assertThat(venda.status()).isEqualTo(StatusVenda.CANCELADA);
        assertThat(venda.parcelas()).singleElement()
                .satisfies(parcela -> assertThat(parcela.status())
                        .isEqualTo(StatusPagamento.CONFIRMADO));
        verifyNoInteractions(gateway);
    }

    @Test
    @DisplayName("pelo lote do aparelho sem rede, o operador lança o Pix à mão e conclui a própria Venda")
    void loteDoOperadorConclui() {
        Balcao balcao = abrirBalcao("Lanchonete Aurora");
        UsuarioCriado operador = criador.criarOperadorEm(balcao.conta().contaId(), "Rui");
        UUID sessaoId = UUID.randomUUID();
        UUID vendaId = UUID.randomUUID();
        GestoDeTeste abertura = GestoDeTeste.abertura(sessaoId, "0");
        GestoDeTeste inicio = GestoDeTeste.inicio(vendaId, sessaoId, abertura);
        GestoDeTeste item = GestoDeTeste.item(vendaId, UUID.randomUUID(), balcao.produtoId(), "1",
                "20.00", inicio);
        UUID parcelaPix = UUID.randomUUID();
        GestoDeTeste pix = GestoDeTeste.de("venda.registrarPagamento", vendaId,
                conteudo("pagamentoId", parcelaPix, "forma", "PIX", "valor",
                        new BigDecimal("20.00"), "valorRecebido", null), item);
        GestoDeTeste conclusao = GestoDeTeste.conclusao(vendaId, pix);
        List<GestoDeTeste> gestos = List.of(abertura, inicio, item, pix, conclusao);

        List<ResultadoDaOperacao> resultados = operador.comoUsuario(() ->
                sincronizacao.sincronizar(gestos.stream().map(g -> g.recebida(json)).toList()));

        assertThat(resultados).extracting(ResultadoDaOperacao::resultado)
                .containsOnly(Resultado.APLICADA);
        VendaParaTela venda = operador.comoUsuario(() -> vendas.consultar(vendaId));
        assertThat(venda.status()).isEqualTo(StatusVenda.CONCLUIDA);
        assertThat(venda.parcelas()).singleElement().satisfies(parcela -> {
            assertThat(parcela.id()).isEqualTo(parcelaPix);
            assertThat(parcela.status()).isEqualTo(StatusPagamento.CONFIRMADO);
            assertThat(parcela.cobrancaPix()).isNull();
        });
        verifyNoInteractions(gateway);
    }
}
