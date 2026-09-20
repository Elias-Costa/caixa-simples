package br.com.caixasimples.cadastro.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import br.com.caixasimples.cadastro.TipoMovimentoEstoque;
import br.com.caixasimples.cadastro.TipoProduto;
import br.com.caixasimples.shared.Money;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regras que a raiz do agregado garante sozinha, sem banco e sem Spring, que é justamente a razão
 * de {@code domain/} não importar framework nenhum.
 *
 * <p>A raiz não carrega o histórico de movimentos, então a invariante de que {@code estoqueAtual}
 * é a soma deles não se prova aqui, e sim em integração, onde saldo e movimento são gravados
 * juntos. O que se prova aqui é o que a raiz garante sozinha: só produto baixa e estorna, só
 * quantidade positiva baixa e estorna, cada baixa, estorno ou ajuste devolve exatamente o
 * movimento que o explica, ajuste sem motivo não passa, e o limiar do alerta de estoque baixo é
 * comparado como a raiz decide.
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

    @Test
    @DisplayName("só produto controla estoque; serviço não")
    void soProdutoControlaEstoque() {
        assertThat(valido(Money.de("6.50")).controlaEstoque()).isTrue();
        assertThat(new Produto("Corte", Money.de("40.00"), TipoProduto.SERVICO, null, null, null,
                null).controlaEstoque()).isFalse();
    }

    @Test
    @DisplayName("a baixa desce o saldo e devolve a SAIDA que a explica, apontando para a venda")
    void baixaDesceOSaldoEDevolveOMovimento() {
        Produto produto = valido(Money.de("6.50"));
        UUID vendaId = UUID.randomUUID();

        MovimentoEstoque movimento = produto.darBaixaPorVenda(new BigDecimal("0.750"), vendaId);

        assertThat(produto.getEstoqueAtual()).isEqualByComparingTo("-0.750");
        assertThat(movimento.tipo()).isEqualTo(TipoMovimentoEstoque.SAIDA);
        assertThat(movimento.quantidade()).isEqualByComparingTo("0.750");
        assertThat(movimento.vendaId()).isEqualTo(vendaId);
        assertThat(movimento.motivo()).isNull();
        assertThat(movimento.id()).isNotNull();
        assertThat(movimento.criadoEm()).isNotNull();
    }

    @Test
    @DisplayName("o saldo pode ficar negativo: a venda já aconteceu, e o acerto é um ajuste de contagem")
    void saldoPodeFicarNegativo() {
        Produto produto = valido(Money.de("6.50"));

        produto.darBaixaPorVenda(new BigDecimal("2"), UUID.randomUUID());
        produto.darBaixaPorVenda(new BigDecimal("3"), UUID.randomUUID());

        assertThat(produto.getEstoqueAtual()).isEqualByComparingTo("-5");
    }

    @Test
    @DisplayName("serviço não baixa: não tem estoque")
    void servicoNaoBaixa() {
        Produto servico = new Produto("Corte", Money.de("40.00"), TipoProduto.SERVICO, null, null,
                null, null);

        assertThatIllegalStateException()
                .isThrownBy(() -> servico.darBaixaPorVenda(BigDecimal.ONE, UUID.randomUUID()))
                .withMessageContaining("servico");

        assertThat(servico.getEstoqueAtual()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("quantidade da baixa tem de ser positiva: zero e negativo são recusados")
    void baixaExigeQuantidadePositiva() {
        Produto produto = valido(Money.de("6.50"));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> produto.darBaixaPorVenda(BigDecimal.ZERO, UUID.randomUUID()))
                .withMessageContaining("positiva");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> produto.darBaixaPorVenda(new BigDecimal("-1"),
                        UUID.randomUUID()))
                .withMessageContaining("positiva");
        assertThatNullPointerException()
                .isThrownBy(() -> produto.darBaixaPorVenda(null, UUID.randomUUID()));
        assertThatNullPointerException()
                .isThrownBy(() -> produto.darBaixaPorVenda(BigDecimal.ONE, null));

        assertThat(produto.getEstoqueAtual())
                .as("nenhuma recusa pode ter movido o saldo")
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("produto inativado ainda baixa: a venda aconteceu antes da inativação")
    void produtoInativoAindaBaixa() {
        Produto produto = valido(Money.de("6.50"));
        produto.inativar();

        produto.darBaixaPorVenda(BigDecimal.ONE, UUID.randomUUID());

        assertThat(produto.getEstoqueAtual()).isEqualByComparingTo("-1");
    }

    @Test
    @DisplayName("o estorno sobe o saldo e devolve a ENTRADA que o explica, apontando para a venda (RF12)")
    void estornoSobeOSaldoEDevolveOMovimento() {
        Produto produto = valido(Money.de("6.50"));
        UUID vendaId = UUID.randomUUID();
        produto.darBaixaPorVenda(new BigDecimal("0.750"), vendaId);

        MovimentoEstoque movimento = produto.estornarPorCancelamento(new BigDecimal("0.750"),
                vendaId);

        assertThat(produto.getEstoqueAtual())
                .as("o estorno devolve exatamente o que a baixa tirou")
                .isEqualByComparingTo("0");
        assertThat(movimento.tipo()).isEqualTo(TipoMovimentoEstoque.ENTRADA);
        assertThat(movimento.quantidade())
                .as("a quantidade é positiva; quem diz que entra é o tipo")
                .isEqualByComparingTo("0.750");
        assertThat(movimento.vendaId()).isEqualTo(vendaId);
        assertThat(movimento.motivo()).isNull();
        assertThat(movimento.id()).isNotNull();
        assertThat(movimento.criadoEm()).isNotNull();
    }

    @Test
    @DisplayName("estorno recusa serviço e quantidade não positiva, e não olha se o produto está ativo")
    void estornoTemAsGuardasDaBaixa() {
        Produto servico = new Produto("Corte", Money.de("40.00"), TipoProduto.SERVICO, null, null,
                null, null);
        assertThatIllegalStateException()
                .isThrownBy(() -> servico.estornarPorCancelamento(BigDecimal.ONE,
                        UUID.randomUUID()))
                .withMessageContaining("servico");

        Produto produto = valido(Money.de("6.50"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> produto.estornarPorCancelamento(BigDecimal.ZERO,
                        UUID.randomUUID()))
                .withMessageContaining("positiva");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> produto.estornarPorCancelamento(new BigDecimal("-1"),
                        UUID.randomUUID()))
                .withMessageContaining("positiva");
        assertThatNullPointerException()
                .isThrownBy(() -> produto.estornarPorCancelamento(null, UUID.randomUUID()));
        assertThatNullPointerException()
                .isThrownBy(() -> produto.estornarPorCancelamento(BigDecimal.ONE, null));
        assertThat(produto.getEstoqueAtual())
                .as("nenhuma recusa pode ter movido o saldo")
                .isEqualByComparingTo("0");

        // O item saiu do catálogo depois da venda; o estoque que volta para a prateleira, volta.
        produto.inativar();
        produto.estornarPorCancelamento(BigDecimal.ONE, UUID.randomUUID());
        assertThat(produto.getEstoqueAtual()).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("o ajuste soma a diferença com sinal e devolve o AJUSTE que a explica, sem venda")
    void ajusteSomaADiferencaEDevolveOMovimento() {
        Produto produto = valido(Money.de("6.50"));

        MovimentoEstoque contagem = produto.ajustarEstoque(new BigDecimal("12.500"), "contagem");
        MovimentoEstoque perda = produto.ajustarEstoque(new BigDecimal("-2"), "quebra");

        assertThat(produto.getEstoqueAtual()).isEqualByComparingTo("10.500");
        assertThat(contagem.tipo()).isEqualTo(TipoMovimentoEstoque.AJUSTE);
        assertThat(contagem.quantidade()).isEqualByComparingTo("12.500");
        assertThat(contagem.motivo()).isEqualTo("contagem");
        assertThat(contagem.vendaId()).isNull();
        assertThat(perda.quantidade()).as("a diferença carrega o sinal").isEqualByComparingTo("-2");
        assertThat(perda.motivo()).isEqualTo("quebra");
    }

    @Test
    @DisplayName("o ajuste corrige o saldo negativo de uma venda que levou mais do que havia")
    void ajusteCorrigeSaldoNegativo() {
        Produto produto = valido(Money.de("6.50"));
        produto.darBaixaPorVenda(new BigDecimal("3"), UUID.randomUUID());

        produto.ajustarEstoque(new BigDecimal("8"), "contagem apos a venda");

        assertThat(produto.getEstoqueAtual()).isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("ajuste sem motivo é recusado, e a recusa não move o saldo (RF19)")
    void ajusteExigeMotivo() {
        Produto produto = valido(Money.de("6.50"));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> produto.ajustarEstoque(new BigDecimal("-1"), null))
                .withMessageContaining("motivo");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> produto.ajustarEstoque(new BigDecimal("-1"), "   "))
                .withMessageContaining("motivo");

        assertThat(produto.getEstoqueAtual()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("ajuste de zero é recusado: nada entrou nem saiu")
    void ajusteRecusaZeroENulo() {
        Produto produto = valido(Money.de("6.50"));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> produto.ajustarEstoque(BigDecimal.ZERO, "contagem"))
                .withMessageContaining("zero");
        assertThatNullPointerException()
                .isThrownBy(() -> produto.ajustarEstoque(null, "contagem"));

        assertThat(produto.getEstoqueAtual()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("serviço e produto inativo não têm estoque ajustado")
    void ajusteRecusaServicoEInativo() {
        Produto servico = new Produto("Corte", Money.de("40.00"), TipoProduto.SERVICO, null, null,
                null, null);
        Produto inativo = valido(Money.de("6.50"));
        inativo.inativar();

        assertThatIllegalStateException()
                .isThrownBy(() -> servico.ajustarEstoque(BigDecimal.ONE, "contagem"))
                .withMessageContaining("servico");
        assertThatIllegalStateException()
                .isThrownBy(() -> inativo.ajustarEstoque(BigDecimal.ONE, "contagem"))
                .withMessageContaining("inativo");

        assertThat(servico.getEstoqueAtual()).isEqualByComparingTo("0");
        assertThat(inativo.getEstoqueAtual()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("estoque mínimo nasce em zero e aceita valor com até três casas")
    void estoqueMinimoNasceEmZero() {
        Produto produto = valido(Money.de("6.50"));
        assertThat(produto.getEstoqueMinimo()).isEqualByComparingTo("0");

        produto.definirEstoqueMinimo(new BigDecimal("1.500"));

        assertThat(produto.getEstoqueMinimo()).isEqualByComparingTo("1.500");
    }

    @Test
    @DisplayName("estoque mínimo recusa negativo, quarta casa, nulo, serviço e inativo")
    void estoqueMinimoRecusaOQueNaoELimiar() {
        Produto produto = valido(Money.de("6.50"));
        Produto servico = new Produto("Corte", Money.de("40.00"), TipoProduto.SERVICO, null, null,
                null, null);
        Produto inativo = valido(Money.de("6.50"));
        inativo.inativar();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> produto.definirEstoqueMinimo(new BigDecimal("-1")))
                .withMessageContaining("negativo");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> produto.definirEstoqueMinimo(new BigDecimal("1.0005")))
                .withMessageContaining("casas decimais");
        assertThatNullPointerException()
                .isThrownBy(() -> produto.definirEstoqueMinimo(null));
        assertThatIllegalStateException()
                .isThrownBy(() -> servico.definirEstoqueMinimo(BigDecimal.ONE))
                .withMessageContaining("servico");
        assertThatIllegalStateException()
                .isThrownBy(() -> inativo.definirEstoqueMinimo(BigDecimal.ONE))
                .withMessageContaining("inativo");

        assertThat(produto.getEstoqueMinimo()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("está baixo quando o saldo é igual ou menor que o mínimo; com mínimo zero, quando acabou")
    void estaComEstoqueBaixoNoLimiarOuAbaixo() {
        Produto novo = valido(Money.de("6.50"));
        assertThat(novo.estaComEstoqueBaixo())
                .as("mínimo zero e saldo zero: acabou, e acabar é baixo")
                .isTrue();

        novo.ajustarEstoque(new BigDecimal("0.001"), "contagem");
        assertThat(novo.estaComEstoqueBaixo()).as("qualquer saldo acima de zero").isFalse();

        Produto negativo = valido(Money.de("6.50"));
        negativo.darBaixaPorVenda(new BigDecimal("3"), UUID.randomUUID());
        assertThat(negativo.estaComEstoqueBaixo()).as("saldo negativo com mínimo zero").isTrue();

        Produto comMinimo = valido(Money.de("6.50"));
        comMinimo.ajustarEstoque(new BigDecimal("10"), "contagem");
        comMinimo.definirEstoqueMinimo(new BigDecimal("5"));
        assertThat(comMinimo.estaComEstoqueBaixo()).as("10 acima de 5").isFalse();

        comMinimo.ajustarEstoque(new BigDecimal("-5"), "perda");
        assertThat(comMinimo.estaComEstoqueBaixo()).as("5 igual a 5: no limiar").isTrue();

        comMinimo.ajustarEstoque(new BigDecimal("-1"), "perda");
        assertThat(comMinimo.estaComEstoqueBaixo()).as("4 abaixo de 5").isTrue();
    }

    @Test
    @DisplayName("serviço nunca está com estoque baixo, mesmo com saldo zero")
    void servicoNuncaEstaBaixo() {
        Produto servico = new Produto("Corte", Money.de("40.00"), TipoProduto.SERVICO, null, null,
                null, null);

        assertThat(servico.estaComEstoqueBaixo()).isFalse();
    }
}
