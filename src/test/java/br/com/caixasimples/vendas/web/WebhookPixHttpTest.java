package br.com.caixasimples.vendas.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.cadastro.TipoProduto;
import br.com.caixasimples.cadastro.application.ProdutoService;
import br.com.caixasimples.cadastro.application.ProdutoService.DadosDoProduto;
import br.com.caixasimples.cadastro.internal.ProdutoRepository;
import br.com.caixasimples.caixa.application.SessaoCaixaService;
import br.com.caixasimples.caixa.internal.SessaoCaixaRepository;
import br.com.caixasimples.caixa.TipoMovimentoCaixa;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.contas.AutenticadorDeTeste;
import br.com.caixasimples.pagamentos.StatusPagamento;
import br.com.caixasimples.pagamentos.domain.SolicitacaoPagamento;
import br.com.caixasimples.pagamentos.domain.ConsultaPix;
import br.com.caixasimples.pagamentos.domain.PixGateway;
import br.com.caixasimples.pagamentos.domain.WebhookPixAutenticador;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.vendas.StatusVenda;
import br.com.caixasimples.vendas.VendaConcluida;
import br.com.caixasimples.vendas.application.VendaPixService;
import br.com.caixasimples.vendas.application.VendaService;
import br.com.caixasimples.vendas.internal.VendaRepository;
import java.math.BigDecimal;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import org.springframework.transaction.support.TransactionTemplate;

/** Reconsulta, isolamento, reentrega e transição financeira da raiz. */
@RecordApplicationEvents
class WebhookPixHttpTest extends TesteDeIntegracao {
    @Autowired MockMvc http;
    @Autowired AutenticadorDeTeste autenticadorHttp;
    @Autowired CriadorDeContaDeTeste criador;
    @Autowired ProdutoService produtos;
    @Autowired ProdutoRepository produtosNoBanco;
    @Autowired SessaoCaixaService caixas;
    @Autowired SessaoCaixaRepository sessoes;
    @Autowired VendaService vendas;
    @Autowired VendaPixService pix;
    @Autowired VendaRepository linhas;
    @Autowired TransactionTemplate transacoes;
    @MockitoBean PixGateway gateway;
    @MockitoBean WebhookPixAutenticador autenticador;

    @Test
    void autenticaReconsultaEConcluiUmaVezSemAtravessarConta(ApplicationEvents eventos)
            throws Exception {
        Cenario a = criar("Webhook A");
        ContaCriada b = criador.criar("Webhook B", "uma senha longa de teste");
        when(autenticador.autenticar("config-a", "segredo-a")).thenReturn(a.conta.contaId());
        when(autenticador.autenticar("config-b", "segredo-b")).thenReturn(b.contaId());
        when(gateway.consultar(any())).thenReturn(new ConsultaPix(a.txid(), "chave-teste",
                Money.de("12.50"), true));

        http.perform(post("/api/webhooks/pix/config-a/pix?hmac=segredo-a")
                .contentType(MediaType.APPLICATION_JSON).content(aviso(a.txid())))
                .andExpect(status().isForbidden());
        http.perform(post("/api/webhooks/pix/config-a?hmac=segredo-errado")
                .secure(true).requestAttr("jakarta.servlet.request.X509Certificate", certificado())
                .contentType(MediaType.APPLICATION_JSON).content(aviso(a.txid())))
                .andExpect(status().isForbidden());
        http.perform(callback("config-b", "segredo-b", a.txid()))
                .andExpect(status().isNoContent());
        http.perform(callback("config-a", "segredo-a", UUID.randomUUID().toString().replace("-", "")))
                .andExpect(status().isNoContent());
        verify(gateway, never()).consultar(any());
        a.conta.comoUsuario(() -> assertThat(vendas.consultar(a.venda).status())
                .isEqualTo(StatusVenda.ABERTA));

        when(gateway.consultar(any())).thenReturn(new ConsultaPix(a.txid(), "chave-teste",
                Money.de("12.00"), true));
        http.perform(callback("config-a", "segredo-a", a.txid()))
                .andExpect(status().isServiceUnavailable());
        a.conta.comoUsuario(() -> assertThat(vendas.consultar(a.venda).status())
                .isEqualTo(StatusVenda.ABERTA));

        when(gateway.consultar(any())).thenReturn(new ConsultaPix(a.txid(), "chave-teste",
                Money.de("12.50"), true));
        http.perform(callback("config-a", "segredo-a", a.txid()))
                .andExpect(status().isNoContent());
        http.perform(callback("config-a", "segredo-a", a.txid()))
                .andExpect(status().isNoContent());
        a.conta.comoUsuario(() -> {
            var venda = linhas.findById(a.venda).orElseThrow().paraDominio();
            assertThat(venda.getStatus()).isEqualTo(StatusVenda.CONCLUIDA);
            assertThat(venda.getPagamentos()).singleElement()
                    .satisfies(p -> assertThat(p.status()).isEqualTo(StatusPagamento.CONFIRMADO));
        });
        assertThat(eventos.stream(VendaConcluida.class)).hasSize(1);
    }

