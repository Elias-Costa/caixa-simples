package br.com.caixasimples.vendas.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.ObjectMapper;

/** Contrato HTTP do PDV: comanda, parcelas, comprovante e isolamento (RF06–RF12, RNF05). */
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
        http.perform(post("/api/vendas/{id}/pagamentos", venda).with(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"forma\":\"DINHEIRO\",\"valor\":25,\"valorRecebido\":30}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.troco").value(5.0));
        http.perform(post("/api/vendas/{id}/conclusao", venda).with(admin))
                .andExpect(status().isNoContent());
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
                .content("{\"forma\":\"CARTAO\",\"valor\":5.00}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.troco").value(0.0));
        http.perform(post("/api/vendas/{id}/pagamentos", venda).with(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"forma\":\"CARTAO\",\"valor\":2.50}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.troco").value(0.0));
        http.perform(post("/api/vendas/{id}/pagamentos", venda).with(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"forma\":\"DINHEIRO\",\"valor\":2.50,\"valorRecebido\":10.00}"))
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
                .content("{\"forma\":\"FIADO\",\"valor\":20}"))
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
        UUID recebimento = uuidDaResposta(http.perform(post("/api/vendas/{id}/recebimentos", venda)
                .with(tokenOperador).contentType(MediaType.APPLICATION_JSON)
                .content("{\"valor\":7,\"forma\":\"PIX\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.saldoDevedor").value(13.0))
                .andReturn().getResponse().getContentAsString());
        http.perform(get("/api/vendas/{id}/recebimentos/{recebimento}/comprovante", venda,
                recebimento).with(tokenOperador))
                .andExpect(status().isOk()).andExpect(jsonPath("$.saldoApos").value(13.0))
                .andExpect(jsonPath("$.nomeCliente").value("Lia"));
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

    private UUID uuidDaResposta(String corpo) {
        return UUID.fromString(json.readTree(corpo).get("id").asText());
    }
}
