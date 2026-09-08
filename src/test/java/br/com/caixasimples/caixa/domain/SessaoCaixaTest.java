package br.com.caixasimples.caixa.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import br.com.caixasimples.caixa.StatusSessaoCaixa;
import br.com.caixasimples.caixa.TipoMovimentoCaixa;
import br.com.caixasimples.shared.Money;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A invariante do agregado Caixa (modelo de dados §4):
 * {@code valorFechamentoEsperado = valorAbertura + soma assinada dos movimentos}.
 *
 * <p>Teste de unidade puro, sem contexto Spring e sem banco — {@link SessaoCaixa} nao conhece
 * framework nenhum, e a conta que ele faz nao depende de persistencia para estar certa.
 *
 * <p>O que este arquivo <strong>nao</strong> cobre, porque ainda nao existe: motivo obrigatorio em
 * sangria (RF14, R07), recusa de movimento em sessao fechada e fechamento com diferenca (RF15,
 * R08). No R06 o {@code registrar} e cru de proposito.
 */
class SessaoCaixaTest {

    private static final UUID OPERADOR = UUID.randomUUID();

    @Test
    @DisplayName("sessao nasce ABERTA e ja com o esperado igual ao que foi posto na gaveta")
    void abreComEsperadoIgualAAbertura() {
        SessaoCaixa sessao = new SessaoCaixa(OPERADOR, Money.de("150.00"));

        assertThat(sessao.getStatus()).isEqualTo(StatusSessaoCaixa.ABERTA);
        assertThat(sessao.getValorAbertura()).isEqualTo(Money.de("150.00"));
        assertThat(sessao.getValorFechamentoEsperado())
                .as("D21a — o esperado e coluna viva, nao calculo do fechamento")
                .isEqualTo(Money.de("150.00"));
        assertThat(sessao.getMovimentos()).isEmpty();

        // As tres so nascem no fechamento, e nascem juntas.
        assertThat(sessao.getValorFechamentoContado()).isNull();
        assertThat(sessao.getDiferenca()).isNull();
        assertThat(sessao.getFechadaEm()).isNull();
    }

    @Test
    @DisplayName("venda e suprimento somam, sangria subtrai — o sinal vem do tipo, nunca do valor")
    void esperadoAcompanhaOSinalDoTipo() {
        SessaoCaixa sessao = new SessaoCaixa(OPERADOR, Money.de("100.00"));

        sessao.registrar(TipoMovimentoCaixa.VENDA, Money.de("25.00"), null, UUID.randomUUID());
        assertThat(sessao.getValorFechamentoEsperado()).isEqualTo(Money.de("125.00"));

        sessao.registrar(TipoMovimentoCaixa.SUPRIMENTO, Money.de("50.00"), "Reforco de troco", null);
        assertThat(sessao.getValorFechamentoEsperado()).isEqualTo(Money.de("175.00"));

        // D21b — a sangria e lancada com 30 positivo; quem sabe que ela sai e o tipo.
        sessao.registrar(TipoMovimentoCaixa.SANGRIA, Money.de("30.00"), "Pagamento do entregador",
                null);
        assertThat(sessao.getValorFechamentoEsperado()).isEqualTo(Money.de("145.00"));

        assertThat(sessao.getMovimentos()).hasSize(3);
        assertThat(sessao.getMovimentos().get(2).valor())
                .as("o valor gravado e o que o operador digitou, sem sinal embutido")
                .isEqualTo(Money.de("30.00"));
    }

    @Test
    @DisplayName("movimento com valor negativo e recusado: o sinal ja esta no tipo")
    void recusaValorNegativo() {
        SessaoCaixa sessao = new SessaoCaixa(OPERADOR, Money.de("100.00"));

        // Uma sangria de -30 subtrairia duas vezes: uma pelo sinal, outra pelo tipo.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> sessao.registrar(TipoMovimentoCaixa.SANGRIA, Money.de("-30.00"),
                        "Sinal duplicado", null));

        assertThat(sessao.getValorFechamentoEsperado())
                .as("movimento recusado nao pode ter mexido no esperado")
                .isEqualTo(Money.de("100.00"));
        assertThat(sessao.getMovimentos()).isEmpty();
    }

    @Test
    @DisplayName("motivo em branco vira ausencia, para sem motivo ter uma representacao so")
    void motivoEmBrancoViraNulo() {
        SessaoCaixa sessao = new SessaoCaixa(OPERADOR, Money.de("0.00"));

        sessao.registrar(TipoMovimentoCaixa.VENDA, Money.de("10.00"), "   ", UUID.randomUUID());

        assertThat(sessao.getMovimentos().get(0).motivo()).isNull();
    }

    @Test
    @DisplayName("a lista de movimentos so se altera pela raiz")
    void movimentosNaoSaoAlteraveisPorFora() {
        SessaoCaixa sessao = new SessaoCaixa(OPERADOR, Money.de("100.00"));
        sessao.registrar(TipoMovimentoCaixa.SANGRIA, Money.de("10.00"), "Almoco", null);

        assertThat(sessao.getMovimentos()).isUnmodifiable();
    }
}
