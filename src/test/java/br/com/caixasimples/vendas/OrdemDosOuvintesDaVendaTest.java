package br.com.caixasimples.vendas;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;

/**
 * Os ouvintes da conclusão e do cancelamento declaram a ordem, e o caixa vem antes do estoque.
 *
 * <p>Os ouvintes rodam na transação de quem publica, e cada um grava uma raiz com versão: o do
 * caixa a SessaoCaixa, o do estoque cada Produto. Duas transações que gravam as mesmas raízes em
 * ordens opostas esperariam uma pela outra até o banco derrubar uma delas. Sem {@code @Order}, a
 * ordem entre os ouvintes de um evento é a do registro dos beans, que nenhum contrato do Spring
 * fixa.
 */
class OrdemDosOuvintesDaVendaTest {

    private static final JavaClasses PRODUCAO = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("br.com.caixasimples");

    @Test
    void conclusaoPassaPeloCaixaAntesDoEstoque() {
        exigirCaixaAntesDoEstoque(VendaConcluida.class);
    }

    @Test
    void cancelamentoPassaPeloCaixaAntesDoEstoque() {
        exigirCaixaAntesDoEstoque(VendaCancelada.class);
    }

    private static void exigirCaixaAntesDoEstoque(Class<?> evento) {
        List<JavaMethod> ouvintes = PRODUCAO.stream()
                .flatMap(classe -> classe.getMethods().stream())
                .filter(metodo -> metodo.isAnnotatedWith(EventListener.class))
                .filter(metodo -> metodo.getRawParameterTypes().stream()
                        .anyMatch(tipo -> tipo.isEquivalentTo(evento)))
                .toList();

        assertThat(ouvintes)
                .as("todo ouvinte de %s declara a ordem", evento.getSimpleName())
                .isNotEmpty()
                .allMatch(metodo -> metodo.isAnnotatedWith(Order.class));
        assertThat(ordemDoOuvinte(ouvintes, "caixa"))
                .as("o caixa ouve %s antes do estoque", evento.getSimpleName())
                .isLessThan(ordemDoOuvinte(ouvintes, "estoque"));
    }

    /** A ordem declarada pelo ouvinte do módulo, que hoje tem um só para cada evento. */
    private static int ordemDoOuvinte(List<JavaMethod> ouvintes, String modulo) {
        List<Integer> ordens = ouvintes.stream()
                .filter(metodo -> metodo.getOwner().getPackageName()
                        .startsWith("br.com.caixasimples." + modulo + "."))
                .map(metodo -> metodo.getAnnotationOfType(Order.class).value())
                .toList();
        assertThat(ordens).as("ouvintes do módulo %s", modulo).hasSize(1);
        return ordens.get(0);
    }
}
