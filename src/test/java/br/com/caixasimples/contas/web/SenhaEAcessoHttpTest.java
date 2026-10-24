package br.com.caixasimples.contas.web;

import static br.com.caixasimples.sincronizacao.GestoDeTeste.conteudo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.VerificadorDeSenhaVazadaFalso;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.contas.Plano;
import br.com.caixasimples.contas.internal.VerificadorDeSenhaVazada;
import br.com.caixasimples.sincronizacao.GestoDeTeste;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Troca de senha, saída de todos os aparelhos e redefinição pelo administrador, pela API (RNF06).
 *
 * <p>Cada aparelho é um login próprio, com o seu token. A revogação vale na requisição seguinte de
 * todos os tokens emitidos antes, sem tocar em outro Usuário nem em outra Conta (RNF05).
 *
 * <p>A contagem da contenção do login vive no contexto do Spring, que é o da suíte inteira; cada
 * teste que pede a troca usa uma origem própria, para não somar as falhas de outro teste.
 */
class SenhaEAcessoHttpTest extends TesteDeIntegracao {

    private static final String SENHA = "uma senha longa de teste";
    private static final String NOVA = "outra frase longa escolhida por mim";
    private static final String PROVISORIA = "senha provisoria dada pelo dono";
    private static final String CABECALHO_DO_TOKEN = "X-Caixa-Simples-Token";

    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired CriadorDeContaDeTeste criador;
    @Autowired VerificadorDeSenhaVazada verificador;
    @Autowired JwtEncoder codificadorDeToken;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void limparVerificador() {
        ((VerificadorDeSenhaVazadaFalso) verificador).limpar();
    }

    @Test
    @DisplayName("o ADMIN único troca a senha: os outros aparelhos dele recebem 401, este segue com o token novo")
    void adminUnicoTrocaASenha() throws Exception {
        ContaCriada conta = criador.criar("Cafeteria Aurora", SENHA);
        criador.contratar(conta.contaId(), Plano.COMPLETO);
        String emailDoOperador = criarOperador(entrar(conta.email(), SENHA)).email();
        ContaCriada outraConta = criador.criar("Loja da Esquina", SENHA);

        String tablet = entrar(conta.email(), SENHA);
        String celular = entrar(conta.email(), SENHA);
        String operador = entrar(emailDoOperador, SENHA);
        String daOutraConta = entrar(outraConta.email(), SENHA);
        eu(tablet).andExpect(status().isOk()).andExpect(jsonPath("$.senhaProvisoria").value(true));

        MvcResult troca = trocar(celular, SENHA, NOVA, "203.0.113.70")
                .andExpect(status().isOk())
                .andReturn();
        String novo = json.readTree(troca.getResponse().getContentAsString()).get("token").asText();

        eu(tablet).andExpect(status().isUnauthorized());
        eu(celular).andExpect(status().isUnauthorized());
        // O cabeçalho de renovação da própria troca saiu antes dela, com a versão anterior.
        eu(troca.getResponse().getHeader(CABECALHO_DO_TOKEN)).andExpect(status().isUnauthorized());
        eu(novo).andExpect(status().isOk()).andExpect(jsonPath("$.senhaProvisoria").value(false));

        // Outro Usuário da mesma Conta e a outra Conta não são afetados.
        eu(operador).andExpect(status().isOk());
        eu(daOutraConta).andExpect(status().isOk())
                .andExpect(jsonPath("$.senhaProvisoria").value(true));
        login(outraConta.email(), SENHA).andExpect(status().isOk());

        login(conta.email(), SENHA).andExpect(status().isUnauthorized());
        login(conta.email(), NOVA).andExpect(status().isOk());
    }

    @Test
    @DisplayName("senha atual errada responde 400, sem derrubar a sessão, e conta na contenção do login")
    void senhaAtualErradaContaNaContencao() throws Exception {
        ContaCriada conta = criador.criar("Padaria Central", SENHA);
        String token = entrar(conta.email(), SENHA);
        String origem = "203.0.113.71";

        for (int i = 0; i < 10; i++) {
            trocar(token, "nao e a senha de ninguem", NOVA, origem)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value("A senha atual não confere."));
        }
        trocar(token, SENHA, NOVA, origem).andExpect(status().isTooManyRequests());
        // O login da mesma origem e do mesmo e-mail espera a mesma janela.
        http.perform(post("/api/auth/login").with(deOrigem(origem))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", conta.email(), "senha", SENHA))))
                .andExpect(status().isTooManyRequests());

        // O 400 não derrubou o token, e de outra origem a troca segue.
        eu(token).andExpect(status().isOk());
        trocar(token, SENHA, NOVA, "203.0.113.72").andExpect(status().isOk());
    }

