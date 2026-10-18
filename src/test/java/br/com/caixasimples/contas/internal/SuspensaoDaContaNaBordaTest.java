package br.com.caixasimples.contas.internal;

import static br.com.caixasimples.sincronizacao.GestoDeTeste.conteudo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.UsuarioCriado;
import br.com.caixasimples.shared.Perfil;
import br.com.caixasimples.shared.TenantContext;
import br.com.caixasimples.sincronizacao.GestoDeTeste;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * A Conta suspensa pela execução do mantenedor, vista pela API: os aparelhos já entrados saem, o login
 * com a senha certa diz que o acesso está suspenso, o lote não é aplicado, a outra Conta segue (RNF05),
 * e o reverso devolve o acesso só a quem entra de novo.
 *
 * <p>Fica no pacote das execuções para rodá-las como em produção, montadas à mão com os beans do
 * contexto. A contenção do login é da suíte inteira, então cada teste usa uma origem própria.
 */
@ExtendWith(OutputCaptureExtension.class)
class SuspensaoDaContaNaBordaTest extends TesteDeIntegracao {

    private static final String SENHA = "uma senha longa de teste";
    private static final String ERRADA = "nao e a senha de ninguem";
    private static final String MENSAGEM =
            "O acesso desta Conta está suspenso. O administrador recebeu o aviso por e-mail.";

    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired CriadorDeContaDeTeste criador;
    @Autowired ContaRepository contas;
    @Autowired UsuarioRepository usuarios;
    @Autowired TransactionTemplate transacao;
    @Autowired EmissorDeToken emissor;

