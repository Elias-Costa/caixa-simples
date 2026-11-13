package br.com.caixasimples.estoque.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.cadastro.TipoProduto;
import br.com.caixasimples.cadastro.application.ProdutoService;
import br.com.caixasimples.cadastro.application.ProdutoService.DadosDoProduto;
import br.com.caixasimples.contas.AutenticadorDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.contas.Plano;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.Perfil;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * A entrada de mercadoria comprada pela rota: o saldo sobe num movimento ENTRADA sem venda, com o
 * motivo opcional, e as recusas do perfil, do plano, do controle, do item e da outra Conta (RNF05).
 */
class EntradaDeEstoqueHttpTest extends TesteDeIntegracao {

    private static final String SENHA = "uma senha longa de teste";

    @Autowired MockMvc http;
    @Autowired CriadorDeContaDeTeste criador;
    @Autowired AutenticadorDeTeste autenticador;
    @Autowired ProdutoService produtos;
    @Autowired JdbcTemplate jdbc;

    @Test
    void administradorRegistraAEntradaQueSomaAoSaldoSemVenda() throws Exception {
        ContaCriada conta = contaComEstoque("Mercado da Reposição");
        UUID arroz = cadastrar(conta, TipoProduto.PRODUTO, "Arroz");

        entrada(conta, arroz, "{\"quantidade\":12.5,\"motivo\":\"  nota 123  \"}")
                .andExpect(status().isNoContent());
        entrada(conta, arroz, "{\"quantidade\":0.25}").andExpect(status().isNoContent());

        http.perform(get("/api/estoque/produtos").with(autenticador.como(conta)))
                .andExpect(jsonPath("$[0].estoqueAtual").value(12.75));
        List<Map<String, Object>> movimentos = jdbc.queryForList(
                "SELECT tipo, quantidade, motivo, venda_id FROM movimento_estoque"
                        + " WHERE produto_id = ? ORDER BY quantidade DESC", arroz);
        assertThat(movimentos).extracting(linha -> linha.get("tipo"))
                .containsExactly("ENTRADA", "ENTRADA");
        assertThat(movimentos).extracting(linha -> linha.get("quantidade"))
                .containsExactly(new BigDecimal("12.500"), new BigDecimal("0.250"));
        assertThat(movimentos).extracting(linha -> linha.get("motivo"))
                .containsExactly("nota 123", null);
        assertThat(movimentos).extracting(linha -> linha.get("venda_id")).containsOnlyNulls();
    }

    @Test
    void recusaQuantidadeInvalidaEMotivoLongo() throws Exception {
        ContaCriada conta = contaComEstoque("Mercado das Recusas");
        UUID feijao = cadastrar(conta, TipoProduto.PRODUTO, "Feijão");

        entrada(conta, feijao, "{\"quantidade\":0}").andExpect(status().isBadRequest());
        entrada(conta, feijao, "{\"quantidade\":-1}").andExpect(status().isBadRequest());
        entrada(conta, feijao, "{\"quantidade\":1.2345}").andExpect(status().isBadRequest());
        entrada(conta, feijao, "{}").andExpect(status().isBadRequest());
        entrada(conta, feijao, "{\"quantidade\":1,\"motivo\":\"" + "m".repeat(121) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        "motivo da entrada de estoque com 121 caracteres; o limite é 120."));

