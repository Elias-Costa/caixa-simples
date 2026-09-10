package br.com.caixasimples.caixa.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNoException;

import br.com.caixasimples.caixa.StatusSessaoCaixa;
import br.com.caixasimples.caixa.TipoMovimentoCaixa;
import br.com.caixasimples.shared.Money;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A invariante do agregado Caixa,
 * {@code valorFechamentoEsperado = valorAbertura + soma assinada dos movimentos}, mais as regras de
 * lançamento e de fechamento que a raiz protege.
 *
 * <p>Teste de unidade puro, sem contexto Spring e sem banco, porque {@link SessaoCaixa} não conhece
 * framework nenhum e a conta que ele faz não depende de persistência para estar certa.
 *
 * <p>O que este arquivo <strong>não</strong> cobre, de propósito: a regra de uma sessão aberta por
 * operador e a delimitação do dia no histórico. Nenhuma das duas mora na raiz, já que uma sessão
 * não enxerga as outras nem delimita dia, então as duas são provadas em
 * {@code SessaoCaixaServiceTest}, contra o banco.
 */
class SessaoCaixaTest {

    private static final UUID OPERADOR = UUID.randomUUID();

    @Test
    @DisplayName("sessão nasce ABERTA e já com o esperado igual ao que foi posto na gaveta")
    void abreComEsperadoIgualAAbertura() {
        SessaoCaixa sessao = new SessaoCaixa(OPERADOR, Money.de("150.00"));

        assertThat(sessao.getStatus()).isEqualTo(StatusSessaoCaixa.ABERTA);
        assertThat(sessao.getValorAbertura()).isEqualTo(Money.de("150.00"));
        assertThat(sessao.getValorFechamentoEsperado())
                .as("o esperado é coluna viva, não cálculo do fechamento")
                .isEqualTo(Money.de("150.00"));
        assertThat(sessao.getMovimentos()).isEmpty();

        // As três só nascem no fechamento, e nascem juntas.
        assertThat(sessao.getValorFechamentoContado()).isNull();
        assertThat(sessao.getDiferenca()).isNull();
        assertThat(sessao.getFechadaEm()).isNull();
    }

