package br.com.caixasimples.contas.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.contas.Plano;
import br.com.caixasimples.contas.internal.AssinaturaDePedido;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.Perfil;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.ObjectMapper;

/**
 * Os eventos de segurança que a API escreve no log, um por gesto, com os campos esperados e na
 * ordem em que saem.
 *
 * <p>Em todo caso o log não leva e-mail, nome, contato nem senha: é o que torna o evento um dado
 * pseudônimo, ligado a uma pessoa só pelo banco.
 *
 * <p>A contagem da contenção do login vive no contexto do Spring, que é o da suíte inteira; cada
 * teste usa uma origem própria, para não somar as falhas de outro teste.
 */
@ExtendWith(OutputCaptureExtension.class)
class EventosDeSegurancaHttpTest extends TesteDeIntegracao {

    private static final String SENHA = "uma senha longa de teste";
    private static final String ERRADA = "nao e a senha de ninguem";
    private static final String NOVA = "outra frase longa escolhida por mim";
    private static final String PROVISORIA = "senha provisoria dada pelo dono";

    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired CriadorDeContaDeTeste criador;
    @Autowired JwtEncoder codificadorDeToken;
    @Autowired AssinaturaDePedido assinatura;

    @Test
    @DisplayName("o login certo e os três motivos da recusa, com a Conta e o Usuário quando o e-mail existe")
    void login(CapturedOutput saida) throws Exception {
        String origem = "192.0.2.10";
        ContaCriada conta = criador.criar("Cafeteria Aurora", SENHA);
        ContaCriada inativo = criador.criar("Loja da Esquina", SENHA, Perfil.OPERADOR, false);
        String desconhecido = "ninguem-" + UUID.randomUUID() + "@exemplo.test";

        login(conta.email(), SENHA, origem).andExpect(status().isOk());
        login(conta.email(), ERRADA, origem).andExpect(status().isUnauthorized());
        login(desconhecido, ERRADA, origem).andExpect(status().isUnauthorized());
        login(inativo.email(), SENHA, origem).andExpect(status().isUnauthorized());

        assertThat(saida.getAll())
                .contains("evento=login.certo conta=" + conta.contaId() + " usuario="
                        + conta.usuarioId() + " origem=" + origem)
                .contains("evento=login.recusado conta=" + conta.contaId() + " usuario="
                        + conta.usuarioId() + " motivo=senha origem=" + origem)
                .contains("evento=login.recusado motivo=email_desconhecido origem=" + origem)
                .contains("evento=login.recusado conta=" + inativo.contaId() + " usuario="
                        + inativo.usuarioId() + " motivo=usuario_inativo origem=" + origem);
        semDadoPessoal(saida, conta.email(), inativo.email(), desconhecido, "Pessoa de");
    }

    @Test
    @DisplayName("o bloqueio do login vai ao log uma vez, com a origem e sem o e-mail")
    void bloqueio(CapturedOutput saida) throws Exception {
        String origem = "192.0.2.11";
        ContaCriada conta = criador.criar("Padaria Central", SENHA);

        for (int i = 0; i < 10; i++) {
            login(conta.email(), ERRADA, origem).andExpect(status().isUnauthorized());
        }
        login(conta.email(), SENHA, origem).andExpect(status().isTooManyRequests());
        login(conta.email(), SENHA, origem).andExpect(status().isTooManyRequests());

        assertThat(saida.getAll().split("evento=login.contido contagem=par ate=", -1)).hasSize(2);
        assertThat(saida.getAll()).containsPattern(
                "evento=login\\.contido contagem=par ate=\\S+ origem=" + origem.replace(".", "\\."));
        semDadoPessoal(saida, conta.email());
    }

