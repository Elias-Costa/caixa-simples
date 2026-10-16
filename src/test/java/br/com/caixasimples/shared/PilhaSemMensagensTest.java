package br.com.caixasimples.shared;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PilhaSemMensagensTest {

    @Test
    @DisplayName("as classes da cadeia e as linhas aparecem; a mensagem com e-mail e a com telefone, não")
    void semAsMensagens() {
        RuntimeException erro = falhaComCausa();

        String pilha = PilhaSemMensagens.de(erro);

        assertThat(pilha)
                .startsWith(IllegalStateException.class.getName())
                .contains("Caused by: " + IllegalArgumentException.class.getName())
                .contains("\tat " + PilhaSemMensagensTest.class.getName() + ".falhaComCausa(")
                .contains("\tat " + PilhaSemMensagensTest.class.getName() + ".causa(")
                .doesNotContain("ana@exemplo.test")
                .doesNotContain("98888-7777");
    }

    @Test
    @DisplayName("a causa omite as linhas finais que tem em comum com a exceção de cima")
    void linhasEmComumOmitidas() {
        String pilha = PilhaSemMensagens.de(falhaComCausa());

        String daCausa = pilha.substring(pilha.indexOf("Caused by: "));
        assertThat(daCausa).containsPattern("\t\\.\\.\\. \\d+ more");
        assertThat(daCausa).doesNotContain(".semAsMensagens(").doesNotContain(".linhasEmComumOmitidas(");
    }

    @Test
    @DisplayName("nunca lê a mensagem, nem a da causa")
    void naoLeAMensagem() {
        RuntimeException erro = new Delatora(new Delatora(null));

        assertThat(PilhaSemMensagens.de(erro)).contains("Caused by: " + Delatora.class.getName());
    }

    @Test
    @DisplayName("uma cadeia de causas que volta ao começo termina")
    void cicloTermina() {
        RuntimeException primeira = new RuntimeException("primeira");
        RuntimeException segunda = new RuntimeException("segunda", primeira);
        primeira.initCause(segunda);

        String pilha = PilhaSemMensagens.de(primeira);

        assertThat(pilha.split("Caused by: ", -1)).hasSize(3);
        assertThat(pilha).contains("ciclo na cadeia de causas");
    }

    private static RuntimeException falhaComCausa() {
        return new IllegalStateException("falhou para ana@exemplo.test", causa());
    }

    private static RuntimeException causa() {
        return new IllegalArgumentException("contato (11) 98888-7777 repetido");
    }

    /** Falha o teste se alguém pedir a mensagem, inclusive pelo toString. */
    private static final class Delatora extends RuntimeException {

        /** Sem mensagem própria: o construtor só com a causa leria a mensagem dela. */
        Delatora(Throwable causa) {
            super(null, causa);
        }

        @Override
        public String getMessage() {
            throw new AssertionError("a mensagem foi lida");
        }
    }
}
