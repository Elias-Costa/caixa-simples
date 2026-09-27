package br.com.caixasimples.shared.web;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.caixasimples.TesteDeIntegracao;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A rota que a hospedagem consulta para saber se a instância está de pé, e o que mais o Actuator
 * mostra por HTTP.
 *
 * <p>A hospedagem não tem token, então a rota responde sem ele; e responde só o estado, sem
 * detalhe, porque é pública. Ela não consulta o banco: quem decide reiniciar a instância por ela
 * não pode reiniciar por causa de uma queda que não é da aplicação.
 */
class SaudeDaInstanciaTest extends TesteDeIntegracao {

    @Autowired
    private MockMvc http;

    @Test
    @DisplayName("a rota de vida responde UP sem token e sem detalhe")
    void vidaRespondeSemToken() throws Exception {
        http.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    @DisplayName("o Actuator expõe só o health por HTTP")
    void soOHealthEhExposto() throws Exception {
        http.perform(get("/actuator"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._links", aMapWithSize(3)))
                .andExpect(jsonPath("$._links.self").exists())
                .andExpect(jsonPath("$._links.health").exists())
                .andExpect(jsonPath("$._links['health-path']").exists());
    }
}
