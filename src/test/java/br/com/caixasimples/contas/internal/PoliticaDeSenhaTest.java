package br.com.caixasimples.contas.internal;

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
    @DisplayName("senha nula é recusada antes de qualquer verificação externa")
    void senhaNula() {
        verificador.simularIndisponibilidade(true);

        assertThatThrownBy(() -> politica.exigirValida(null))
                .isInstanceOf(SenhaRecusadaException.class)
                .hasMessageContaining("15");
    }
}
