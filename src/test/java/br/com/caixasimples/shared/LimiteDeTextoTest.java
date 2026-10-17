package br.com.caixasimples.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LimiteDeTextoTest {

    @Test
    @DisplayName("o texto no limite passa e um caractere a mais é recusado com o campo e os números")
    void recusaUmCaractereAMais() {
        String centoEVinte = "a".repeat(120);

        assertThat(LimiteDeTexto.exigirAte(centoEVinte, 120, "nome do produto"))
                .isSameAs(centoEVinte);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LimiteDeTexto.exigirAte(centoEVinte + "b", 120,
                        "nome do produto"))
                .withMessage("nome do produto com 121 caracteres; o limite é 120.");
    }

    @Test
    @DisplayName("o campo opcional ausente passa")
    void nuloPassa() {
        assertThat(LimiteDeTexto.exigirAte(null, 60, "código do produto")).isNull();
    }

    @Test
    @DisplayName("a mensagem não leva o texto recebido, que pode ser dado pessoal")
    void mensagemSemOTexto() {
        String contato = "maria.da.silva@exemplo.com.br ".repeat(7);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> LimiteDeTexto.exigirAte(contato, 180, "contato do cliente"))
                .withMessageNotContaining("maria");
    }

    /**
     * A contagem é a do Java e do campo do aplicativo, em unidades UTF-16. O emoji vale dois aqui
     * e um no PostgreSQL, então o texto com emoji perto do limite é recusado mesmo cabendo na
     * coluna. O contrário, passar aqui e ser recusado pelo banco, não acontece.
     */
    @Test
    @DisplayName("o emoji conta duas unidades")
    void emojiContaDois() {
        String comEmoji = "a".repeat(118) + "😀";

        assertThat(comEmoji).hasSize(120);
        assertThat(LimiteDeTexto.exigirAte(comEmoji, 120, "nome do cliente")).isSameAs(comEmoji);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LimiteDeTexto.exigirAte(comEmoji, 119, "nome do cliente"))
                .withMessage("nome do cliente com 120 caracteres; o limite é 119.");
    }
}
