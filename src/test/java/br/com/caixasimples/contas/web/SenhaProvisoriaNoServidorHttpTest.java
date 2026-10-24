package br.com.caixasimples.contas.web;

import static br.com.caixasimples.sincronizacao.GestoDeTeste.conteudo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.contas.Plano;
import br.com.caixasimples.sincronizacao.GestoDeTeste;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Com a senha escolhida por outra pessoa, o servidor só responde à identidade, à troca da própria
 * senha e à saída de todos os aparelhos; toda outra rota da API recebe 403, inclusive o lote do
 * aparelho, que não é aplicado. Escolhida a senha nova, tudo volta.
 *
 * <p>A senha provisória vem do caminho real: o administrador cria o operador pela API.
 */
@ExtendWith(OutputCaptureExtension.class)
class SenhaProvisoriaNoServidorHttpTest extends TesteDeIntegracao {

    private static final String SENHA = "uma senha longa de teste";
    private static final String PROVISORIA = "senha provisoria dada pelo dono";
    private static final String NOVA = "outra frase longa escolhida por mim";
    private static final String RECUSA = "Troque a senha provisória para continuar.";

    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired CriadorDeContaDeTeste criador;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("com a senha provisória, venda, caixa, cadastro e lote recebem 403 e a identidade responde; depois da troca, tudo volta")
    void soATrocaEAIdentidadeRespondem(CapturedOutput log) throws Exception {
        String email = operadorComSenhaProvisoria("Cafeteria Aurora");
        String provisorio = entrar(email, PROVISORIA);
        GestoDeTeste gesto = GestoDeTeste.de("cliente.criar", UUID.randomUUID(),
                conteudo("nome", "Ana", "contato", null));

        http.perform(get("/api/auth/eu").with(comToken(provisorio)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.senhaProvisoria").value(true));
        recusada(http.perform(get("/api/caixa/sessoes/aberta").with(comToken(provisorio))));
        recusada(http.perform(post("/api/caixa/sessoes").with(comToken(provisorio))
                .contentType(MediaType.APPLICATION_JSON).content("{\"valorAbertura\":0}")));
        recusada(http.perform(get("/api/produtos").with(comToken(provisorio))));
        recusada(http.perform(get("/api/auth/meus-dados").with(comToken(provisorio))));
        recusada(enviar(provisorio, gesto));
        // O caminho com uma barra a mais não é a identidade: cai na recusa, que é o lado seguro.
        http.perform(get("/api/auth/eu/").with(comToken(provisorio)))
                .andExpect(status().isForbidden());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM sessao_caixa s JOIN credencial c"
                + " ON c.usuario_id = s.usuario_id WHERE c.email = ?", Long.class, email)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM operacao_sincronizada WHERE operacao_id = ?", Long.class,
                gesto.operacaoId())).isZero();
        assertThat(log).contains("evento=acesso.recusado")
                .contains("motivo=senha_provisoria")
                .contains("caminho=/api/caixa/sessoes/aberta");

        MvcResult troca = http.perform(post("/api/auth/senha").with(comToken(provisorio))
                        .with(deOrigem("203.0.113.90"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                Map.of("senhaAtual", PROVISORIA, "senhaNova", NOVA))))
                .andExpect(status().isOk()).andReturn();
        String novo = json.readTree(troca.getResponse().getContentAsString()).get("token").asText();

        http.perform(get("/api/caixa/sessoes/aberta").with(comToken(novo)))
                .andExpect(status().isNoContent());
        http.perform(get("/api/auth/meus-dados").with(comToken(novo)))
                .andExpect(status().isOk());
        JsonNode resposta = json.readTree(enviar(novo, gesto)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(resposta.path("resultados").get(0).path("resultado").asText())
                .isEqualTo("APLICADA");
    }

