package br.com.caixasimples.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * As duas perguntas de autorização, sem Spring e sem banco: quem passa por {@code exigirAdmin} e
 * por {@code exigirDonoOuAdmin}, e o que acontece quando não há ninguém no contexto.
 */
class UsuarioContextTest {

    private static final UsuarioAutenticado ADMIN =
            new UsuarioAutenticado(UUID.randomUUID(), Perfil.ADMIN);
    private static final UsuarioAutenticado OPERADOR =
            new UsuarioAutenticado(UUID.randomUUID(), Perfil.OPERADOR);

    @AfterEach
    void limpar() {
        UsuarioContext.limpar();
    }

    @Test
    @DisplayName("sem usuário no contexto, as duas perguntas falham fechado")
    void semUsuarioFalhaFechado() {
        assertThat(UsuarioContext.atual()).isEmpty();
        assertThatExceptionOfType(UsuarioNaoResolvidoException.class)
                .isThrownBy(UsuarioContext::exigirAtual);
        assertThatExceptionOfType(UsuarioNaoResolvidoException.class)
                .isThrownBy(UsuarioContext::exigirAdmin);
        assertThatExceptionOfType(UsuarioNaoResolvidoException.class)
                .isThrownBy(() -> UsuarioContext.exigirDonoOuAdmin(OPERADOR.usuarioId()));
    }

    @Test
    @DisplayName("exigirAdmin: o administrador passa, o operador não")
    void exigirAdmin() {
        UsuarioContext.executarComo(ADMIN, () ->
                assertThatNoException().isThrownBy(UsuarioContext::exigirAdmin));

        UsuarioContext.executarComo(OPERADOR, () ->
                assertThatExceptionOfType(AcessoNegadoException.class)
                        .isThrownBy(UsuarioContext::exigirAdmin)
                        .withMessageContaining("administrador"));
    }

    @Test
    @DisplayName("exigirDonoOuAdmin: o operador só passa como dono, e nulo nunca é dono")
    void exigirDonoOuAdmin() {
        UsuarioContext.executarComo(OPERADOR, () -> {
            assertThatNoException()
                    .isThrownBy(() -> UsuarioContext.exigirDonoOuAdmin(OPERADOR.usuarioId()));
            assertThatExceptionOfType(AcessoNegadoException.class)
                    .isThrownBy(() -> UsuarioContext.exigirDonoOuAdmin(ADMIN.usuarioId()))
                    .withMessageContaining("proprio caixa");
            assertThatExceptionOfType(AcessoNegadoException.class)
                    .as("sem dono é a conta inteira, e isso é pergunta do administrador")
                    .isThrownBy(() -> UsuarioContext.exigirDonoOuAdmin(null));
        });

        UsuarioContext.executarComo(ADMIN, () -> {
            assertThatNoException()
                    .isThrownBy(() -> UsuarioContext.exigirDonoOuAdmin(OPERADOR.usuarioId()));
            assertThatNoException()
                    .isThrownBy(() -> UsuarioContext.exigirDonoOuAdmin(null));
        });
    }

    @Test
    @DisplayName("executarComo restaura o usuário anterior, ou limpa se não havia")
    void executarComoRestaura() {
        UsuarioContext.executarComo(ADMIN, () -> {
            UsuarioContext.executarComo(OPERADOR, () ->
                    assertThat(UsuarioContext.exigirAtual()).isEqualTo(OPERADOR));
            assertThat(UsuarioContext.exigirAtual()).isEqualTo(ADMIN);
        });
        assertThat(UsuarioContext.atual()).isEmpty();
    }
}
