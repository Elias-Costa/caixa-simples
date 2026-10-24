package br.com.caixasimples.contas.web;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.contas.AutenticadorDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.contas.application.TermosDeUsoService;
import br.com.caixasimples.shared.Perfil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * O aceite dos termos pela API, com o token de verdade: o que a tela obrigatória do administrador
 * manda e a pendência que o aplicativo lê na identidade.
 */
class TermosDeUsoHttpTest extends TesteDeIntegracao {

    private static final String SENHA = "uma senha longa de teste";

    @Autowired
    private MockMvc http;

    @Autowired
    private CriadorDeContaDeTeste criador;

    @Autowired
    private AutenticadorDeTeste autenticador;

    @Test
    @DisplayName("o administrador aceita a versão vigente, e a identidade deixa de trazer a pendência")
    void aceiteDoAdministrador() throws Exception {
        ContaCriada conta = criador.criar("Cafeteria Aurora", SENHA);
        RequestPostProcessor admin = autenticador.como(conta);

        http.perform(get("/api/auth/eu").with(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.termosPendentes").value(true));

        http.perform(aceitar(admin, TermosDeUsoService.VERSAO_VIGENTE))
                .andExpect(status().isNoContent());
        // Repetir, como num segundo toque antes da resposta, responde igual.
        http.perform(aceitar(admin, TermosDeUsoService.VERSAO_VIGENTE))
                .andExpect(status().isNoContent());

        http.perform(get("/api/auth/eu").with(admin))
                .andExpect(jsonPath("$.termosPendentes").value(false));
    }

    @Test
    @DisplayName("o operador recebe 403, a versão antiga 409 e o pedido sem versão 400, e a pendência continua")
    void recusasPorStatus() throws Exception {
        ContaCriada conta = criador.criar("Loja da Esquina", SENHA);
        ContaCriada operador = criador.criar("Loja do Operador", SENHA, Perfil.OPERADOR, true);
        RequestPostProcessor admin = autenticador.como(conta);

        http.perform(aceitar(autenticador.como(operador), TermosDeUsoService.VERSAO_VIGENTE))
                .andExpect(status().isForbidden());
        http.perform(get("/api/auth/eu").with(autenticador.como(operador)))
                .andExpect(jsonPath("$.termosPendentes").value(true));

        http.perform(aceitar(admin, "2000-01-01"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("Os termos mudaram")));
        http.perform(post("/api/conta/termos").with(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        http.perform(get("/api/auth/eu").with(admin))
                .andExpect(jsonPath("$.termosPendentes").value(true));
    }

    private static RequestBuilder aceitar(RequestPostProcessor quem, String versao) {
        return post("/api/conta/termos").with(quem)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"versao\":\"" + versao + "\"}");
    }
}
