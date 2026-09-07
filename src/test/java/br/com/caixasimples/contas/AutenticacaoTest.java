package br.com.caixasimples.contas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import tools.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Autenticacao ponta a ponta (RNF06) — etapa R01.
 *
 * <p>Cobre tambem o achado que motivou a tabela {@code credencial}: o login precisa encontrar
 * alguem <strong>antes</strong> de existir tenant no contexto. Se a busca fosse pelo
 * {@code Usuario}, que tem {@code @TenantId}, ela rodaria sob o tenant sentinela e devolveria
 * vazio — nada aqui passaria.
 */
class AutenticacaoTest extends TesteDeIntegracao {

    private static final String SENHA_VALIDA = "uma senha longa de teste";

    @Autowired
    private MockMvc http;

    @Autowired
    private CriadorDeContaDeTeste criador;

    @Autowired
    private ObjectMapper json;

    @Test
    @DisplayName("login com senha correta devolve token")
    void loginComSenhaCorreta() throws Exception {
        ContaCriada conta = criador.criar("Cafeteria Piloto", SENHA_VALIDA);

        http.perform(login(conta.email(), SENHA_VALIDA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    @Test
    @DisplayName("senha errada, e-mail inexistente e usuario inativo respondem igual: 401")
    void falhasDeLoginNaoSeDistinguem() throws Exception {
        ContaCriada conta = criador.criar("Loja Teste", SENHA_VALIDA);
        ContaCriada inativa = criador.criar("Loja Fechada", SENHA_VALIDA, Perfil.OPERADOR, false);

        http.perform(login(conta.email(), "outra senha bem longa")).andExpect(status().isUnauthorized());
        http.perform(login("ninguem@exemplo.test", SENHA_VALIDA)).andExpect(status().isUnauthorized());
        http.perform(login(inativa.email(), SENHA_VALIDA)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("requisicao sem token e recusada")
    void semTokenNaoEntra() throws Exception {
        http.perform(get("/api/auth/eu")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("com token, o tenant do contexto vem do claim, nao do pedido")
    void tenantVemDoToken() throws Exception {
        ContaCriada conta = criador.criar("Salao Teste", SENHA_VALIDA);
        String token = extrairToken(conta);

        http.perform(get("/api/auth/eu").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contaId").value(conta.contaId().valor().toString()))
                .andExpect(jsonPath("$.usuarioId").value(conta.usuarioId().toString()))
                .andExpect(jsonPath("$.perfil").value("ADMIN"));
    }

    @Test
    @DisplayName("resposta autenticada devolve token renovado")
    void respostaAutenticadaRenovaOToken() throws Exception {
        ContaCriada conta = criador.criar("Oficina Teste", SENHA_VALIDA);
        String token = extrairToken(conta);

        http.perform(get("/api/auth/eu").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Caixa-Simples-Token"));
    }

    @Test
    @DisplayName("token de uma conta nunca resolve para outra")
    void tokenDeUmaContaNaoServeParaOutra() throws Exception {
        ContaCriada contaA = criador.criar("Negocio A", SENHA_VALIDA);
        ContaCriada contaB = criador.criar("Negocio B", SENHA_VALIDA);

        String tokenDeA = extrairToken(contaA);

        String contaResolvida = json
                .readTree(http.perform(get("/api/auth/eu").header("Authorization", "Bearer " + tokenDeA))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString())
                .get("contaId").asText();

        assertThat(contaResolvida).isEqualTo(contaA.contaId().valor().toString());
        assertThat(contaResolvida).isNotEqualTo(contaB.contaId().valor().toString());
    }

    private String extrairToken(ContaCriada conta) throws Exception {
        String corpo = http.perform(login(conta.email(), conta.senha()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(corpo).get("token").asText();
    }

    private org.springframework.test.web.servlet.RequestBuilder login(String email, String senha)
            throws Exception {
        return post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "senha", senha)));
    }
}
