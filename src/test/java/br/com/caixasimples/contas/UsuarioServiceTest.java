package br.com.caixasimples.contas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.UsuarioCriado;
import br.com.caixasimples.contas.application.EmailJaCadastradoException;
import br.com.caixasimples.contas.application.PlanoSuspensoException;
import br.com.caixasimples.contas.application.RecursoForaDoPlanoException;
import br.com.caixasimples.contas.application.UltimoAdministradorException;
import br.com.caixasimples.contas.application.UsuarioNaoEncontradoException;
import br.com.caixasimples.contas.application.UsuarioService;
import br.com.caixasimples.contas.application.UsuarioService.UsuarioDaConta;
import br.com.caixasimples.contas.internal.SenhaRecusadaException;
import br.com.caixasimples.shared.AcessoNegadoException;
import br.com.caixasimples.shared.Perfil;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import tools.jackson.databind.ObjectMapper;

/**
 * Gestão de usuários (RF29) contra o banco de verdade, com o login no fim de cada caminho feliz:
 * é ele que prova que o usuário e a credencial nasceram casados, e é por ele que se vê que
 * inativar vale na requisição seguinte, sem esperar o token expirar.
 */
class UsuarioServiceTest extends TesteDeIntegracao {

    private static final String SENHA_DE_TESTE = "uma senha longa de teste";
    private static final String SENHA_DO_OPERADOR = "outra senha longa do atendente";

    @Autowired
    private UsuarioService usuarioService;

    @Autowired
    private CriadorDeContaDeTeste criador;

    @Autowired
    private MockMvc http;

    @Autowired
    private ObjectMapper json;