    @Test
    void sessaoFechadaEComandaCanceladaGuardamPixSemConcluir(ApplicationEvents eventos)
            throws Exception {
        Cenario fechada = criar("Pix apos fechamento");
        fechada.conta.comoUsuario(() -> caixas.fechar(fechada.sessao, Money.ZERO));
        when(gateway.consultar(any())).thenAnswer(chamada -> {
            var cobranca = chamada.getArgument(0, br.com.caixasimples.pagamentos.domain.CobrancaPix.class);
            return new ConsultaPix(cobranca.txid(), cobranca.chaveRecebedora(),
                    Money.de("12.50"), true);
        });
        pix.receberNotificacao(fechada.conta.contaId(), fechada.txid());
        fechada.conta.comoUsuario(() -> {
            var venda = linhas.findById(fechada.venda).orElseThrow().paraDominio();
            assertThat(venda.getStatus()).isEqualTo(StatusVenda.ABERTA);
            assertThat(venda.getPagamentos().getFirst().status())
                    .isEqualTo(StatusPagamento.CONFIRMADO);
        });

        Cenario cancelada = criar("Pix apos cancelamento");
        cancelada.conta.comoUsuario(() -> transacoes.executeWithoutResult(estado -> {
            var linha = linhas.findById(cancelada.venda).orElseThrow();
            var venda = linha.paraDominio();
            venda.cancelar();
            linha.atualizarCom(venda);
            linhas.save(linha);
        }));
        pix.receberNotificacao(cancelada.conta.contaId(), cancelada.txid());
        cancelada.conta.comoUsuario(() -> {
            var venda = linhas.findById(cancelada.venda).orElseThrow().paraDominio();
            assertThat(venda.getStatus()).isEqualTo(StatusVenda.CANCELADA);
            assertThat(venda.getPagamentos().getFirst().status())
                    .isEqualTo(StatusPagamento.CONFIRMADO);
        });
        assertThat(eventos.stream(VendaConcluida.class)).isEmpty();
        http.perform(get("/api/vendas/conciliacoes-pix")
                .with(autenticadorHttp.como(fechada.conta)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].vendaId").value(fechada.venda.toString()));
        http.perform(get("/api/vendas/conciliacoes-pix")
                .with(autenticadorHttp.como(cancelada.conta)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].vendaId").value(cancelada.venda.toString()));
        ContaCriada operador = criador.criar("Operador sem acesso", "uma senha longa de teste",
                br.com.caixasimples.shared.Perfil.OPERADOR, true);
        http.perform(get("/api/vendas/conciliacoes-pix")
                .with(autenticadorHttp.como(operador)))
                .andExpect(status().isForbidden());
    }

    @Test
    void callbacksSimultaneosNaoDuplicamBaixaDeEstoque() throws Exception {
        Cenario c = criar("Pix simultaneo");
        criador.habilitarEstoque(c.conta.contaId());
        c.conta.comoUsuario(() -> {
            UUID outro = produtos.cadastrar(TipoProduto.PRODUTO,
                    new DadosDoProduto("Dinheiro", Money.de("5.00"), null, null, "un", null));
            vendas.adicionarItem(c.venda, outro, BigDecimal.ONE, Money.ZERO);
            vendas.registrarPagamento(c.venda,
                    SolicitacaoPagamento.emDinheiro(Money.de("5.00"), Money.de("5.00")));
        });
        when(gateway.consultar(any())).thenReturn(new ConsultaPix(c.txid(), "chave-teste",
                Money.de("12.50"), true));
        CountDownLatch inicio = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var primeira = executor.submit(() -> {
                inicio.await();
                pix.receberNotificacao(c.conta.contaId(), c.txid());
                return null;
            });
            var segunda = executor.submit(() -> {
                inicio.await();
                pix.receberNotificacao(c.conta.contaId(), c.txid());
                return null;
            });
            inicio.countDown();
            primeira.get(20, TimeUnit.SECONDS);
            segunda.get(20, TimeUnit.SECONDS);
        }
        c.conta.comoUsuario(() -> assertThat(vendas.consultar(c.venda).status())
                .isEqualTo(StatusVenda.CONCLUIDA));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> c.conta.comoUsuario(() ->
                assertThat(produtosNoBanco.findById(c.produto).orElseThrow()
                        .paraDominio().getEstoqueAtual()).isEqualByComparingTo("-1")));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> c.conta.comoUsuario(() -> {
            var movimentos = sessoes.findById(c.sessao).orElseThrow()
                    .paraDominio().getMovimentos();
            assertThat(movimentos).singleElement().satisfies(movimento -> {
                assertThat(movimento.tipo()).isEqualTo(TipoMovimentoCaixa.VENDA);
                assertThat(movimento.valor()).isEqualTo(Money.de("5.00"));
            });
        }));
    }

    private Cenario criar(String nome) {
        ContaCriada conta = criador.criar(nome, "uma senha longa de teste");
        when(gateway.chaveRecebedora()).thenReturn("chave-teste");
        when(gateway.criarCobranca(any(), any())).thenReturn("codigo-pix");
        return conta.comoUsuario(() -> {
            UUID produto = produtos.cadastrar(TipoProduto.PRODUTO,
                    new DadosDoProduto("Item Pix", Money.de("12.50"), null, null, "un", null));
            UUID sessao = caixas.abrir(Money.ZERO);
            UUID venda = vendas.iniciar(sessao);
            vendas.adicionarItem(venda, produto, BigDecimal.ONE, Money.ZERO);
            UUID tentativa = UUID.randomUUID();
            pix.cobrar(venda, tentativa, Money.de("12.50"));
            return new Cenario(conta, produto, sessao, venda, tentativa);
        });
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder callback(
            String id, String segredo, String txid) {
        return post("/api/webhooks/pix/{id}/pix?hmac={segredo}", id, segredo)
                .secure(true).requestAttr("jakarta.servlet.request.X509Certificate", certificado())
                .contentType(MediaType.APPLICATION_JSON).content(aviso(txid));
    }

    private static X509Certificate[] certificado() {
        return new X509Certificate[]{org.mockito.Mockito.mock(X509Certificate.class)};
    }

    private static String aviso(String txid) {
        return "{\"pix\":[{\"txid\":\"" + txid + "\",\"valor\":\"0.01\"}]}";
    }

    private record Cenario(ContaCriada conta, UUID produto, UUID sessao, UUID venda,
            UUID tentativa) {
        String txid() {
            return tentativa.toString().replace("-", "");
        }
    }
}