        http.perform(get("/api/estoque/produtos").with(autenticador.como(conta)))
                .andExpect(jsonPath("$[0].estoqueAtual").value(0));
    }

    @Test
    void recusaOItemSemEstoqueOInativoOControleDesligadoEOGratuito() throws Exception {
        ContaCriada conta = contaComEstoque("Cafeteria das Recusas");
        UUID cafe = cadastrar(conta, TipoProduto.SERVICO, "Café coado");
        UUID inativo = cadastrar(conta, TipoProduto.PRODUTO, "Bolo antigo");
        conta.comoUsuario(() -> produtos.inativar(inativo));

        entrada(conta, cafe, "{\"quantidade\":3}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(
                        "Café coado está cadastrado sem estoque e não recebe entrada de estoque."));
        entrada(conta, inativo, "{\"quantidade\":3}").andExpect(status().isConflict());

        ContaCriada desligada = criador.criar("Mercado Desligado", SENHA);
        criador.contratar(desligada.contaId(), Plano.COMPLETO);
        UUID doDesligado = cadastrar(desligada, TipoProduto.PRODUTO, "Arroz");
        entrada(desligada, doDesligado, "{\"quantidade\":3}").andExpect(status().isConflict());

        ContaCriada gratis = criador.criar("Banca Gratuita", SENHA);
        criador.habilitarEstoque(gratis.contaId());
        UUID doGratis = cadastrar(gratis, TipoProduto.PRODUTO, "Arroz");
        entrada(gratis, doGratis, "{\"quantidade\":3}").andExpect(status().isConflict());
    }

    @Test
    void operadorRecebe403EOutraContaRecebe404() throws Exception {
        ContaCriada operador = criador.criar("Loja do Operador", SENHA, Perfil.OPERADOR, true);
        criador.contratar(operador.contaId(), Plano.COMPLETO);
        criador.habilitarEstoque(operador.contaId());
        entrada(operador, UUID.randomUUID(), "{\"quantidade\":1}").andExpect(status().isForbidden());

        ContaCriada primeira = contaComEstoque("Mercado Primeiro");
        ContaCriada segunda = contaComEstoque("Mercado Segundo");
        UUID daPrimeira = cadastrar(primeira, TipoProduto.PRODUTO, "Arroz");

        entrada(segunda, daPrimeira, "{\"quantidade\":5}").andExpect(status().isNotFound());
        http.perform(get("/api/estoque/produtos").with(autenticador.como(primeira)))
                .andExpect(jsonPath("$[0].estoqueAtual").value(0));
    }

    @Test
    void entradaComIdDoAparelhoNaoSomaDeNovoNoReenvioERecusaOutroConteudo() throws Exception {
        ContaCriada conta = contaComEstoque("Mercado do Reenvio da Entrada");
        UUID arroz = cadastrar(conta, TipoProduto.PRODUTO, "Arroz");
        UUID semMotivo = UUID.randomUUID();
        UUID comMotivo = UUID.randomUUID();
        String recusa = "Esta entrada já foi registrada com outro produto, quantidade ou motivo;"
                + " abra o estoque de novo para ver o saldo.";

        entrada(conta, arroz, entradaCom(semMotivo, "12", null)).andExpect(status().isNoContent());
        // O reenvio igual não soma de novo; o motivo em branco vale como ausente.
        entrada(conta, arroz, entradaCom(semMotivo, "12", null)).andExpect(status().isNoContent());
        entrada(conta, arroz, entradaCom(semMotivo, "12.000", "  ")).andExpect(status().isNoContent());
        entrada(conta, arroz, entradaCom(semMotivo, "13", null))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value(recusa));
        entrada(conta, arroz, entradaCom(semMotivo, "12", "nota 9"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value(recusa));

        entrada(conta, arroz, entradaCom(comMotivo, "0.5", "nota 10")).andExpect(status().isNoContent());
        entrada(conta, arroz, entradaCom(comMotivo, "0.5", " nota 10 ")).andExpect(status().isNoContent());
        entrada(conta, arroz, entradaCom(comMotivo, "0.5", null))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value(recusa));
        // O id de uma entrada mandado como ajuste também é outro conteúdo.
        http.perform(post("/api/estoque/produtos/{id}/ajustes", arroz)
                        .with(autenticador.como(conta)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"movimentoId\":\"" + comMotivo + "\",\"diferenca\":0.5,"
                                + "\"motivo\":\"nota 10\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Este ajuste já foi registrado com outro"
                        + " produto, diferença ou motivo; abra o estoque de novo para ver o saldo."));

        http.perform(get("/api/estoque/produtos").with(autenticador.como(conta)))
                .andExpect(jsonPath("$[0].estoqueAtual").value(12.5));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM movimento_estoque"
                + " WHERE produto_id = ?", Integer.class, arroz)).isEqualTo(2);
    }

    @Test
    void entradaComIdDeMovimentoDeOutraContaERecusadaSemTocarNela() throws Exception {
        ContaCriada primeira = contaComEstoque("Mercado Aurora da Entrada");
        ContaCriada segunda = contaComEstoque("Mercado Esquina da Entrada");
        UUID daPrimeira = cadastrar(primeira, TipoProduto.PRODUTO, "Arroz");
        UUID daSegunda = cadastrar(segunda, TipoProduto.PRODUTO, "Arroz");
        UUID movimento = UUID.randomUUID();
        entrada(segunda, daSegunda, entradaCom(movimento, "7", null)).andExpect(status().isNoContent());

        String corpo = entrada(primeira, daPrimeira, entradaCom(movimento, "7", null))
                .andExpect(status().isInternalServerError())
                .andReturn().getResponse().getContentAsString();
        assertThat(corpo).doesNotContain(movimento.toString()).doesNotContain(daSegunda.toString());

        http.perform(get("/api/estoque/produtos").with(autenticador.como(primeira)))
                .andExpect(jsonPath("$[0].estoqueAtual").value(0));
        http.perform(get("/api/estoque/produtos").with(autenticador.como(segunda)))
                .andExpect(jsonPath("$[0].estoqueAtual").value(7));
    }

    private static String entradaCom(UUID movimento, String quantidade, String motivo) {
        String comMotivo = motivo == null ? "" : ",\"motivo\":\"" + motivo + "\"";
        return "{\"movimentoId\":\"" + movimento + "\",\"quantidade\":" + quantidade + comMotivo
                + "}";
    }

    private ContaCriada contaComEstoque(String nome) {
        ContaCriada conta = criador.criar(nome, SENHA);
        criador.contratar(conta.contaId(), Plano.COMPLETO);
        criador.habilitarEstoque(conta.contaId());
        return conta;
    }

    private ResultActions entrada(ContaCriada conta, UUID produto, String corpo) throws Exception {
        return http.perform(post("/api/estoque/produtos/{id}/entradas", produto)
                .with(autenticador.como(conta)).contentType(MediaType.APPLICATION_JSON)
                .content(corpo));
    }

    private UUID cadastrar(ContaCriada conta, TipoProduto tipo, String nome) {
        return conta.comoUsuario(() -> produtos.cadastrar(tipo,
                new DadosDoProduto(nome, Money.de("5.00"), null, null, "un", null)));
    }
}
