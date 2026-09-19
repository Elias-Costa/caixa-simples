package br.com.caixasimples.cadastro.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import br.com.caixasimples.cadastro.TipoMovimentoEstoque;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * As guardas do membro do agregado Produto. Teste de unidade puro, sem Spring e sem banco.
 *
 * <p>O que este arquivo <strong>não</strong> cobre, de propósito: a baixa em si, que é da raiz e
 * está em {@code ProdutoTest}, e a gravação junto do saldo, que é integração.
 */
class MovimentoEstoqueTest {

    private static MovimentoEstoque movimento(TipoMovimentoEstoque tipo, String quantidade,
            String motivo) {
        return new MovimentoEstoque(UUID.randomUUID(), tipo, new BigDecimal(quantidade), motivo,
                null, Instant.now());
    }

    @Test
    @DisplayName("saída e entrada aceitam quantidade positiva e fracionada, e motivo é opcional")
    void aceitaOsLimitesValidos() {
        MovimentoEstoque saida = movimento(TipoMovimentoEstoque.SAIDA, "0.750", null);
        MovimentoEstoque entrada = movimento(TipoMovimentoEstoque.ENTRADA, "2.000", "  ");

        assertThat(saida.quantidade()).isEqualByComparingTo("0.750");
        assertThat(saida.motivo()).isNull();
        assertThat(entrada.motivo()).as("texto em branco vira ausência").isNull();
    }

    @Test
    @DisplayName("quantidade zero é recusada em qualquer tipo: nada entrou nem saiu")
    void recusaQuantidadeZero() {
        for (TipoMovimentoEstoque tipo : TipoMovimentoEstoque.values()) {
            assertThatIllegalArgumentException()
                    .as("tipo " + tipo)
                    .isThrownBy(() -> movimento(tipo, "0", "contagem"))
                    .withMessageContaining("zero");
        }
    }

    @Test
    @DisplayName("saída e entrada recusam quantidade negativa: o sinal é do tipo")
    void recusaNegativoForaDeAjuste() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> movimento(TipoMovimentoEstoque.SAIDA, "-1", null))
                .withMessageContaining("negativa");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> movimento(TipoMovimentoEstoque.ENTRADA, "-1", null))
                .withMessageContaining("negativa");
    }

    @Test
    @DisplayName("ajuste aceita quantidade negativa: a convenção de sinal dele ainda não foi decidida")
    void ajusteNaoPrendeOSinal() {
        assertThat(movimento(TipoMovimentoEstoque.AJUSTE, "-3", "quebra").quantidade())
                .isEqualByComparingTo("-3");
        assertThat(movimento(TipoMovimentoEstoque.AJUSTE, "3", "contagem").quantidade())
                .isEqualByComparingTo("3");
    }

    @Test
    @DisplayName("quarta casa decimal é recusada, mas zero à direita não conta")
    void recusaQuartaCasa() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> movimento(TipoMovimentoEstoque.SAIDA, "0.7505", null))
                .withMessageContaining("casas decimais");
        assertThat(movimento(TipoMovimentoEstoque.SAIDA, "2.0000", null).quantidade())
                .isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("id, tipo, quantidade e instante são obrigatórios")
    void recusaNulos() {
        Instant agora = Instant.now();
        assertThatNullPointerException().isThrownBy(() -> new MovimentoEstoque(null,
                TipoMovimentoEstoque.SAIDA, BigDecimal.ONE, null, null, agora));
        assertThatNullPointerException().isThrownBy(() -> new MovimentoEstoque(UUID.randomUUID(),
                null, BigDecimal.ONE, null, null, agora));
        assertThatNullPointerException().isThrownBy(() -> new MovimentoEstoque(UUID.randomUUID(),
                TipoMovimentoEstoque.SAIDA, null, null, null, agora));
        assertThatNullPointerException().isThrownBy(() -> new MovimentoEstoque(UUID.randomUUID(),
                TipoMovimentoEstoque.SAIDA, BigDecimal.ONE, null, null, null));
    }
}
