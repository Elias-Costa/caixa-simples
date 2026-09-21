package br.com.caixasimples.contas;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.ObjectMapper;

/**
 * Faz o login real de uma conta de teste e devolve o que um endpoint autenticado precisa.
 *
 * <p>Passa pelo mesmo caminho de produção, {@code POST /api/auth/login}, e não fabrica um token:
 * assim todo teste de endpoint também prova que a credencial gravada pela fixture entra. Quem
 * testa o próprio login continua fazendo à mão, porque ali o login é o assunto.
 */
public class AutenticadorDeTeste {

    private final MockMvc http;
    private final ObjectMapper json;

    public AutenticadorDeTeste(MockMvc http, ObjectMapper json) {
        this.http = http;
        this.json = json;
    }

    public String tokenDe(ContaCriada conta) {
        try {
            String corpo = http.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(
                                    Map.of("email", conta.email(), "senha", conta.senha()))))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            return json.readTree(corpo).get("token").asText();
        } catch (Exception e) {
            throw new IllegalStateException("login de teste falhou para " + conta.email(), e);
        }
    }

    /** Para {@code perform(get(...).with(autenticador.como(conta)))}. */
    public RequestPostProcessor como(ContaCriada conta) {
        String token = tokenDe(conta);
        return requisicao -> {
            requisicao.addHeader("Authorization", "Bearer " + token);
            return requisicao;
        };
    }
}
