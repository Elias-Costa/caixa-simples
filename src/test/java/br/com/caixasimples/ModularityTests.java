package br.com.caixasimples;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Fitness function das fronteiras de modulo (RNF11) — etapa 0.4 do plano de implementacao.
 *
 * <p>Falha se um modulo acessar o subpacote {@code internal} de outro, ou se houver ciclo entre
 * modulos. A mensagem de erro aponta exatamente quem violou o que; a correcao e sempre na direcao
 * da fronteira (evento de dominio ou API publica do pacote), nunca relaxando a regra.
 *
 * @see <a href="file:../../../../../.claude/rules/fronteiras-modulos.md">rules/fronteiras-modulos</a>
 */
class ModularityTests {

    static final ApplicationModules modules = ApplicationModules.of(CaixaSimplesApplication.class);

    @Test
    void verificaFronteiras() {
        modules.verify();
    }

    @Test
    void geraDocumentacao() {
        new Documenter(modules).writeDocumentation();
    }
}