    @Test
    @DisplayName("o token do administrador e o do operador recebem 401, o lote não é aplicado, e a outra Conta segue")
    void aparelhosEntradosSaem(CapturedOutput saida) throws Exception {
        String origem = "198.51.100.171";
        ContaCriada conta = criador.criar("Cafeteria Aurora", SENHA);
        UsuarioCriado operador = criador.criarOperadorEm(conta.contaId(), "Atendente");
        ContaCriada outra = criador.criar("Loja da Esquina", SENHA);
        String doAdministrador = entrar(conta.email(), SENHA, origem);
        String doOperador = emissor.emitir(operador.usuarioId(), conta.contaId(), Perfil.OPERADOR, 0);
        String daOutra = entrar(outra.email(), SENHA, origem);

        suspender(conta);

        http.perform(eu(doAdministrador, origem)).andExpect(status().isUnauthorized());
        http.perform(eu(doOperador, origem)).andExpect(status().isUnauthorized());
        GestoDeTeste cadastro = GestoDeTeste.de("cliente.criar", UUID.randomUUID(),
                conteudo("nome", "Maria", "contato", null));
        http.perform(post("/api/sincronizacao").with(comToken(doOperador)).with(deOrigem(origem))
                        .contentType(MediaType.APPLICATION_JSON).content(corpo(cadastro)))
                .andExpect(status().isUnauthorized());
        http.perform(eu(daOutra, origem)).andExpect(status().isOk());

        assertThat(saida.getAll())
                .contains("evento=token.recusado conta=" + conta.contaId() + " usuario="
                        + conta.usuarioId() + " motivo=conta_suspensa metodo=GET"
                        + " caminho=/api/auth/eu origem=" + origem)
                .contains("evento=token.recusado conta=" + conta.contaId() + " usuario="
                        + operador.usuarioId() + " motivo=conta_suspensa metodo=POST"
                        + " caminho=/api/sincronizacao origem=" + origem)
                .doesNotContain("Maria");

        // Depois do reverso e de um login novo, a Conta não tem o Cliente do lote recusado.
        reativar(conta);
        String novo = entrar(conta.email(), SENHA, origem);
        http.perform(get("/api/clientes").with(comToken(novo)).with(deOrigem(origem)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("mesmo o token com a versão de acesso atual, emitido depois da suspensão, recebe 401")
    void tokenDaVersaoAtualTambemSai(CapturedOutput saida) throws Exception {
        String origem = "198.51.100.172";
        ContaCriada conta = criador.criar("Padaria Central", SENHA);

        suspender(conta);
        // O token que um login emitiria se lesse a Conta antes da suspensão e a versão depois dela.
        String depois = emissor.emitir(conta.usuarioId(), conta.contaId(), Perfil.ADMIN,
                versao(conta));

        http.perform(eu(depois, origem)).andExpect(status().isUnauthorized());
        assertThat(saida.getAll()).contains("evento=token.recusado conta=" + conta.contaId()
                + " usuario=" + conta.usuarioId() + " motivo=conta_suspensa");
    }

    @Test
    @DisplayName("o login com a senha certa responde 403 com a mensagem, sem o primeiro acesso; com a errada, 401")
    void loginDizQueEstaSuspensa(CapturedOutput saida) throws Exception {
        String origem = "198.51.100.173";
        ContaCriada conta = criador.criar("Mercado da Praça", SENHA);
        ContaCriada outra = criador.criar("Armazém do Centro", SENHA);

        suspender(conta);

        login(conta.email(), SENHA, origem)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(MENSAGEM));
        login(conta.email(), ERRADA, origem).andExpect(status().isUnauthorized());
        login(outra.email(), SENHA, origem).andExpect(status().isOk());

        assertThat(contas.findById(conta.contaId().valor()).orElseThrow()
                .isCatalogoInicialAplicado()).isFalse();
        assertThat(saida.getAll())
                .contains("evento=login.recusado conta=" + conta.contaId() + " usuario="
                        + conta.usuarioId() + " motivo=conta_suspensa origem=" + origem)
                .contains("evento=login.recusado conta=" + conta.contaId() + " usuario="
                        + conta.usuarioId() + " motivo=senha origem=" + origem)
                .doesNotContain(conta.email())
                .doesNotContain(SENHA);
    }

    @Test
    @DisplayName("a senha certa da Conta suspensa não conta na contenção do login")
    void suspensaNaoContaNaContencao() throws Exception {
        String origem = "198.51.100.174";
        ContaCriada conta = criador.criar("Livraria Rosalinda", SENHA);
        suspender(conta);

        // Mais que as dez falhas do par: nenhuma vira a espera do 429.
        for (int i = 0; i < 11; i++) {
            login(conta.email(), SENHA, origem).andExpect(status().isForbidden());
        }

        reativar(conta);
        login(conta.email(), SENHA, origem).andExpect(status().isOk());
    }

    @Test
    @DisplayName("depois do reverso, o token de antes da suspensão continua recusado, e o login novo entra")
    void reversoSoParaQuemEntraDeNovo(CapturedOutput saida) throws Exception {
        String origem = "198.51.100.175";
        ContaCriada conta = criador.criar("Quitanda da Vila", SENHA);
        String antes = entrar(conta.email(), SENHA, origem);

        suspender(conta);
        reativar(conta);

        http.perform(eu(antes, origem)).andExpect(status().isUnauthorized());
        String novo = entrar(conta.email(), SENHA, origem);
        http.perform(eu(novo, origem)).andExpect(status().isOk());
        assertThat(saida.getAll()).contains("evento=token.recusado conta=" + conta.contaId()
                + " usuario=" + conta.usuarioId() + " motivo=versao_antiga");
    }

    private void suspender(ContaCriada conta) {
        new SuspensaoDeContaDoMantenedor(new PropriedadesDaSuspensao(id(conta)), contas, usuarios,
                transacao).run(new DefaultApplicationArguments());
    }

    private void reativar(ContaCriada conta) {
        new ReativacaoDeContaDoMantenedor(new PropriedadesDaSuspensao(id(conta)), contas, transacao)
                .run(new DefaultApplicationArguments());
    }

    private static String id(ContaCriada conta) {
        return conta.contaId().valor().toString();
    }

    private int versao(ContaCriada conta) {
        return TenantContext.executarComo(conta.contaId(),
                () -> usuarios.findById(conta.usuarioId()).orElseThrow().getVersaoDoAcesso());
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

    private String corpo(GestoDeTeste gesto) {
        return json.writeValueAsString(Map.of("operacoes", List.of(gesto.paraJson())));
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
