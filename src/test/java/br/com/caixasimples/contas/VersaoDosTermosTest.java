package br.com.caixasimples.contas;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.caixasimples.contas.application.TermosDeUsoService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * O texto dos termos mora no aplicativo, e a versão que o servidor confere mora aqui. Mudar uma sem a
 * outra deixaria a Conta sem aceite do texto novo, ou o administrador preso numa tela que o servidor
 * sempre recusa.
 */
class VersaoDosTermosTest {

    private static final Path VERSAO_NO_APLICATIVO = Path.of("frontend/src/telas/versaoDosTermos.ts");

    @Test
    @DisplayName("a versão dos termos do aplicativo é a que o servidor confere")
    void versaoDoAplicativoEADoServidor() throws Exception {
        Matcher versao = Pattern.compile("VERSAO_DOS_TERMOS = '([^']+)'")
                .matcher(Files.readString(VERSAO_NO_APLICATIVO));

        assertThat(versao.find()).as("a constante em " + VERSAO_NO_APLICATIVO).isTrue();
        assertThat(versao.group(1)).isEqualTo(TermosDeUsoService.VERSAO_VIGENTE);
    }
}
