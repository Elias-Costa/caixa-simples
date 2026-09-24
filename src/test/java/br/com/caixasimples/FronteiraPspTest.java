package br.com.caixasimples;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/** Protege RF27 mesmo quando a implementação de um PSP muda dentro do módulo. */
class FronteiraPspTest {
    private static final String ADAPTERS = "..pagamentos.internal..";
    private static final DescribedPredicate<JavaClass> TIPO_DA_EFI =
            new DescribedPredicate<>("tipo específico da Efí") {
                @Override
                public boolean test(JavaClass tipo) {
                    String pacote = "." + tipo.getPackageName() + ".";
                    return tipo.getSimpleName().startsWith("Efi")
                            || pacote.contains(".efipay.")
                            || pacote.contains(".gerencianet.");
                }
            };

    private static final ArchRule TIPOS_DO_PSP_FICAM_NO_ADAPTER = classes()
            .that(TIPO_DA_EFI)
            .should().resideInAPackage(ADAPTERS);

    private static final ArchRule FORA_DO_ADAPTER_NAO_DEPENDE_DO_PSP = noClasses()
            .that().resideOutsideOfPackage(ADAPTERS)
            .should().dependOnClassesThat(TIPO_DA_EFI);

    private static final ArchRule FORA_DO_ADAPTER_NAO_ACESSA_INTERNAL = noClasses()
            .that().resideOutsideOfPackage(ADAPTERS)
            .should().dependOnClassesThat().resideInAPackage(ADAPTERS);

    @Test
    void tiposDaEfiNaoEscapamDoAdapter() {
        JavaClasses producao = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("br.com.caixasimples");

        TIPOS_DO_PSP_FICAM_NO_ADAPTER.check(producao);
        FORA_DO_ADAPTER_NAO_DEPENDE_DO_PSP.check(producao);
        FORA_DO_ADAPTER_NAO_ACESSA_INTERNAL.check(producao);
    }

    @Test
    void regraDetectaReferenciaAoPspForaDoAdapter() {
        JavaClasses violacao = new ClassFileImporter()
                .importClasses(EfiTipoDeProva.class, UsoIndevidoDoPsp.class);

        assertThat(FORA_DO_ADAPTER_NAO_DEPENDE_DO_PSP.evaluate(violacao).hasViolation()).isTrue();
        assertThat(TIPOS_DO_PSP_FICAM_NO_ADAPTER.evaluate(violacao).hasViolation()).isTrue();
    }

    private static final class EfiTipoDeProva {
    }

    private static final class UsoIndevidoDoPsp {
        private EfiTipoDeProva tipo;
    }
}
