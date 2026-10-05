package br.com.caixasimples.contas.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import br.com.caixasimples.contas.Plano;
import br.com.caixasimples.contas.internal.CicloDeVencimento.Periodo;
import br.com.caixasimples.contas.internal.PedidoDePlano.Situacao;
import br.com.caixasimples.shared.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * O que o pedido de plano recusa por conta própria, sem o caso de uso na frente: aplicar ou
 * substituir o que não está aberto, a adesão sem o primeiro período, o período dado na aplicação
 * de quem não é adesão, o plano grátis e o valor negativo. Depois de cada recusa, o pedido continua
 * como estava.
 *
 * <p>Teste de unidade puro. Um pedido aberto por Conta não mora aqui, porque um pedido não enxerga
 * os outros: quem o garante é o índice do banco, com a trava da Conta no caso de uso, provados em
 * {@code PlanoServiceTest}.
 */
class PedidoDePlanoTest {

    private static final UUID QUEM_PEDE = UUID.randomUUID();
    private static final UUID QUEM_APLICA = UUID.randomUUID();
    private static final UUID OUTRA_PESSOA = UUID.randomUUID();
    private static final Instant AGORA = Instant.parse("2026-03-10T15:00:00Z");
    private static final Instant DEPOIS = AGORA.plusSeconds(60);
    private static final Money MENSALIDADE = Money.de("30.00");
    private static final Periodo MARCO = new Periodo(LocalDate.of(2026, 3, 10),
            LocalDate.of(2026, 4, 10));
    private static final Periodo ABRIL = new Periodo(LocalDate.of(2026, 4, 10),
            LocalDate.of(2026, 5, 10));

    /** O upgrade pedido no dia 25 paga do pedido até o vencimento do período em curso. */
    private static final Periodo RESTO_DE_MARCO = new Periodo(LocalDate.of(2026, 3, 25),
            LocalDate.of(2026, 4, 10));

    @Test
    @DisplayName("só o pedido aberto se aplica ou é substituído: o aplicado e o substituído recusam e não mudam")
    void soOAbertoSeAplicaOuESubstituido() {
        PedidoDePlano renovacaoAplicada = renovacao();
        renovacaoAplicada.aplicar(QUEM_APLICA, AGORA);
        PedidoDePlano renovacaoSubstituida = renovacao();
        renovacaoSubstituida.substituir();
        PedidoDePlano adesaoAplicada = adesao();
        adesaoAplicada.aplicarAdesao(MARCO, QUEM_APLICA, AGORA);
        PedidoDePlano adesaoSubstituida = adesao();
        adesaoSubstituida.substituir();

        for (PedidoDePlano pedido : List.of(renovacaoAplicada, renovacaoSubstituida)) {
            assertThatIllegalStateException()
                    .isThrownBy(() -> pedido.aplicar(OUTRA_PESSOA, DEPOIS))
                    .withMessageContaining("nao esta aberto");
            assertThatIllegalStateException()
                    .isThrownBy(pedido::substituir)
                    .withMessageContaining("nao esta aberto");
        }
        for (PedidoDePlano pedido : List.of(adesaoAplicada, adesaoSubstituida)) {
            assertThatIllegalStateException()
                    .isThrownBy(() -> pedido.aplicarAdesao(ABRIL, OUTRA_PESSOA, DEPOIS))
                    .withMessageContaining("nao esta aberto");
            assertThatIllegalStateException()
                    .isThrownBy(pedido::substituir)
                    .withMessageContaining("nao esta aberto");
        }

        conferir(renovacaoAplicada, Situacao.APLICADO, MARCO, AGORA, QUEM_APLICA);
        conferir(renovacaoSubstituida, Situacao.SUBSTITUIDO, MARCO, null, null);
        conferir(adesaoAplicada, Situacao.APLICADO, MARCO, AGORA, QUEM_APLICA);
        conferir(adesaoSubstituida, Situacao.SUBSTITUIDO, null, null, null);
    }

