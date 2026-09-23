package br.com.caixasimples.contas.web;

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
import br.com.caixasimples.estoque.application.EstoqueService;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.Perfil;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/** Contrato HTTP da administração, com autorização e isolamento entre Contas (RF17, RF29, RNF05). */
class AdministracaoHttpTest extends TesteDeIntegracao {

    private static final String SENHA = "uma senha longa de teste";

    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired CriadorDeContaDeTeste criador;
    @Autowired AutenticadorDeTeste autenticador;
    @Autowired ProdutoService produtos;
    @Autowired EstoqueService estoque;

    @Test
    void adminDoCompletoCriaOperadorQueEntraEListaSemEmail() throws Exception {
        ContaCriada conta = criador.criar("Loja das Palmeiras", SENHA);
        criador.trocarPlano(conta.contaId(), Plano.COMPLETO);
        String email = "operador-" + UUID.randomUUID() + "@exemplo.test";

        String corpo = http.perform(post("/api/usuarios")
                        .with(autenticador.como(conta))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("nome", "Atendente", "perfil",
                                "OPERADOR", "email", email, "senha", SENHA))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(json.readTree(corpo).get("id").asText());

        String lista = http.perform(get("/api/usuarios").with(autenticador.como(conta)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(lista).contains(id.toString(), "Atendente", "OPERADOR", "ativo")
                .doesNotContain(email, "senha");

        String login = http.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "senha", SENHA))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = json.readTree(login).get("token").asText();
        http.perform(get("/api/auth/eu").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.perfil").value("OPERADOR"));
    }

    @Test
    void planoSemMultiusuarioEUltimoAdminRespondemConflito() throws Exception {
        ContaCriada conta = criador.criar("Armazém do Centro", SENHA);
        http.perform(post("/api/usuarios").with(autenticador.como(conta))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("nome", "Outro", "perfil", "OPERADOR",
                                "email", "outro-" + UUID.randomUUID() + "@exemplo.test",
                                "senha", SENHA))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("plano")));
        http.perform(post("/api/usuarios/{id}/inativar", conta.usuarioId())
                        .with(autenticador.como(conta)))
                .andExpect(status().isConflict());
    }

    @Test
    void outraContaNaoEnxergaNemInativaUsuario() throws Exception {
        ContaCriada primeira = criador.criar("Loja Primeira", SENHA);
        ContaCriada segunda = criador.criar("Loja Segunda", SENHA);
        String lista = http.perform(get("/api/usuarios").with(autenticador.como(segunda)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(lista).doesNotContain(primeira.usuarioId().toString());
        http.perform(post("/api/usuarios/{id}/inativar", primeira.usuarioId())
                        .with(autenticador.como(segunda)))
                .andExpect(status().isNotFound());
    }

    @Test
    void configuracaoEPorContaEHistoricoImpedeDesligar() throws Exception {
        ContaCriada primeira = criador.criar("Mercado da Praça", SENHA);
        ContaCriada segunda = criador.criar("Mercado da Rua", SENHA);
        http.perform(put("/api/conta/configuracao").with(autenticador.como(primeira))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"estoqueHabilitado\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estoqueHabilitado").value(true));
        http.perform(get("/api/conta/configuracao").with(autenticador.como(segunda)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estoqueHabilitado").value(false));

        http.perform(put("/api/conta/configuracao").with(autenticador.como(primeira))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"estoqueHabilitado\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estoqueHabilitado").value(false));
        http.perform(put("/api/conta/configuracao").with(autenticador.como(primeira))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"estoqueHabilitado\":true}"))
                .andExpect(status().isOk());

        UUID produtoId = primeira.comoUsuario(() -> produtos.cadastrar(TipoProduto.PRODUTO,
                new DadosDoProduto("Arroz", Money.de("10.00"), null, null, "un", null)));
        primeira.comoUsuario(() -> estoque.ajustar(produtoId, new BigDecimal("2"), "Contagem"));
        primeira.comoUsuario(() -> produtos.inativar(produtoId));

        http.perform(put("/api/conta/configuracao").with(autenticador.como(primeira))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"estoqueHabilitado\":false}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").exists());
        http.perform(get("/api/conta/configuracao").with(autenticador.como(primeira)))
                .andExpect(jsonPath("$.estoqueHabilitado").value(true));

        http.perform(put("/api/conta/configuracao").with(autenticador.como(segunda))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"estoqueHabilitado\":true}"))
                .andExpect(status().isOk());
        http.perform(put("/api/conta/configuracao").with(autenticador.como(segunda))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"estoqueHabilitado\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estoqueHabilitado").value(false));
    }

    @Test
    void operadorRecebe403EmTodasAsRotasDeAdministracao() throws Exception {
        ContaCriada operador = criador.criar("Ponto do Operador", SENHA, Perfil.OPERADOR, true);
        http.perform(get("/api/usuarios").with(autenticador.como(operador)))
                .andExpect(status().isForbidden());
        http.perform(post("/api/usuarios").with(autenticador.como(operador))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("nome", "Pessoa", "perfil", "ADMIN",
                                "email", "pessoa@exemplo.test", "senha", SENHA))))
                .andExpect(status().isForbidden());
        http.perform(post("/api/usuarios/{id}/inativar", operador.usuarioId())
                        .with(autenticador.como(operador)))
                .andExpect(status().isForbidden());
        http.perform(get("/api/conta/configuracao").with(autenticador.como(operador)))
                .andExpect(status().isForbidden());
        http.perform(put("/api/conta/configuracao").with(autenticador.como(operador))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"estoqueHabilitado\":true}"))
                .andExpect(status().isForbidden());
    }
}
