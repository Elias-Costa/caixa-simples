package br.com.caixasimples.cadastro.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import br.com.caixasimples.cadastro.TipoProduto;
import br.com.caixasimples.shared.Money;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regras que a raiz do agregado garante sozinha, sem banco e sem Spring, que é justamente a razão
 * de {@code domain/} não importar framework nenhum.
 *
 * <p>A invariante de que {@code estoqueAtual} é a soma dos movimentos ainda não pode ser violada
 * aqui, porque o movimento de estoque ainda não existe. O teste dela nasce junto com ele.
 */
class ProdutoTest {

    private static Produto valido(Money preco) {
        return new Produto("Cafe coado", preco, TipoProduto.PRODUTO, null, null, null, null);
    }

    @Test
    @DisplayName("preço negativo é recusado, porque não é preço")
    void precoNegativoERecusado() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> valido(Money.de("-0.01")))
                .withMessageContaining("preco");
    }

    @Test
    @DisplayName("preço zero é aceito: cortesia, brinde, item de acompanhamento")
    void precoZeroEAceito() {
        assertThat(valido(Money.ZERO).getPreco()).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("nome é obrigatório; preço e tipo também")
    void camposObrigatorios() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Produto("   ", Money.de("1.00"), TipoProduto.PRODUTO, null,
                        null, null, null))
                .withMessageContaining("nome");

        assertThatNullPointerException()
                .isThrownBy(() -> valido(null));

        assertThatNullPointerException()
                .isThrownBy(() -> new Produto("Cafe", Money.de("1.00"), null, null, null, null,
                        null));
    }

    @Test
    @DisplayName("categoria, unidade e código são opcionais")
    void camposOpcionais() {
        Produto produto = valido(Money.de("6.50"));

        assertThat(produto.getCategoria()).isNull();
        assertThat(produto.getUnidade()).isNull();
        assertThat(produto.getCodigo()).isNull();
    }

    @Test
    @DisplayName("texto em branco vira ausência, não um código vazio disputando o índice único")
    void textoEmBrancoViraNulo() {
        Produto produto = new Produto("Corte", Money.de("40.00"), TipoProduto.SERVICO, "   ", "  ",
                "", null);

        assertThat(produto.getCodigo()).isNull();
        assertThat(produto.getCategoria()).isNull();
        assertThat(produto.getUnidade()).isNull();
    }

    @Test
    @DisplayName("espaços nas pontas somem, de modo que ABC-12 com e sem espaço é o mesmo cadastro")
    void textoEhAparado() {
        Produto produto = new Produto("  Cappuccino  ", Money.de("9.00"), TipoProduto.PRODUTO,
                " ABC-12 ", " Bebidas ", " un ", null);

        assertThat(produto.getNome()).isEqualTo("Cappuccino");
        assertThat(produto.getCodigo()).isEqualTo("ABC-12");
        assertThat(produto.getCategoria()).isEqualTo("Bebidas");
        assertThat(produto.getUnidade()).isEqualTo("un");
    }

    @Test
    @DisplayName("estoque nasce zerado, inclusive em serviço")
    void estoqueNasceZerado() {
        Produto servico = new Produto("Corte", Money.de("40.00"), TipoProduto.SERVICO, null, null,
                null, null);

        assertThat(servico.getEstoqueAtual()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("atributo só muda pela raiz: o mapa de fora não entra e o de dentro não sai mutável")
    void atributosSaoIsolados() {
        Map<String, Object> mutavel = new HashMap<>();
        mutavel.put("tamanho", "M");

        Produto produto = new Produto("Camiseta", Money.de("49.90"), TipoProduto.PRODUTO, null,
                null, null, mutavel);

        mutavel.put("cor", "preta");

        assertThat(produto.getAtributos())
                .as("mapa de fora não entra depois de construído")
                .containsExactlyEntriesOf(Map.of("tamanho", "M"));

        assertThatExceptionOfType(UnsupportedOperationException.class)
                .as("mapa de dentro não sai mutável")
                .isThrownBy(() -> produto.getAtributos().put("cor", "preta"));
    }

    @Test
    @DisplayName("sem atributo específico, o mapa é vazio e nunca nulo para quem lê (RF02)")
    void semAtributosViraMapaVazio() {
        assertThat(valido(Money.de("6.50")).getAtributos()).isEmpty();
    }

    @Test
    @DisplayName("inativar preserva o registro, que é o soft delete do RF05")
    void inativarNaoApaga() {
        Produto produto = valido(Money.de("6.50"));
        assertThat(produto.isAtivo()).isTrue();

        produto.inativar();

        assertThat(produto.isAtivo()).isFalse();
        assertThat(produto.getId()).isNotNull();
        assertThat(produto.getNome()).isEqualTo("Cafe coado");
    }

    @Test
    @DisplayName("inativar é idempotente, porque dois cliques no balcão não são erro")
    void inativarDuasVezesNaoEstoura() {
        Produto produto = valido(Money.de("6.50"));

        produto.inativar();
        produto.inativar();

        assertThat(produto.isAtivo()).isFalse();
    }

    @Test
    @DisplayName("alterar cobra as mesmas regras do cadastro: nome obrigatório, preço não negativo")
    void alterarValidaComoOConstrutor() {
        Produto produto = valido(Money.de("6.50"));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> produto.alterar("  ", Money.de("7.00"), null, null, null, null))
                .withMessageContaining("nome");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> produto.alterar("Cafe", Money.de("-0.01"), null, null, null,
                        null))
                .withMessageContaining("preco");

        assertThatNullPointerException()
                .isThrownBy(() -> produto.alterar("Cafe", null, null, null, null, null));
    }

    @Test
    @DisplayName("alterar substitui os campos editáveis, aparando texto e tratando branco como ausência")
    void alterarSubstituiOsCamposEditaveis() {
        Produto produto = new Produto("Cafe coado", Money.de("6.50"), TipoProduto.PRODUTO, "ABC-1",
                "Bebidas", "un", Map.of("tamanho", "M"));

        produto.alterar("  Cafe coado grande  ", Money.de("8.00"), " ABC-2 ", "  ", "",
                Map.of("cor", "preta"));

        assertThat(produto.getNome()).isEqualTo("Cafe coado grande");
        assertThat(produto.getPreco()).isEqualTo(Money.de("8.00"));
        assertThat(produto.getCodigo()).isEqualTo("ABC-2");
        assertThat(produto.getCategoria()).isNull();
        assertThat(produto.getUnidade()).isNull();
        assertThat(produto.getAtributos())
                .as("atributo é substituído por inteiro, nunca mesclado com o que estava lá")
                .containsExactlyEntriesOf(Map.of("cor", "preta"));
    }

    @Test
    @DisplayName("alterar não mexe em tipo nem em estoque")
    void alterarNaoTocaNoQueNaoEDoCadastro() {
        Produto servico = new Produto("Corte", Money.de("40.00"), TipoProduto.SERVICO, null, null,
                null, null);

        // Não há assinatura por onde passar tipo ou estoque: a ausência é que é a regra.
        servico.alterar("Corte masculino", Money.de("45.00"), null, null, "hora", null);

        assertThat(servico.getTipo()).isEqualTo(TipoProduto.SERVICO);
        assertThat(servico.getEstoqueAtual()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("produto inativo não pode ser editado")
    void alterarProdutoInativoERecusado() {
        Produto produto = valido(Money.de("6.50"));
        produto.inativar();

        assertThatIllegalStateException()
                .isThrownBy(() -> produto.alterar("Outro nome", Money.de("9.00"), null, null, null,
                        null))
                .withMessageContaining("inativo");

        assertThat(produto.getNome())
                .as("a recusa não pode ter aplicado nada pela metade")
                .isEqualTo("Cafe coado");
    }
}
