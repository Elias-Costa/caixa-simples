package br.com.caixasimples.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * A linha do evento de segurança: o formato chave=valor que o alerta conta e a limpeza que impede
 * um valor vindo do cliente de forjar outro campo ou outra linha.
 */
@ExtendWith(OutputCaptureExtension.class)
class EventoDeSegurancaTest {

    @Test
    @DisplayName("sai numa linha só, com o nome primeiro, os campos na ordem e o nulo omitido")
    void formato(CapturedOutput saida) {
        EventoDeSeguranca.de("login.certo")
                .com("conta", "c1")
                .com("usuario", null)
                .com("motivo", "senha")
                .daOrigem("203.0.113.9")
                .registrar();

        assertThat(saida.getAll())
                .contains("INFO")
                .contains("evento=login.certo conta=c1 motivo=senha origem=203.0.113.9")
                .doesNotContain("usuario=");
    }

    @Test
    @DisplayName("a recusa sai como aviso")
    void recusaEmAviso(CapturedOutput saida) {
        EventoDeSeguranca.de("token.recusado").com("motivo", "invalido").registrarRecusa();

        assertThat(saida.getAll()).contains("WARN").contains("evento=token.recusado motivo=invalido");
    }

    @Test
    @DisplayName("espaço, quebra de linha e igual vindos do cliente viram sublinhado")
    void valorNaoForjaCampoNemLinha(CapturedOutput saida) {
        EventoDeSeguranca.de("acesso.recusado")
                .com("caminho", "/api/x y\nevento=login.certo\r\tconta=outra")
                .daOrigem("2001:db8::1%eth0")
                .registrarRecusa();

        assertThat(saida.getAll())
                .contains("caminho=/api/x_y_evento_login.certo__conta_outra origem=2001:db8::1%eth0");
        assertThat(saida.getAll().split("evento=", -1)).hasSize(2);
        assertThat(saida.getAll().split(" conta=", -1)).hasSize(1);
    }

    @Test
    @DisplayName("acento e caractere de controle também viram sublinhado")
    void foraDoPermitido(CapturedOutput saida) {
        EventoDeSeguranca.de("acesso.recusado").com("caminho", "/ação\u0000\u001b[31m").registrar();

        assertThat(saida.getAll()).contains("caminho=/a__o___31m");
    }

    @Test
    @DisplayName("cada valor para em 200 caracteres")
    void corte(CapturedOutput saida) {
        EventoDeSeguranca.de("acesso.recusado").com("caminho", "a".repeat(300)).registrar();

        assertThat(saida.getAll())
                .contains("caminho=" + "a".repeat(200))
                .doesNotContain("a".repeat(201));
    }

    @Test
    @DisplayName("o contexto dá a Conta e o Usuário quando existem, e nada quando não existem")
    void contexto(CapturedOutput saida) {
        ContaId conta = ContaId.nova();
        UUID usuario = UUID.randomUUID();

        EventoDeSeguranca.de("fora.do.contexto").doContexto().registrar();
        TenantContext.executarComo(conta, () -> UsuarioContext.executarComo(
                new UsuarioAutenticado(usuario, Perfil.ADMIN),
                () -> EventoDeSeguranca.de("no.contexto").doContexto().registrar()));

        assertThat(saida.getAll())
                .contains("evento=fora.do.contexto" + System.lineSeparator())
                .contains("evento=no.contexto conta=" + conta + " usuario=" + usuario);
    }
}
