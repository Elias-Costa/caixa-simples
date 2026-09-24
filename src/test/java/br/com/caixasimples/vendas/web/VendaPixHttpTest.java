package br.com.caixasimples.vendas.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import br.com.caixasimples.pagamentos.domain.PixGateway;
import br.com.caixasimples.pagamentos.application.PixIndisponivelException;
import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.pagamentos.StatusPagamento;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.vendas.application.VendaService;
import br.com.caixasimples.vendas.internal.VendaRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/** R27: repetição da tentativa, parcela pendente e RNF05 no contrato HTTP. */
class VendaPixHttpTest extends TesteDeIntegracao {
    @Autowired MockMvc http;
    @Autowired CriadorDeContaDeTeste criador;
    @Autowired AutenticadorDeTeste autenticador;
    @Autowired ProdutoService produtos;
    @Autowired SessaoCaixaService caixas;
    @Autowired VendaService vendas;
    @Autowired VendaRepository linhas;
    @Autowired TransactionTemplate transacoes;
    @MockitoBean PixGateway gateway;

    @Test
    void mesmaTentativaTresVezesGeraUmaParcelaEOutraContaNaoAVe() throws Exception {
        ContaCriada conta = criador.criar("Pix A", "uma senha longa de teste");
        ContaCriada outra = criador.criar("Pix B", "uma senha longa de teste");
        UUID vendaId = vendaComUmItem(conta);
        UUID tentativaId = UUID.randomUUID();
        String pedido = "{\"tentativaId\":\"" + tentativaId + "\",\"valor\":12.50}";
        when(gateway.chaveRecebedora()).thenReturn("chave-da-conta-a");
        when(gateway.criarCobranca(any(), eq(Money.de("12.50"))))
                .thenReturn("codigo-pix-de-teste");

        for (int i = 0; i < 3; i++) {
            http.perform(post("/api/vendas/{id}/pagamentos/pix", vendaId)
                    .with(autenticador.como(conta)).contentType(MediaType.APPLICATION_JSON)
                    .content(pedido))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(tentativaId.toString()))
                    .andExpect(jsonPath("$.status").value("PENDENTE"))
                    .andExpect(jsonPath("$.pix.txid")
                            .value(tentativaId.toString().replace("-", "")))
                    .andExpect(jsonPath("$.pix.estado").value("DISPONIVEL"));
        }
        verify(gateway, times(1)).criarCobranca(any(), eq(Money.de("12.50")));
        http.perform(get("/api/vendas/{id}", vendaId).with(autenticador.como(conta)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ABERTA"))
                .andExpect(jsonPath("$.parcelas.length()").value(1))
                .andExpect(jsonPath("$.parcelas[0].pix.copiaECola").value("codigo-pix-de-teste"));
        http.perform(post("/api/vendas/{id}/conclusao", vendaId)
                .with(autenticador.como(conta))).andExpect(status().isConflict());
        http.perform(post("/api/vendas/{id}/cancelamento", vendaId)
                .with(autenticador.como(conta))).andExpect(status().isConflict());

        http.perform(get("/api/vendas/{id}", vendaId).with(autenticador.como(outra)))
                .andExpect(status().isNotFound());
        http.perform(post("/api/vendas/{id}/pagamentos/pix", vendaId)
                .with(autenticador.como(outra)).contentType(MediaType.APPLICATION_JSON)
                .content(pedido)).andExpect(status().isNotFound());
    }

    @Test
    void respostaIncertaMantemTentativaEReenvioUsaOMesmoTxid() throws Exception {
        ContaCriada conta = criador.criar("Pix incerto", "uma senha longa de teste");
        UUID vendaId = vendaComUmItem(conta);
        UUID tentativaId = UUID.randomUUID();
        String pedido = "{\"tentativaId\":\"" + tentativaId + "\",\"valor\":12.50}";
        when(gateway.chaveRecebedora()).thenReturn("chave-teste");
        when(gateway.criarCobranca(any(), eq(Money.de("12.50"))))
                .thenThrow(new PixIndisponivelException("timeout"))
                .thenReturn("codigo-confirmado");

        http.perform(post("/api/vendas/{id}/pagamentos/pix", vendaId)
                .with(autenticador.como(conta)).contentType(MediaType.APPLICATION_JSON)
                .content(pedido)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDENTE"))
                .andExpect(jsonPath("$.pix.estado").value("INCERTA"));
        http.perform(post("/api/vendas/{id}/pagamentos/pix", vendaId)
                .with(autenticador.como(conta)).contentType(MediaType.APPLICATION_JSON)
                .content(pedido)).andExpect(status().isOk())
                .andExpect(jsonPath("$.pix.estado").value("DISPONIVEL"));
        http.perform(get("/api/vendas/{id}", vendaId).with(autenticador.como(conta)))
                .andExpect(jsonPath("$.parcelas.length()").value(1))
                .andExpect(jsonPath("$.parcelas[0].id").value(tentativaId.toString()));
        http.perform(post("/api/vendas/{id}/pagamentos/pix", vendaId)
                .with(autenticador.como(conta)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"tentativaId\":\"" + tentativaId + "\",\"valor\":12.00}"))
                .andExpect(status().isConflict());
        verify(gateway, times(2)).criarCobranca(any(), eq(Money.de("12.50")));
    }

    @Test
    void pixManualHistoricoContinuaLegivel() throws Exception {
        ContaCriada conta = criador.criar("Pix antigo", "uma senha longa de teste");
        UUID vendaId = vendaComUmItem(conta);
        conta.comoUsuario(() -> transacoes.executeWithoutResult(estado -> {
            var linha = linhas.findById(vendaId).orElseThrow();
            var venda = linha.paraDominio();
            venda.registrarPagamento(FormaPagamento.PIX, Money.de("12.50"),
                    StatusPagamento.CONFIRMADO, Money.ZERO);
            linha.atualizarCom(venda);
            linhas.save(linha);
        }));
        http.perform(get("/api/vendas/{id}", vendaId).with(autenticador.como(conta)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parcelas[0].forma").value("PIX"))
                .andExpect(jsonPath("$.parcelas[0].status").value("CONFIRMADO"))
                .andExpect(jsonPath("$.parcelas[0].pix").doesNotExist());
    }

    private UUID vendaComUmItem(ContaCriada conta) {
        return conta.comoUsuario(() -> {
            UUID produto = produtos.cadastrar(TipoProduto.PRODUTO,
                    new DadosDoProduto("Item Pix", Money.de("12.50"), null, null, "un", null));
            UUID sessao = caixas.abrir(Money.ZERO);
            UUID venda = vendas.iniciar(sessao);
            vendas.adicionarItem(venda, produto, BigDecimal.ONE, Money.ZERO);
            return venda;
        });
    }
}
