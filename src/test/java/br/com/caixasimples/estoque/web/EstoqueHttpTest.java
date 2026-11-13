package br.com.caixasimples.estoque.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Contrato HTTP do estoque: ajuste, mínimo, alerta, perfil e isolamento entre Contas. */
class EstoqueHttpTest extends TesteDeIntegracao {

    private static final String SENHA = "uma senha longa de teste";

    @Autowired MockMvc http;
    @Autowired CriadorDeContaDeTeste criador;
    @Autowired AutenticadorDeTeste autenticador;
    @Autowired ProdutoService produtos;
    @Autowired JdbcTemplate jdbc;

    @Test
    void adminAjustaDefineMinimoEConsultaAlerta() throws Exception {
        ContaCriada conta = criador.criar("Mercado do Estoque", SENHA);
        criador.contratar(conta.contaId(), Plano.COMPLETO);
        criador.habilitarEstoque(conta.contaId());
        UUID produto = cadastrar(conta, TipoProduto.PRODUTO, "Arroz");
        cadastrar(conta, TipoProduto.SERVICO, "Entrega");

        http.perform(get("/api/estoque/produtos").with(autenticador.como(conta)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(produto.toString()))
                .andExpect(jsonPath("$[0].estoqueAtual").value(0))
                .andExpect(jsonPath("$[0].estoqueMinimo").value(0));
        http.perform(get("/api/estoque/baixo").with(autenticador.como(conta)))
                .andExpect(jsonPath("$.length()").value(1));

        http.perform(post("/api/estoque/produtos/{id}/ajustes", produto)
                        .with(autenticador.como(conta)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"diferenca\":5,\"motivo\":\"contagem\"}"))
                .andExpect(status().isNoContent());
        http.perform(put("/api/estoque/produtos/{id}/minimo", produto)
                        .with(autenticador.como(conta)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"minimo\":6}"))
                .andExpect(status().isNoContent());
        http.perform(get("/api/estoque/produtos").with(autenticador.como(conta)))
                .andExpect(jsonPath("$[0].estoqueAtual").value(5))
                .andExpect(jsonPath("$[0].estoqueMinimo").value(6));
        http.perform(get("/api/estoque/baixo").with(autenticador.como(conta)))
                .andExpect(jsonPath("$[0].id").value(produto.toString()));
    }

    @Test
    void contaDesligadaRecebe409NasQuatroRotas() throws Exception {
        ContaCriada conta = criador.criar("Salao sem Estoque", SENHA);
        criador.contratar(conta.contaId(), Plano.COMPLETO);
        UUID produto = cadastrar(conta, TipoProduto.PRODUTO, "Shampoo");

        http.perform(get("/api/estoque/produtos").with(autenticador.como(conta)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").exists());
        http.perform(get("/api/estoque/baixo").with(autenticador.como(conta)))
                .andExpect(status().isConflict());
        http.perform(post("/api/estoque/produtos/{id}/ajustes", produto)
                        .with(autenticador.como(conta)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"diferenca\":1,\"motivo\":\"contagem\"}"))
                .andExpect(status().isConflict());
        http.perform(put("/api/estoque/produtos/{id}/minimo", produto)
                        .with(autenticador.como(conta)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"minimo\":2}"))
                .andExpect(status().isConflict());
    }

    @Test
    void operadorRecebe403NasQuatroRotas() throws Exception {
        ContaCriada operador = criador.criar("Loja do Operador", SENHA, Perfil.OPERADOR, true);
        criador.habilitarEstoque(operador.contaId());
        UUID produto = UUID.randomUUID();

        http.perform(get("/api/estoque/produtos").with(autenticador.como(operador)))
                .andExpect(status().isForbidden());
        http.perform(get("/api/estoque/baixo").with(autenticador.como(operador)))
                .andExpect(status().isForbidden());
        http.perform(post("/api/estoque/produtos/{id}/ajustes", produto)
                        .with(autenticador.como(operador)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"diferenca\":1,\"motivo\":\"contagem\"}"))
                .andExpect(status().isForbidden());
        http.perform(put("/api/estoque/produtos/{id}/minimo", produto)
                        .with(autenticador.como(operador)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"minimo\":2}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void outraContaNaoLeNemAlteraProduto() throws Exception {
        ContaCriada primeira = criador.criar("Mercado Primeiro", SENHA);
        criador.contratar(primeira.contaId(), Plano.COMPLETO);
        ContaCriada segunda = criador.criar("Mercado Segundo", SENHA);
        criador.contratar(segunda.contaId(), Plano.COMPLETO);
        criador.habilitarEstoque(primeira.contaId());
        criador.habilitarEstoque(segunda.contaId());
        UUID produto = cadastrar(primeira, TipoProduto.PRODUTO, "Arroz");

        http.perform(get("/api/estoque/produtos").with(autenticador.como(segunda)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        http.perform(get("/api/estoque/baixo").with(autenticador.como(segunda)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        http.perform(post("/api/estoque/produtos/{id}/ajustes", produto)
                        .with(autenticador.como(segunda)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"diferenca\":3,\"motivo\":\"contagem\"}"))
                .andExpect(status().isNotFound());
        http.perform(put("/api/estoque/produtos/{id}/minimo", produto)
                        .with(autenticador.como(segunda)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"minimo\":2}"))
                .andExpect(status().isNotFound());
        http.perform(get("/api/estoque/produtos").with(autenticador.como(primeira)))
                .andExpect(jsonPath("$[0].estoqueAtual").value(0))
                .andExpect(jsonPath("$[0].estoqueMinimo").value(0));
    }

    private UUID cadastrar(ContaCriada conta, TipoProduto tipo, String nome) {
        return conta.comoUsuario(() -> produtos.cadastrar(tipo,
                new DadosDoProduto(nome, Money.de("5.00"), null, null, "un", null)));
    }

    @Test
    void ajusteComIdDoAparelhoNaoMoveOSaldoDeNovoNoReenvioERecusaOutroConteudo()
            throws Exception {
        ContaCriada conta = contaComEstoque("Mercado do Reenvio");
        UUID arroz = cadastrar(conta, TipoProduto.PRODUTO, "Arroz");
        UUID feijao = cadastrar(conta, TipoProduto.PRODUTO, "Feijão");
        UUID movimento = UUID.randomUUID();
        String quebra = ajusteCom(movimento, "-2", "quebra");

        ajuste(conta, arroz, quebra).andExpect(status().isNoContent());
        // A resposta perdida faz o navegador, ou um segundo toque, mandar o mesmo pedido de novo.
        ajuste(conta, arroz, quebra).andExpect(status().isNoContent());
        // A mesma diferença escrita de outro jeito e o motivo com espaços são o mesmo conteúdo.
        ajuste(conta, arroz, ajusteCom(movimento, "-2.000", "  quebra "))
                .andExpect(status().isNoContent());
        String recusa = "Este ajuste já foi registrado com outro produto, diferença ou motivo; abra"
                + " o estoque de novo para ver o saldo.";
        ajuste(conta, arroz, ajusteCom(movimento, "-3", "quebra"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value(recusa));
        ajuste(conta, arroz, ajusteCom(movimento, "-2", "perda"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value(recusa));
        ajuste(conta, feijao, quebra)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value(recusa));
        // O id de um ajuste mandado como entrada também é outro conteúdo.
        http.perform(post("/api/estoque/produtos/{id}/entradas", arroz)
                        .with(autenticador.como(conta)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"movimentoId\":\"" + movimento + "\",\"quantidade\":2,"
                                + "\"motivo\":\"quebra\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Esta entrada já foi registrada com outro"
                        + " produto, quantidade ou motivo; abra o estoque de novo para ver o saldo."));

        assertThat(saldo(arroz)).isEqualByComparingTo("-2");
        assertThat(saldo(feijao)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForList("SELECT produto_id, tipo, quantidade, motivo"
                + " FROM movimento_estoque WHERE id = ?", movimento))
                .singleElement()
                .satisfies(linha -> {
                    assertThat(linha.get("produto_id")).isEqualTo(arroz);
                    assertThat(linha.get("tipo")).isEqualTo("AJUSTE");
                    assertThat((BigDecimal) linha.get("quantidade")).isEqualByComparingTo("-2");
                    assertThat(linha.get("motivo")).isEqualTo("quebra");
                });

        // O corpo sem o id continua valendo, e o servidor gera outro movimento.
        ajuste(conta, arroz, "{\"diferenca\":-2,\"motivo\":\"quebra\"}")
                .andExpect(status().isNoContent());
        assertThat(saldo(arroz)).isEqualByComparingTo("-4");

        // Com o produto já inativado, o reenvio igual responde como da primeira vez.
        conta.comoUsuario(() -> produtos.inativar(arroz));
        ajuste(conta, arroz, quebra).andExpect(status().isNoContent());
        assertThat(saldo(arroz)).isEqualByComparingTo("-4");
    }

    @Test
    void ajusteComIdDeMovimentoDeOutraContaERecusadoSemTocarNela() throws Exception {
        ContaCriada primeira = contaComEstoque("Mercado Aurora");
        ContaCriada segunda = contaComEstoque("Mercado Esquina");
        UUID daPrimeira = cadastrar(primeira, TipoProduto.PRODUTO, "Arroz");
        UUID daSegunda = cadastrar(segunda, TipoProduto.PRODUTO, "Arroz");
        UUID movimento = UUID.randomUUID();
        ajuste(segunda, daSegunda, ajusteCom(movimento, "5", "contagem"))
                .andExpect(status().isNoContent());

        // A consulta da primeira Conta não enxerga o movimento da segunda, e quem recusa é a chave
        // primária; a resposta não diz nada sobre a outra Conta.
        String corpo = ajuste(primeira, daPrimeira, ajusteCom(movimento, "5", "contagem"))
                .andExpect(status().isInternalServerError())
                .andReturn().getResponse().getContentAsString();
        assertThat(corpo).doesNotContain(movimento.toString()).doesNotContain(daSegunda.toString());

        assertThat(saldo(daPrimeira)).isEqualByComparingTo("0");
        assertThat(saldo(daSegunda)).isEqualByComparingTo("5");
        assertThat(jdbc.queryForObject("SELECT produto_id FROM movimento_estoque WHERE id = ?",
                UUID.class, movimento)).isEqualTo(daSegunda);
    }

    private ContaCriada contaComEstoque(String nome) {
        ContaCriada conta = criador.criar(nome, SENHA);
        criador.contratar(conta.contaId(), Plano.COMPLETO);
        criador.habilitarEstoque(conta.contaId());
        return conta;
    }

    private static String ajusteCom(UUID movimento, String diferenca, String motivo) {
        return "{\"movimentoId\":\"" + movimento + "\",\"diferenca\":" + diferenca
                + ",\"motivo\":\"" + motivo + "\"}";
    }

    private ResultActions ajuste(ContaCriada conta, UUID produto, String corpo) throws Exception {
        return http.perform(post("/api/estoque/produtos/{id}/ajustes", produto)
                .with(autenticador.como(conta)).contentType(MediaType.APPLICATION_JSON)
                .content(corpo));
    }

    /** Pelo banco, porque a lista do estoque deixa de fora o produto inativado. */
    private BigDecimal saldo(UUID produto) {
        return jdbc.queryForObject("SELECT estoque_atual FROM produto WHERE id = ?",
                BigDecimal.class, produto);
    }

    /** O motivo de 121 caracteres respondia 500, com a pilha no log. */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void motivoDoAjusteAcimaDaColunaRecebe400(CapturedOutput log) throws Exception {
        ContaCriada conta = criador.criar("Mercado do Motivo", SENHA);
        criador.contratar(conta.contaId(), Plano.COMPLETO);
        criador.habilitarEstoque(conta.contaId());
        UUID produto = cadastrar(conta, TipoProduto.PRODUTO, "Feijao");

        http.perform(post("/api/estoque/produtos/{id}/ajustes", produto)
                        .with(autenticador.como(conta)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"diferenca\":5,\"motivo\":\"" + "m".repeat(121) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail")
                        .value("motivo do ajuste de estoque com 121 caracteres; o limite é 120."));
        http.perform(post("/api/estoque/produtos/{id}/ajustes", produto)
                        .with(autenticador.como(conta)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"diferenca\":5,\"motivo\":\"" + "m".repeat(120) + "\"}"))
                .andExpect(status().isNoContent());
        http.perform(get("/api/estoque/produtos").with(autenticador.como(conta)))
                .andExpect(jsonPath("$[0].estoqueAtual").value(5));
        assertThat(log).doesNotContain("Erro inesperado");
    }
}
