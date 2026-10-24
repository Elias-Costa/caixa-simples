package br.com.caixasimples.contas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.contas.application.AutenticacaoService;
import br.com.caixasimples.contas.application.TermosDeUsoService;
import br.com.caixasimples.contas.internal.AceiteDosTermos;
import br.com.caixasimples.contas.internal.AceiteDosTermosRepository;
import br.com.caixasimples.shared.AcessoNegadoException;
import br.com.caixasimples.shared.Perfil;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/** O aceite dos termos de uso pela Conta, contra o banco de verdade. */
class TermosDeUsoServiceTest extends TesteDeIntegracao {

    private static final String SENHA_DE_TESTE = "uma senha longa de teste";

    @Autowired
    private CriadorDeContaDeTeste criador;

    @Autowired
    private TermosDeUsoService termos;

    @Autowired
    private AceiteDosTermosRepository aceites;

    @Autowired
    private AutenticacaoService autenticacao;

    @Test
    @DisplayName("a Conta nasce sem aceite, e o aceite do administrador grava quem, quando e a versão")
    void aceiteGravaAVersaoVigente() {
        ContaCriada conta = criador.criar("Cafeteria Aurora", SENHA_DE_TESTE);
        assertThat(conta.comoUsuario(termos::pendentes)).isTrue();
        assertThat(conta.comoUsuario(autenticacao::identidade).termosPendentes()).isTrue();
        Instant antes = Instant.now();

        conta.comoUsuario(() -> termos.aceitar(TermosDeUsoService.VERSAO_VIGENTE));

        assertThat(conta.comoUsuario(termos::pendentes)).isFalse();
        assertThat(conta.comoUsuario(autenticacao::identidade).termosPendentes()).isFalse();
        assertThat(conta.comoUsuario(() -> aceites.findAll()))
                .singleElement()
                .satisfies(aceite -> {
                    assertThat(aceite.getVersao()).isEqualTo(TermosDeUsoService.VERSAO_VIGENTE);
                    assertThat(aceite.getAceitoPor()).isEqualTo(conta.usuarioId());
                    assertThat(aceite.getContaId()).isEqualTo(conta.contaId().valor());
                    assertThat(aceite.getAceitoEm()).isAfterOrEqualTo(antes);
                });
    }

    @Test
    @DisplayName("aceitar de novo a mesma versão não grava outra linha")
    void aceiteRepetidoNaoGravaOutraLinha() {
        ContaCriada conta = criador.criar("Quitanda Aurora", SENHA_DE_TESTE);
        conta.comoUsuario(() -> termos.aceitar(TermosDeUsoService.VERSAO_VIGENTE));
        Instant primeiro = conta.comoUsuario(() -> aceites.findAll()).getFirst().getAceitoEm();

        conta.comoUsuario(() -> termos.aceitar(TermosDeUsoService.VERSAO_VIGENTE));

        assertThat(conta.comoUsuario(() -> aceites.findAll()))
                .singleElement()
                .extracting(AceiteDosTermos::getAceitoEm)
                .as("a prova do primeiro aceite fica como estava")
                .isEqualTo(primeiro);
    }

    @Test
    @DisplayName("o operador não aceita pela Conta, e nada é gravado")
    void operadorNaoAceita() {
        ContaCriada operador = criador.criar("Banca Aurora", SENHA_DE_TESTE, Perfil.OPERADOR, true);

        assertThatExceptionOfType(AcessoNegadoException.class).isThrownBy(() ->
                operador.comoUsuario(() -> termos.aceitar(TermosDeUsoService.VERSAO_VIGENTE)));

        assertThat(operador.comoUsuario(() -> aceites.findAll())).isEmpty();
        assertThat(operador.comoUsuario(termos::pendentes)).isTrue();
    }

    @Test
    @DisplayName("uma versão que não é a vigente é recusada sem gravar, como a de um aparelho com o texto antigo")
    void versaoQueNaoEAVigenteERecusada() {
        ContaCriada conta = criador.criar("Loja da Esquina", SENHA_DE_TESTE);

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> conta.comoUsuario(() -> termos.aceitar("2000-01-01")))
                .withMessageContaining("Os termos mudaram");

        assertThat(conta.comoUsuario(() -> aceites.findAll())).isEmpty();
        assertThat(conta.comoUsuario(termos::pendentes)).isTrue();
    }

    @Test
    @DisplayName("o banco recusa a segunda linha da mesma versão na Conta, gravada sem passar pelo serviço")
    void bancoRecusaSegundoAceiteDaMesmaVersao() {
        ContaCriada conta = criador.criar("Padaria Aurora", SENHA_DE_TESTE);
        conta.comoUsuario(() -> termos.aceitar(TermosDeUsoService.VERSAO_VIGENTE));

        // O serviço confere o aceite antes de gravar, então aqui a gravação é direta: é o que
        // aconteceria com dois aceites passando juntos pela conferência.
        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> conta.comoUsuario(() -> aceites.save(new AceiteDosTermos(
                        TermosDeUsoService.VERSAO_VIGENTE, conta.usuarioId(), Instant.now()))))
                .withMessageContaining("uq_aceite_dos_termos_versao_por_conta");

        assertThat(conta.comoUsuario(() -> aceites.findAll())).hasSize(1);
    }
}
