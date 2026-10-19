package br.com.caixasimples.contas.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import br.com.caixasimples.contas.internal.ChavePix.Tipo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A conferência da chave Pix pelo tipo escolhido: o formato, os dígitos verificadores do CPF e do
 * CNPJ, inclusive o alfanumérico, a forma gravada e a recusa que nunca repete a chave.
 */
class ChavePixTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "CPF       | 529.982.247-25             | 52998224725",
        "CPF       | 52998224725                | 52998224725",
        "CNPJ      | 11.222.333/0001-81         | 11222333000181",
        "CNPJ      | 12.ABC.345/01DE-35         | 12ABC34501DE35",
        "CNPJ      | 12.abc.345/01de-35         | 12ABC34501DE35",
        "TELEFONE  | (61) 91234-5678            | +5561912345678",
        "TELEFONE  | +55 61 91234-5678          | +5561912345678",
        "TELEFONE  | 5561912345678              | +5561912345678",
        "TELEFONE  | (55) 99123-4567            | +5555991234567",
        "EMAIL     | Fulano@Exemplo.COM         | fulano@exemplo.com",
        "ALEATORIA | 123E4567-E89B-12D3-B456-426614174000 | 123e4567-e89b-12d3-b456-426614174000"
    })
    @DisplayName("cada tipo aceita a digitação comum e grava a forma que o QR leva")
    void normaliza(Tipo tipo, String digitada, String gravada) {
        ChavePix chave = ChavePix.conferir(tipo, "  " + digitada + " ");

        assertThat(chave.tipo()).isEqualTo(tipo);
        assertThat(chave.valor()).isEqualTo(gravada);
        assertThat(ChavePix.tipoDaGravada(chave.valor())).isEqualTo(tipo);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "CPF       | 529.982.247-24     | dígitos verificadores",
        "CPF       | 111.111.111-11     | dígitos verificadores",
        "CPF       | 5299822472         | 11 dígitos",
        "CNPJ      | 11.222.333/0001-82 | dígitos verificadores",
        "CNPJ      | 12.ABC.345/01DE-36 | dígitos verificadores",
        "CNPJ      | 00.000.000/0000-00 | dígitos verificadores",
        "CNPJ      | 12.ABC.345/01DE-3X | 14 caracteres",
        "TELEFONE  | (61) 3234-5678     | 9 dígitos",
        "TELEFONE  | (01) 91234-5678    | 9 dígitos",
        "TELEFONE  | +1 202 555 0100    | código 55",
        "EMAIL     | fulano.exemplo.com | arroba",
        "EMAIL     | fulano @exemplo.com | arroba",
        "ALEATORIA | 123e4567e89b12d3a456426614174000 | cinco grupos"
    })
    @DisplayName("a chave que não serve ao tipo é recusada com o campo e o motivo, sem repetir a chave")
    void recusa(Tipo tipo, String digitada, String motivo) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ChavePix.conferir(tipo, digitada))
                .withMessageStartingWith("chave Pix:")
                .withMessageContaining(motivo)
                .withMessageNotContaining(digitada.replaceAll("[^0-9A-Za-z]", "").substring(0, 8));
    }

    @Test
    @DisplayName("o tipo escolhido decide: onze dígitos de um celular não passam como CPF")
    void tipoDecide() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ChavePix.conferir(Tipo.CPF, "61912345678"))
                .withMessageContaining("dígitos verificadores");
        assertThat(ChavePix.conferir(Tipo.TELEFONE, "61912345678").valor())
                .isEqualTo("+5561912345678");
    }

    @Test
    @DisplayName("o e-mail cabe até 77 caracteres, o maior aceito como chave")
    void limiteDoEmail() {
        String dominio = "@exemplo.com";
        String noLimite = "a".repeat(ChavePix.TAMANHO_MAXIMO - dominio.length()) + dominio;

        assertThat(ChavePix.conferir(Tipo.EMAIL, noLimite).valor()).hasSize(77);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ChavePix.conferir(Tipo.EMAIL, "a" + noLimite))
                .withMessageContaining("o limite é 77");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    @DisplayName("sem chave ou sem tipo, a recusa pede o que falta")
    void faltaChaveOuTipo(String vazia) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ChavePix.conferir(Tipo.EMAIL, vazia))
                .withMessage("chave Pix: informe a chave.");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ChavePix.conferir(null, "fulano@exemplo.com"))
                .withMessage("chave Pix: informe o tipo da chave.");
    }

    @Test
    @DisplayName("uma chave gravada fora das formas conferidas não é lida como de tipo nenhum")
    void gravadaForaDasFormas() {
        assertThatIllegalStateException().isThrownBy(() -> ChavePix.tipoDaGravada("123"));
    }
}