    @Test
    @DisplayName("a adesão só se aplica com o primeiro período, que começa no dia da aplicação")
    void adesaoSoSeAplicaComOPrimeiroPeriodo() {
        PedidoDePlano adesao = adesao();

        assertThatIllegalStateException()
                .isThrownBy(() -> adesao.aplicar(QUEM_APLICA, AGORA))
                .withMessageContaining("primeiro periodo");
        assertThatNullPointerException()
                .isThrownBy(() -> adesao.aplicarAdesao(null, QUEM_APLICA, AGORA))
                .withMessageContaining("periodo");

        conferir(adesao, Situacao.ABERTO, null, null, null);
    }

    @Test
    @DisplayName("só a adesão recebe o período na aplicação: a renovação e o upgrade já nascem com o que pagam")
    void periodoNaAplicacaoSoNaAdesao() {
        PedidoDePlano renovacao = renovacao();
        PedidoDePlano upgrade = PedidoDePlano.upgrade(Money.de("20.65"), RESTO_DE_MARCO, QUEM_PEDE,
                AGORA);

        assertThatIllegalStateException()
                .isThrownBy(() -> renovacao.aplicarAdesao(ABRIL, QUEM_APLICA, AGORA))
                .withMessageContaining("so a adesao");
        assertThatIllegalStateException()
                .isThrownBy(() -> upgrade.aplicarAdesao(ABRIL, QUEM_APLICA, AGORA))
                .withMessageContaining("so a adesao");

        conferir(renovacao, Situacao.ABERTO, MARCO, null, null);
        conferir(upgrade, Situacao.ABERTO, RESTO_DE_MARCO, null, null);
    }

    @Test
    @DisplayName("o plano grátis não tem pedido")
    void planoGratisNaoTemPedido() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PedidoDePlano.adesao(Plano.GRATIS, MENSALIDADE, QUEM_PEDE, AGORA))
                .withMessageContaining("gratuito nao tem pedido");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PedidoDePlano.renovacao(Plano.GRATIS, MENSALIDADE, MARCO,
                        QUEM_PEDE, AGORA))
                .withMessageContaining("gratuito nao tem pedido");
    }

    @Test
    @DisplayName("valor negativo é recusado nos três tipos; zero vale, porque o upgrade pode arredondar a nada")
    void valorNegativoRecusadoEZeroAceito() {
        Money negativo = Money.de("-0.01");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> PedidoDePlano.adesao(Plano.CAIXA_SIMPLES, negativo, QUEM_PEDE,
                        AGORA))
                .withMessageContaining("negativo");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PedidoDePlano.upgrade(negativo, RESTO_DE_MARCO, QUEM_PEDE, AGORA))
                .withMessageContaining("negativo");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PedidoDePlano.renovacao(Plano.COMPLETO, negativo, MARCO,
                        QUEM_PEDE, AGORA))
                .withMessageContaining("negativo");

        // Uma diferença mensal pequena, pedida na véspera do vencimento, dá menos de meio centavo.
        Periodo vespera = new Periodo(LocalDate.of(2026, 4, 9), LocalDate.of(2026, 4, 10));
        assertThat(PedidoDePlano.upgrade(Money.ZERO, vespera, QUEM_PEDE, AGORA).getValor())
                .isEqualTo(Money.ZERO);
    }

    private static PedidoDePlano adesao() {
        return PedidoDePlano.adesao(Plano.CAIXA_SIMPLES, MENSALIDADE, QUEM_PEDE, AGORA);
    }

    private static PedidoDePlano renovacao() {
        return PedidoDePlano.renovacao(Plano.CAIXA_SIMPLES, MENSALIDADE, MARCO, QUEM_PEDE, AGORA);
    }

    /** A situação, o período e a aplicação: o que uma recusa não pode ter mudado. */
    private static void conferir(PedidoDePlano pedido, Situacao situacao, Periodo periodo,
            Instant aplicadoEm, UUID aplicadoPor) {
        assertThat(pedido)
                .extracting(PedidoDePlano::getSituacao, PedidoDePlano::getPeriodoInicio,
                        PedidoDePlano::getPeriodoFim, PedidoDePlano::getAplicadoEm,
                        PedidoDePlano::getAplicadoPor)
                .containsExactly(situacao, periodo == null ? null : periodo.inicio(),
                        periodo == null ? null : periodo.fim(), aplicadoEm, aplicadoPor);
    }
}