    @Test
    @DisplayName("o token inválido e o vencido vão ao log com o método, o caminho sem a query e a origem")
    void tokenInvalidoEVencido(CapturedOutput saida) throws Exception {
        String origem = "192.0.2.12";
        ContaCriada conta = criador.criar("Mercado da Praça", SENHA);

        http.perform(eu("nao.e.token", origem).queryParam("busca", "Maria"))
                .andExpect(status().isUnauthorized());
        http.perform(eu(tokenVencido(conta), origem)).andExpect(status().isUnauthorized());

        assertThat(saida.getAll())
                .contains("evento=token.recusado motivo=invalido metodo=GET caminho=/api/auth/eu"
                        + " origem=" + origem)
                .contains("evento=token.recusado motivo=vencido metodo=GET caminho=/api/auth/eu"
                        + " origem=" + origem)
                .doesNotContain("Maria")
                .doesNotContain("nao.e.token");
    }

    @Test
    @DisplayName("o pedido sem token recebe 401 e não vai ao log")
    void semTokenNaoVaiAoLog(CapturedOutput saida) throws Exception {
        http.perform(get("/api/auth/eu").with(deOrigem("192.0.2.13")))
                .andExpect(status().isUnauthorized());

        assertThat(saida.getAll()).doesNotContain("evento=");
    }

    @Test
    @DisplayName("Sair de todos os aparelhos vai ao log, e o token de antes, recusado, também")
    void revogacaoETokenDeVersaoAntiga(CapturedOutput saida) throws Exception {
        String origem = "192.0.2.14";
        ContaCriada conta = criador.criar("Armazém do Centro", SENHA);
        String token = entrar(conta.email(), SENHA, origem);

        http.perform(post("/api/auth/sair-de-todos").with(comToken(token)).with(deOrigem(origem)))
                .andExpect(status().isNoContent());
        http.perform(eu(token, origem)).andExpect(status().isUnauthorized());

        assertThat(saida.getAll())
                .contains("evento=acessos.revogados conta=" + conta.contaId() + " usuario="
                        + conta.usuarioId() + " origem=" + origem)
                .contains("evento=token.recusado conta=" + conta.contaId() + " usuario="
                        + conta.usuarioId() + " motivo=versao_antiga metodo=GET"
                        + " caminho=/api/auth/eu origem=" + origem);
        semDadoPessoal(saida, conta.email());
    }

    @Test
    @DisplayName("a troca de senha recusada e a feita vão ao log, sem nenhuma das senhas")
    void trocaDeSenha(CapturedOutput saida) throws Exception {
        String origem = "192.0.2.15";
        ContaCriada conta = criador.criar("Quitanda da Vila", SENHA);
        String token = entrar(conta.email(), SENHA, origem);

        trocar(token, ERRADA, NOVA, origem).andExpect(status().isBadRequest());
        trocar(token, SENHA, NOVA, origem).andExpect(status().isOk());

        String quem = "conta=" + conta.contaId() + " usuario=" + conta.usuarioId();
        assertThat(saida.getAll())
                .contains("evento=senha.troca_recusada " + quem + " motivo=senha_atual origem="
                        + origem)
                .contains("evento=senha.trocada " + quem + " origem=" + origem);
        semDadoPessoal(saida, conta.email());
    }

