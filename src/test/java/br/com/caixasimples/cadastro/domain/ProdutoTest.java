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
 * Regras que a raiz do agregado garante sozinha, sem banco e sem Spring — que e a razao de
 * {@code domain/} nao importar framework nenhum (arquitetura §2).
 *
 * <p>A invariante do modelo de dados §4 ({@code estoqueAtual} = soma dos movimentos) ainda nao
 * pode ser violada: {@code MovimentoEstoque} nasce na etapa 1.7 (R15), e o teste dela vai junto.
 */
class ProdutoTest {

    private static Produto valido(Money preco) {
        return new Produto("Cafe coado", preco, TipoProduto.PRODUTO, null, null, null, null);
    }

    @Test
    @DisplayName("preco negativo e recusado — nao e preco")
    void precoNegativoERecusado() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> valido(Money.de("-0.01")))
                .withMessageContaining("preco");
    }

    @Test
    @DisplayName("preco zero e aceito: cortesia, brinde, item de acompanhamento (D16c)")
    void precoZeroEAceito() {
        assertThat(valido(Money.ZERO).getPreco()).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("nome e obrigatorio; preco e tipo tambem (D16a)")
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
    @DisplayName("categoria e unidade sao opcionais (D16a), e codigo tambem (D11)")
    void camposOpcionais() {
        Produto produto = valido(Money.de("6.50"));

        assertThat(produto.getCategoria()).isNull();
        assertThat(produto.getUnidade()).isNull();
        assertThat(produto.getCodigo()).isNull();
    }

    @Test
    @DisplayName("texto em branco vira ausencia, nao um codigo vazio disputando o indice unico")
    void textoEmBrancoViraNulo() {
        Produto produto = new Produto("Corte", Money.de("40.00"), TipoProduto.SERVICO, "   ", "  ",
                "", null);

        assertThat(produto.getCodigo()).isNull();
        assertThat(produto.getCategoria()).isNull();
        assertThat(produto.getUnidade()).isNull();
    }

    @Test
    @DisplayName("espacos nas pontas somem — 'ABC-12 ' e 'ABC-12' sao o mesmo cadastro")
    void textoEhAparado() {
        Produto produto = new Produto("  Cappuccino  ", Money.de("9.00"), TipoProduto.PRODUTO,
                " ABC-12 ", " Bebidas ", " un ", null);

        assertThat(produto.getNome()).isEqualTo("Cappuccino");
        assertThat(produto.getCodigo()).isEqualTo("ABC-12");
        assertThat(produto.getCategoria()).isEqualTo("Bebidas");
        assertThat(produto.getUnidade()).isEqualTo("un");
    }

    @Test
    @DisplayName("estoque nasce zerado, inclusive em servico (D16b)")
    void estoqueNasceZerado() {
        Produto servico = new Produto("Corte", Money.de("40.00"), TipoProduto.SERVICO, null, null,
                null, null);

        assertThat(servico.getEstoqueAtual()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("atributo so muda pela raiz: o mapa de fora nao entra e o de dentro nao sai mutavel")
    void atributosSaoIsolados() {
        Map<String, Object> mutavel = new HashMap<>();
        mutavel.put("tamanho", "M");

        Produto produto = new Produto("Camiseta", Money.de("49.90"), TipoProduto.PRODUTO, null,
                null, null, mutavel);

        mutavel.put("cor", "preta");

        assertThat(produto.getAtributos())
                .as("mapa de fora nao entra depois de construido")
                .containsExactlyEntriesOf(Map.of("tamanho", "M"));

        assertThatExceptionOfType(UnsupportedOperationException.class)
                .as("mapa de dentro nao sai mutavel")
                .isThrownBy(() -> produto.getAtributos().put("cor", "preta"));
    }

    @Test
    @DisplayName("sem atributo especifico, mapa vazio — nunca nulo para quem le (RF02)")
    void semAtributosViraMapaVazio() {
        assertThat(valido(Money.de("6.50")).getAtributos()).isEmpty();
    }

    @Test
    @DisplayName("inativar preserva o registro — soft delete do RF05")
    void inativarNaoApaga() {
        Produto produto = valido(Money.de("6.50"));
        assertThat(produto.isAtivo()).isTrue();

        produto.inativar();

        assertThat(produto.isAtivo()).isFalse();
        assertThat(produto.getId()).isNotNull();
        assertThat(produto.getNome()).isEqualTo("Cafe coado");
    }

    @Test
    @DisplayName("inativar e idempotente — dois cliques no balcao nao sao erro (D17c)")
    void inativarDuasVezesNaoEstoura() {
        Produto produto = valido(Money.de("6.50"));

        produto.inativar();
        produto.inativar();

        assertThat(produto.isAtivo()).isFalse();
    }

    @Test
    @DisplayName("alterar cobra as mesmas regras do cadastro: nome obrigatorio, preco nao negativo")
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
    @DisplayName("alterar substitui os campos editaveis, aparando texto e tratando branco como ausencia")
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
                .as("atributo e substituido por inteiro, nunca mesclado com o que estava la")
                .containsExactlyEntriesOf(Map.of("cor", "preta"));
    }

    @Test
    @DisplayName("alterar nao mexe em tipo nem em estoque (D17a, R15)")
    void alterarNaoTocaNoQueNaoEDoCadastro() {
        Produto servico = new Produto("Corte", Money.de("40.00"), TipoProduto.SERVICO, null, null,
                null, null);

        // Nao ha assinatura por onde passar tipo ou estoque — a ausencia e que e a regra.
        servico.alterar("Corte masculino", Money.de("45.00"), null, null, "hora", null);

        assertThat(servico.getTipo()).isEqualTo(TipoProduto.SERVICO);
        assertThat(servico.getEstoqueAtual()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("produto inativo nao pode ser editado (D17c)")
    void alterarProdutoInativoERecusado() {
        Produto produto = valido(Money.de("6.50"));
        produto.inativar();

        assertThatIllegalStateException()
                .isThrownBy(() -> produto.alterar("Outro nome", Money.de("9.00"), null, null, null,
                        null))
                .withMessageContaining("inativo");

        assertThat(produto.getNome())
                .as("a recusa nao pode ter aplicado nada pela metade")
                .isEqualTo("Cafe coado");
    }
}
