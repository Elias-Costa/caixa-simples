package br.com.caixasimples.vendas.web;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.cadastro.TipoProduto;
import br.com.caixasimples.cadastro.application.ProdutoService;
import br.com.caixasimples.cadastro.application.ProdutoService.DadosDoProduto;
import br.com.caixasimples.caixa.application.SessaoCaixaService;
import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.pagamentos.domain.SolicitacaoPagamento;
import br.com.caixasimples.contas.AutenticadorDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.UsuarioCriado;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.Perfil;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.ObjectMapper;

/** Contrato HTTP do PDV: comanda, parcelas, comprovante e isolamento (RF06 a RF12, RNF05). */
class VendaHttpTest extends TesteDeIntegracao {

    private static final String SENHA = "uma senha longa de teste";

    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired CriadorDeContaDeTeste criador;
    @Autowired AutenticadorDeTeste autenticador;
    @Autowired ProdutoService produtos;
    @Autowired SessaoCaixaService caixas;

    @Test
    void vendaEmDinheiroBuscaComandaTrocoEComprovante() throws Exception {
        ContaCriada conta = criador.criar("PDV A", SENHA);
        UUID produto = produto(conta);
        UUID sessao = conta.comoUsuario(() -> caixas.abrir(Money.ZERO));
        RequestPostProcessor admin = autenticador.como(conta);

        http.perform(get("/api/produtos/busca").param("termo", "CA-1").with(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(produto.toString()));
        UUID venda = criarVenda(admin, sessao);
        UUID item = uuidDaResposta(http.perform(post("/api/vendas/{id}/itens", venda).with(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"produtoId\":\"" + produto + "\",\"quantidade\":2,\"desconto\":0}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        http.perform(get("/api/vendas/{id}", venda).with(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.itens[0].id").value(item.toString()))
                .andExpect(jsonPath("$.itens[0].nome").value("Café"))
                .andExpect(jsonPath("$.total").value(25.0))
                .andExpect(jsonPath("$.faltaPagar").value(25.0));
        http.perform(get("/api/vendas").param("sessaoCaixaId", sessao.toString()).with(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].status").value("ABERTA"));
        UUID pagamentoId = UUID.randomUUID();
        http.perform(post("/api/vendas/{id}/pagamentos", venda).with(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pagamentoId\":\"" + pagamentoId
                        + "\",\"forma\":\"DINHEIRO\",\"valor\":25,\"valorRecebido\":30}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.troco").value(5.0));
        http.perform(post("/api/vendas/{id}/conclusao", venda).with(admin))
                .andExpect(status().isNoContent());
        String pedidoRepetido = "{\"pagamentoId\":\"" + pagamentoId
                + "\",\"forma\":\"DINHEIRO\",\"valor\":25,\"valorRecebido\":30}";
        http.perform(post("/api/vendas/{id}/pagamentos", venda).with(admin)
                .contentType(MediaType.APPLICATION_JSON).content(pedidoRepetido))
                .andExpect(status().isOk()).andExpect(jsonPath("$.troco").value(5.0));
        http.perform(post("/api/vendas/{id}/pagamentos", venda).with(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(pedidoRepetido.replace("30}", "31}")))
                .andExpect(status().isConflict());
        http.perform(get("/api/vendas/{id}", venda).with(admin))
                .andExpect(jsonPath("$.parcelas.length()").value(1));
        http.perform(get("/api/vendas/{id}/comprovante", venda).with(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valorTotal").value(25.0))
                .andExpect(jsonPath("$.linhas[0].nome").value("Café"))
                .andExpect(jsonPath("$.parcelas[0].forma").value("DINHEIRO"))
                .andExpect(jsonPath("$.troco").value(5.0));
    }

    @Test
    void divisaoRemocaoDescontoEContasSeparadas() throws Exception {
        ContaCriada contaA = criador.criar("PDV B", SENHA);
        ContaCriada contaB = criador.criar("PDV C", SENHA);
        UUID produto = produto(contaA);
        UUID sessao = contaA.comoUsuario(() -> caixas.abrir(Money.ZERO));
        RequestPostProcessor admin = autenticador.como(contaA);
        RequestPostProcessor outraConta = autenticador.como(contaB);
        UUID venda = criarVenda(admin, sessao);
        String pedido = "{\"produtoId\":\"" + produto + "\",\"quantidade\":1,\"desconto\":0}";
        UUID item = uuidDaResposta(http.perform(post("/api/vendas/{id}/itens", venda).with(admin)
                .contentType(MediaType.APPLICATION_JSON).content(pedido))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        http.perform(delete("/api/vendas/{id}/itens/{item}", venda, item).with(admin))
                .andExpect(status().isNoContent());
        http.perform(post("/api/vendas/{id}/itens", venda).with(admin)
                .contentType(MediaType.APPLICATION_JSON).content(pedido))
                .andExpect(status().isCreated());
        http.perform(put("/api/vendas/{id}/desconto", venda).with(admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"valor\":2.50}"))
                .andExpect(status().isNoContent());
        http.perform(post("/api/vendas/{id}/pagamentos", venda).with(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pagamentoId\":\"" + UUID.randomUUID()
                        + "\",\"forma\":\"CARTAO\",\"valor\":5.00}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.troco").value(0.0));
        http.perform(post("/api/vendas/{id}/pagamentos", venda).with(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pagamentoId\":\"" + UUID.randomUUID()
                        + "\",\"forma\":\"CARTAO\",\"valor\":2.50}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.troco").value(0.0));
        http.perform(post("/api/vendas/{id}/pagamentos", venda).with(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pagamentoId\":\"" + UUID.randomUUID()
                        + "\",\"forma\":\"DINHEIRO\",\"valor\":2.50,\"valorRecebido\":10.00}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.troco").value(7.5));
        http.perform(get("/api/vendas/{id}", venda).with(admin))
                .andExpect(jsonPath("$.pago").value(10.0))
                .andExpect(jsonPath("$.faltaPagar").value(0.0));
        http.perform(post("/api/vendas/{id}/conclusao", venda).with(admin))
                .andExpect(status().isNoContent());
        http.perform(post("/api/vendas/{id}/cancelamento", venda).with(admin))
                .andExpect(status().isNoContent());
        http.perform(get("/api/vendas/{id}/comprovante", venda).with(admin))
                .andExpect(status().isConflict());
        http.perform(get("/api/vendas/{id}", venda).with(outraConta))
                .andExpect(status().isNotFound());
        http.perform(get("/api/produtos/busca").param("termo", "CA-1").with(outraConta))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        http.perform(get("/api/vendas").param("sessaoCaixaId", sessao.toString())
                .with(outraConta)).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        http.perform(post("/api/vendas/{id}/cancelamento", venda).with(outraConta))
                .andExpect(status().isNotFound());
    }

    @Test
    void operadorNaoDescontaNemTocaVendaDoColega() throws Exception {
        ContaCriada operador = criador.criar("PDV D", SENHA, Perfil.OPERADOR, true);
        UsuarioCriado admin = criador.criarAdminEm(operador.contaId(), "Gerente");
        UUID produto = admin.comoUsuario(() -> produtos.cadastrar(TipoProduto.PRODUTO,
                new DadosDoProduto("Água", Money.de("4.00"), null, null, "un", Map.of())));
        UUID sessaoDoOperador = operador.comoUsuario(() -> caixas.abrir(Money.ZERO));
        UUID sessaoDoAdmin = admin.comoUsuario(() -> caixas.abrir(Money.ZERO));
        RequestPostProcessor tokenOperador = autenticador.como(operador);
        UUID vendaDoOperador = criarVenda(tokenOperador, sessaoDoOperador);
        UUID vendaDoAdmin = admin.comoUsuario(() -> {
            // O caminho do administrador é direto porque a fixture secundária não tem credencial.
            return vendaService.iniciar(sessaoDoAdmin);
        });

        http.perform(post("/api/vendas/{id}/itens", vendaDoOperador).with(tokenOperador)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"produtoId\":\"" + produto + "\",\"quantidade\":1,\"desconto\":1}"))
                .andExpect(status().isForbidden());
        http.perform(put("/api/vendas/{id}/desconto", vendaDoOperador).with(tokenOperador)
                .contentType(MediaType.APPLICATION_JSON).content("{\"valor\":1}"))
                .andExpect(status().isForbidden());
        http.perform(get("/api/vendas/{id}", vendaDoAdmin).with(tokenOperador))
                .andExpect(status().isForbidden());
        http.perform(post("/api/vendas/{id}/cancelamento", vendaDoAdmin).with(tokenOperador))
                .andExpect(status().isForbidden());
        http.perform(post("/api/vendas/{id}/cancelamento", vendaDoOperador).with(tokenOperador))
                .andExpect(status().isNoContent());
        http.perform(get("/api/vendas").param("sessaoCaixaId", sessaoDoOperador.toString())
                .with(tokenOperador)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("CANCELADA"));
    }

    @Test
    void operadorNaoVendeFiadoMasRecebeEConsultaComprovante() throws Exception {
        ContaCriada operador = criador.criar("PDV Fiado", SENHA, Perfil.OPERADOR, true);
        UsuarioCriado admin = criador.criarAdminEm(operador.contaId(), "Gerente");
        UUID produto = admin.comoUsuario(() -> produtos.cadastrar(TipoProduto.PRODUTO,
                new DadosDoProduto("Café fiado", Money.de("20.00"), null, null, "un", Map.of())));
        UUID sessao = operador.comoUsuario(() -> caixas.abrir(Money.ZERO));
        RequestPostProcessor tokenOperador = autenticador.como(operador);
        UUID cliente = uuidDaResposta(http.perform(post("/api/clientes").with(tokenOperador)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nome\":\"Lia\",\"contato\":null}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        UUID venda = criarVenda(tokenOperador, sessao);
        http.perform(post("/api/vendas/{id}/itens", venda).with(tokenOperador)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"produtoId\":\"" + produto + "\",\"quantidade\":1,\"desconto\":0}"))
                .andExpect(status().isCreated());
        http.perform(put("/api/vendas/{id}/cliente", venda).with(tokenOperador)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"clienteId\":\"" + cliente + "\"}"))
                .andExpect(status().isNoContent());
        http.perform(post("/api/vendas/{id}/pagamentos", venda).with(tokenOperador)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pagamentoId\":\"" + UUID.randomUUID()
                        + "\",\"forma\":\"FIADO\",\"valor\":20}"))
                .andExpect(status().isForbidden());
        admin.comoUsuario(() -> vendaService.registrarPagamento(venda,
                SolicitacaoPagamento.de(FormaPagamento.FIADO, Money.de("20.00"))));
        http.perform(post("/api/vendas/{id}/conclusao", venda).with(tokenOperador))
                .andExpect(status().isForbidden());
        admin.comoUsuario(() -> vendaService.concluir(venda));
        http.perform(get("/api/fiado/clientes/{id}/saldo", cliente).with(tokenOperador))
                .andExpect(status().isOk()).andExpect(jsonPath("$.saldoDevedor").value(20.0));
        http.perform(get("/api/fiado/dividas").with(tokenOperador))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].vendaId")
                        .value(venda.toString()));
        http.perform(get("/api/vendas/{id}/comprovante", venda).with(tokenOperador))
                .andExpect(status().isOk()).andExpect(jsonPath("$.valorFiado").value(20.0))
                .andExpect(jsonPath("$.saldoDevedor").value(20.0));
        UUID recebimentoId = UUID.randomUUID();
        String pedidoDeRecebimento = "{\"recebimentoId\":\"" + recebimentoId
                + "\",\"valor\":7,\"forma\":\"PIX\"}";
        UUID recebimento = uuidDaResposta(http.perform(post("/api/vendas/{id}/recebimentos", venda)
                .with(tokenOperador).contentType(MediaType.APPLICATION_JSON)
                .content(pedidoDeRecebimento))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.saldoDevedor").value(13.0))
                .andReturn().getResponse().getContentAsString());
        http.perform(post("/api/caixa/sessoes/{id}/fechamento", sessao).with(tokenOperador)
                .contentType(MediaType.APPLICATION_JSON).content("{\"valorContado\":0}"))
                .andExpect(status().isOk());
        http.perform(post("/api/vendas/{id}/recebimentos", venda).with(tokenOperador)
                .contentType(MediaType.APPLICATION_JSON).content(pedidoDeRecebimento))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(recebimento.toString()))
                .andExpect(jsonPath("$.saldoDevedor").value(13.0));
        http.perform(post("/api/vendas/{id}/recebimentos", venda).with(tokenOperador)
                .contentType(MediaType.APPLICATION_JSON)
                .content(pedidoDeRecebimento.replace("\"valor\":7", "\"valor\":8")))
                .andExpect(status().isConflict());
        http.perform(get("/api/vendas/{id}", venda).with(tokenOperador))
                .andExpect(jsonPath("$.recebimentos.length()").value(1));
        http.perform(get("/api/vendas/{id}/recebimentos/{recebimento}/comprovante", venda,
                recebimento).with(tokenOperador))
                .andExpect(status().isOk()).andExpect(jsonPath("$.saldoApos").value(13.0))
                .andExpect(jsonPath("$.nomeCliente").value("Lia"));
    }

    @Test
    void nsuDoCartaoExigidoPelaContaVoltaNoDetalheENaoNoComprovante() throws Exception {
        ContaCriada conta = criador.criar("PDV NSU", SENHA);
        UUID produto = produto(conta);
        UUID sessao = conta.comoUsuario(() -> caixas.abrir(Money.ZERO));
        RequestPostProcessor admin = autenticador.como(conta);
        UUID venda = criarVenda(admin, sessao);
        http.perform(post("/api/vendas/{id}/itens", venda).with(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"produtoId\":\"" + produto + "\",\"quantidade\":2,\"desconto\":0}"))
                .andExpect(status().isCreated());
        http.perform(put("/api/conta/configuracao/nsu").with(admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"nsuObrigatorio\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nsuObrigatorio").value(true));

        pagar(admin, venda, UUID.randomUUID(), "CARTAO", "10", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("exige o NSU")));
        pagar(admin, venda, UUID.randomUUID(), "CARTAO", "10", "   ")
                .andExpect(status().isBadRequest());
        pagar(admin, venda, UUID.randomUUID(), "CARTAO", "10", "A".repeat(41))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("limite e 40")));
        pagar(admin, venda, UUID.randomUUID(), "PIX", "10", "004512")
                .andExpect(status().isBadRequest());
        pagar(admin, venda, UUID.randomUUID(), "DINHEIRO", "10", "004512")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("NSU so existe em cartao")));

        UUID pagamentoId = UUID.randomUUID();
        pagar(admin, venda, pagamentoId, "CARTAO", "10", " 004512 ")
                .andExpect(status().isOk()).andExpect(jsonPath("$.troco").value(0.0));
        // O reenvio compara o NSU já sem os espaços; outro NSU com o mesmo id é conflito.
        pagar(admin, venda, pagamentoId, "CARTAO", "10", "004512")
                .andExpect(status().isOk());
        pagar(admin, venda, pagamentoId, "CARTAO", "10", "004513")
                .andExpect(status().isConflict());
        pagar(admin, venda, UUID.randomUUID(), "DINHEIRO", "15", null)
                .andExpect(status().isOk());
        http.perform(post("/api/vendas/{id}/conclusao", venda).with(admin))
                .andExpect(status().isNoContent());

        http.perform(get("/api/vendas/{id}", venda).with(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parcelas.length()").value(2))
                .andExpect(jsonPath("$.parcelas[?(@.forma == 'CARTAO')].nsu").value("004512"));
        http.perform(get("/api/vendas/{id}/comprovante", venda).with(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parcelas[0].nsu").doesNotExist())
                .andExpect(jsonPath("$.parcelas[1].nsu").doesNotExist());

        UUID cliente = uuidDaResposta(http.perform(post("/api/clientes").with(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nome\":\"Lia\",\"contato\":null}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        UUID fiada = criarVenda(admin, sessao);
        http.perform(post("/api/vendas/{id}/itens", fiada).with(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"produtoId\":\"" + produto + "\",\"quantidade\":2,\"desconto\":0}"))
                .andExpect(status().isCreated());
        http.perform(put("/api/vendas/{id}/cliente", fiada).with(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"clienteId\":\"" + cliente + "\"}"))
                .andExpect(status().isNoContent());
        pagar(admin, fiada, UUID.randomUUID(), "FIADO", "25", null).andExpect(status().isOk());
        http.perform(post("/api/vendas/{id}/conclusao", fiada).with(admin))
                .andExpect(status().isNoContent());

        UUID recebimentoId = UUID.randomUUID();
        http.perform(post("/api/vendas/{id}/recebimentos", fiada).with(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"recebimentoId\":\"" + recebimentoId
                        + "\",\"valor\":5,\"forma\":\"CARTAO\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("exige o NSU")));
        http.perform(post("/api/vendas/{id}/recebimentos", fiada).with(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"recebimentoId\":\"" + recebimentoId
                        + "\",\"valor\":5,\"forma\":\"CARTAO\",\"nsu\":\"778899\"}"))
                .andExpect(status().isCreated());
        http.perform(get("/api/vendas/{id}", fiada).with(admin))
                .andExpect(jsonPath("$.recebimentos[0].nsu").value("778899"));
        http.perform(get("/api/vendas/{id}/recebimentos/{recebimento}/comprovante", fiada,
                recebimentoId).with(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nsu").doesNotExist());
    }

    @Test
    void sessaoOuProdutoQueNaoExisteNaContaResponde404() throws Exception {
        ContaCriada contaA = criador.criar("PDV E", SENHA);
        ContaCriada contaB = criador.criar("PDV F", SENHA);
        UUID sessaoDaContaA = contaA.comoUsuario(() -> caixas.abrir(Money.ZERO));
        UUID produtoDaContaB = produto(contaB);
        UUID sessaoQueNaoExiste = UUID.randomUUID();
        UUID produtoQueNaoExiste = UUID.randomUUID();
        RequestPostProcessor adminA = autenticador.como(contaA);
        RequestPostProcessor adminB = autenticador.como(contaB);

        exigirNaoEncontrado(http.perform(post("/api/vendas").with(adminA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sessaoCaixaId\":\"" + sessaoQueNaoExiste + "\"}")),
                sessaoQueNaoExiste);
        // A sessão existe e está ABERTA, mas na Conta A: para a Conta B o id não existe (RNF05).
        exigirNaoEncontrado(http.perform(post("/api/vendas").with(adminB)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sessaoCaixaId\":\"" + sessaoDaContaA + "\"}")),
                sessaoDaContaA);

        UUID venda = criarVenda(adminA, sessaoDaContaA);
        exigirNaoEncontrado(http.perform(post("/api/vendas/{id}/itens", venda).with(adminA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"produtoId\":\"" + produtoQueNaoExiste
                        + "\",\"quantidade\":1,\"desconto\":0}")), produtoQueNaoExiste);
        exigirNaoEncontrado(http.perform(post("/api/vendas/{id}/itens", venda).with(adminA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"produtoId\":\"" + produtoDaContaB
                        + "\",\"quantidade\":1,\"desconto\":0}")), produtoDaContaB);
        http.perform(get("/api/vendas/{id}", venda).with(adminA))
                .andExpect(status().isOk()).andExpect(jsonPath("$.itens").isEmpty());
    }

    @Autowired br.com.caixasimples.vendas.application.VendaService vendaService;

    private UUID produto(ContaCriada conta) {
        return conta.comoUsuario(() -> produtos.cadastrar(TipoProduto.PRODUTO,
                new DadosDoProduto("Café", Money.de("12.50"), "CA-1", null, "un", Map.of())));
    }

    private UUID criarVenda(RequestPostProcessor usuario, UUID sessao) throws Exception {
        return uuidDaResposta(http.perform(post("/api/vendas").with(usuario)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sessaoCaixaId\":\"" + sessao + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private ResultActions pagar(RequestPostProcessor usuario, UUID venda, UUID pagamentoId,
            String forma, String valor, String nsu) throws Exception {
        // Dinheiro vai com o valor exato recebido, que a forma exige.
        String recebido = forma.equals("DINHEIRO") ? ",\"valorRecebido\":" + valor : "";
        String comNsu = nsu == null ? "" : ",\"nsu\":" + json.writeValueAsString(nsu);
        return http.perform(post("/api/vendas/{id}/pagamentos", venda).with(usuario)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pagamentoId\":\"" + pagamentoId + "\",\"forma\":\"" + forma
                        + "\",\"valor\":" + valor + recebido + comNsu + "}"));
    }

    private UUID uuidDaResposta(String corpo) {
        return UUID.fromString(json.readTree(corpo).get("id").asText());
    }

    /** O 404 sai em Problem Details, com o id recusado no detalhe, e não como erro inesperado. */
    private void exigirNaoEncontrado(ResultActions resposta, UUID id) throws Exception {
        resposta.andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value(containsString(id.toString())));
    }
}
