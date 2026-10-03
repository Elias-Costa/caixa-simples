package br.com.caixasimples.contas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.caixasimples.CodificadorDeSenhaQueRegistra;
import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.contas.internal.CredencialRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import tools.jackson.databind.ObjectMapper;

/**
 * Contenção de abuso no login (RNF06), pela API: as falhas contam pelo par de origem e e-mail e pela
 * origem, a tentativa além do limite recebe 429 com o tempo de espera, e a recusa de um e-mail
 * inexistente custa o mesmo que a de uma senha errada.
 *
 * <p>A contagem vive no contexto do Spring, que é o da suíte inteira. Cada teste usa uma origem
 * própria, de uma faixa reservada para documentação, para não somar com os logins dos outros
 * testes, que chegam todos de 127.0.0.1. O IP lido da direita do cabeçalho do proxy, com o prefixo
 * forjado, é assunto de {@code ContencaoDoLoginNaBordaTest}, que passa pelo servidor de verdade.
 */
class ContencaoDoLoginTest extends TesteDeIntegracao {

    private static final String SENHA = "uma senha longa de teste";
    private static final String SENHA_ERRADA = "outra senha bem longa";

    @Autowired
    private MockMvc http;

    @Autowired
    private CriadorDeContaDeTeste criador;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private CodificadorDeSenhaQueRegistra codificador;

    @Autowired
    private CredencialRepository credenciais;

    @Test
    @DisplayName("dez senhas erradas do mesmo e-mail na mesma origem, e a seguinte espera a janela acabar")
    void parEsgotadoRecusaComAEspera() throws Exception {
        ContaCriada conta = criador.criar("Loja da Esquina", SENHA);
        String origem = "203.0.113.11";

        for (int i = 0; i < 10; i++) {
            http.perform(login(conta.email(), SENHA_ERRADA, origem)).andExpect(status().isUnauthorized());
        }

        MvcResult recusa = http.perform(login(conta.email(), SENHA_ERRADA, origem))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail").value(matchesPattern(
                        "Muitas tentativas de entrar\\. Tente de novo em \\d+ minutos?\\.")))
                .andReturn();
        assertThat(Long.parseLong(recusa.getResponse().getHeader("Retry-After"))).isBetween(1L, 900L);

        // A senha certa também espera: aceitá-la deixaria quem tenta descobrir a senha do mesmo jeito.
        http.perform(login(conta.email(), SENHA, origem)).andExpect(status().isTooManyRequests());

        // Quem tenta de outra origem não alcança a contagem do Usuário legítimo.
        http.perform(login(conta.email(), SENHA, "198.51.100.11")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("trinta falhas da mesma origem com e-mails diferentes, e a seguinte espera mesmo certa")
    void origemEsgotadaRecusaQualquerEmail() throws Exception {
        ContaCriada conta = criador.criar("Mercearia Boa Vista", SENHA);
        String origem = "203.0.113.12";

        for (int i = 0; i < 30; i++) {
            http.perform(login(inexistente(), SENHA_ERRADA, origem)).andExpect(status().isUnauthorized());
        }

        http.perform(login(conta.email(), SENHA, origem)).andExpect(status().isTooManyRequests());
        http.perform(login(conta.email(), SENHA, "198.51.100.12")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("o login certo zera a contagem do par")
    void loginCertoZeraOPar() throws Exception {
        ContaCriada conta = criador.criar("Padaria Aurora", SENHA);
        String origem = "203.0.113.13";

        for (int i = 0; i < 9; i++) {
            http.perform(login(conta.email(), SENHA_ERRADA, origem)).andExpect(status().isUnauthorized());
        }
        http.perform(login(conta.email(), SENHA, origem)).andExpect(status().isOk());

        for (int i = 0; i < 10; i++) {
            http.perform(login(conta.email(), SENHA_ERRADA, origem)).andExpect(status().isUnauthorized());
        }
        http.perform(login(conta.email(), SENHA_ERRADA, origem)).andExpect(status().isTooManyRequests());
    }

    /**
     * Vinte e nove falhas deixam a origem a uma do limite. Se o login certo contasse, os três que
     * vêm em seguida a esgotariam e a falha seguinte já seria recusada.
     */
    @Test
    @DisplayName("o login certo não pesa na contagem da origem")
    void loginCertoNaoPesaNaOrigem() throws Exception {
        ContaCriada conta = criador.criar("Oficina do Bairro", SENHA);
        String origem = "203.0.113.14";

        for (int i = 0; i < 29; i++) {
            http.perform(login(inexistente(), SENHA_ERRADA, origem)).andExpect(status().isUnauthorized());
        }
        for (int i = 0; i < 3; i++) {
            http.perform(login(conta.email(), SENHA, origem)).andExpect(status().isOk());
        }

        http.perform(login(inexistente(), SENHA_ERRADA, origem)).andExpect(status().isUnauthorized());
        http.perform(login(inexistente(), SENHA_ERRADA, origem)).andExpect(status().isTooManyRequests());
    }

    /**
     * O custo de uma conferência BCrypt está no prefixo do hash conferido, com a versão e o fator de
     * trabalho. Recusar o e-mail inexistente sem conferir senha nenhuma responderia mais rápido do que
     * a senha errada, e o tempo diria quais e-mails existem.
     */
    @Test
    @DisplayName("o e-mail inexistente custa a mesma conferência de senha que a senha errada, sem criar credencial")
    void recusasComOMesmoCusto() throws Exception {
        ContaCriada conta = criador.criar("Cafeteria Aurora", SENHA);
        String emailInexistente = inexistente();
        String origem = "203.0.113.15";

        codificador.esquecer();
        http.perform(login(conta.email(), SENHA_ERRADA, origem)).andExpect(status().isUnauthorized());
        List<String> naSenhaErrada = codificador.conferidos();

        codificador.esquecer();
        http.perform(login(emailInexistente, SENHA_ERRADA, origem)).andExpect(status().isUnauthorized());
        List<String> noEmailInexistente = codificador.conferidos();

        assertThat(naSenhaErrada).hasSize(1);
        assertThat(noEmailInexistente).hasSize(1);
        assertThat(custo(noEmailInexistente.get(0))).isEqualTo(custo(naSenhaErrada.get(0)));
        assertThat(noEmailInexistente.get(0)).isNotEqualTo(naSenhaErrada.get(0));
        assertThat(credenciais.findByEmailIgnoreCase(emailInexistente)).isEmpty();
    }

    private static String inexistente() {
        return "ninguem-" + UUID.randomUUID() + "@exemplo.test";
    }

    /** O prefixo de um hash BCrypt, como {@code $2a$10$}: a versão e o fator de trabalho. */
    private static String custo(String hash) {
        return hash.substring(0, 7);
    }

    private RequestBuilder login(String email, String senha, String origem) throws Exception {
        return post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "senha", senha)))
                .with(requisicao -> {
                    requisicao.setRemoteAddr(origem);
                    return requisicao;
                });
    }
}
