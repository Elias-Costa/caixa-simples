package br.com.caixasimples.contas.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.caixasimples.contas.internal.ContencaoDeLogin.Tentativa;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Contenção do login, sem Spring nem banco: o instante de cada tentativa é passado à mão, então a
 * virada da janela se prova sem esperar quinze minutos.
 *
 * <p>Uma reserva que não é devolvida é uma falha, como no login em que a senha não confere.
 */
class ContencaoDeLoginTest {

    private static final Instant INICIO = Instant.parse("2026-10-03T12:00:00Z");
    private static final String ORIGEM = "203.0.113.50";
    private static final String EMAIL = "ana@exemplo.test";

    private final ContencaoDeLogin contencao = new ContencaoDeLogin();

    @Test
    @DisplayName("esgotado o par, a tentativa espera a janela acabar e volta a ser conferida na virada")
    void recuperacaoNaViradaDaJanela() {
        falhar(ORIGEM, EMAIL, 10, INICIO);

        assertThatThrownBy(() -> contencao.reservar(ORIGEM, EMAIL, INICIO.plus(Duration.ofSeconds(899))))
                .isInstanceOfSatisfying(LoginContidoException.class,
                        contido -> assertThat(contido.espera()).isEqualTo(Duration.ofSeconds(1)));
        assertThatCode(() -> contencao.reservar(ORIGEM, EMAIL, INICIO.plus(ContencaoDeLogin.JANELA)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a recusa não conta: insistir durante a espera não a estende")
    void recusaNaoConta() {
        falhar(ORIGEM, EMAIL, 10, INICIO);
        for (int segundo = 1; segundo < 100; segundo++) {
            Instant agora = INICIO.plusSeconds(segundo);
            assertThatThrownBy(() -> contencao.reservar(ORIGEM, EMAIL, agora))
                    .isInstanceOf(LoginContidoException.class);
        }

        assertThatCode(() -> contencao.reservar(ORIGEM, EMAIL, INICIO.plus(ContencaoDeLogin.JANELA)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a origem soma os e-mails, e outra origem tem contagem própria")
    void origemSomaOsEmails() {
        for (int i = 0; i < 30; i++) {
            contencao.reservar(ORIGEM, "pessoa" + i + "@exemplo.test", INICIO);
        }

        assertThatThrownBy(() -> contencao.reservar(ORIGEM, EMAIL, INICIO))
                .isInstanceOf(LoginContidoException.class);
        assertThatCode(() -> contencao.reservar("203.0.113.51", EMAIL, INICIO)).doesNotThrowAnyException();
    }

    /**
     * Vinte falhas da origem às 12h e dez do par às 12h05 esgotam as duas contagens. A da origem
     * termina às 12h15 e a do par às 12h20: antes das 12h20 a tentativa seria recusada de novo.
     */
    @Test
    @DisplayName("com as duas contagens esgotadas, a espera é a da que termina por último")
    void esperaDaQueTerminaPorUltimo() {
        for (int i = 0; i < 20; i++) {
            contencao.reservar(ORIGEM, "pessoa" + i + "@exemplo.test", INICIO);
        }
        Instant dozeECinco = INICIO.plus(Duration.ofMinutes(5));
        falhar(ORIGEM, EMAIL, 10, dozeECinco);

        assertThatThrownBy(() -> contencao.reservar(ORIGEM, EMAIL, INICIO.plus(Duration.ofMinutes(6))))
                .isInstanceOfSatisfying(LoginContidoException.class,
                        contido -> assertThat(contido.espera()).isEqualTo(Duration.ofMinutes(14)));
        assertThatThrownBy(() -> contencao.reservar(ORIGEM, EMAIL, INICIO.plus(Duration.ofMinutes(16))))
                .isInstanceOfSatisfying(LoginContidoException.class,
                        contido -> assertThat(contido.espera()).isEqualTo(Duration.ofMinutes(4)));
    }

    @Test
    @DisplayName("o login certo zera o par")
    void sucessoZeraOPar() {
        falhar(ORIGEM, EMAIL, 9, INICIO);
        contencao.registrarSucesso(contencao.reservar(ORIGEM, EMAIL, INICIO));

        falhar(ORIGEM, EMAIL, 10, INICIO);
        assertThatThrownBy(() -> contencao.reservar(ORIGEM, EMAIL, INICIO))
                .isInstanceOf(LoginContidoException.class);
    }

    @Test
    @DisplayName("o login certo devolve a reserva da origem")
    void sucessoDevolveAOrigem() {
        for (int i = 0; i < 29; i++) {
            contencao.reservar(ORIGEM, "pessoa" + i + "@exemplo.test", INICIO);
        }
        contencao.registrarSucesso(contencao.reservar(ORIGEM, EMAIL, INICIO));

        contencao.reservar(ORIGEM, "mais.uma@exemplo.test", INICIO);
        assertThatThrownBy(() -> contencao.reservar(ORIGEM, "outra@exemplo.test", INICIO))
                .isInstanceOf(LoginContidoException.class);
    }

    @Test
    @DisplayName("desfazer devolve as duas reservas")
    void desfazerDevolveAsDuas() {
        for (int i = 0; i < 40; i++) {
            contencao.desfazer(contencao.reservar(ORIGEM, EMAIL, INICIO));
        }

        falhar(ORIGEM, EMAIL, 10, INICIO);
        assertThatThrownBy(() -> contencao.reservar(ORIGEM, EMAIL, INICIO))
                .isInstanceOf(LoginContidoException.class);
    }

    /**
     * A tentativa contada numa janela termina depois que ela virou. Descontá-la da janela nova daria
     * a quem insiste uma falha a mais nela.
     */
    @Test
    @DisplayName("a devolução só desconta da janela em que a tentativa foi contada")
    void devolucaoNaJanelaEmQueContou() {
        Tentativa antiga = contencao.reservar(ORIGEM, EMAIL, INICIO);
        Instant janelaNova = INICIO.plus(ContencaoDeLogin.JANELA);
        falhar(ORIGEM, EMAIL, 10, janelaNova);

        contencao.desfazer(antiga);

        assertThatThrownBy(() -> contencao.reservar(ORIGEM, EMAIL, janelaNova))
                .isInstanceOf(LoginContidoException.class);
    }

    @Test
    @DisplayName("IPv6 conta pelo /64: o mesmo prefixo soma, e outro prefixo tem contagem própria")
    void ipv6PeloPrefixo() {
        for (int i = 1; i <= 30; i++) {
            contencao.reservar("2001:db8:1:2::" + Integer.toHexString(i), "pessoa" + i + "@exemplo.test", INICIO);
        }

        assertThatThrownBy(() -> contencao.reservar("2001:db8:1:2:ffff:ffff:ffff:ffff", EMAIL, INICIO))
                .isInstanceOf(LoginContidoException.class);
        assertThatCode(() -> contencao.reservar("2001:db8:1:3::1", EMAIL, INICIO)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a chave da origem: IPv4 inteiro, IPv4 dentro de IPv6 igual ao IPv4, e o que não é IPv6 como veio")
    void chaveDaOrigem() {
        assertThat(ContencaoDeLogin.agruparOrigem("203.0.113.7")).isEqualTo("203.0.113.7");
        assertThat(ContencaoDeLogin.agruparOrigem("::ffff:203.0.113.7")).isEqualTo("203.0.113.7");
        assertThat(ContencaoDeLogin.agruparOrigem("2001:0db8:0001:0002:aaaa:bbbb:cccc:dddd"))
                .isEqualTo(ContencaoDeLogin.agruparOrigem("2001:db8:1:2::1"));
        // Um nome nunca vira consulta de DNS, e um IPv6 inválido também não.
        assertThat(ContencaoDeLogin.agruparOrigem("cliente.exemplo.test")).isEqualTo("cliente.exemplo.test");
        assertThat(ContencaoDeLogin.agruparOrigem("cafe:exemplo")).isEqualTo("cafe:exemplo");
        assertThat(ContencaoDeLogin.agruparOrigem("1:2")).isEqualTo("1:2");
    }

    @Test
    @DisplayName("o e-mail conta igual com espaços nas pontas e com maiúsculas, como a busca da credencial")
    void emailNormalizado() {
        falhar(ORIGEM, "  Ana@Exemplo.TEST ", 5, INICIO);
        falhar(ORIGEM, EMAIL, 5, INICIO);

        assertThatThrownBy(() -> contencao.reservar(ORIGEM, EMAIL, INICIO))
                .isInstanceOf(LoginContidoException.class);
    }

    @Test
    @DisplayName("as contagens vencidas saem da memória na limpeza seguinte")
    void limpezaDasVencidas() {
        for (int i = 0; i < 20; i++) {
            contencao.reservar("203.0.113." + i, EMAIL, INICIO);
        }
        assertThat(contencao.contagensGuardadas()).isEqualTo(40);

        contencao.reservar(ORIGEM, EMAIL, INICIO.plus(ContencaoDeLogin.JANELA));

        assertThat(contencao.contagensGuardadas()).isEqualTo(2);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("o bloqueio vai ao log uma vez por contagem e janela, sem IP nem e-mail")
    void avisoNoLogSemDadoPessoal(CapturedOutput saida) {
        falhar(ORIGEM, EMAIL, 10, INICIO);
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> contencao.reservar(ORIGEM, EMAIL, INICIO))
                    .isInstanceOf(LoginContidoException.class);
        }

        assertThat(saida.getAll().split("login contido", -1)).hasSize(2);
        assertThat(saida.getAll()).doesNotContain(ORIGEM).doesNotContain(EMAIL).doesNotContain("ana@");
    }

    /** Reservas sem devolução, como logins em que a senha não confere. */
    private void falhar(String origem, String email, int vezes, Instant agora) {
        for (int i = 0; i < vezes; i++) {
            contencao.reservar(origem, email, agora);
        }
    }
}
