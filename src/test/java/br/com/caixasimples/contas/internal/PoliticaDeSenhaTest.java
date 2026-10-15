package br.com.caixasimples.contas.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.caixasimples.VerificadorDeSenhaVazadaFalso;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Política de senha. Teste de unidade: não precisa de Spring nem de banco.
 */
class PoliticaDeSenhaTest {

    private VerificadorDeSenhaVazadaFalso verificador;
    private PoliticaDeSenha politica;

    @BeforeEach
    void montar() {
        verificador = new VerificadorDeSenhaVazadaFalso();
        politica = new PoliticaDeSenha(verificador);
    }

    @Test
    @DisplayName("senha com 15 caracteres passa; com 14 não")
    void comprimentoMinimo() {
        assertThatCode(() -> politica.exigirValida("a".repeat(15))).doesNotThrowAnyException();

        assertThatThrownBy(() -> politica.exigirValida("a".repeat(14)))
                .isInstanceOf(SenhaRecusadaException.class)
                .hasMessageContaining("15");
    }

    @Test
    @DisplayName("não exige número, símbolo nem maiúscula")
    void semRegraDeComposicao() {
        assertThatCode(() -> politica.exigirValida("apenas letras minusculas aqui"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("senha em vazamento conhecido é recusada")
    void senhaVazada() {
        String vazada = "senha que vazou por ai";
        verificador.marcarComoVazada(vazada);

        assertThatThrownBy(() -> politica.exigirValida(vazada))
                .isInstanceOf(SenhaRecusadaException.class)
                .hasMessageContaining("vazamentos");
    }

    @Test
    @DisplayName("verificação indisponível recusa a senha, em vez de deixar passar")
    void falhaFechadaQuandoNaoDaParaVerificar() {
        verificador.simularIndisponibilidade(true);

        assertThatThrownBy(() -> politica.exigirValida("uma senha longa o suficiente"))
                .isInstanceOf(SenhaRecusadaException.class)
                .hasMessageContaining("Nao foi possivel verificar");
    }

    @Test
    @DisplayName("72 bytes passam; 73 são recusados com a mensagem em português, sem consultar a lista")
    void maximoEmBytes() {
        assertThatCode(() -> politica.exigirValida("a".repeat(72))).doesNotThrowAnyException();

        verificador.simularIndisponibilidade(true);
        assertThatThrownBy(() -> politica.exigirValida("a".repeat(73)))
                .isInstanceOf(SenhaRecusadaException.class)
                .isNotInstanceOf(ListaDeVazadasIndisponivelException.class)
                .hasMessageContaining("no máximo 72 letras sem acento")
                .hasMessageContaining("acento ou cedilha conta como duas");
    }

    /**
     * A letra acentuada ocupa dois bytes em UTF-8: 70 letras e um á somam 72 bytes em 71
     * caracteres, e 71 letras e um á somam 73 em 72 caracteres, abaixo do que um limite em
     * caracteres recusaria.
     */
    @Test
    @DisplayName("senha acentuada no limite: conta os bytes, não os caracteres")
    void senhaAcentuadaNoLimite() {
        String noLimite = "a".repeat(70) + "á";
        String passaDoLimite = "a".repeat(71) + "á";
        assertThat(noLimite).hasSize(71);
        assertThat(passaDoLimite).hasSize(72);

        assertThatCode(() -> politica.exigirValida(noLimite)).doesNotThrowAnyException();

        verificador.simularIndisponibilidade(true);
        assertThatThrownBy(() -> politica.exigirValida(passaDoLimite))
                .isInstanceOf(SenhaRecusadaException.class)
                .isNotInstanceOf(ListaDeVazadasIndisponivelException.class)
                .hasMessageContaining("72");
        assertThatThrownBy(() -> politica.exigirValidaEscolhidaPelaPropriaPessoa(passaDoLimite))
                .isInstanceOf(SenhaRecusadaException.class)
                .hasMessageContaining("72");
    }

    @Test
    @DisplayName("com a lista fora do ar, a senha da própria pessoa entra e a escolhida por outra não")
    void listaForaDoArDecidePorQuemEscolheu() {
        verificador.simularIndisponibilidade(true);
        String senha = "uma senha longa o suficiente";

        assertThatCode(() -> politica.exigirValidaEscolhidaPelaPropriaPessoa(senha))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> politica.exigirValida(senha))
                .isInstanceOf(ListaDeVazadasIndisponivelException.class);
    }

    @Test
    @DisplayName("a senha vazada é recusada também quando a própria pessoa a escolheu")
    void vazadaRecusadaParaAPropriaPessoa() {
        String vazada = "senha que vazou por ai";
        verificador.marcarComoVazada(vazada);

        assertThatThrownBy(() -> politica.exigirValidaEscolhidaPelaPropriaPessoa(vazada))
                .isInstanceOf(SenhaRecusadaException.class)
                .hasMessageContaining("vazamentos");
    }

    @Test
    @DisplayName("a senha curta é recusada também quando a própria pessoa a escolheu")
    void curtaRecusadaParaAPropriaPessoa() {
        verificador.simularIndisponibilidade(true);

        assertThatThrownBy(() -> politica.exigirValidaEscolhidaPelaPropriaPessoa("a".repeat(14)))
                .isInstanceOf(SenhaRecusadaException.class)
                .hasMessageContaining("15");
    }

    @Test
    @DisplayName("senha nula é recusada antes de qualquer verificação externa")
    void senhaNula() {
        verificador.simularIndisponibilidade(true);

        assertThatThrownBy(() -> politica.exigirValida(null))
                .isInstanceOf(SenhaRecusadaException.class)
                .hasMessageContaining("15");
    }
}
