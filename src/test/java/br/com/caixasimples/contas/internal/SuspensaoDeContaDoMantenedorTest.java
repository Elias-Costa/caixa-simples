package br.com.caixasimples.contas.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.UsuarioCriado;
import br.com.caixasimples.shared.ContaId;
import br.com.caixasimples.shared.TenantContext;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * As execuções avulsas com que o mantenedor suspende o acesso de uma Conta e o devolve.
 *
 * <p>Montadas à mão com os beans do contexto, como a da redefinição de senha: com o perfil delas, a
 * subida do contexto já rodaria a execução, antes de a Conta do teste existir. O efeito na API, com o
 * login e o filtro do token, está em {@link SuspensaoDaContaNaBordaTest}.
 */
@ExtendWith(OutputCaptureExtension.class)
class SuspensaoDeContaDoMantenedorTest extends TesteDeIntegracao {

    private static final String SENHA = "uma senha longa de teste";

    @Autowired CriadorDeContaDeTeste criador;
    @Autowired ContaRepository contas;
    @Autowired UsuarioRepository usuarios;
    @Autowired TransactionTemplate transacao;

    @Test
    @DisplayName("suspende a Conta e revoga todos os usuários dela, e só dela, sem nome nem e-mail no log")
    void suspendeERevogaSoDaConta(CapturedOutput logs) {
        ContaCriada conta = criador.criar("Cafeteria Aurora", SENHA);
        UsuarioCriado operador = criador.criarOperadorEm(conta.contaId(), "Atendente Lúcia");
        ContaCriada outra = criador.criar("Loja da Esquina", SENHA);
        Instant antes = Instant.now().truncatedTo(ChronoUnit.MICROS);

        suspender(id(conta));

        assertThat(suspensaEm(conta.contaId())).isBetween(antes, Instant.now());
        assertThat(versao(conta.contaId(), conta.usuarioId())).isEqualTo(1);
        assertThat(versao(conta.contaId(), operador.usuarioId())).isEqualTo(1);
        assertThat(suspensaEm(outra.contaId())).isNull();
        assertThat(versao(outra.contaId(), outra.usuarioId())).isZero();
        assertThat(logs.getAll())
                .contains("evento=conta.suspensa conta=" + conta.contaId() + " usuarios=2")
                .doesNotContain("Cafeteria Aurora", "Atendente Lúcia", conta.email());
    }

    @Test
    @DisplayName("suspender de novo não troca o instante nem revoga outra vez")
    void suspenderDeNovoNaoMudaNada(CapturedOutput logs) {
        ContaCriada conta = criador.criar("Padaria Central", SENHA);
        suspender(id(conta));
        Instant primeiro = suspensaEm(conta.contaId());

        suspender(id(conta));

        assertThat(suspensaEm(conta.contaId())).isEqualTo(primeiro);
        assertThat(versao(conta.contaId(), conta.usuarioId())).isEqualTo(1);
        assertThat(logs.getAll())
                .contains("A Conta " + conta.contaId() + " já estava suspensa desde " + primeiro
                        + "; nada mudou");
        assertThat(logs.getAll().split("evento=conta.suspensa ", -1)).hasSize(2);
    }

    @Test
    @DisplayName("o reverso limpa a marca sem mexer nas versões, não alcança outra Conta, e na Conta ativa não faz nada")
    void reverso(CapturedOutput logs) {
        ContaCriada conta = criador.criar("Mercado da Praça", SENHA);
        ContaCriada outra = criador.criar("Armazém do Centro", SENHA);
        suspender(id(conta));
        suspender(id(outra));

        reativar(id(conta));

        assertThat(suspensaEm(conta.contaId())).isNull();
        // O token de antes continua recusado pela versão que a suspensão subiu.
        assertThat(versao(conta.contaId(), conta.usuarioId())).isEqualTo(1);
        assertThat(suspensaEm(outra.contaId())).isNotNull();
        assertThat(logs.getAll()).contains("evento=conta.reativada conta=" + conta.contaId());

        reativar(id(conta));

        assertThat(logs.getAll())
                .contains("A Conta " + conta.contaId() + " não estava suspensa; nada mudou");
        assertThat(logs.getAll().split("evento=conta.reativada ", -1)).hasSize(2);
    }

    @Test
    @DisplayName("sem id, com um texto que não é id ou com o id de Conta nenhuma, as duas terminam com erro")
    void idInvalido() {
        String nenhuma = UUID.randomUUID().toString();

        assertThatThrownBy(() -> suspender(" "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Informe fechadia.suspensao.conta");
        assertThatThrownBy(() -> suspender("cafeteria-aurora"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("não é o id de uma Conta");
        assertThatThrownBy(() -> suspender(nenhuma))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Nenhuma Conta com esse id");

        assertThatThrownBy(() -> reativar(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Informe fechadia.suspensao.conta");
        assertThatThrownBy(() -> reativar("cafeteria-aurora"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reativar(nenhuma))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Nenhuma Conta com esse id");
    }

    private void suspender(String conta) {
        new SuspensaoDeContaDoMantenedor(new PropriedadesDaSuspensao(conta), contas, usuarios,
                transacao).run(new DefaultApplicationArguments());
    }

    private void reativar(String conta) {
        new ReativacaoDeContaDoMantenedor(new PropriedadesDaSuspensao(conta), contas, transacao)
                .run(new DefaultApplicationArguments());
    }

    private static String id(ContaCriada conta) {
        return conta.contaId().valor().toString();
    }

    private Instant suspensaEm(ContaId conta) {
        return contas.findById(conta.valor()).orElseThrow().getSuspensaEm();
    }

    private int versao(ContaId conta, UUID usuarioId) {
        return TenantContext.executarComo(conta,
                () -> usuarios.findById(usuarioId).orElseThrow().getVersaoDoAcesso());
    }
}