    @Test
    @DisplayName("senha nova igual à atual ou acima de 72 bytes é recusada com 400, e nada muda")
    void senhaNovaRecusada() throws Exception {
        ContaCriada conta = criador.criar("Mercado da Praça", SENHA);
        String token = entrar(conta.email(), SENHA);

        trocar(token, SENHA, SENHA, "203.0.113.73")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("A senha nova precisa ser diferente da atual."));
        trocar(token, SENHA, "a".repeat(71) + "á", "203.0.113.73")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("72")));

        eu(token).andExpect(status().isOk()).andExpect(jsonPath("$.senhaProvisoria").value(true));
        login(conta.email(), SENHA).andExpect(status().isOk());
    }

    @Test
    @DisplayName("com a lista de vazadas fora do ar, a troca pela própria pessoa entra e a redefinição não")
    void listaForaDoAr() throws Exception {
        ContaCriada conta = criador.criar("Armazém do Centro", SENHA);
        criador.contratar(conta.contaId(), Plano.COMPLETO);
        String admin = entrar(conta.email(), SENHA);
        UUID operador = criarOperador(admin).id();

        ((VerificadorDeSenhaVazadaFalso) verificador).simularIndisponibilidade(true);

        redefinir(admin, operador, PROVISORIA).andExpect(status().isBadRequest());
        trocar(admin, SENHA, NOVA, "203.0.113.74").andExpect(status().isOk());
        login(conta.email(), NOVA).andExpect(status().isOk());
    }

    @Test
    @DisplayName("sair de todos os aparelhos derruba todos, inclusive o que pediu, e a senha continua a mesma")
    void sairDeTodosOsAparelhos() throws Exception {
        ContaCriada conta = criador.criar("Salão Bela Vista", SENHA);
        String tablet = entrar(conta.email(), SENHA);
        String celular = entrar(conta.email(), SENHA);

        http.perform(post("/api/auth/sair-de-todos").with(comToken(celular)))
                .andExpect(status().isNoContent());

        eu(tablet).andExpect(status().isUnauthorized());
        eu(celular).andExpect(status().isUnauthorized());
        eu(entrar(conta.email(), SENHA)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("o ADMIN redefine a senha do operador: os aparelhos dele saem e a senha provisória entra")
    void adminRedefineASenhaDoOperador() throws Exception {
        ContaCriada conta = criador.criar("Loja das Palmeiras", SENHA);
        criador.contratar(conta.contaId(), Plano.COMPLETO);
        String admin = entrar(conta.email(), SENHA);
        OperadorCriado operador = criarOperador(admin);
        String emailDoOperador = operador.email();
        String aparelhoDoOperador = entrar(emailDoOperador, SENHA);

        redefinir(admin, operador.id(), PROVISORIA).andExpect(status().isNoContent());

        eu(aparelhoDoOperador).andExpect(status().isUnauthorized());
        eu(admin).andExpect(status().isOk());
        login(emailDoOperador, SENHA).andExpect(status().isUnauthorized());
        String comProvisoria = entrar(emailDoOperador, PROVISORIA);
        eu(comProvisoria).andExpect(status().isOk())
                .andExpect(jsonPath("$.senhaProvisoria").value(true));

        MvcResult troca = trocar(comProvisoria, PROVISORIA, NOVA, "203.0.113.75")
                .andExpect(status().isOk()).andReturn();
        String novo = json.readTree(troca.getResponse().getContentAsString()).get("token").asText();
        eu(novo).andExpect(status().isOk()).andExpect(jsonPath("$.senhaProvisoria").value(false));
    }

    @Test
    @DisplayName("redefinir a senha de um ADMIN ou de um inativo responde 409; o operador não redefine, 403")
    void redefinicaoRecusada() throws Exception {
        ContaCriada conta = criador.criar("Empório do Bairro", SENHA);
        criador.contratar(conta.contaId(), Plano.COMPLETO);
        String admin = entrar(conta.email(), SENHA);
        OperadorCriado criado = criarOperador(admin);
        UUID operador = criado.id();
        String aparelhoDoOperador = entrar(criado.email(), SENHA);

        redefinir(admin, conta.usuarioId(), PROVISORIA)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail")
                        .value("Só a senha de um operador ativo pode ser redefinida."));
        redefinir(aparelhoDoOperador, conta.usuarioId(), PROVISORIA).andExpect(status().isForbidden());

        http.perform(post("/api/usuarios/{id}/inativar", operador).with(comToken(admin)))
                .andExpect(status().isOk());
        redefinir(admin, operador, PROVISORIA).andExpect(status().isConflict());
        login(conta.email(), SENHA).andExpect(status().isOk());
    }

    @Test
    @DisplayName("o ADMIN de outra Conta recebe 404 ao redefinir a senha de um operador, como para um id que não existe")
    void redefinicaoNaoAtravessaConta() throws Exception {
        ContaCriada conta = criador.criar("Padaria do Porto", SENHA);
        criador.contratar(conta.contaId(), Plano.COMPLETO);
        OperadorCriado operador = criarOperador(entrar(conta.email(), SENHA));
        String aparelhoDoOperador = entrar(operador.email(), SENHA);
        ContaCriada outraConta = criador.criar("Loja do Cais", SENHA);
        String adminDeFora = entrar(outraConta.email(), SENHA);

        redefinir(adminDeFora, operador.id(), PROVISORIA)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Usuário não encontrado nesta Conta."));
        UUID inexistente = UUID.randomUUID();
        redefinir(adminDeFora, inexistente, PROVISORIA)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Usuário não encontrado nesta Conta."));

        // Nem a senha nem a versão de acesso do operador mudaram.
        eu(aparelhoDoOperador).andExpect(status().isOk())
                .andExpect(jsonPath("$.senhaProvisoria").value(true));
        login(operador.email(), SENHA).andExpect(status().isOk());
        login(operador.email(), PROVISORIA).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("token sem a versão de acesso é recusado; com a versão do banco, aceito")
    void tokenSemVersao() throws Exception {
        ContaCriada conta = criador.criar("Cafeteria do Largo", SENHA);

        eu(token(conta, null)).andExpect(status().isUnauthorized());
        eu(token(conta, 1)).andExpect(status().isUnauthorized());
        eu(token(conta, 0)).andExpect(status().isOk());
    }

    /**
     * O aparelho que ficou com a fila sem rede volta com o token revogado: o lote recebe 401, nada é
     * aplicado nem gravado no registro do lote, e o mesmo lote, enviado depois do novo login do mesmo
     * Usuário, é aplicado. É o que o aplicativo faz: devolve o lote à fila e leva ao login.
     */
    @Test
    @DisplayName("o lote do aparelho revogado recebe 401 sem efeito e é aplicado depois do novo login")
    void loteDoAparelhoRevogado() throws Exception {
        ContaCriada conta = criador.criar("Mercearia Nova", SENHA);
        String tablet = entrar(conta.email(), SENHA);
        String celular = entrar(conta.email(), SENHA);
        GestoDeTeste gesto = GestoDeTeste.de("cliente.criar", UUID.randomUUID(),
                conteudo("nome", "Ana", "contato", null));

        MvcResult troca = trocar(celular, SENHA, NOVA, "203.0.113.76")
                .andExpect(status().isOk()).andReturn();
        String novo = json.readTree(troca.getResponse().getContentAsString()).get("token").asText();

        enviar(tablet, gesto).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM operacao_sincronizada WHERE operacao_id = ?", Long.class,
                gesto.operacaoId())).isZero();
        http.perform(get("/api/clientes").with(comToken(novo)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        String tabletDeNovo = entrar(conta.email(), NOVA);
        JsonNode resposta = json.readTree(enviar(tabletDeNovo, gesto)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(resposta.path("resultados").get(0).path("resultado").asText())
                .isEqualTo("APLICADA");
        http.perform(get("/api/clientes").with(comToken(novo)))
                .andExpect(jsonPath("$[0].nome").value("Ana"));
    }

    private String token(ContaCriada conta, Integer versao) {
        Instant agora = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .subject(conta.usuarioId().toString())
                .claim("contaId", conta.contaId().valor().toString())
                .claim("perfil", conta.perfil().name())
                .issuedAt(agora)
                .expiresAt(agora.plus(Duration.ofHours(1)));
        if (versao != null) {
            claims.claim("versaoDoAcesso", versao);
        }
        return codificadorDeToken.encode(JwtEncoderParameters.from(
                JwsHeader.with(() -> "HS256").build(), claims.build())).getTokenValue();
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

    private ResultActions eu(String token) throws Exception {
        return http.perform(get("/api/auth/eu").with(comToken(token)));
    }

    private ResultActions trocar(String token, String atual, String nova, String origem)
            throws Exception {
        return http.perform(post("/api/auth/senha").with(comToken(token)).with(deOrigem(origem))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("senhaAtual", atual, "senhaNova", nova))));
    }

    private ResultActions redefinir(String token, UUID usuarioId, String senha) throws Exception {
        return http.perform(post("/api/usuarios/{id}/redefinir-senha", usuarioId)
                .with(comToken(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("senha", senha))));
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