    @Test
    @DisplayName("com a senha provisória, sair de todos os aparelhos responde, e o token cai")
    void sairDeTodosResponde() throws Exception {
        String email = operadorComSenhaProvisoria("Padaria Aurora");
        String provisorio = entrar(email, PROVISORIA);

        http.perform(post("/api/auth/sair-de-todos").with(comToken(provisorio)))
                .andExpect(status().isNoContent());
        http.perform(get("/api/auth/eu").with(comToken(provisorio)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a senha redefinida pelo administrador volta a ser provisória e barra o operador até a troca")
    void redefinicaoVoltaABarrar() throws Exception {
        ContaCriada conta = criador.criar("Mercado Aurora", SENHA);
        criador.contratar(conta.contaId(), Plano.COMPLETO);
        String admin = entrar(conta.email(), SENHA);
        String email = "operador-" + UUID.randomUUID() + "@exemplo.test";
        UUID operador = criarOperador(admin, email);
        String provisorio = entrar(email, PROVISORIA);
        http.perform(post("/api/auth/senha").with(comToken(provisorio)).with(deOrigem("203.0.113.91"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                Map.of("senhaAtual", PROVISORIA, "senhaNova", NOVA))))
                .andExpect(status().isOk());
        String escolhida = entrar(email, NOVA);
        http.perform(get("/api/produtos").with(comToken(escolhida))).andExpect(status().isOk());

        http.perform(post("/api/usuarios/{id}/redefinir-senha", operador).with(comToken(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("senha", PROVISORIA))))
                .andExpect(status().isNoContent());

        recusada(http.perform(get("/api/produtos").with(comToken(entrar(email, PROVISORIA)))));
        // O administrador, com a senha escolhida por ele, segue.
        http.perform(get("/api/produtos").with(comToken(admin))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("a senha provisória de um usuário numa Conta não barra o usuário de outra Conta, nem o administrador da própria")
    void aMarcaNaoAtravessaConta() throws Exception {
        ContaCriada contaA = criador.criar("Empório Aurora", SENHA);
        criador.contratar(contaA.contaId(), Plano.COMPLETO);
        String adminA = entrar(contaA.email(), SENHA);
        String emailDoOperador = "operador-" + UUID.randomUUID() + "@exemplo.test";
        criarOperador(adminA, emailDoOperador);
        ContaCriada contaB = criador.criar("Loja da Esquina", SENHA);

        String provisorioA = entrar(emailDoOperador, PROVISORIA);
        String adminB = entrar(contaB.email(), SENHA);

        recusada(http.perform(get("/api/produtos").with(comToken(provisorioA))));
        http.perform(get("/api/produtos").with(comToken(adminB))).andExpect(status().isOk());
        http.perform(get("/api/produtos").with(comToken(adminA))).andExpect(status().isOk());
    }

    private static void recusada(ResultActions resposta) throws Exception {
        resposta.andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail").value(RECUSA));
    }

    /** Um operador criado pelo administrador, cuja senha nasce provisória; devolve o e-mail. */
    private String operadorComSenhaProvisoria(String negocio) throws Exception {
        ContaCriada conta = criador.criar(negocio, SENHA);
        criador.contratar(conta.contaId(), Plano.COMPLETO);
        String email = "operador-" + UUID.randomUUID() + "@exemplo.test";
        criarOperador(entrar(conta.email(), SENHA), email);
        return email;
    }

    private UUID criarOperador(String tokenDoAdmin, String email) throws Exception {
        String corpo = http.perform(post("/api/usuarios").with(comToken(tokenDoAdmin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("nome", "Atendente", "perfil",
                                "OPERADOR", "email", email, "senha", PROVISORIA))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(corpo).get("id").asText());
    }

    private String entrar(String email, String senha) throws Exception {
        String corpo = http.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "senha", senha))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(corpo).get("token").asText();
    }

    private ResultActions enviar(String token, GestoDeTeste gesto) throws Exception {
        return http.perform(post("/api/sincronizacao").with(comToken(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("operacoes", List.of(gesto.paraJson())))));
    }

    private static RequestPostProcessor comToken(String token) {
        return requisicao -> {
            requisicao.addHeader("Authorization", "Bearer " + token);
            return requisicao;
        };
    }

    private static RequestPostProcessor deOrigem(String origem) {
        return requisicao -> {
            requisicao.setRemoteAddr(origem);
            return requisicao;
        };
    }
}