    @Test
    @DisplayName("o administrador cria um operador no plano completo, e o operador entra")
    void adminCriaOperadorQueEntra() throws Exception {
        ContaCriada conta = criador.criar("Mercado Completo", SENHA_DE_TESTE);
        criador.contratar(conta.contaId(), Plano.COMPLETO);
        String email = "atendente-" + UUID.randomUUID() + "@exemplo.test";

        UUID operadorId = conta.comoUsuario(() ->
                usuarioService.criar("Atendente da tarde", Perfil.OPERADOR, email,
                        SENHA_DO_OPERADOR));

        // O login prova que Usuario e Credencial casaram, e o perfil que volta é o do banco.
        String token = entrar(email, SENHA_DO_OPERADOR);
        http.perform(get("/api/auth/eu").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usuarioId").value(operadorId.toString()))
                .andExpect(jsonPath("$.contaId").value(conta.contaId().toString()))
                .andExpect(jsonPath("$.perfil").value("OPERADOR"));

        conta.comoUsuario(() ->
                assertThat(usuarioService.listar())
                        .extracting(UsuarioDaConta::id, UsuarioDaConta::perfil,
                                UsuarioDaConta::ativo)
                        .containsExactlyInAnyOrder(
                                tuple(conta.usuarioId(), Perfil.ADMIN, true),
                                tuple(operadorId, Perfil.OPERADOR, true)));
    }

    @Test
    @DisplayName("com o plano suspenso, não se cria usuário, e quem já existe continua entrando")
    void planoSuspensoNaoCriaUsuario() throws Exception {
        ContaCriada conta = criador.criar("Mercado Suspenso", SENHA_DE_TESTE);
        criador.contratar(conta.contaId(), Plano.COMPLETO);
        String email = "atendente-" + UUID.randomUUID() + "@exemplo.test";
        conta.comoUsuario(() -> usuarioService.criar("Atendente da manhã", Perfil.OPERADOR, email,
                SENHA_DO_OPERADOR));

        criador.vencerPlano(conta.contaId(), 7);
        conta.comoUsuario(() -> usuarioService.criar("Atendente da tolerância", Perfil.OPERADOR,
                "x-" + UUID.randomUUID() + "@exemplo.test", SENHA_DO_OPERADOR));

        criador.vencerPlano(conta.contaId(), 8);
        assertThatExceptionOfType(PlanoSuspensoException.class)
                .isThrownBy(() -> conta.comoUsuario(() ->
                        usuarioService.criar("Atendente da noite", Perfil.OPERADOR,
                                "x-" + UUID.randomUUID() + "@exemplo.test", SENHA_DO_OPERADOR)));

        String token = entrar(email, SENHA_DO_OPERADOR);
        http.perform(get("/api/auth/eu").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.situacaoDoPlano").value("SUSPENSO"));
    }

    @Test
    @DisplayName("o plano grátis e o intermediário só admitem um usuário")
    void planoSemMultiusuarioRecusa() {
        ContaCriada gratis = criador.criar("Banca Gratis", SENHA_DE_TESTE);
        ContaCriada intermediaria = criador.criar("Banca Intermediaria", SENHA_DE_TESTE);
        criador.contratar(intermediaria.contaId(), Plano.CAIXA_SIMPLES);

        for (ContaCriada conta : new ContaCriada[] {gratis, intermediaria}) {
            assertThatExceptionOfType(RecursoForaDoPlanoException.class)
                    .isThrownBy(() -> conta.comoUsuario(() ->
                            usuarioService.criar("Atendente", Perfil.OPERADOR,
                                    "x-" + UUID.randomUUID() + "@exemplo.test", SENHA_DO_OPERADOR)))
                    .withMessageContaining("plano Completo");

            conta.comoUsuario(() ->
                    assertThat(usuarioService.listar())
                            .as("nada foi gravado")
                            .extracting(UsuarioDaConta::id)
                            .containsExactly(conta.usuarioId()));
        }
    }

    @Test
    @DisplayName("e-mail repetido é recusado, inclusive quando o dono dele é outra conta")
    void emailRepetidoRecusado() {
        ContaCriada conta = criador.criar("Loja Completa", SENHA_DE_TESTE);
        criador.contratar(conta.contaId(), Plano.COMPLETO);
        ContaCriada outra = criador.criar("Outra Loja", SENHA_DE_TESTE);

        assertThatExceptionOfType(EmailJaCadastradoException.class)
                .as("o e-mail do próprio dono")
                .isThrownBy(() -> conta.comoUsuario(() ->
                        usuarioService.criar("Repetido", Perfil.OPERADOR, conta.email(),
                                SENHA_DO_OPERADOR)));

        assertThatExceptionOfType(EmailJaCadastradoException.class)
                .as("o e-mail é único no sistema inteiro, porque é ele que descobre a conta")
                .isThrownBy(() -> conta.comoUsuario(() ->
                        usuarioService.criar("Repetido", Perfil.OPERADOR,
                                outra.email().toUpperCase(), SENHA_DO_OPERADOR)));
    }

    @Test
    @DisplayName("a senha inicial passa pela mesma política de qualquer senha")
    void senhaCurtaRecusada() {
        ContaCriada conta = criador.criar("Padaria Completa", SENHA_DE_TESTE);
        criador.contratar(conta.contaId(), Plano.COMPLETO);

        assertThatExceptionOfType(SenhaRecusadaException.class)
                .isThrownBy(() -> conta.comoUsuario(() ->
                        usuarioService.criar("Atendente", Perfil.OPERADOR,
                                "curta-" + UUID.randomUUID() + "@exemplo.test", "curta")));

        conta.comoUsuario(() ->
                assertThat(usuarioService.listar()).hasSize(1));
    }

    @Test
    @DisplayName("o operador não cria, não lista nem inativa usuário (RF30)")
    void operadorNaoGereUsuarios() {
        ContaCriada conta = criador.criar("Oficina Completa", SENHA_DE_TESTE);
        criador.contratar(conta.contaId(), Plano.COMPLETO);
        UsuarioCriado operador = criador.criarOperadorEm(conta.contaId(), "Atendente");

        assertThatExceptionOfType(AcessoNegadoException.class)
                .isThrownBy(() -> operador.comoUsuario(() ->
                        usuarioService.criar("Outro", Perfil.OPERADOR,
                                "o-" + UUID.randomUUID() + "@exemplo.test", SENHA_DO_OPERADOR)));
        assertThatExceptionOfType(AcessoNegadoException.class)
                .isThrownBy(() -> operador.comoUsuario(usuarioService::listar));
        assertThatExceptionOfType(AcessoNegadoException.class)
                .isThrownBy(() -> operador.comoUsuario(() ->
                        usuarioService.inativar(conta.usuarioId())));

        conta.comoUsuario(() ->
                assertThat(usuarioService.listar())
                        .extracting(UsuarioDaConta::ativo)
                        .containsOnly(true));
    }

    @Test
    @DisplayName("o último administrador ativo não se inativa; com um segundo, pode")
    void ultimoAdministradorFica() {
        ContaCriada conta = criador.criar("Salao Completo", SENHA_DE_TESTE);
        criador.contratar(conta.contaId(), Plano.COMPLETO);

        assertThatExceptionOfType(UltimoAdministradorException.class)
                .isThrownBy(() -> conta.comoUsuario(() ->
                        usuarioService.inativar(conta.usuarioId())));

        UsuarioCriado segundoAdmin = criador.criarAdminEm(conta.contaId(), "Socio");

        assertThatNoException()
                .as("com outro administrador ativo, o dono pode sair")
                .isThrownBy(() -> conta.comoUsuario(() ->
                        usuarioService.inativar(conta.usuarioId())));

        assertThatExceptionOfType(UltimoAdministradorException.class)
                .as("e agora o sócio é o último")
                .isThrownBy(() -> segundoAdmin.comoUsuario(() ->
                        usuarioService.inativar(segundoAdmin.usuarioId())));
    }

    @Test
    @DisplayName("inativar vale na hora: o token que o operador já tinha deixa de entrar")
    void inativacaoValeNaHora() throws Exception {
        ContaCriada conta = criador.criar("Quitanda Completa", SENHA_DE_TESTE);
        criador.contratar(conta.contaId(), Plano.COMPLETO);
        String email = "atendente-" + UUID.randomUUID() + "@exemplo.test";
        UUID operadorId = conta.comoUsuario(() ->
                usuarioService.criar("Atendente", Perfil.OPERADOR, email, SENHA_DO_OPERADOR));

        String tokenAntesDeInativar = entrar(email, SENHA_DO_OPERADOR);
        http.perform(get("/api/auth/eu").header("Authorization", "Bearer " + tokenAntesDeInativar))
                .andExpect(status().isOk());

        conta.comoUsuario(() -> usuarioService.inativar(operadorId));

        // O token continua válido e assinado; quem deixou de valer foi o usuário.
        http.perform(get("/api/auth/eu").header("Authorization", "Bearer " + tokenAntesDeInativar))
                .andExpect(status().isUnauthorized());
        http.perform(login(email, SENHA_DO_OPERADOR))
                .andExpect(status().isUnauthorized());

        conta.comoUsuario(() ->
                assertThat(usuarioService.listar())
                        .filteredOn(usuario -> usuario.id().equals(operadorId))
                        .singleElement()
                        .extracting(UsuarioDaConta::ativo)
                        .isEqualTo(false));
    }

    @Test
    @DisplayName("a conta B não lista nem inativa usuário da conta A (RNF05)")
    void isolamentoEntreContas() {
        ContaCriada contaA = criador.criar("Negocio A", SENHA_DE_TESTE);
        ContaCriada contaB = criador.criar("Negocio B", SENHA_DE_TESTE);
        criador.contratar(contaA.contaId(), Plano.COMPLETO);
        UUID operadorDeA = contaA.comoUsuario(() ->
                usuarioService.criar("Atendente de A", Perfil.OPERADOR,
                        "a-" + UUID.randomUUID() + "@exemplo.test", SENHA_DO_OPERADOR));

        contaB.comoUsuario(() ->
                assertThat(usuarioService.listar())
                        .extracting(UsuarioDaConta::id)
                        .containsExactly(contaB.usuarioId()));

        assertThatExceptionOfType(UsuarioNaoEncontradoException.class)
                .as("um id de outra conta é indistinguível de um id que nunca existiu")
                .isThrownBy(() -> contaB.comoUsuario(() -> usuarioService.inativar(operadorDeA)));

        contaA.comoUsuario(() ->
                assertThat(usuarioService.listar())
                        .filteredOn(usuario -> usuario.id().equals(operadorDeA))
                        .singleElement()
                        .extracting(UsuarioDaConta::ativo)
                        .isEqualTo(true));
    }

    @Test
    @DisplayName("inativar apaga o login, libera o e-mail e permite anonimizar o nome a pedido")
    void inativacaoEAnonimizacao() throws Exception {
        ContaCriada conta = criador.criar("Conta com usuário removido", SENHA_DE_TESTE);
        criador.contratar(conta.contaId(), Plano.COMPLETO);
        String email = "removido-" + UUID.randomUUID() + "@exemplo.test";
        UUID primeiro = conta.comoUsuario(() -> usuarioService.criar(
                "Nome pessoal", Perfil.OPERADOR, email, SENHA_DO_OPERADOR));

        conta.comoUsuario(() -> usuarioService.inativar(primeiro));
        http.perform(login(email, SENHA_DO_OPERADOR)).andExpect(status().isUnauthorized());
        conta.comoUsuario(() -> usuarioService.anonimizarNome(primeiro));
        conta.comoUsuario(() -> assertThat(usuarioService.listar())
                .filteredOn(usuario -> usuario.id().equals(primeiro))
                .singleElement().extracting(UsuarioDaConta::nome)
                .isEqualTo("Usuário removido"));

        UUID segundo = conta.comoUsuario(() -> usuarioService.criar(
                "Novo usuário", Perfil.OPERADOR, email, SENHA_DO_OPERADOR));
        assertThat(segundo).isNotEqualTo(primeiro);
        assertThat(entrar(email, SENHA_DO_OPERADOR)).isNotBlank();
    }

    @Test
    @DisplayName("pedido de outra Conta não anonimiza o Usuário")
    void anonimizarNomeIsolaContas() {
        ContaCriada dona = criador.criar("Dona do usuário", SENHA_DE_TESTE);
        ContaCriada outra = criador.criar("Outra usuária", SENHA_DE_TESTE);
        criador.contratar(dona.contaId(), Plano.COMPLETO);
        UUID id = dona.comoUsuario(() -> usuarioService.criar("Nome pessoal", Perfil.OPERADOR,
                "isolado-" + UUID.randomUUID() + "@exemplo.test", SENHA_DO_OPERADOR));
        dona.comoUsuario(() -> usuarioService.inativar(id));

        assertThatExceptionOfType(UsuarioNaoEncontradoException.class).isThrownBy(() ->
                outra.comoUsuario(() -> usuarioService.anonimizarNome(id)));
        dona.comoUsuario(() -> assertThat(usuarioService.listar())
                .filteredOn(usuario -> usuario.id().equals(id))
                .singleElement().extracting(UsuarioDaConta::nome)
                .isEqualTo("Nome pessoal"));
    }

    private String entrar(String email, String senha) throws Exception {
        String corpo = http.perform(login(email, senha))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(corpo).get("token").asText();
    }

    private RequestBuilder login(String email, String senha) throws Exception {
        return post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "senha", senha)));
    }
}
