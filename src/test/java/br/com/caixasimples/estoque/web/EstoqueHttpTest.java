package br.com.caixasimples.estoque.web;

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
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.Perfil;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Contrato HTTP do estoque: ajuste, mínimo, alerta, perfil e isolamento entre Contas. */
class EstoqueHttpTest extends TesteDeIntegracao {

    private static final String SENHA = "uma senha longa de teste";

    @Autowired MockMvc http;
    @Autowired CriadorDeContaDeTeste criador;
    @Autowired AutenticadorDeTeste autenticador;
    @Autowired ProdutoService produtos;

    @Test
    void adminAjustaDefineMinimoEConsultaAlerta() throws Exception {
        ContaCriada conta = criador.criar("Mercado do Estoque", SENHA);
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
        ContaCriada segunda = criador.criar("Mercado Segundo", SENHA);
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
}
