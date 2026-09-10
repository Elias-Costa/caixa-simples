package br.com.caixasimples;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Fitness function das fronteiras de módulo (RNF11).
 *
 * <p>Falha se um módulo acessar o subpacote {@code internal} de outro, ou se houver ciclo entre
 * módulos. A mensagem de erro aponta exatamente quem violou o quê, e a correção é sempre na direção
 * da fronteira, seja por evento de domínio ou pela API pública do pacote, nunca relaxando a regra.
 *
 * <p>O segundo teste gera a documentação de arquitetura a partir dos módulos detectados. Ele
 * também é o que faz o javadoc de {@code src/main/java} ser exportado para JSON, e por isso um
 * delimitador sem par num comentário de lá quebra esta classe, com um erro que não menciona
 * javadoc.
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
