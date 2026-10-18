package br.com.caixasimples.contas.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.contas.Plano;
import java.util.Map;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.ObjectMapper;

/**
 * A correção do cadastro pela API (LGPD, art. 18, I a III): os próprios dados, a troca do próprio
 * e-mail, a correção do nome de um Usuário e a do nome do negócio, cada uma só na Conta de quem pede
 * (RNF05).
 *
 * <p>A contagem da contenção do login vive no contexto do Spring, que é o da suíte inteira; cada
 * teste que confere a senha atual usa uma origem própria, para não somar as falhas de outro teste.
 */
class CorrecaoDoCadastroHttpTest extends TesteDeIntegracao {

    private static final String SENHA = "uma senha longa de teste";
    private static final String ERRADA = "nao e a senha de ninguem";

    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired CriadorDeContaDeTeste criador;

    @Test
    @DisplayName("cada um troca o próprio e-mail com a senha atual: o login passa a ser pelo novo, em minúsculas, e os aparelhos seguem dentro")
    void trocaDoEmail() throws Exception {
        ContaCriada conta = criador.criar("Cafeteria Aurora", SENHA);
        String tablet = entrar(conta.email(), SENHA);
        String celular = entrar(conta.email(), SENHA);
        meusDados(tablet).andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(conta.email()))
                .andExpect(jsonPath("$.nome").value("Pessoa de Cafeteria Aurora"))
                .andExpect(jsonPath("$.perfil").value("ADMIN"));

        String novo = "Novo-" + UUID.randomUUID() + "@Exemplo.Test";
        trocarEmail(celular, SENHA, novo, "203.0.113.81").andExpect(status().isNoContent());

