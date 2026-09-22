package br.com.caixasimples.cadastro.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.contas.AutenticadorDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.shared.Perfil;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.ObjectMapper;

/** Contrato HTTP do cadastro, inclusive autorização e isolamento entre Contas (RNF05). */
class CadastroHttpTest extends TesteDeIntegracao {

    private static final String SENHA = "uma senha longa de teste";
    private static final String PRODUTO = """
            {"tipo":"PRODUTO","nome":"Café especial","preco":12.50,
             "codigo":"C-1","categoria":"Bebidas","unidade":"un",
             "atributos":{"torra":"média","gramas":250}}
            """;
    private static final String PRODUTO_EDITADO = """
            {"nome":"Café da casa","preco":15.00,
             "codigo":"C-1","categoria":"Bebidas","unidade":"un",
             "atributos":{"torra":"escura"}}
            """;
    private static final String CLIENTE = "{" + "\"nome\":\"Dona Marta\",\"contato\":\"(75) 90000-0000\"}";

    @Autowired MockMvc http;
    @Autowired CriadorDeContaDeTeste criador;
    @Autowired AutenticadorDeTeste autenticador;
    @Autowired ObjectMapper json;

    @Test
    void produtoCadastroEdicaoInativacaoEIsolamento() throws Exception {
        ContaCriada contaA = criador.criar("Cafeteria A", SENHA);
        ContaCriada contaB = criador.criar("Cafeteria B", SENHA);
        RequestPostProcessor adminA = autenticador.como(contaA);
        RequestPostProcessor adminB = autenticador.como(contaB);

        UUID id = UUID.fromString(json.readTree(http.perform(post("/api/produtos")
                        .with(adminA).contentType(MediaType.APPLICATION_JSON).content(PRODUTO))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").exists())
                .andReturn().getResponse().getContentAsString()).get("id").asText());

        http.perform(get("/api/produtos").with(adminA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id.toString()))
                .andExpect(jsonPath("$[0].atributos.gramas").value(250));
        http.perform(post("/api/produtos").with(adminA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PRODUTO.replace("Café especial", "Café repetido")))
                .andExpect(status().isConflict());
        http.perform(get("/api/produtos").with(adminB))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        http.perform(put("/api/produtos/{id}", id).with(adminB)
                        .contentType(MediaType.APPLICATION_JSON).content(PRODUTO_EDITADO))
                .andExpect(status().isNotFound());

        http.perform(put("/api/produtos/{id}", id).with(adminA)
                        .contentType(MediaType.APPLICATION_JSON).content(PRODUTO_EDITADO))
                .andExpect(status().isNoContent());
        http.perform(get("/api/produtos").with(adminA))
                .andExpect(jsonPath("$[0].nome").value("Café da casa"))
                .andExpect(jsonPath("$[0].atributos.torra").value("escura"));
        http.perform(post("/api/produtos/{id}/inativar", id).with(adminA))
                .andExpect(status().isNoContent());
        http.perform(get("/api/produtos").with(adminA))
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void operadorRecebe403NasTresEscritasDeProduto() throws Exception {
        ContaCriada admin = criador.criar("Loja administrada", SENHA);
        ContaCriada operador = criador.criar("Loja com operador", SENHA, Perfil.OPERADOR, true);
        UUID id = UUID.fromString(json.readTree(http.perform(post("/api/produtos")
                        .with(autenticador.como(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content(PRODUTO)).andReturn().getResponse().getContentAsString())
                .get("id").asText());
        RequestPostProcessor tokenOperador = autenticador.como(operador);

        http.perform(post("/api/produtos").with(tokenOperador)
                        .contentType(MediaType.APPLICATION_JSON).content(PRODUTO))
                .andExpect(status().isForbidden());
        http.perform(put("/api/produtos/{id}", id).with(tokenOperador)
                        .contentType(MediaType.APPLICATION_JSON).content(PRODUTO_EDITADO))
                .andExpect(status().isForbidden());
        http.perform(post("/api/produtos/{id}/inativar", id).with(tokenOperador))
                .andExpect(status().isForbidden());
        http.perform(get("/api/produtos").with(tokenOperador))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void clienteDoOperadorEditaInativaReativaEOutraContaNaoEnxerga() throws Exception {
        ContaCriada contaA = criador.criar("Balcão A", SENHA, Perfil.OPERADOR, true);
        ContaCriada contaB = criador.criar("Balcão B", SENHA);
        RequestPostProcessor operador = autenticador.como(contaA);
        RequestPostProcessor outraConta = autenticador.como(contaB);

        UUID id = UUID.fromString(json.readTree(http.perform(post("/api/clientes")
                        .with(operador).contentType(MediaType.APPLICATION_JSON).content(CLIENTE))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("id").asText());
        http.perform(get("/api/clientes").with(operador))
                .andExpect(jsonPath("$[0].nome").value("Dona Marta"));
        http.perform(get("/api/clientes").with(outraConta))
                .andExpect(jsonPath("$").isEmpty());
        http.perform(put("/api/clientes/{id}", id).with(outraConta)
                        .contentType(MediaType.APPLICATION_JSON).content(CLIENTE))
                .andExpect(status().isNotFound());

        http.perform(put("/api/clientes/{id}", id).with(operador)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nome\":\"Marta\",\"contato\":null}"))
                .andExpect(status().isNoContent());
        http.perform(post("/api/clientes/{id}/inativar", id).with(operador))
                .andExpect(status().isNoContent());
        http.perform(get("/api/clientes").with(operador))
                .andExpect(jsonPath("$").isEmpty());
        http.perform(get("/api/clientes/inativos").with(operador))
                .andExpect(jsonPath("$[0].nome").value("Marta"));
        http.perform(get("/api/clientes/inativos").with(outraConta))
                .andExpect(jsonPath("$").isEmpty());
        http.perform(post("/api/clientes/{id}/reativar", id).with(operador))
                .andExpect(status().isNoContent());
        http.perform(get("/api/clientes").with(operador))
                .andExpect(jsonPath("$[0].nome").value("Marta"));
    }

    @Test
    void pedidosInvalidosRecebem400ComCampo() throws Exception {
        ContaCriada conta = criador.criar("Cadastro validado", SENHA);
        RequestPostProcessor admin = autenticador.como(conta);
        http.perform(post("/api/produtos").with(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tipo\":\"PRODUTO\",\"nome\":\" \",\"preco\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.nome").exists())
                .andExpect(jsonPath("$.campos.preco").exists());
        http.perform(post("/api/clientes").with(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nome\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.nome").exists());
    }
}