    @Test
    @DisplayName("abrir com zero vale; com valor negativo, não")
    void aberturaAceitaZeroERecusaNegativo() {
        // Abrir a gaveta sem troco é situação real: primeiro dia, ou caixa que só recebe por Pix.
        // Já um valor negativo envenenaria o esperado desde a primeira linha.
        assertThatNoException()
                .isThrownBy(() -> new SessaoCaixa(OPERADOR, Money.ZERO));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SessaoCaixa(OPERADOR, Money.de("-1.00")));
    }

    @Test
    @DisplayName("venda e suprimento somam, sangria subtrai: o sinal vem do tipo, nunca do valor")
    void esperadoAcompanhaOSinalDoTipo() {
        SessaoCaixa sessao = new SessaoCaixa(OPERADOR, Money.de("100.00"));

        sessao.registrarVenda(Money.de("25.00"), UUID.randomUUID());
        assertThat(sessao.getValorFechamentoEsperado()).isEqualTo(Money.de("125.00"));

        sessao.suprir(Money.de("50.00"), "Reforco de troco");
        assertThat(sessao.getValorFechamentoEsperado()).isEqualTo(Money.de("175.00"));

        // A sangria é lançada com 30 positivo; quem sabe que ela sai é o tipo do movimento.
        sessao.sangrar(Money.de("30.00"), "Pagamento do entregador");
        assertThat(sessao.getValorFechamentoEsperado()).isEqualTo(Money.de("145.00"));

        assertThat(sessao.getMovimentos()).hasSize(3);
        assertThat(sessao.getMovimentos().get(2).valor())
                .as("o valor gravado é o que o operador digitou, sem sinal embutido")
                .isEqualTo(Money.de("30.00"));
    }

    @Test
    @DisplayName("sangria e suprimento sem motivo são recusados (RF14)")
    void motivoEObrigatorioEmSangriaESuprimento() {
        SessaoCaixa sessao = new SessaoCaixa(OPERADOR, Money.de("100.00"));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> sessao.sangrar(Money.de("10.00"), null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> sessao.suprir(Money.de("10.00"), null));

        // Em branco é ausência, e não um motivo vazio que satisfaria a regra na letra.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> sessao.sangrar(Money.de("10.00"), "   "));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> sessao.suprir(Money.de("10.00"), "   "));

        assertThat(sessao.getMovimentos())
                .as("lançamento recusado não deixa rastro no agregado")
                .isEmpty();
        assertThat(sessao.getValorFechamentoEsperado()).isEqualTo(Money.de("100.00"));
    }

    @Test
    @DisplayName("não se retira da gaveta mais do que deveria haver nela")
    void sangriaMaiorQueOEsperadoERecusada() {
        SessaoCaixa sessao = new SessaoCaixa(OPERADOR, Money.de("100.00"));

        // Digitar 3000 em vez de 30,00 é o erro que esta regra pega no instante em que acontece,
        // em vez de só no fechamento.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> sessao.sangrar(Money.de("100.01"), "Deposito bancario"));

        assertThat(sessao.getMovimentos()).isEmpty();
        assertThat(sessao.getValorFechamentoEsperado()).isEqualTo(Money.de("100.00"));
    }

    @Test
    @DisplayName("sangria igual ao esperado vale: zerar a gaveta é legítimo")
    void sangriaIgualAoEsperadoEhAceita() {
        SessaoCaixa sessao = new SessaoCaixa(OPERADOR, Money.de("100.00"));

        sessao.sangrar(Money.de("100.00"), "Recolhimento do fim do expediente");

        assertThat(sessao.getValorFechamentoEsperado()).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("sessão FECHADA não aceita movimento nenhum")
    void sessaoFechadaNaoAceitaMovimento() {
        SessaoCaixa fechada = new SessaoCaixa(OPERADOR, Money.de("100.00"));
        fechada.fechar(Money.de("100.00"));

        assertThatIllegalStateException()
                .isThrownBy(() -> fechada.sangrar(Money.de("10.00"), "Almoco"));
        assertThatIllegalStateException()
                .isThrownBy(() -> fechada.suprir(Money.de("10.00"), "Troco"));
        assertThatIllegalStateException()
                .isThrownBy(() -> fechada.registrarVenda(Money.de("10.00"), UUID.randomUUID()));

        assertThat(fechada.getMovimentos()).isEmpty();
        assertThat(fechada.getValorFechamentoEsperado())
                .as("o esperado conferido no fechamento não pode mudar depois dele")
                .isEqualTo(Money.de("100.00"));
    }

    @Test
    @DisplayName("movimento com valor negativo é recusado: o sinal já está no tipo")
    void recusaValorNegativo() {
        SessaoCaixa sessao = new SessaoCaixa(OPERADOR, Money.de("100.00"));

        // Uma sangria de -30 subtrairia duas vezes: uma pelo sinal, outra pelo tipo.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> sessao.sangrar(Money.de("-30.00"), "Sinal duplicado"));

        assertThat(sessao.getValorFechamentoEsperado())
                .as("movimento recusado não pode ter mexido no esperado")
                .isEqualTo(Money.de("100.00"));
        assertThat(sessao.getMovimentos()).isEmpty();
    }

    @Test
    @DisplayName("venda nasce sem motivo, e movimento com motivo em branco guarda ausência")
    void motivoEmBrancoViraNulo() {
        SessaoCaixa sessao = new SessaoCaixa(OPERADOR, Money.ZERO);

        sessao.registrarVenda(Money.de("10.00"), UUID.randomUUID());
        assertThat(sessao.getMovimentos().get(0).motivo())
                .as("o RF14 exige motivo de sangria e suprimento, não de venda")
                .isNull();

        // A normalização do próprio membro do agregado continua valendo, e vale sozinha: pela raiz
        // ninguém mais consegue passar um motivo em branco, já que sangrar e suprir o exigem.
        MovimentoCaixa comMotivoEmBranco = new MovimentoCaixa(UUID.randomUUID(),
                TipoMovimentoCaixa.VENDA, Money.de("10.00"), "   ", UUID.randomUUID(),
                Instant.now());
        assertThat(comMotivoEmBranco.motivo()).isNull();
    }

    @Test
    @DisplayName("a lista de movimentos só se altera pela raiz")
    void movimentosNaoSaoAlteraveisPorFora() {
        SessaoCaixa sessao = new SessaoCaixa(OPERADOR, Money.de("100.00"));
        sessao.sangrar(Money.de("10.00"), "Almoco");

        assertThat(sessao.getMovimentos()).isUnmodifiable();
    }

    @Test
    @DisplayName("fechar apura a diferença sobre o esperado de um expediente inteiro (RF15)")
    void fechamentoApuraADiferenca() {
        SessaoCaixa sessao = new SessaoCaixa(OPERADOR, Money.de("100.00"));
        sessao.suprir(Money.de("50.00"), "Reforco de troco");
        sessao.sangrar(Money.de("30.00"), "Pagamento do entregador");

        // 100 mais 50 menos 30 dá 120 na gaveta; o operador contou 118.
        Money diferenca = sessao.fechar(Money.de("118.00"));

        assertThat(diferenca)
                .as("esperado menos contado: positivo é o que faltou na gaveta")
                .isEqualTo(Money.de("2.00"));
        assertThat(sessao.getDiferenca()).isEqualTo(Money.de("2.00"));
        assertThat(sessao.getValorFechamentoContado()).isEqualTo(Money.de("118.00"));
        assertThat(sessao.getStatus()).isEqualTo(StatusSessaoCaixa.FECHADA);
        assertThat(sessao.getFechadaEm()).isNotNull();

        assertThat(sessao.getValorFechamentoEsperado())
                .as("o esperado é a base da conferência e não pode ser tocado pelo fechamento")
                .isEqualTo(Money.de("120.00"));
    }

    @Test
    @DisplayName("sobra na gaveta dá diferença negativa, e caixa que bate dá zero")
    void diferencaCarregaOSinalDaSobra() {
        SessaoCaixa sobrando = new SessaoCaixa(OPERADOR, Money.de("100.00"));
        SessaoCaixa batendo = new SessaoCaixa(OPERADOR, Money.de("100.00"));

        // O sinal é esperado menos contado. Contar mais do que devia dá negativo, e a intuição lê
        // isso ao contrário com facilidade, que é por isso que este teste existe.
        assertThat(sobrando.fechar(Money.de("103.50"))).isEqualTo(Money.de("-3.50"));
        assertThat(batendo.fechar(Money.de("100.00"))).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("contar zero na gaveta vale; contar valor negativo, não")
    void fechamentoAceitaZeroERecusaNegativo() {
        SessaoCaixa gavetaVazia = new SessaoCaixa(OPERADOR, Money.de("100.00"));
        SessaoCaixa outra = new SessaoCaixa(OPERADOR, Money.de("100.00"));

        // Mesmo desenho da abertura: gaveta vazia se conta como zero, porque o dia pode ter sido só
        // de Pix, ou tudo pode ter saído em sangria.
        assertThatNoException().isThrownBy(() -> gavetaVazia.fechar(Money.ZERO));
        assertThat(gavetaVazia.getDiferenca()).isEqualTo(Money.de("100.00"));

        assertThatIllegalArgumentException().isThrownBy(() -> outra.fechar(Money.de("-1.00")));
        assertThat(outra.getStatus())
                .as("um fechamento recusado não pode deixar a sessão pela metade")
                .isEqualTo(StatusSessaoCaixa.ABERTA);
        assertThat(outra.getDiferenca()).isNull();
        assertThat(outra.getFechadaEm()).isNull();
    }

    @Test
    @DisplayName("sessão FECHADA não fecha de novo: a conferência não é rascunho")
    void naoSeFechaDuasVezes() {
        SessaoCaixa sessao = new SessaoCaixa(OPERADOR, Money.de("100.00"));
        sessao.fechar(Money.de("90.00"));
        Instant primeiroFechamento = sessao.getFechadaEm();

        // Complemento da regra anterior: aquela protege a diferença de um movimento posterior, esta
        // a protege de um segundo fechamento que a reescreveria por cima.
        assertThatIllegalStateException().isThrownBy(() -> sessao.fechar(Money.de("100.00")));

        assertThat(sessao.getDiferenca()).isEqualTo(Money.de("10.00"));
        assertThat(sessao.getValorFechamentoContado()).isEqualTo(Money.de("90.00"));
        assertThat(sessao.getFechadaEm()).isEqualTo(primeiroFechamento);
    }
}
