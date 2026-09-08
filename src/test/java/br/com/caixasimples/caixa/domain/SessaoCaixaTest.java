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
 * A invariante do agregado Caixa (modelo de dados §4):
 * {@code valorFechamentoEsperado = valorAbertura + soma assinada dos movimentos}, mais as regras de
 * lancamento do R07 (D22) e as de fechamento do R08 (D23d, D23e).
 *
 * <p>Teste de unidade puro, sem contexto Spring e sem banco — {@link SessaoCaixa} nao conhece
 * framework nenhum, e a conta que ele faz nao depende de persistencia para estar certa.
 *
 * <p>O que este arquivo <strong>nao</strong> cobre, de proposito: a regra de uma sessao aberta por
 * operador (D22a) e o dia do historico (D23a). Nenhuma das duas mora na raiz — uma sessao nao
 * enxerga as outras nem delimita dia —, entao as duas sao provadas em
 * {@code SessaoCaixaServiceTest}, contra o banco.
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
        // D22d. Agora que fechar() existe (R08), a sessao chega ao estado FECHADA pelo caminho de
        // verdade — antes o teste precisava remonta-la, que era contorno anotado como tal.
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

    @Test
    @DisplayName("fechar apura a diferenca sobre o esperado de um expediente inteiro (RF15)")
    void fechamentoApuraADiferenca() {
        SessaoCaixa sessao = new SessaoCaixa(OPERADOR, Money.de("100.00"));
        sessao.suprir(Money.de("50.00"), "Reforco de troco");
        sessao.sangrar(Money.de("30.00"), "Pagamento do entregador");

        // 100 + 50 - 30 = 120 na gaveta; o operador contou 118.
        Money diferenca = sessao.fechar(Money.de("118.00"));

        assertThat(diferenca)
                .as("esperado menos contado: positivo e o que faltou na gaveta")
                .isEqualTo(Money.de("2.00"));
        assertThat(sessao.getDiferenca()).isEqualTo(Money.de("2.00"));
        assertThat(sessao.getValorFechamentoContado()).isEqualTo(Money.de("118.00"));
        assertThat(sessao.getStatus()).isEqualTo(StatusSessaoCaixa.FECHADA);
        assertThat(sessao.getFechadaEm()).isNotNull();

        assertThat(sessao.getValorFechamentoEsperado())
                .as("o esperado e a base da conferencia e nao pode ser tocado pelo fechamento")
                .isEqualTo(Money.de("120.00"));
    }

    @Test
    @DisplayName("sobra na gaveta da diferenca negativa, e caixa que bate da zero")
    void diferencaCarregaOSinalDaSobra() {
        SessaoCaixa sobrando = new SessaoCaixa(OPERADOR, Money.de("100.00"));
        SessaoCaixa batendo = new SessaoCaixa(OPERADOR, Money.de("100.00"));

        // O sinal e o do dicionario de dados: esperado - contado. Contar mais do que devia da
        // negativo, e a intuicao le isso ao contrario com facilidade — por isso o teste existe.
        assertThat(sobrando.fechar(Money.de("103.50"))).isEqualTo(Money.de("-3.50"));
        assertThat(batendo.fechar(Money.de("100.00"))).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("contar zero na gaveta vale; contar valor negativo, nao")
    void fechamentoAceitaZeroERecusaNegativo() {
        SessaoCaixa gavetaVazia = new SessaoCaixa(OPERADOR, Money.de("100.00"));
        SessaoCaixa outra = new SessaoCaixa(OPERADOR, Money.de("100.00"));

        // D23d, mesmo desenho da D22b na abertura: gaveta vazia se conta como zero — o dia pode ter
        // sido so de Pix, ou tudo pode ter saido em sangria.
        assertThatNoException().isThrownBy(() -> gavetaVazia.fechar(Money.ZERO));
        assertThat(gavetaVazia.getDiferenca()).isEqualTo(Money.de("100.00"));

        assertThatIllegalArgumentException().isThrownBy(() -> outra.fechar(Money.de("-1.00")));
        assertThat(outra.getStatus())
                .as("um fechamento recusado nao pode deixar a sessao pela metade")
                .isEqualTo(StatusSessaoCaixa.ABERTA);
        assertThat(outra.getDiferenca()).isNull();
        assertThat(outra.getFechadaEm()).isNull();
    }

    @Test
    @DisplayName("sessao FECHADA nao fecha de novo: a conferencia nao e rascunho")
    void naoSeFechaDuasVezes() {
        SessaoCaixa sessao = new SessaoCaixa(OPERADOR, Money.de("100.00"));
        sessao.fechar(Money.de("90.00"));
        Instant primeiroFechamento = sessao.getFechadaEm();

        // D23e — complemento da D22d: aquela protege a diferenca de um movimento posterior, esta a
        // protege de um segundo fechamento que a reescreveria por cima.
        assertThatIllegalStateException().isThrownBy(() -> sessao.fechar(Money.de("100.00")));

        assertThat(sessao.getDiferenca()).isEqualTo(Money.de("10.00"));
        assertThat(sessao.getValorFechamentoContado()).isEqualTo(Money.de("90.00"));
        assertThat(sessao.getFechadaEm()).isEqualTo(primeiroFechamento);
    }
}
