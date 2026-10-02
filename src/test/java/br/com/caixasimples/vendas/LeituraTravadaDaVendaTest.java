package br.com.caixasimples.vendas;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.caixasimples.vendas.application.VendaService;
import br.com.caixasimples.vendas.internal.VendaEntity;
import br.com.caixasimples.vendas.internal.VendaRepository;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * Toda alteração da Venda lê a raiz com a trava da linha.
 *
 * <p>A linha da Venda não tem versão: duas escritas que leram a mesma comanda sem trava gravam uma
 * por cima da outra, e a que confirma por último desfaz a outra. Com a trava, a segunda espera a
 * primeira confirmar e lê o que ela deixou. O PostgreSQL recusa trava em transação somente
 * leitura, então a leitura sem trava continua existindo, e estas regras dizem onde ela pode estar:
 * só ela chama {@code findById}, e só as consultas a chamam.
 */
class LeituraTravadaDaVendaTest {

    private static final DescribedPredicate<JavaMethod> SOMENTE_LEITURA =
            new DescribedPredicate<>("anotados com @Transactional(readOnly = true)") {
                @Override
                public boolean test(JavaMethod metodo) {
                    return metodo.tryGetAnnotationOfType(Transactional.class)
                            .map(Transactional::readOnly)
                            .orElse(false);
                }
            };

    @Test
    void alteracaoDaVendaLeComTrava() {
        JavaClasses producao = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("br.com.caixasimples.vendas");

        soALeituraSemTravaChamaFindById(VendaService.class).check(producao);
        soConsultaChamaALeituraSemTrava(VendaService.class).check(producao);
    }

    @Test
    void regrasRecusamEscritaQueLeSemTrava() {
        assertThat(violaAsRegras(EscritorQueLeSemTrava.class)).isTrue();
        assertThat(violaAsRegras(EscritorPelaLeituraSemTrava.class)).isTrue();
        assertThat(violaAsRegras(ServicoQueSegueAsRegras.class)).isFalse();
    }

    private static ArchRule soALeituraSemTravaChamaFindById(Class<?> servico) {
        return methods().that().areDeclaredIn(servico).and().doNotHaveName("lerSemTrava")
                .should(naoChamar(VendaRepository.class, "findById"))
                .because("quem altera a Venda lê a raiz com a trava da linha");
    }

    private static ArchRule soConsultaChamaALeituraSemTrava(Class<?> servico) {
        return methods().that().areDeclaredIn(servico).and(DescribedPredicate.not(SOMENTE_LEITURA))
                .should(naoChamar(servico, "lerSemTrava"))
                .because("só a transação somente leitura dispensa a trava");
    }

    /** Toda chamada do método ao alvo, inclusive dentro de lambda, que vira método próprio. */
    private static ArchCondition<JavaMethod> naoChamar(Class<?> dono, String nome) {
        return new ArchCondition<>("não chamar %s.%s", dono.getSimpleName(), nome) {
            @Override
            public void check(JavaMethod metodo, ConditionEvents eventos) {
                for (JavaMethodCall chamada : metodo.getMethodCallsFromSelf()) {
                    if (chamada.getTargetOwner().isEquivalentTo(dono)
                            && chamada.getName().equals(nome)) {
                        eventos.add(SimpleConditionEvent.violated(chamada,
                                chamada.getDescription()));
                    }
                }
            }
        };
    }

    private static boolean violaAsRegras(Class<?> servico) {
        JavaClasses importada = new ClassFileImporter().importClasses(servico);
        return soALeituraSemTravaChamaFindById(servico).evaluate(importada).hasViolation()
                || soConsultaChamaALeituraSemTrava(servico).evaluate(importada).hasViolation();
    }

    // As fixtures nunca rodam: só o bytecode delas é lido, e o repositório fica sem valor.

    private static final class EscritorQueLeSemTrava {
        private VendaRepository vendas;

        @Transactional
        void alterar(UUID vendaId) {
            vendas.findById(vendaId).orElseThrow();
        }
    }

    /** A leitura sem trava existe, mas uma alteração a usa. */
    private static final class EscritorPelaLeituraSemTrava {
        private VendaRepository vendas;

        @Transactional
        void alterar(UUID vendaId) {
            lerSemTrava(vendaId);
        }

        private VendaEntity lerSemTrava(UUID vendaId) {
            return vendas.findById(vendaId).orElseThrow();
        }
    }

    /** Controle: a recusa vem de quem chama, não de uma regra que recusa tudo. */
    private static final class ServicoQueSegueAsRegras {
        private VendaRepository vendas;

        @Transactional
        void alterar(UUID vendaId) {
            vendas.findLockedById(vendaId).orElseThrow();
        }

        @Transactional(readOnly = true)
        void consultar(UUID vendaId) {
            lerSemTrava(vendaId);
        }

        private VendaEntity lerSemTrava(UUID vendaId) {
            return vendas.findById(vendaId).orElseThrow();
        }
    }
}
