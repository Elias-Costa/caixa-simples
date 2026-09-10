package br.com.caixasimples.cadastro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.tuple;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.cadastro.application.CatalogoInicialService;
import br.com.caixasimples.cadastro.application.ProdutoService;
import br.com.caixasimples.cadastro.application.ProdutoService.DadosDoProduto;
import br.com.caixasimples.cadastro.domain.Produto;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.TenantContext;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * O catálogo inicial sugerido (RF32) pelo lado de fora: o que um controller enxergaria.
 *
 * <p>Este teste só fala com {@link CatalogoInicialService} e {@link ProdutoService}, e não alcança
 * {@code ModeloProdutoEntity} nem o repositório dele, de propósito. O que depende de montar modelo
 * novo vive em {@code CatalogoInicialDoModeloTest}, dentro de {@code cadastro.internal}.
 *
 * <p>O catálogo de fábrica usado aqui é o de cafeteria, gravado por migration.
 */
class CatalogoInicialServiceTest extends TesteDeIntegracao {

    private static final String SENHA_DE_TESTE = "uma senha longa de teste";

    @Autowired
    private CatalogoInicialService catalogoInicial;

    @Autowired
    private ProdutoService produtos;

    @Autowired
    private CriadorDeContaDeTeste criador;

    @AfterEach
    void limparContexto() {
        TenantContext.limpar();
    }

    @Test
    @DisplayName("conta de cafeteria recebe o catálogo de fábrica, com preço zero em todo item")
    void cafeteriaRecebeOCatalogoDeFabrica() {
        ContaCriada conta = criador.criar("Cafeteria Aurora", SENHA_DE_TESTE);

        int copiados = TenantContext.executarComo(conta.contaId(),
                () -> catalogoInicial.aplicarPara("cafeteria"));

        assertThat(copiados).isPositive();

        TenantContext.executarComo(conta.contaId(), () -> {
            assertThat(produtos.listarAtivos())
                    .hasSize(copiados)
                    .extracting(Produto::getNome)
                    .contains("Cafe expresso", "Pao de queijo");

            // O preço nasce zero e o dono precifica; um valor sugerido pela plataforma seria chute
            // sobre o mercado daquele negócio.
            assertThat(produtos.listarAtivos())
                    .allSatisfy(item -> assertThat(item.getPreco()).isEqualTo(Money.ZERO));

            // A cópia carrega o resto da linha, não só o nome (RF32).
            assertThat(produtos.listarAtivos())
                    .extracting(Produto::getCategoria, Produto::getUnidade)
                    .contains(tuple("Bebidas", "un"));
        });
    }

    @Test
    @DisplayName("tipo de negócio casa ignorando maiúscula e espaço nas pontas")
    void tipoDeNegocioCasaIgnorandoCaixa() {
        ContaCriada conta = criador.criar("Cafeteria em Caixa Alta", SENHA_DE_TESTE);

        int copiados = TenantContext.executarComo(conta.contaId(),
                () -> catalogoInicial.aplicarPara("  CAFETERIA  "));

        // O campo aceita texto livre, e quem cadastrou a conta pode ter digitado com inicial
        // maiúscula. O catálogo não pode sumir por causa disso.
        assertThat(copiados).isPositive();
    }

    @Test
    @DisplayName("tipo de negócio sem modelo, em branco ou nulo começa em branco, sem estourar")
    void tipoSemModeloComecaEmBranco() {
        ContaCriada conta = criador.criar("Negocio Sem Catalogo", SENHA_DE_TESTE);

        TenantContext.executarComo(conta.contaId(), () -> {
            // Sem tipo correspondente, o cadastro simplesmente começa em branco. Não é erro, e não
            // existe exceção para isso.
            assertThat(catalogoInicial.aplicarPara("tipo que ninguem cadastrou")).isZero();
            assertThatNoException().isThrownBy(() -> catalogoInicial.aplicarPara(null));
            assertThat(catalogoInicial.aplicarPara("   ")).isZero();

            assertThat(produtos.listarAtivos()).isEmpty();
        });
    }

    @Test
    @DisplayName("editar o produto copiado não alcança a cópia da outra conta")
    void editarACopiaNaoAlcancaAOutraConta() {
        ContaCriada contaA = criador.criar("Cafeteria A", SENHA_DE_TESTE);
        ContaCriada contaB = criador.criar("Cafeteria B", SENHA_DE_TESTE);

        TenantContext.executarComo(contaA.contaId(), () -> catalogoInicial.aplicarPara("cafeteria"));
        TenantContext.executarComo(contaB.contaId(), () -> catalogoInicial.aplicarPara("cafeteria"));

        TenantContext.executarComo(contaA.contaId(), () -> {
            Produto expresso = produtos.listarAtivos().stream()
                    .filter(item -> item.getNome().equals("Cafe expresso"))
                    .findFirst()
                    .orElseThrow();

            produtos.editar(expresso.getId(), new DadosDoProduto("Expresso curto da casa",
                    Money.de("7.00"), "EXP-1", "Cafes", "un", Map.of("tamanho", "curto")));
        });

        // Isolamento entre contas pela porta do catálogo inicial: as duas contas copiaram a mesma
        // linha de modelo_produto, e ainda assim uma editou sem tocar na outra. É o que a cópia,
        // em vez de referência viva, compra (RNF05).
        TenantContext.executarComo(contaB.contaId(), () ->
                assertThat(produtos.listarAtivos())
                        .extracting(Produto::getNome)
                        .contains("Cafe expresso")
                        .doesNotContain("Expresso curto da casa"));
    }
}