    @Test
    @DisplayName("criar, redefinir a senha, inativar e remover o nome de um Usuário vão ao log com o alvo")
    void gestaoDeUsuarios(CapturedOutput saida) throws Exception {
        String origem = "192.0.2.16";
        ContaCriada conta = criador.criar("Sorveteria do Largo", SENHA);
        criador.contratar(conta.contaId(), Plano.COMPLETO);
        String admin = entrar(conta.email(), SENHA, origem);
        String emailDoOperador = "operador-" + UUID.randomUUID() + "@exemplo.test";

        String corpo = http.perform(post("/api/usuarios").with(comToken(admin)).with(deOrigem(origem))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("nome", "Atendente Joana",
                                "perfil", "OPERADOR", "email", emailDoOperador, "senha", SENHA))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID operador = UUID.fromString(json.readTree(corpo).get("id").asText());
        http.perform(post("/api/usuarios/{id}/redefinir-senha", operador).with(comToken(admin))
                        .with(deOrigem(origem))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("senha", PROVISORIA))))
                .andExpect(status().isNoContent());
        String doOperador = entrar(emailDoOperador, PROVISORIA, origem);
        http.perform(post("/api/usuarios/{id}/inativar", operador).with(comToken(admin))
                        .with(deOrigem(origem)))
                .andExpect(status().isNoContent());
        http.perform(eu(doOperador, origem)).andExpect(status().isUnauthorized());
        http.perform(post("/api/usuarios/{id}/anonimizar", operador).with(comToken(admin))
                        .with(deOrigem(origem)))
                .andExpect(status().isNoContent());

        String quem = "conta=" + conta.contaId() + " usuario=" + conta.usuarioId();
        assertThat(saida.getAll())
                .contains("evento=usuario.criado " + quem + " alvo=" + operador
                        + " perfil=OPERADOR origem=" + origem)
                .contains("evento=senha.redefinida " + quem + " alvo=" + operador + " origem="
                        + origem)
                .contains("evento=usuario.inativado " + quem + " alvo=" + operador + " origem="
                        + origem)
                .contains("evento=token.recusado conta=" + conta.contaId() + " usuario=" + operador
                        + " motivo=usuario_inativo metodo=GET caminho=/api/auth/eu origem=" + origem)
                .contains("evento=usuario.nome_removido " + quem + " alvo=" + operador
                        + " origem=" + origem);
        semDadoPessoal(saida, conta.email(), emailDoOperador, "Atendente", "Joana");
    }

    @Test
    @DisplayName("o pedido de plano e o código recusado e o aplicado vão ao log, sem o código")
    void plano(CapturedOutput saida) throws Exception {
        String origem = "192.0.2.17";
        ContaCriada conta = criador.criar("Banca da Feira", SENHA);
        String admin = entrar(conta.email(), SENHA, origem);

        String corpo = http.perform(post("/api/conta/plano/pedidos").with(comToken(admin))
                        .with(deOrigem(origem))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plano\":\"COMPLETO\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID pedido = UUID.fromString(json.readTree(corpo).get("id").asText());
        String trocado = assinatura.codigo(pedido, Plano.COMPLETO, Money.de("0.01"));
        String certo = assinatura.codigo(pedido, Plano.COMPLETO, Money.de(MENSALIDADE_COMPLETO));

        aplicar(admin, pedido, trocado, origem).andExpect(status().isBadRequest());
        aplicar(admin, pedido, certo, origem).andExpect(status().isOk());

        String quem = "conta=" + conta.contaId() + " usuario=" + conta.usuarioId();
        assertThat(saida.getAll())
                .contains("evento=plano.pedido " + quem + " pedido=" + pedido
                        + " plano=COMPLETO origem=" + origem)
                .contains("evento=plano.codigo_recusado " + quem + " pedido=" + pedido
                        + " origem=" + origem)
                .contains("evento=plano.codigo_aplicado " + quem + " pedido=" + pedido
                        + " origem=" + origem)
                .doesNotContain(trocado)
                .doesNotContain(certo);
        semDadoPessoal(saida, conta.email());
    }

    @Test
    @DisplayName("as duas chaves da configuração vão ao log com o campo e o valor novo")
    void configuracao(CapturedOutput saida) throws Exception {
        String origem = "192.0.2.18";
        ContaCriada conta = criador.criar("Empório da Serra", SENHA);
        criador.contratar(conta.contaId(), Plano.COMPLETO);
        String admin = entrar(conta.email(), SENHA, origem);

        http.perform(put("/api/conta/configuracao").with(comToken(admin)).with(deOrigem(origem))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"estoqueHabilitado\":true}"))
                .andExpect(status().isOk());
        http.perform(put("/api/conta/configuracao/nsu").with(comToken(admin)).with(deOrigem(origem))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nsuObrigatorio\":true}"))
                .andExpect(status().isOk());

        String quem = "conta=" + conta.contaId() + " usuario=" + conta.usuarioId();
        assertThat(saida.getAll())
                .contains("evento=configuracao.alterada " + quem + " campo=estoque valor=true"
                        + " origem=" + origem)
                .contains("evento=configuracao.alterada " + quem + " campo=nsu valor=true"
                        + " origem=" + origem);
        semDadoPessoal(saida, conta.email());
    }

    @Test
    @DisplayName("a remoção de Cliente vai ao log com o alvo, sem o nome nem o contato apagados")
    void remocaoDeCliente(CapturedOutput saida) throws Exception {
        String origem = "192.0.2.19";
        ContaCriada conta = criador.criar("Açougue Bom Corte", SENHA);
        String admin = entrar(conta.email(), SENHA, origem);

        String corpo = http.perform(post("/api/clientes").with(comToken(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nome\":\"Maria das Dores\",\"contato\":\"(11) 98888-7777\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID cliente = UUID.fromString(json.readTree(corpo).get("id").asText());
        http.perform(post("/api/clientes/{id}/remover", cliente).with(comToken(admin))
                        .with(deOrigem(origem)))
                .andExpect(status().isNoContent());

        assertThat(saida.getAll()).contains("evento=cliente.removido conta=" + conta.contaId()
                + " usuario=" + conta.usuarioId() + " alvo=" + cliente + " origem=" + origem);
        semDadoPessoal(saida, conta.email(), "Maria", "98888");
    }

    /** As senhas de teste e os dados pessoais do caso não aparecem em lugar nenhum do log. */
    private static void semDadoPessoal(CapturedOutput saida, String... dados) {
        assertThat(saida.getAll())
                .doesNotContain(SENHA)
                .doesNotContain(ERRADA)
                .doesNotContain(NOVA)
                .doesNotContain(PROVISORIA)
                .doesNotContain("@exemplo.test");
        for (String dado : dados) {
            assertThat(saida.getAll()).doesNotContain(dado);
        }
    }

    /** Assinado com a chave da aplicação, vencido há mais que a tolerância do relógio. */
    private String tokenVencido(ContaCriada conta) {
        Instant antes = Instant.now().minus(Duration.ofHours(2));
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(conta.usuarioId().toString())
                .claim("contaId", conta.contaId().valor().toString())
                .claim("perfil", conta.perfil().name())
                .claim("versaoDoAcesso", 0)
                .issuedAt(antes)
                .expiresAt(antes.plus(Duration.ofHours(1)))
                .build();
        return codificadorDeToken.encode(JwtEncoderParameters.from(
                JwsHeader.with(() -> "HS256").build(), claims)).getTokenValue();
    }

    private String entrar(String email, String senha, String origem) throws Exception {
        String corpo = login(email, senha, origem).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(corpo).get("token").asText();
    }

    private ResultActions login(String email, String senha, String origem) throws Exception {
        return http.perform(post("/api/auth/login").with(deOrigem(origem))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "senha", senha))));
    }

    private ResultActions trocar(String token, String atual, String nova, String origem)
            throws Exception {
        return http.perform(post("/api/auth/senha").with(comToken(token)).with(deOrigem(origem))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("senhaAtual", atual, "senhaNova", nova))));
    }

    private ResultActions aplicar(String token, UUID pedido, String codigo, String origem)
            throws Exception {
        return http.perform(post("/api/conta/plano/pedidos/{id}/codigo", pedido)
                .with(comToken(token)).with(deOrigem(origem))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("codigo", codigo))));
    }

    private static MockHttpServletRequestBuilder eu(String token, String origem) {
        return get("/api/auth/eu").with(comToken(token)).with(deOrigem(origem));
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