        // O token não leva o e-mail: os dois aparelhos seguem, e os dois veem o novo.
        meusDados(tablet).andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(novo.toLowerCase()));
        http.perform(get("/api/auth/eu").with(comToken(celular))).andExpect(status().isOk());
        login(conta.email(), SENHA).andExpect(status().isUnauthorized());
        login(novo.toLowerCase(), SENHA).andExpect(status().isOk());
    }

    @Test
    @DisplayName("a senha atual errada na troca do e-mail responde 400, conta na contenção do login e não muda nada")
    void senhaAtualErradaNaTrocaDoEmail() throws Exception {
        ContaCriada conta = criador.criar("Padaria Central", SENHA);
        String token = entrar(conta.email(), SENHA);
        String origem = "203.0.113.82";
        String novo = "outro-" + UUID.randomUUID() + "@exemplo.test";

        for (int i = 0; i < 10; i++) {
            trocarEmail(token, ERRADA, novo, origem)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value("A senha atual não confere."));
        }
        trocarEmail(token, SENHA, novo, origem).andExpect(status().isTooManyRequests());
        http.perform(post("/api/auth/login").with(deOrigem(origem))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", conta.email(), "senha", SENHA))))
                .andExpect(status().isTooManyRequests());

        meusDados(token).andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(conta.email()));
    }

    @Test
    @DisplayName("e-mail já usado em outra Conta responde 409, o igual ao atual termina sem efeito, e o malformado ou longo demais, 400")
    void emailNovoRecusado() throws Exception {
        ContaCriada conta = criador.criar("Mercado da Praça", SENHA);
        ContaCriada outraConta = criador.criar("Loja da Esquina", SENHA);
        String token = entrar(conta.email(), SENHA);
        String origem = "203.0.113.83";

        trocarEmail(token, SENHA, outraConta.email(), origem)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(Matchers.containsString("ja existe")));
        trocarEmail(token, SENHA, conta.email().toUpperCase(), origem)
                .andExpect(status().isNoContent());
        trocarEmail(token, SENHA, "sem-arroba", origem).andExpect(status().isBadRequest());
        // 181 caracteres, num formato que a validação de e-mail aceita.
        String longo = "a".repeat(64) + "@" + "b".repeat(55) + "." + "c".repeat(55) + ".test";
        trocarEmail(token, SENHA, longo, origem)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("e-mail com 181 caracteres; o limite é 180."));

        meusDados(token).andExpect(jsonPath("$.email").value(conta.email()));
        login(conta.email(), SENHA).andExpect(status().isOk());
        login(outraConta.email(), SENHA).andExpect(status().isOk());
    }

    @Test
    @DisplayName("o ADMIN corrige o nome do operador, que o vê ao entrar; o operador não corrige, e a outra Conta recebe 404")
    void correcaoDoNome() throws Exception {
        ContaCriada conta = criador.criar("Loja das Palmeiras", SENHA);
        criador.contratar(conta.contaId(), Plano.COMPLETO);
        String admin = entrar(conta.email(), SENHA);
        OperadorCriado operador = criarOperador(admin);
        String doOperador = entrar(operador.email(), SENHA);
        String adminDeFora = entrar(criador.criar("Loja do Cais", SENHA).email(), SENHA);

        corrigirNome(admin, operador.id(), "  Atendente Joana  ").andExpect(status().isNoContent());
        http.perform(get("/api/auth/eu").with(comToken(doOperador)))
                .andExpect(jsonPath("$.nome").value("Atendente Joana"));
        meusDados(doOperador).andExpect(jsonPath("$.nome").value("Atendente Joana"))
                .andExpect(jsonPath("$.email").value(operador.email()))
                .andExpect(jsonPath("$.perfil").value("OPERADOR"));

        corrigirNome(doOperador, conta.usuarioId(), "Nome dado pelo operador")
                .andExpect(status().isForbidden());
        corrigirNome(adminDeFora, operador.id(), "Nome dado pela outra Conta")
                .andExpect(status().isNotFound());
        corrigirNome(admin, operador.id(), "Usuário removido").andExpect(status().isBadRequest());
        corrigirNome(admin, operador.id(), " ").andExpect(status().isBadRequest());
        corrigirNome(admin, operador.id(), "a".repeat(121)).andExpect(status().isBadRequest());
        http.perform(get("/api/auth/eu").with(comToken(doOperador)))
                .andExpect(jsonPath("$.nome").value("Atendente Joana"));

        http.perform(post("/api/usuarios/{id}/inativar", operador.id()).with(comToken(admin)))
                .andExpect(status().isNoContent());
        http.perform(post("/api/usuarios/{id}/anonimizar", operador.id()).with(comToken(admin)))
                .andExpect(status().isNoContent());
        corrigirNome(admin, operador.id(), "Atendente Joana").andExpect(status().isConflict());
        http.perform(get("/api/usuarios").with(comToken(admin)))
                .andExpect(jsonPath("$[?(@.id == '" + operador.id() + "')].nome")
                        .value("Usuário removido"));
    }

    @Test
    @DisplayName("o ADMIN corrige o nome do negócio, que a identidade e a configuração passam a mostrar; o operador não, e a outra Conta fica com o dela")
    void nomeDoNegocio() throws Exception {
        ContaCriada conta = criador.criar("Empório do Bairro", SENHA);
        criador.contratar(conta.contaId(), Plano.COMPLETO);
        String admin = entrar(conta.email(), SENHA);
        String doOperador = entrar(criarOperador(admin).email(), SENHA);
        String daOutraConta = entrar(criador.criar("Empório da Serra", SENHA).email(), SENHA);

        corrigirNegocio(admin, "  Empório do Bairro Novo  ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nomeNegocio").value("Empório do Bairro Novo"))
                .andExpect(jsonPath("$.estoqueHabilitado").value(false));
        http.perform(get("/api/conta/configuracao").with(comToken(admin)))
                .andExpect(jsonPath("$.nomeNegocio").value("Empório do Bairro Novo"));
        http.perform(get("/api/auth/eu").with(comToken(doOperador)))
                .andExpect(jsonPath("$.nomeNegocio").value("Empório do Bairro Novo"));

        corrigirNegocio(doOperador, "Nome dado pelo operador").andExpect(status().isForbidden());
        corrigirNegocio(admin, " ").andExpect(status().isBadRequest());
        corrigirNegocio(admin, "a".repeat(121))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail")
                        .value("nome do negócio com 121 caracteres; o limite é 120."));

        http.perform(get("/api/auth/eu").with(comToken(admin)))
                .andExpect(jsonPath("$.nomeNegocio").value("Empório do Bairro Novo"));
        http.perform(get("/api/auth/eu").with(comToken(daOutraConta)))
                .andExpect(jsonPath("$.nomeNegocio").value("Empório da Serra"));
    }

    /** Um operador na Conta do administrador, criado pela API, com login e a senha de teste. */
    private OperadorCriado criarOperador(String tokenDoAdmin) throws Exception {
        String email = "operador-" + UUID.randomUUID() + "@exemplo.test";
        String corpo = http.perform(post("/api/usuarios").with(comToken(tokenDoAdmin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("nome", "Atendente", "perfil",
                                "OPERADOR", "email", email, "senha", SENHA))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new OperadorCriado(UUID.fromString(json.readTree(corpo).get("id").asText()), email);
    }

    private record OperadorCriado(UUID id, String email) {
    }

    private String entrar(String email, String senha) throws Exception {
        String corpo = login(email, senha).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(corpo).get("token").asText();
    }

    private ResultActions login(String email, String senha) throws Exception {
        return http.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "senha", senha))));
    }

    private ResultActions meusDados(String token) throws Exception {
        return http.perform(get("/api/auth/meus-dados").with(comToken(token)));
    }

    private ResultActions trocarEmail(String token, String senhaAtual, String emailNovo,
            String origem) throws Exception {
        return http.perform(post("/api/auth/email").with(comToken(token)).with(deOrigem(origem))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(
                        Map.of("senhaAtual", senhaAtual, "emailNovo", emailNovo))));
    }

    private ResultActions corrigirNome(String token, UUID usuarioId, String nome) throws Exception {
        return http.perform(put("/api/usuarios/{id}/nome", usuarioId).with(comToken(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("nome", nome))));
    }

    private ResultActions corrigirNegocio(String token, String nome) throws Exception {
        return http.perform(put("/api/conta/configuracao/nome-do-negocio").with(comToken(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("nomeNegocio", nome))));
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
