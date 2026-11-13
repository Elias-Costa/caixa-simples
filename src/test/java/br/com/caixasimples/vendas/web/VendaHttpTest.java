package br.com.caixasimples.vendas.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
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
import br.com.caixasimples.contas.Plano;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.Perfil;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
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
    void clienteSaiDaComandaSemFiadoEFicaNaComandaComFiado() throws Exception {
        ContaCriada operador = criador.criar("PDV Cliente", SENHA, Perfil.OPERADOR, true);
        UsuarioCriado admin = criador.criarAdminEm(operador.contaId(), "Gerente");
        ContaCriada outraConta = criador.criar("PDV Vizinho", SENHA);
        UUID produto = admin.comoUsuario(() -> produtos.cadastrar(TipoProduto.PRODUTO,
                new DadosDoProduto("Pão", Money.de("6.00"), null, null, "un", Map.of())));
        UUID sessaoDoOperador = operador.comoUsuario(() -> caixas.abrir(Money.ZERO));
        UUID sessaoDoAdmin = admin.comoUsuario(() -> caixas.abrir(Money.ZERO));
        RequestPostProcessor tokenOperador = autenticador.como(operador);
        RequestPostProcessor tokenDaOutraConta = autenticador.como(outraConta);
        UUID cliente = uuidDaResposta(http.perform(post("/api/clientes").with(tokenOperador)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nome\":\"Rita\",\"contato\":null}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());

        UUID semFiado = criarVenda(tokenOperador, sessaoDoOperador);
        vincular(tokenOperador, semFiado, cliente).andExpect(status().isNoContent());
        // A venda existe, mas na outra Conta: para esta o id não existe (RNF05).
        http.perform(delete("/api/vendas/{id}/cliente", semFiado).with(tokenDaOutraConta))
                .andExpect(status().isNotFound());
        http.perform(get("/api/vendas/{id}", semFiado).with(tokenOperador))
                .andExpect(jsonPath("$.clienteId").value(cliente.toString()));
        http.perform(delete("/api/vendas/{id}/cliente", semFiado).with(tokenOperador))
                .andExpect(status().isNoContent());
        http.perform(get("/api/vendas/{id}", semFiado).with(tokenOperador))
                .andExpect(status().isOk()).andExpect(jsonPath("$.clienteId").doesNotExist());

        UUID comFiado = criarVenda(tokenOperador, sessaoDoOperador);
        http.perform(post("/api/vendas/{id}/itens", comFiado).with(tokenOperador)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"produtoId\":\"" + produto + "\",\"quantidade\":1,\"desconto\":0}"))
                .andExpect(status().isCreated());
        vincular(tokenOperador, comFiado, cliente).andExpect(status().isNoContent());
        admin.comoUsuario(() -> vendaService.registrarPagamento(comFiado,
                SolicitacaoPagamento.de(FormaPagamento.FIADO, Money.de("6.00"))));
        http.perform(delete("/api/vendas/{id}/cliente", comFiado).with(tokenOperador))
                                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("pagamento no fiado")));
        http.perform(get("/api/vendas/{id}", comFiado).with(tokenOperador))
                .andExpect(jsonPath("$.clienteId").value(cliente.toString()));

        UUID vendaDoAdmin = admin.comoUsuario(() -> {
            UUID id = vendaService.iniciar(sessaoDoAdmin);
            vendaService.vincularCliente(id, cliente);
            return id;
        });
        http.perform(delete("/api/vendas/{id}/cliente", vendaDoAdmin).with(tokenOperador))
                .andExpect(status().isForbidden());
        assertThat(admin.comoUsuario(() -> vendaService.consultar(vendaDoAdmin).clienteId()))
                .isEqualTo(cliente);
    }

    @Test
    void administradorVeAsVendasDaSessaoDoOperadorEOperadorNaoVeAsDoAdministrador() throws Exception {
        ContaCriada conta = criador.criar("PDV Dona", SENHA);
        criador.contratar(conta.contaId(), Plano.COMPLETO);
        RequestPostProcessor admin = autenticador.como(conta);
        RequestPostProcessor operador = operadorNovo(admin);
        UUID produto = produto(conta);
        UUID sessaoDoAdmin = conta.comoUsuario(() -> caixas.abrir(Money.ZERO));
        conta.comoUsuario(() -> vendaService.iniciar(sessaoDoAdmin));
        UUID sessaoDoOperador = uuidDaResposta(http.perform(post("/api/caixa/sessoes").with(operador)
                .contentType(MediaType.APPLICATION_JSON).content("{\"valorAbertura\":0}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String item = "{\"produtoId\":\"" + produto + "\",\"quantidade\":1,\"desconto\":0}";

        UUID aberta = criarVenda(operador, sessaoDoOperador);
        UUID concluida = criarVenda(operador, sessaoDoOperador);
        UUID cancelada = criarVenda(operador, sessaoDoOperador);
        for (UUID venda : new UUID[] {aberta, concluida, cancelada}) {
            http.perform(post("/api/vendas/{id}/itens", venda).with(operador)
                    .contentType(MediaType.APPLICATION_JSON).content(item))
                    .andExpect(status().isCreated());
        }
        pagar(operador, concluida, UUID.randomUUID(), "CARTAO", "12.50", null)
                .andExpect(status().isOk());
        http.perform(post("/api/vendas/{id}/conclusao", concluida).with(operador))
                .andExpect(status().isNoContent());
        http.perform(post("/api/vendas/{id}/cancelamento", cancelada).with(operador))
                .andExpect(status().isNoContent());

        http.perform(get("/api/vendas").param("sessaoCaixaId", sessaoDoOperador.toString())
                        .with(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[?(@.id == '" + aberta + "')].status").value("ABERTA"))
                .andExpect(jsonPath("$[?(@.id == '" + concluida + "')].status").value("CONCLUIDA"))
                .andExpect(jsonPath("$[?(@.id == '" + cancelada + "')].status").value("CANCELADA"))
                .andExpect(jsonPath("$[?(@.id == '" + cancelada + "')].total").value(12.5));
        http.perform(get("/api/vendas/{id}", concluida).with(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens[0].nome").value("Café"))
                .andExpect(jsonPath("$.parcelas[0].forma").value("CARTAO"))
                .andExpect(jsonPath("$.total").value(12.5));

        http.perform(get("/api/vendas").param("sessaoCaixaId", sessaoDoAdmin.toString())
                .with(operador)).andExpect(status().isForbidden());
    }

    @Test
    void operadorVeNaListaDoProprioCaixaAVendaQueOAdministradorFezNele() throws Exception {
        ContaCriada conta = criador.criar("PDV Balcão Dividido", SENHA);
        criador.contratar(conta.contaId(), Plano.COMPLETO);
        RequestPostProcessor admin = autenticador.como(conta);
        RequestPostProcessor operador = operadorNovo(admin);
        UUID produto = produto(conta);
        String item = "{\"produtoId\":\"" + produto + "\",\"quantidade\":1,\"desconto\":0}";
        UUID sessaoDoOperador = uuidDaResposta(http.perform(post("/api/caixa/sessoes").with(operador)
                .contentType(MediaType.APPLICATION_JSON).content("{\"valorAbertura\":0}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        UUID vendaDoOperador = criarVenda(operador, sessaoDoOperador);
        http.perform(post("/api/vendas/{id}/itens", vendaDoOperador).with(operador)
                .contentType(MediaType.APPLICATION_JSON).content(item)).andExpect(status().isCreated());
        pagar(operador, vendaDoOperador, UUID.randomUUID(), "CARTAO", "12.50", null)
                .andExpect(status().isOk());
        http.perform(post("/api/vendas/{id}/conclusao", vendaDoOperador).with(operador))
                .andExpect(status().isNoContent());
        http.perform(get("/api/vendas").param("sessaoCaixaId", sessaoDoOperador.toString())
                .with(operador)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));

        // O administrador vende em qualquer sessão da Conta, e a venda nasce com ele como operador.
        UUID vendaDoAdmin = criarVenda(admin, sessaoDoOperador);
        http.perform(get("/api/vendas").param("sessaoCaixaId", sessaoDoOperador.toString())
                        .with(operador))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.id == '" + vendaDoAdmin + "')].status").value("ABERTA"))
                .andExpect(jsonPath("$[?(@.id == '" + vendaDoAdmin + "')].usuarioId")
                        .value(conta.usuarioId().toString()));

        http.perform(post("/api/vendas/{id}/itens", vendaDoAdmin).with(admin)
                .contentType(MediaType.APPLICATION_JSON).content(item)).andExpect(status().isCreated());
        pagar(admin, vendaDoAdmin, UUID.randomUUID(), "DINHEIRO", "12.50", null)
                .andExpect(status().isOk());
        http.perform(post("/api/vendas/{id}/conclusao", vendaDoAdmin).with(admin))
                .andExpect(status().isNoContent());

        // A lista e o extrato contam as mesmas vendas: o dinheiro da venda do administrador está
        // na gaveta que o operador vai contar.
        http.perform(get("/api/vendas").param("sessaoCaixaId", sessaoDoOperador.toString())
                        .with(operador))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.id == '" + vendaDoAdmin + "')].status").value("CONCLUIDA"))
                .andExpect(jsonPath("$[?(@.id == '" + vendaDoAdmin + "')].formas[*].forma",
                        contains("DINHEIRO")));
        http.perform(get("/api/caixa/sessoes/{id}", sessaoDoOperador).with(operador))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valorFechamentoEsperado").value(12.5))
                .andExpect(jsonPath("$.movimentos[?(@.vendaId == '" + vendaDoAdmin + "')].valor")
                        .value(12.5));

        // Abrir a venda continua com quem a fez.
        http.perform(get("/api/vendas/{id}", vendaDoAdmin).with(operador))
                .andExpect(status().isForbidden());
        http.perform(get("/api/vendas/{id}/comprovante", vendaDoAdmin).with(operador))
                .andExpect(status().isForbidden());
        http.perform(get("/api/vendas").param("sessaoCaixaId", sessaoDoOperador.toString())
                .with(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));

        // O caixa de outro usuário é recusado pelo dono dele, mesmo sem venda nenhuma.
        UUID sessaoDoAdmin = conta.comoUsuario(() -> caixas.abrir(Money.ZERO));
        http.perform(get("/api/vendas").param("sessaoCaixaId", sessaoDoAdmin.toString())
                        .with(operador))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail")
                        .value("O operador só alcança o próprio caixa e as próprias vendas."));
    }

    @Test
    void comprovanteDaVendaFiadaTrazOClienteEODaVendaSemFiadoNao() throws Exception {
        ContaCriada conta = criador.criar("PDV Caderneta", SENHA);
        RequestPostProcessor admin = autenticador.como(conta);
        UUID produto = produto(conta);
        UUID sessao = conta.comoUsuario(() -> caixas.abrir(Money.ZERO));
        UUID cliente = uuidDaResposta(http.perform(post("/api/clientes").with(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nome\":\"Marta\",\"contato\":null}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String item = "{\"produtoId\":\"" + produto + "\",\"quantidade\":1,\"desconto\":0}";

        UUID semFiado = criarVenda(admin, sessao);
        http.perform(post("/api/vendas/{id}/itens", semFiado).with(admin)
                .contentType(MediaType.APPLICATION_JSON).content(item)).andExpect(status().isCreated());
        vincular(admin, semFiado, cliente).andExpect(status().isNoContent());
        pagar(admin, semFiado, UUID.randomUUID(), "CARTAO", "12.50", null).andExpect(status().isOk());
        http.perform(post("/api/vendas/{id}/conclusao", semFiado).with(admin))
                .andExpect(status().isNoContent());
        http.perform(get("/api/vendas/{id}/comprovante", semFiado).with(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nomeCliente").doesNotExist());

        UUID fiada = criarVenda(admin, sessao);
        http.perform(post("/api/vendas/{id}/itens", fiada).with(admin)
                .contentType(MediaType.APPLICATION_JSON).content(item)).andExpect(status().isCreated());
        vincular(admin, fiada, cliente).andExpect(status().isNoContent());
        pagar(admin, fiada, UUID.randomUUID(), "FIADO", "12.50", null).andExpect(status().isOk());
        http.perform(post("/api/vendas/{id}/conclusao", fiada).with(admin))
                .andExpect(status().isNoContent());
        http.perform(get("/api/vendas/{id}/comprovante", fiada).with(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nomeCliente").value("Marta"));

        // Quitada a dívida, o Cliente pode ser removido, e o comprovante antigo sai sem o nome.
        http.perform(post("/api/vendas/{id}/recebimentos", fiada).with(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"recebimentoId\":\"" + UUID.randomUUID()
                        + "\",\"valor\":12.50,\"forma\":\"PIX\"}"))
                .andExpect(status().isCreated());
        http.perform(post("/api/clientes/{id}/remover", cliente).with(admin))
                .andExpect(status().isNoContent());
        http.perform(get("/api/vendas/{id}/comprovante", fiada).with(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nomeCliente").value("Cliente removido"));
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
                .andExpect(jsonPath("$.detail").value(containsString("Informe o NSU")));
        pagar(admin, venda, UUID.randomUUID(), "CARTAO", "10", "   ")
                .andExpect(status().isBadRequest());
        pagar(admin, venda, UUID.randomUUID(), "CARTAO", "10", "A".repeat(41))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("o limite é 40")));
        pagar(admin, venda, UUID.randomUUID(), "PIX", "10", "004512")
                .andExpect(status().isBadRequest());
        pagar(admin, venda, UUID.randomUUID(), "DINHEIRO", "10", "004512")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("O NSU só existe no")));

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
                .andExpect(jsonPath("$.detail").value(containsString("Informe o NSU")));
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
    void dinheiroSemValorRecebidoResponde400SemGravarParcela() throws Exception {
        ContaCriada conta = criador.criar("PDV Troco", SENHA);
        UUID produto = produto(conta);
        UUID sessao = conta.comoUsuario(() -> caixas.abrir(Money.ZERO));
        RequestPostProcessor admin = autenticador.como(conta);
        UUID venda = criarVenda(admin, sessao);
        http.perform(post("/api/vendas/{id}/itens", venda).with(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"produtoId\":\"" + produto + "\",\"quantidade\":1,\"desconto\":0}"))
                .andExpect(status().isCreated());

        // Sem o valor entregue não há troco a calcular: o pedido está incompleto, e a resposta
        // diz o que falta em vez de um 500 sem detalhe, que pareceria defeito do servidor.
        UUID pagamentoId = UUID.randomUUID();
        http.perform(post("/api/vendas/{id}/pagamentos", venda).with(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pagamentoId\":\"" + pagamentoId
                        + "\",\"forma\":\"DINHEIRO\",\"valor\":12.50}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail")
                        .value(containsString("Informe o valor recebido em dinheiro")));
        http.perform(get("/api/vendas/{id}", venda).with(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.parcelas").isEmpty());

        // A recusa não gravou nada com o id: o mesmo pedido, completo, passa como o primeiro.
        pagar(admin, venda, pagamentoId, "DINHEIRO", "12.50", null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.troco").value(0.0));
    }

    @Test
    void listaDoCaixaTrazOValorDeCadaFormaEOItemTrazAUnidadeSoDaPropriaConta() throws Exception {
        ContaCriada conta = criador.criar("PDV Formas", SENHA);
        ContaCriada outra = criador.criar("PDV Formas Vizinha", SENHA);
        RequestPostProcessor admin = autenticador.como(conta);
        RequestPostProcessor adminDaOutra = autenticador.como(outra);
        UUID produto = produto(conta);
        UUID sessao = conta.comoUsuario(() -> caixas.abrir(Money.ZERO));
        String item = "{\"produtoId\":\"" + produto + "\",\"quantidade\":1,\"desconto\":0}";

        // Duas parcelas no cartão somam numa linha só; a ordem é a das formas, não a do lançamento.
        UUID dividida = criarVenda(admin, sessao);
        http.perform(post("/api/vendas/{id}/itens", dividida).with(admin)
                .contentType(MediaType.APPLICATION_JSON).content(item)).andExpect(status().isCreated());
        pagar(admin, dividida, UUID.randomUUID(), "CARTAO", "5.00", null).andExpect(status().isOk());
        pagar(admin, dividida, UUID.randomUUID(), "DINHEIRO", "2.50", null).andExpect(status().isOk());
        pagar(admin, dividida, UUID.randomUUID(), "CARTAO", "5.00", null).andExpect(status().isOk());
        http.perform(post("/api/vendas/{id}/conclusao", dividida).with(admin))
                .andExpect(status().isNoContent());

        // O fiado fica pendente até o cliente pagar, e conta como a forma da venda.
        UUID cliente = uuidDaResposta(http.perform(post("/api/clientes").with(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nome\":\"Marta\",\"contato\":null}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        UUID fiada = criarVenda(admin, sessao);
        http.perform(post("/api/vendas/{id}/itens", fiada).with(admin)
                .contentType(MediaType.APPLICATION_JSON).content(item)).andExpect(status().isCreated());
        vincular(admin, fiada, cliente).andExpect(status().isNoContent());
        pagar(admin, fiada, UUID.randomUUID(), "FIADO", "12.50", null).andExpect(status().isOk());
        http.perform(post("/api/vendas/{id}/conclusao", fiada).with(admin))
                .andExpect(status().isNoContent());

        UUID aberta = criarVenda(admin, sessao);
        http.perform(post("/api/vendas/{id}/itens", aberta).with(admin)
                .contentType(MediaType.APPLICATION_JSON).content(item)).andExpect(status().isCreated());

        http.perform(get("/api/vendas").param("sessaoCaixaId", sessao.toString()).with(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[?(@.id == '" + dividida + "')].formas[*].forma",
                        contains("DINHEIRO", "CARTAO")))
                .andExpect(jsonPath("$[?(@.id == '" + dividida + "')].formas[*].valor",
                        contains(2.5, 10.0)))
                .andExpect(jsonPath("$[?(@.id == '" + fiada + "')].formas[*].forma",
                        contains("FIADO")))
                .andExpect(jsonPath("$[?(@.id == '" + fiada + "')].formas[*].valor",
                        contains(12.5)))
                .andExpect(jsonPath("$[?(@.id == '" + aberta + "')].formas[*]").isEmpty());
        http.perform(get("/api/vendas/{id}", aberta).with(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens[0].unidade").value("un"));

        // A sessão da primeira Conta não existe para a vizinha (RNF05), e a lista da vizinha só tem
        // as formas das vendas dela.
        http.perform(get("/api/vendas").param("sessaoCaixaId", sessao.toString())
                .with(adminDaOutra)).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        UUID produtoDaOutra = produto(outra);
        UUID sessaoDaOutra = outra.comoUsuario(() -> caixas.abrir(Money.ZERO));
        UUID vendaDaOutra = criarVenda(adminDaOutra, sessaoDaOutra);
        http.perform(post("/api/vendas/{id}/itens", vendaDaOutra).with(adminDaOutra)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"produtoId\":\"" + produtoDaOutra + "\",\"quantidade\":1,\"desconto\":0}"))
                .andExpect(status().isCreated());
        pagar(adminDaOutra, vendaDaOutra, UUID.randomUUID(), "PIX", "12.50", null)
                .andExpect(status().isOk());
        http.perform(get("/api/vendas").param("sessaoCaixaId", sessaoDaOutra.toString())
                        .with(adminDaOutra))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].formas[*].forma", contains("PIX")));
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

    @Test
    void itemComIdDoAparelhoNaoGravaDeNovoNoReenvioERecusaOutroConteudo() throws Exception {
        ContaCriada conta = criador.criar("PDV Reenvio", SENHA);
        UUID produto = produto(conta);
        UUID sessao = conta.comoUsuario(() -> caixas.abrir(Money.ZERO));
        RequestPostProcessor admin = autenticador.como(conta);
        UUID venda = criarVenda(admin, sessao);
        UUID outraVenda = criarVenda(admin, sessao);
        UUID itemId = UUID.randomUUID();

        http.perform(lancarItem(admin, venda, itemId, produto, "2"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(itemId.toString()));
        // A resposta perdida faz o aparelho, ou o próprio navegador, mandar o mesmo pedido de novo.
        http.perform(lancarItem(admin, venda, itemId, produto, "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(itemId.toString()));
        // A mesma quantidade escrita de outro jeito é o mesmo conteúdo.
        http.perform(lancarItem(admin, venda, itemId, produto, "2.000"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(itemId.toString()));
        http.perform(lancarItem(admin, venda, itemId, produto, "3"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(
                        "Este item já foi lançado com outro produto, quantidade ou desconto; abra a"
                                + " venda de novo para ver o que ficou."));
        http.perform(lancarItem(admin, outraVenda, itemId, produto, "2"))
                .andExpect(status().isConflict());
        http.perform(get("/api/vendas/{id}", venda).with(admin))
                .andExpect(jsonPath("$.itens.length()").value(1))
                .andExpect(jsonPath("$.itens[0].id").value(itemId.toString()))
                .andExpect(jsonPath("$.total").value(25.0));
        http.perform(get("/api/vendas/{id}", outraVenda).with(admin))
                .andExpect(jsonPath("$.itens").isEmpty());

        // O corpo sem o id continua valendo, e o servidor gera outro item.
        UUID semId = uuidDaResposta(http.perform(post("/api/vendas/{id}/itens", venda).with(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"produtoId\":\"" + produto + "\",\"quantidade\":2,\"desconto\":0}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertThat(semId).isNotEqualTo(itemId);

        pagar(admin, venda, UUID.randomUUID(), "DINHEIRO", "50.00", null)
                .andExpect(status().isOk());
        http.perform(post("/api/vendas/{id}/conclusao", venda).with(admin))
                .andExpect(status().isNoContent());
        // O reenvio igual responde como da primeira vez, mesmo com a venda já concluída.
        http.perform(lancarItem(admin, venda, itemId, produto, "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(itemId.toString()));
        http.perform(get("/api/vendas/{id}", venda).with(admin))
                .andExpect(jsonPath("$.status").value("CONCLUIDA"))
                .andExpect(jsonPath("$.itens.length()").value(2));
    }

    @Test
    void itemComIdDeItemDeOutraContaERecusadoSemTocarNela() throws Exception {
        ContaCriada contaA = criador.criar("PDV Aurora", SENHA);
        ContaCriada contaB = criador.criar("PDV Esquina", SENHA);
        UUID produtoA = produto(contaA);
        UUID produtoB = produto(contaB);
        UUID sessaoA = contaA.comoUsuario(() -> caixas.abrir(Money.ZERO));
        UUID sessaoB = contaB.comoUsuario(() -> caixas.abrir(Money.ZERO));
        RequestPostProcessor adminA = autenticador.como(contaA);
        RequestPostProcessor adminB = autenticador.como(contaB);
        UUID vendaA = criarVenda(adminA, sessaoA);
        UUID vendaB = criarVenda(adminB, sessaoB);
        UUID itemId = UUID.randomUUID();
        http.perform(lancarItem(adminB, vendaB, itemId, produtoB, "1"))
                .andExpect(status().isCreated());

        // A consulta da Conta A não enxerga o item da B, e quem recusa é a chave primária; a
        // resposta não diz nada sobre a outra Conta.
        String corpo = http.perform(lancarItem(adminA, vendaA, itemId, produtoA, "1"))
                .andExpect(status().isInternalServerError())
                .andReturn().getResponse().getContentAsString();
        assertThat(corpo).doesNotContain(itemId.toString()).doesNotContain(vendaB.toString());

        http.perform(get("/api/vendas/{id}", vendaA).with(adminA))
                .andExpect(jsonPath("$.itens").isEmpty());
        http.perform(get("/api/vendas/{id}", vendaB).with(adminB))
                .andExpect(jsonPath("$.itens.length()").value(1))
                .andExpect(jsonPath("$.itens[0].id").value(itemId.toString()))
                .andExpect(jsonPath("$.itens[0].produtoId").value(produtoB.toString()))
                .andExpect(jsonPath("$.total").value(12.5));
    }

    @Test
    void inicioComIdDoAparelhoNaoAbreOutraVendaNoReenvioERecusaOutroCaixaOuOperador()
            throws Exception {
        ContaCriada conta = criador.criar("PDV Início", SENHA);
        criador.contratar(conta.contaId(), Plano.COMPLETO);
        RequestPostProcessor admin = autenticador.como(conta);
        RequestPostProcessor operador = operadorNovo(admin);
        UUID produto = produto(conta);
        UUID sessaoDoAdmin = conta.comoUsuario(() -> caixas.abrir(Money.ZERO));
        UUID sessaoDoOperador = uuidDaResposta(http.perform(post("/api/caixa/sessoes").with(operador)
                .contentType(MediaType.APPLICATION_JSON).content("{\"valorAbertura\":0}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        UUID venda = UUID.randomUUID();
        String recusa = "Esta venda já foi aberta em outro caixa ou por outro operador; comece uma"
                + " nova venda.";

        http.perform(iniciar(admin, venda, sessaoDoAdmin))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(venda.toString()));
        // A resposta perdida faz o navegador mandar o mesmo pedido de novo.
        http.perform(iniciar(admin, venda, sessaoDoAdmin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(venda.toString()));
        http.perform(iniciar(admin, venda, sessaoDoOperador))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value(recusa));
        http.perform(iniciar(operador, venda, sessaoDoOperador))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value(recusa));
        http.perform(get("/api/vendas").param("sessaoCaixaId", sessaoDoAdmin.toString())
                        .with(admin))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(venda.toString()));
        http.perform(get("/api/vendas").param("sessaoCaixaId", sessaoDoOperador.toString())
                .with(operador)).andExpect(jsonPath("$").isEmpty());

        // O corpo sem o id continua valendo, e o servidor gera outra venda.
        assertThat(criarVenda(admin, sessaoDoAdmin)).isNotEqualTo(venda);

        // Com a venda concluída, o reenvio igual responde como da primeira vez.
        http.perform(lancarItem(admin, venda, UUID.randomUUID(), produto, "1"))
                .andExpect(status().isCreated());
        pagar(admin, venda, UUID.randomUUID(), "DINHEIRO", "12.50", null)
                .andExpect(status().isOk());
        http.perform(post("/api/vendas/{id}/conclusao", venda).with(admin))
                .andExpect(status().isNoContent());
        http.perform(iniciar(admin, venda, sessaoDoAdmin)).andExpect(status().isOk());
        http.perform(get("/api/vendas/{id}", venda).with(admin))
                .andExpect(jsonPath("$.status").value("CONCLUIDA"))
                .andExpect(jsonPath("$.itens.length()").value(1));
    }

    @Test
    void inicioComIdDeVendaDeOutraContaERecusadoSemTocarNela() throws Exception {
        ContaCriada contaA = criador.criar("PDV Aurora do Início", SENHA);
        ContaCriada contaB = criador.criar("PDV Esquina do Início", SENHA);
        UUID sessaoA = contaA.comoUsuario(() -> caixas.abrir(Money.ZERO));
        UUID sessaoB = contaB.comoUsuario(() -> caixas.abrir(Money.ZERO));
        RequestPostProcessor adminA = autenticador.como(contaA);
        RequestPostProcessor adminB = autenticador.como(contaB);
        UUID venda = UUID.randomUUID();
        http.perform(iniciar(adminB, venda, sessaoB)).andExpect(status().isCreated());

        // A consulta da Conta A não enxerga a venda da B, e quem recusa é a chave primária; a
        // resposta não diz nada sobre a outra Conta.
        String corpo = http.perform(iniciar(adminA, venda, sessaoA))
                .andExpect(status().isInternalServerError())
                .andReturn().getResponse().getContentAsString();
        assertThat(corpo).doesNotContain(venda.toString()).doesNotContain(sessaoB.toString());

        http.perform(get("/api/vendas").param("sessaoCaixaId", sessaoA.toString()).with(adminA))
                .andExpect(jsonPath("$").isEmpty());
        http.perform(get("/api/vendas/{id}", venda).with(adminB))
                .andExpect(jsonPath("$.sessaoCaixaId").value(sessaoB.toString()))
                .andExpect(jsonPath("$.status").value("ABERTA"));
    }

    @Test
    void produtoVendidoPorUnidadeRecusaFracaoEOReenvioIgualContinuaRespondendo() throws Exception {
        ContaCriada conta = criador.criar("PDV Bala", SENHA);
        UUID bala = conta.comoUsuario(() -> produtos.cadastrar(TipoProduto.PRODUTO,
                new DadosDoProduto("Bala de goma", Money.de("0.10"), null, null, "un", Map.of())));
        UUID queijo = conta.comoUsuario(() -> produtos.cadastrar(TipoProduto.PRODUTO,
                new DadosDoProduto("Queijo minas", Money.de("40.00"), null, null, "kg", Map.of())));
        UUID sessao = conta.comoUsuario(() -> caixas.abrir(Money.ZERO));
        RequestPostProcessor admin = autenticador.como(conta);
        UUID venda = criarVenda(admin, sessao);

        http.perform(lancarItem(admin, venda, UUID.randomUUID(), bala, "1.5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("O produto Bala de goma é vendido por unidade"
                        + " e só aceita quantidade inteira."));
        http.perform(lancarItem(admin, venda, UUID.randomUUID(), bala, "2"))
                .andExpect(status().isCreated());
        UUID fracionado = UUID.randomUUID();
        http.perform(lancarItem(admin, venda, fracionado, queijo, "0.750"))
                .andExpect(status().isCreated());

        // O queijo passa a ser vendido por unidade depois de lançado; o reenvio igual do item já
        // gravado responde como da primeira vez, e só o item novo segue a unidade de hoje.
        conta.comoUsuario(() -> produtos.editar(queijo,
                new DadosDoProduto("Queijo minas", Money.de("40.00"), null, null, " UN ", Map.of())));
        http.perform(lancarItem(admin, venda, fracionado, queijo, "0.750"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(fracionado.toString()));
        http.perform(lancarItem(admin, venda, UUID.randomUUID(), queijo, "0.5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("O produto Queijo minas é vendido por unidade"
                        + " e só aceita quantidade inteira."));

        http.perform(get("/api/vendas/{id}", venda).with(admin))
                .andExpect(jsonPath("$.itens.length()").value(2))
                .andExpect(jsonPath("$.total").value(30.2));
    }

    @Test
    void quantidadeQueNaoValeVoltaComFraseDeTelaNoDetalheESemCampos() throws Exception {
        ContaCriada conta = criador.criar("PDV Quantidade", SENHA);
        UUID queijo = conta.comoUsuario(() -> produtos.cadastrar(TipoProduto.PRODUTO,
                new DadosDoProduto("Queijo minas", Money.de("40.00"), null, null, "kg", Map.of())));
        UUID bala = conta.comoUsuario(() -> produtos.cadastrar(TipoProduto.PRODUTO,
                new DadosDoProduto("Bala de goma", Money.de("0.10"), null, null, "un", Map.of())));
        UUID sessao = conta.comoUsuario(() -> caixas.abrir(Money.ZERO));
        RequestPostProcessor admin = autenticador.como(conta);
        UUID venda = criarVenda(admin, sessao);

        // A tela mostra o detalhe como veio: frase de tela, sem o texto do validador nem o número
        // no formato americano.
        Map<String, String> recusas = new LinkedHashMap<>();
        recusas.put("0", "Informe uma quantidade maior que zero.");
        recusas.put("-2", "Informe uma quantidade maior que zero.");
        recusas.put("1.2345", "Informe uma quantidade com até três casas decimais.");
        recusas.put("1000000000", "Informe uma quantidade menor que um bilhão.");
        for (var recusa : recusas.entrySet()) {
            http.perform(lancarItem(admin, venda, UUID.randomUUID(), queijo, recusa.getKey()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value(recusa.getValue()))
                    .andExpect(jsonPath("$.campos").doesNotExist());
        }
        // Negativo com fração em un é quantidade que não vale, não fração.
        http.perform(lancarItem(admin, venda, UUID.randomUUID(), bala, "-1.5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Informe uma quantidade maior que zero."));
        // O pedido sem itemId, do aplicativo instalado antes dele, recebe a mesma frase.
        http.perform(post("/api/vendas/{id}/itens", venda).with(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"produtoId\":\"" + queijo + "\",\"quantidade\":0,\"desconto\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Informe uma quantidade maior que zero."));
        // A quantidade nula continua recusada pelo pedido.
        http.perform(post("/api/vendas/{id}/itens", venda).with(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"produtoId\":\"" + queijo + "\",\"desconto\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.quantidade").exists());

        // Nada do que foi recusado entrou, e a quantidade que vale continua lançando.
        http.perform(lancarItem(admin, venda, UUID.randomUUID(), queijo, "0.750"))
                .andExpect(status().isCreated());
        http.perform(get("/api/vendas/{id}", venda).with(admin))
                .andExpect(jsonPath("$.itens.length()").value(1))
                .andExpect(jsonPath("$.total").value(30.0));
    }

    @Test
    void aTrilhaGuardaQuemCancelouEQuemLancouCadaMovimentoTambemNoCaixaDeOutro() throws Exception {
        ContaCriada conta = criador.criar("PDV Trilha", SENHA);
        criador.contratar(conta.contaId(), Plano.COMPLETO);
        RequestPostProcessor admin = autenticador.como(conta);
        RequestPostProcessor operador = operadorNovo(admin);
        UUID produto = produto(conta);
        String item = "{\"produtoId\":\"" + produto + "\",\"quantidade\":1,\"desconto\":0}";
        UUID sessao = uuidDaResposta(http.perform(post("/api/caixa/sessoes").with(operador)
                .contentType(MediaType.APPLICATION_JSON).content("{\"valorAbertura\":0}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        UUID propria = criarVenda(operador, sessao);
        UUID desfeitaPeloAdmin = criarVenda(operador, sessao);
        for (UUID venda : new UUID[] {propria, desfeitaPeloAdmin}) {
            http.perform(post("/api/vendas/{id}/itens", venda).with(operador)
                    .contentType(MediaType.APPLICATION_JSON).content(item))
                    .andExpect(status().isCreated());
            pagar(operador, venda, UUID.randomUUID(), "DINHEIRO", "12.50", null)
                    .andExpect(status().isOk());
            http.perform(post("/api/vendas/{id}/conclusao", venda).with(operador))
                    .andExpect(status().isNoContent());
        }
        String idDoOperador = json.readTree(http.perform(get("/api/vendas/{id}", propria)
                .with(operador)).andReturn().getResponse().getContentAsString())
                .get("usuarioId").asText();
        String idDoAdmin = conta.usuarioId().toString();
        Instant antes = Instant.now();

        // O operador continua cancelando a própria venda concluída; a trilha diz que foi ele. O
        // administrador cancela a do operador e lança sangria no caixa dele, e a trilha diz que
        // foi o administrador, não o dono do caixa nem quem vendeu.
        http.perform(post("/api/caixa/sessoes/{id}/sangrias", sessao).with(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"movimentoId\":\"" + UUID.randomUUID()
                        + "\",\"valor\":5.00,\"motivo\":\"Deposito\"}"))
                .andExpect(status().isNoContent());
        http.perform(post("/api/vendas/{id}/cancelamento", propria).with(operador))
                .andExpect(status().isNoContent());
        http.perform(post("/api/vendas/{id}/cancelamento", desfeitaPeloAdmin).with(admin))
                .andExpect(status().isNoContent());

        var lista = json.readTree(http.perform(get("/api/vendas")
                .param("sessaoCaixaId", sessao.toString()).with(admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        Map<String, JsonNode> porId = new HashMap<>();
        lista.forEach(venda -> porId.put(venda.get("id").asText(), venda));
        assertThat(porId.get(propria.toString()).get("canceladaPor").asText())
                .isEqualTo(idDoOperador);
        assertThat(porId.get(desfeitaPeloAdmin.toString()).get("canceladaPor").asText())
                .isEqualTo(idDoAdmin);
        assertThat(Instant.parse(porId.get(propria.toString()).get("canceladaEm")
                .asText())).isAfterOrEqualTo(antes.minusSeconds(1));
        http.perform(get("/api/vendas/{id}", desfeitaPeloAdmin).with(operador))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canceladaPor").value(idDoAdmin))
                .andExpect(jsonPath("$.canceladaEm").exists());

        var movimentos = json.readTree(http.perform(get("/api/caixa/sessoes/{id}", sessao)
                .with(admin)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString()).get("movimentos");
        List<String> trilha = new ArrayList<>();
        movimentos.forEach(movimento -> trilha.add(movimento.get("tipo").asText() + " "
                + (movimento.has("vendaId") ? movimento.get("vendaId").asText() : "-") + " "
                + movimento.get("usuarioId").asText()));
        assertThat(trilha).containsExactlyInAnyOrder(
                "VENDA " + propria + " " + idDoOperador,
                "VENDA " + desfeitaPeloAdmin + " " + idDoOperador,
                "ESTORNO " + propria + " " + idDoOperador,
                "ESTORNO " + desfeitaPeloAdmin + " " + idDoAdmin,
                "SANGRIA - " + idDoAdmin);
    }

    @Autowired br.com.caixasimples.vendas.application.VendaService vendaService;

    private MockHttpServletRequestBuilder lancarItem(RequestPostProcessor usuario, UUID venda,
            UUID itemId, UUID produto, String quantidade) {
        return post("/api/vendas/{id}/itens", venda).with(usuario)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"itemId\":\"" + itemId + "\",\"produtoId\":\"" + produto
                        + "\",\"quantidade\":" + quantidade + ",\"desconto\":0}");
    }

    private UUID produto(ContaCriada conta) {
        return conta.comoUsuario(() -> produtos.cadastrar(TipoProduto.PRODUTO,
                new DadosDoProduto("Café", Money.de("12.50"), "CA-1", null, "un", Map.of())));
    }

    /** Um operador com login de verdade, criado pelo administrador como na tela Usuários. */
    private RequestPostProcessor operadorNovo(RequestPostProcessor admin) throws Exception {
        String email = "operador-" + UUID.randomUUID() + "@exemplo.test";
        http.perform(post("/api/usuarios").with(admin).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("nome", "Atendente", "perfil", "OPERADOR",
                        "email", email, "senha", SENHA))))
                .andExpect(status().isCreated());
        // A senha que o administrador escolheu é provisória: o operador escolhe a dele, como no
        // primeiro acesso pelo aplicativo, antes de poder vender.
        String token = autenticador.trocarSenhaProvisoria(email, SENHA,
                "outra frase longa escolhida pelo atendente");
        return requisicao -> {
            requisicao.addHeader("Authorization", "Bearer " + token);
            return requisicao;
        };
    }

    private MockHttpServletRequestBuilder iniciar(RequestPostProcessor usuario, UUID venda,
            UUID sessao) {
        return post("/api/vendas").with(usuario).contentType(MediaType.APPLICATION_JSON)
                .content("{\"vendaId\":\"" + venda + "\",\"sessaoCaixaId\":\"" + sessao + "\"}");
    }

    private UUID criarVenda(RequestPostProcessor usuario, UUID sessao) throws Exception {
        return uuidDaResposta(http.perform(post("/api/vendas").with(usuario)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sessaoCaixaId\":\"" + sessao + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private ResultActions vincular(RequestPostProcessor usuario, UUID venda, UUID cliente)
            throws Exception {
        return http.perform(put("/api/vendas/{id}/cliente", venda).with(usuario)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"clienteId\":\"" + cliente + "\"}"));
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
                .andExpect(jsonPath("$.detail").value(containsString("não encontrad")));
    }
}
