package br.com.caixasimples.caixa.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNoException;

import br.com.caixasimples.caixa.StatusSessaoCaixa;
import br.com.caixasimples.caixa.TipoMovimentoCaixa;
import br.com.caixasimples.shared.Money;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A invariante do agregado Caixa (modelo de dados §4):
 * {@code valorFechamentoEsperado = valorAbertura + soma assinada dos movimentos}, mais as quatro
 * regras que o R07 trouxe (D22).
 *
 * <p>Teste de unidade puro, sem contexto Spring e sem banco — {@link SessaoCaixa} nao conhece
 * framework nenhum, e a conta que ele faz nao depende de persistencia para estar certa.
 *
 * <p>O que este arquivo <strong>nao</strong> cobre, porque ainda nao existe: fechamento com
 * diferenca (RF15, R08) e a regra de uma sessao aberta por operador (D22a), que nao mora na raiz —
 * uma sessao nao enxerga as outras, entao ela e provada em {@code SessaoCaixaServiceTest}.
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
    @DisplayName("abrir com zero vale; com valor negativo, nao")
    void aberturaAceitaZeroERecusaNegativo() {
        // D22b — abrir a gaveta sem troco e situacao real: primeiro dia, ou caixa que so recebe
        // por Pix. Ja um valor negativo envenenaria o esperado desde a primeira linha.
        assertThatNoException()
                .isThrownBy(() -> new SessaoCaixa(OPERADOR, Money.ZERO));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SessaoCaixa(OPERADOR, Money.de("-1.00")));
    }

    @Test
    @DisplayName("venda e suprimento somam, sangria subtrai — o sinal vem do tipo, nunca do valor")
    void esperadoAcompanhaOSinalDoTipo() {
        SessaoCaixa sessao = new SessaoCaixa(OPERADOR, Money.de("100.00"));

        sessao.registrarVenda(Money.de("25.00"), UUID.randomUUID());
        assertThat(sessao.getValorFechamentoEsperado()).isEqualTo(Money.de("125.00"));

        sessao.suprir(Money.de("50.00"), "Reforco de troco");
        assertThat(sessao.getValorFechamentoEsperado()).isEqualTo(Money.de("175.00"));

        // D21b — a sangria e lancada com 30 positivo; quem sabe que ela sai e o tipo.
        sessao.sangrar(Money.de("30.00"), "Pagamento do entregador");
        assertThat(sessao.getValorFechamentoEsperado()).isEqualTo(Money.de("145.00"));

        assertThat(sessao.getMovimentos()).hasSize(3);
        assertThat(sessao.getMovimentos().get(2).valor())
                .as("o valor gravado e o que o operador digitou, sem sinal embutido")
                .isEqualTo(Money.de("30.00"));
    }

    @Test
    @DisplayName("sangria e suprimento sem motivo sao recusados (RF14)")
    void motivoEObrigatorioEmSangriaESuprimento() {
        SessaoCaixa sessao = new SessaoCaixa(OPERADOR, Money.de("100.00"));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> sessao.sangrar(Money.de("10.00"), null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> sessao.suprir(Money.de("10.00"), null));

        // Em branco e ausencia, e nao um motivo vazio que satisfaria a regra na letra.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> sessao.sangrar(Money.de("10.00"), "   "));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> sessao.suprir(Money.de("10.00"), "   "));

        assertThat(sessao.getMovimentos())
                .as("lancamento recusado nao deixa rastro no agregado")
                .isEmpty();
        assertThat(sessao.getValorFechamentoEsperado()).isEqualTo(Money.de("100.00"));
    }

    @Test
    @DisplayName("nao se retira da gaveta mais do que deveria haver nela")
    void sangriaMaiorQueOEsperadoERecusada() {
        SessaoCaixa sessao = new SessaoCaixa(OPERADOR, Money.de("100.00"));

        // D22c — 3000 em vez de 30,00 e o erro de digitacao que esta regra pega no instante em que
        // acontece, em vez de no fechamento.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> sessao.sangrar(Money.de("100.01"), "Deposito bancario"));

        assertThat(sessao.getMovimentos()).isEmpty();
        assertThat(sessao.getValorFechamentoEsperado()).isEqualTo(Money.de("100.00"));
    }

    @Test
    @DisplayName("sangria igual ao esperado vale: zerar a gaveta e legitimo")
    void sangriaIgualAoEsperadoEhAceita() {
        SessaoCaixa sessao = new SessaoCaixa(OPERADOR, Money.de("100.00"));

        sessao.sangrar(Money.de("100.00"), "Recolhimento do fim do expediente");

        assertThat(sessao.getValorFechamentoEsperado()).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("sessao FECHADA nao aceita movimento nenhum")
    void sessaoFechadaNaoAceitaMovimento() {
        // D22d. Como fechar() so nasce no R08, a unica forma de ter uma sessao FECHADA em maos
        // agora e remonta-la — que e o que o banco faz ao ler uma sessao de ontem.
        SessaoCaixa fechada = SessaoCaixa.reconstituir(UUID.randomUUID(), OPERADOR,
                Money.de("100.00"), Money.de("100.00"), Money.de("100.00"), Money.ZERO,
                Instant.now(), Instant.now(), StatusSessaoCaixa.FECHADA, List.of());

        assertThatIllegalStateException()
                .isThrownBy(() -> fechada.sangrar(Money.de("10.00"), "Almoco"));
        assertThatIllegalStateException()
                .isThrownBy(() -> fechada.suprir(Money.de("10.00"), "Troco"));
        assertThatIllegalStateException()
                .isThrownBy(() -> fechada.registrarVenda(Money.de("10.00"), UUID.randomUUID()));

        assertThat(fechada.getMovimentos()).isEmpty();
        assertThat(fechada.getValorFechamentoEsperado())
                .as("o esperado conferido no fechamento nao pode mudar depois dele")
                .isEqualTo(Money.de("100.00"));
    }

    @Test
    @DisplayName("movimento com valor negativo e recusado: o sinal ja esta no tipo")
    void recusaValorNegativo() {
        SessaoCaixa sessao = new SessaoCaixa(OPERADOR, Money.de("100.00"));

        // Uma sangria de -30 subtrairia duas vezes: uma pelo sinal, outra pelo tipo.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> sessao.sangrar(Money.de("-30.00"), "Sinal duplicado"));

        assertThat(sessao.getValorFechamentoEsperado())
                .as("movimento recusado nao pode ter mexido no esperado")
                .isEqualTo(Money.de("100.00"));
        assertThat(sessao.getMovimentos()).isEmpty();
    }

    @Test
    @DisplayName("venda nasce sem motivo, e movimento com motivo em branco guarda ausencia")
    void motivoEmBrancoViraNulo() {
        SessaoCaixa sessao = new SessaoCaixa(OPERADOR, Money.ZERO);

        sessao.registrarVenda(Money.de("10.00"), UUID.randomUUID());
        assertThat(sessao.getMovimentos().get(0).motivo())
                .as("RF14 exige motivo de sangria e suprimento, nao de venda")
                .isNull();

        // A normalizacao do proprio membro do agregado continua valendo, e vale sozinha: pela raiz
        // ninguem mais consegue passar um motivo em branco desde que sangrar e suprir o exigem.
        MovimentoCaixa comMotivoEmBranco = new MovimentoCaixa(UUID.randomUUID(),
                TipoMovimentoCaixa.VENDA, Money.de("10.00"), "   ", UUID.randomUUID(),
                Instant.now());
        assertThat(comMotivoEmBranco.motivo()).isNull();
    }

    @Test
    @DisplayName("a lista de movimentos so se altera pela raiz")
    void movimentosNaoSaoAlteraveisPorFora() {
        SessaoCaixa sessao = new SessaoCaixa(OPERADOR, Money.de("100.00"));
        sessao.sangrar(Money.de("10.00"), "Almoco");

        assertThat(sessao.getMovimentos()).isUnmodifiable();
    }
}
