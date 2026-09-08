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
 * RF32 pelo lado de fora: o que um controller enxergaria do catalogo inicial.
 *
 * <p>Este teste so fala com {@link CatalogoInicialService} e {@link ProdutoService} — nao alcanca
 * {@code ModeloProdutoEntity} nem o repositorio dele de proposito, no mesmo espirito da separacao
 * feita no R04. O que depende de montar modelo novo vive em {@code CatalogoInicialDoModeloTest},
 * dentro de {@code cadastro.internal}.
 *
 * <p>O catalogo de fabrica usado aqui e o da {@code cafeteria}, gravado pela {@code V4} (D20a/D20b).
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
    @DisplayName("conta de cafeteria recebe o catalogo de fabrica, com preco zero em todo item")
    void cafeteriaRecebeOCatalogoDeFabrica() {
        ContaCriada conta = criador.criar("Cafeteria Piloto", SENHA_DE_TESTE);

        int copiados = TenantContext.executarComo(conta.contaId(),
                () -> catalogoInicial.aplicarPara("cafeteria"));

        assertThat(copiados).isPositive();

        TenantContext.executarComo(conta.contaId(), () -> {
            assertThat(produtos.listarAtivos())
                    .hasSize(copiados)
                    .extracting(Produto::getNome)
                    .contains("Cafe expresso", "Pao de queijo");

            // D20c — preco nasce zero e o dono precifica; um valor sugerido pela plataforma seria
            // chute sobre o mercado do negocio.
            assertThat(produtos.listarAtivos())
                    .allSatisfy(item -> assertThat(item.getPreco()).isEqualTo(Money.ZERO));

            // A copia carrega o resto da linha, nao so o nome (RF32).
            assertThat(produtos.listarAtivos())
                    .extracting(Produto::getCategoria, Produto::getUnidade)
                    .contains(tuple("Bebidas", "un"));
        });
    }

    @Test
    @DisplayName("tipo de negocio casa ignorando maiuscula e espaco nas pontas")
    void tipoDeNegocioCasaIgnorandoCaixa() {
        ContaCriada conta = criador.criar("Cafeteria em Caixa Alta", SENHA_DE_TESTE);

        int copiados = TenantContext.executarComo(conta.contaId(),
                () -> catalogoInicial.aplicarPara("  CAFETERIA  "));

        // D20e — o campo aceita texto livre; quem cadastrou a conta pode ter digitado com inicial
        // maiuscula, e o catalogo nao pode sumir por causa disso.
        assertThat(copiados).isPositive();
    }

    @Test
    @DisplayName("tipo de negocio sem modelo, em branco ou nulo comeca em branco, sem estourar")
    void tipoSemModeloComecaEmBranco() {
        ContaCriada conta = criador.criar("Negocio Sem Catalogo", SENHA_DE_TESTE);

        TenantContext.executarComo(conta.contaId(), () -> {
            // Modelo de dados §3: sem tipo correspondente, o cadastro simplesmente comeca em
            // branco. Nao e erro, e nao existe excecao para isso.
            assertThat(catalogoInicial.aplicarPara("tipo que ninguem cadastrou")).isZero();
            assertThatNoException().isThrownBy(() -> catalogoInicial.aplicarPara(null));
            assertThat(catalogoInicial.aplicarPara("   ")).isZero();

            assertThat(produtos.listarAtivos()).isEmpty();
        });
    }

    @Test
    @DisplayName("editar o produto copiado nao alcanca a copia da outra conta")
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

        // RNF05 pela porta do RF32: as duas contas copiaram a mesma linha de modelo_produto, e
        // ainda assim uma editou sem tocar na outra — e o que a copia (em vez de referencia viva)
        // compra.
        TenantContext.executarComo(contaB.contaId(), () ->
                assertThat(produtos.listarAtivos())
                        .extracting(Produto::getNome)
                        .contains("Cafe expresso")
                        .doesNotContain("Expresso curto da casa"));
    }
}
