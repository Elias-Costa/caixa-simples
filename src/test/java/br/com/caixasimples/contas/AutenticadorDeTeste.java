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

    /**
     * Entra com a senha provisória que outra pessoa escolheu, como a do usuário criado pela API, e a
     * troca pela própria, como o aplicativo exige no primeiro acesso; devolve o token novo. Com a
     * provisória, o servidor só responde à identidade e à troca, e o teste que usa esse usuário para
     * outra coisa precisa deste passo antes.
     */
    public String trocarSenhaProvisoria(String email, String provisoria, String nova) {
        try {
            String login = http.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(
                                    Map.of("email", email, "senha", provisoria))))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            String provisorio = json.readTree(login).get("token").asText();
            String troca = http.perform(post("/api/auth/senha")
                            .header("Authorization", "Bearer " + provisorio)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(
                                    Map.of("senhaAtual", provisoria, "senhaNova", nova))))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            return json.readTree(troca).get("token").asText();
        } catch (Exception e) {
            throw new IllegalStateException("troca da senha provisória falhou para " + email, e);
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
