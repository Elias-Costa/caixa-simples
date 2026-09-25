package br.com.caixasimples.shared.web;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.annotation.OrderUtils;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Todo advice além do transversal declara que vem antes dele.
 *
 * <p>Entre os advices aplicáveis a um controller, o Spring MVC usa o primeiro que tenha tratador
 * compatível com a exceção, e o transversal tem um para {@link Exception}. Sem {@code @Order}, o
 * desempate é a ordem em que a varredura registrou os beans, que nenhum contrato do Spring fixa:
 * o 404 ou o 409 de um módulo sairia como o 500 sem detalhe do transversal.
 */
class PrecedenciaDosAdvicesTest {

    private static final int ORDEM_DO_TRANSVERSAL =
            OrderUtils.getOrder(TratamentoDeErrosHttp.class, Ordered.LOWEST_PRECEDENCE);

    private static final DescribedPredicate<JavaAnnotation<?>> ORDEM_ANTES_DO_TRANSVERSAL =
            new DescribedPredicate<>("@Order de valor menor que o do tratador transversal") {
                @Override
                public boolean test(JavaAnnotation<?> anotacao) {
                    return anotacao.getRawType().isEquivalentTo(Order.class)
                            && anotacao.as(Order.class).value() < ORDEM_DO_TRANSVERSAL;
                }
            };

    private static final ArchRule ADVICE_VEM_ANTES_DO_TRANSVERSAL = classes()
            .that().areMetaAnnotatedWith(ControllerAdvice.class)
            .and().doNotBelongToAnyOf(TratamentoDeErrosHttp.class)
            .should().beAnnotatedWith(ORDEM_ANTES_DO_TRANSVERSAL)
            .because("entre os advices responde o primeiro que sabe tratar a exceção, e o"
                    + " transversal sabe tratar qualquer uma");

    @Test
    void todoAdviceAlemDoTransversalDeclaraPrecedencia() {
        JavaClasses producao = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("br.com.caixasimples");

        ADVICE_VEM_ANTES_DO_TRANSVERSAL.check(producao);
    }

    @Test
    void regraRecusaAdviceSemOrdemOuComAOrdemPadrao() {
        assertThat(violaARegra(AdviceSemOrdem.class)).isTrue();
        assertThat(violaARegra(AdviceComOrdemPadrao.class)).isTrue();
        assertThat(violaARegra(AdviceComPrecedencia.class)).isFalse();
    }

    private static boolean violaARegra(Class<?> advice) {
        JavaClasses importada = new ClassFileImporter().importClasses(advice);
        return ADVICE_VEM_ANTES_DO_TRANSVERSAL.evaluate(importada).hasViolation();
    }

    // As fixtures ficam aninhadas nesta classe de teste, o que as tira da varredura dos testes de
    // integração, e não têm tratador: registradas, não mudariam resposta alguma.

    @RestControllerAdvice(basePackageClasses = PrecedenciaDosAdvicesTest.class)
    private static final class AdviceSemOrdem {
    }

    /** O valor padrão de {@code @Order} é a menor precedência, a mesma do transversal. */
    @RestControllerAdvice(basePackageClasses = PrecedenciaDosAdvicesTest.class)
    @Order
    private static final class AdviceComOrdemPadrao {
    }

    /** Controle: a recusa vem do valor de {@code @Order}, não de uma regra que recusa tudo. */
    @RestControllerAdvice(basePackageClasses = PrecedenciaDosAdvicesTest.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    private static final class AdviceComPrecedencia {
    }
}
