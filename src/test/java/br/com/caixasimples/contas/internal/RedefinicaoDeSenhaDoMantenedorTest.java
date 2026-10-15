package br.com.caixasimples.contas.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.VerificadorDeSenhaVazadaFalso;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.UsuarioCriado;
import br.com.caixasimples.shared.Perfil;
import br.com.caixasimples.shared.TenantContext;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A execução avulsa com que o mantenedor socorre o administrador sem acesso.
 *
 * <p>Monta a execução à mão com os beans do contexto, em vez de subir outro contexto com o perfil
 * dela: na subida, a execução precisaria de uma Conta que ainda não existe. A ordem entre o tenant e
 * a transação, que é o que um bean montado pelo Spring revelaria, está no código dela, com o
 * {@link TransactionTemplate} recebido do contexto.
 */
@ExtendWith(OutputCaptureExtension.class)
class RedefinicaoDeSenhaDoMantenedorTest extends TesteDeIntegracao {

    private static final String SENHA = "uma senha longa de teste";
    private static final String PROVISORIA = "senha provisoria do mantenedor";

    @Autowired CriadorDeContaDeTeste criador;
    @Autowired UsuarioRepository usuarios;
    @Autowired CredencialRepository credenciais;
    @Autowired PasswordEncoder encoder;
    @Autowired PoliticaDeSenha politica;
    @Autowired TransactionTemplate transacao;
    @Autowired VerificadorDeSenhaVazada verificador;

    @AfterEach
    void limparVerificador() {
        ((VerificadorDeSenhaVazadaFalso) verificador).limpar();
    }

    @Test
    @DisplayName("dá ao administrador uma senha provisória e revoga todos os usuários da Conta, e só dela")
    void revogaTodosDaConta(CapturedOutput logs) {
        ContaCriada conta = criador.criar("Cafeteria Aurora", SENHA);
        UsuarioCriado operador = criador.criarOperadorEm(conta.contaId(), "Atendente");
        ContaCriada outra = criador.criar("Loja da Esquina", SENHA);

        executar(conta.email(), PROVISORIA);

        Credencial credencial = credenciais.findByUsuarioId(conta.usuarioId()).orElseThrow();
        assertThat(encoder.matches(PROVISORIA, credencial.getSenhaHash())).isTrue();
        assertThat(encoder.matches(SENHA, credencial.getSenhaHash())).isFalse();
        assertThat(credencial.isSenhaProvisoria()).isTrue();
        assertThat(versao(conta, conta.usuarioId())).isEqualTo(1);
        assertThat(versao(conta, operador.usuarioId())).isEqualTo(1);
        assertThat(versao(outra, outra.usuarioId())).isZero();
        assertThat(encoder.matches(SENHA,
                credenciais.findByUsuarioId(outra.usuarioId()).orElseThrow().getSenhaHash()))
                .isTrue();

        assertThat(logs.getOut())
                .contains("Senha provisória definida na Conta " + conta.contaId())
                .contains("acessos de 2 usuários revogados")
                .doesNotContain(conta.email(), PROVISORIA);
    }

    @Test
    @DisplayName("e-mail de operador ou desconhecido termina com erro, sem gravar nada")
    void recusaQuemNaoEAdministrador() {
        ContaCriada operador = criador.criar("Padaria Central", SENHA, Perfil.OPERADOR, true);

        assertThatThrownBy(() -> executar(operador.email(), PROVISORIA))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("não é de um administrador");
        assertThatThrownBy(() -> executar("ninguem-" + UUID.randomUUID() + "@exemplo.test",
                PROVISORIA))
                .isInstanceOf(IllegalStateException.class);

        assertThat(encoder.matches(SENHA,
                credenciais.findByUsuarioId(operador.usuarioId()).orElseThrow().getSenhaHash()))
                .isTrue();
        assertThat(versao(operador, operador.usuarioId())).isZero();
    }

    @Test
    @DisplayName("com a lista de vazadas fora do ar, a senha provisória é recusada e nada muda")
    void listaForaDoArRecusa() {
        ContaCriada conta = criador.criar("Mercado da Praça", SENHA);
        ((VerificadorDeSenhaVazadaFalso) verificador).simularIndisponibilidade(true);

        assertThatThrownBy(() -> executar(conta.email(), PROVISORIA))
                .isInstanceOf(ListaDeVazadasIndisponivelException.class);

        assertThat(encoder.matches(SENHA,
                credenciais.findByUsuarioId(conta.usuarioId()).orElseThrow().getSenhaHash()))
                .isTrue();
        assertThat(versao(conta, conta.usuarioId())).isZero();
    }

    private void executar(String email, String senha) {
        new RedefinicaoDeSenhaDoMantenedor(new PropriedadesDaRedefinicao(email, senha), usuarios,
                credenciais, encoder, politica, transacao)
                .run(new DefaultApplicationArguments());
    }

    private int versao(ContaCriada conta, UUID usuarioId) {
        return TenantContext.executarComo(conta.contaId(),
                () -> usuarios.findById(usuarioId).orElseThrow().getVersaoDoAcesso());
    }
}
