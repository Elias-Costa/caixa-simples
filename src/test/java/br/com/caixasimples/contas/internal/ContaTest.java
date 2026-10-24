package br.com.caixasimples.contas.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import br.com.caixasimples.contas.Plano;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * O ciclo do plano pago e a suspensão do acesso na raiz {@link Conta}: o que cada mudança recusa, e
 * que a recusa não deixa nada pela metade.
 *
 * <p>Teste de unidade puro, com datas fixas: a raiz não lê relógio, e quem chama informa o dia de
 * hoje. O caso de uso recusa antes boa parte destes casos, então a raiz é a segunda linha; a
 * terceira são as restrições do ciclo no banco, provadas em {@code PlanoServiceTest}.
 */
class ContaTest {

    /** A adesão de todos os testes: o ciclo vence todo dia 10. */
    private static final LocalDate ADESAO = LocalDate.of(2026, 3, 10);

    private static final LocalDate VENCIMENTO = LocalDate.of(2026, 4, 10);

    @Test
    @DisplayName("a adesão parte do plano grátis para o plano pago, e só uma vez")
    void adesaoSoDoGratisParaUmPlanoPago() {
        Conta gratis = gratis();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> gratis.ativarPlano(Plano.GRATIS, ADESAO))
                .withMessageContaining("gratuito nao se ativa");
        conferirCiclo(gratis, Plano.GRATIS, null, null);

        // Uma segunda adesão recomeçaria o ciclo no dia dela, apagando o período já pago.
        Conta completa = paga(Plano.COMPLETO);
        assertThatIllegalStateException()
                .isThrownBy(() -> completa.ativarPlano(Plano.COMPLETO, ADESAO.plusDays(5)))
                .withMessageContaining("já está no plano");
        conferirCiclo(completa, Plano.COMPLETO, 10, VENCIMENTO);
    }

    @Test
    @DisplayName("a renovação é do plano da Conta e de um período ainda não pago")
    void renovacaoDoProprioPlanoEDeUmPeriodoAindaNaoPago() {
        Conta conta = paga(Plano.COMPLETO);
        LocalDate maio = LocalDate.of(2026, 5, 10);

        assertThatIllegalStateException()
                .isThrownBy(() -> conta.renovar(Plano.GRATIS, maio))
                .withMessageContaining("A renovação é do plano");
        assertThatIllegalStateException()
                .isThrownBy(() -> conta.renovar(Plano.COMPLETO, VENCIMENTO))
                .withMessageContaining("já está pago");
        assertThatIllegalStateException()
                .isThrownBy(() -> conta.renovar(Plano.COMPLETO, VENCIMENTO.minusDays(1)))
                .withMessageContaining("já está pago");
        conferirCiclo(conta, Plano.COMPLETO, 10, VENCIMENTO);

        // O limite: o período seguinte ao pago renova, e o dia do ciclo não muda.
        conta.renovar(Plano.COMPLETO, maio);
        conferirCiclo(conta, Plano.COMPLETO, 10, maio);
    }

    @Test
    @DisplayName("a Conta no plano grátis não tem ciclo: não renova nem tem período")
    void contaGratisNaoTemCiclo() {
        Conta gratis = gratis();

        assertThatIllegalStateException()
                .isThrownBy(() -> gratis.renovar(Plano.COMPLETO, VENCIMENTO))
                .withMessageContaining("gratuito não tem vencimento");
        assertThatIllegalStateException()
                .isThrownBy(() -> gratis.periodoDaRenovacao(ADESAO))
                .withMessageContaining("gratuito não tem vencimento");
        conferirCiclo(gratis, Plano.GRATIS, null, null);
    }

    @Test
    @DisplayName("o nome do negócio é corrigido sem os espaços das pontas; em branco ou acima de 120 caracteres, recusado, também na criação")
    void nomeDoNegocio() {
        Conta conta = paga(Plano.COMPLETO);
        conta.corrigirNomeDoNegocio("  Cafeteria Aurora do Centro  ");
        assertThat(conta.getNomeNegocio()).isEqualTo("Cafeteria Aurora do Centro");

        assertThatIllegalArgumentException().isThrownBy(() -> conta.corrigirNomeDoNegocio(" "));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> conta.corrigirNomeDoNegocio("a".repeat(121)))
                .withMessage("nome do negócio com 121 caracteres; o limite é 120.");
        assertThat(conta.getNomeNegocio()).isEqualTo("Cafeteria Aurora do Centro");
        // A correção não mexe no ciclo do plano.
        conferirCiclo(conta, Plano.COMPLETO, 10, VENCIMENTO);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Conta("a".repeat(121), null, Plano.GRATIS))
                .withMessageContaining("o limite é 120");
    }

    @Test
    @DisplayName("a suspensão do acesso começa uma vez e só se desfaz na Conta suspensa, sem mexer no plano")
    void suspensaoDoAcesso() {
        Conta conta = paga(Plano.COMPLETO);
        Instant inicio = Instant.parse("2026-03-20T15:00:00Z");
        assertThatIllegalStateException()
                .isThrownBy(conta::reativar)
                .withMessage("a Conta não está suspensa");

        conta.suspender(inicio);
        assertThat(conta.isSuspensa()).isTrue();
        // Suspender de novo trocaria o instante que o aviso ao administrador informa.
        assertThatIllegalStateException()
                .isThrownBy(() -> conta.suspender(inicio.plusSeconds(60)))
                .withMessageContaining("já está suspensa");
        assertThat(conta.getSuspensaEm()).isEqualTo(inicio);
        conferirCiclo(conta, Plano.COMPLETO, 10, VENCIMENTO);

        conta.reativar();
        assertThat(conta.isSuspensa()).isFalse();
        assertThat(conta.getSuspensaEm()).isNull();
    }

    private static Conta gratis() {
        return new Conta("Cafeteria Aurora", null, Plano.GRATIS);
    }

    private static Conta paga(Plano plano) {
        Conta conta = gratis();
        conta.ativarPlano(plano, ADESAO);
        return conta;
    }

    /** O plano e as duas datas do ciclo, que o banco exige juntas no plano pago. */
    private static void conferirCiclo(Conta conta, Plano plano, Integer diaDeVencimento,
            LocalDate proximoVencimento) {
        assertThat(conta)
                .extracting(Conta::getPlano, Conta::getDiaDeVencimento, Conta::getProximoVencimento)
                .containsExactly(plano, diaDeVencimento, proximoVencimento);
    }
}
