package br.com.caixasimples.contas.internal;

import br.com.caixasimples.contas.SituacaoDoPlano;
import br.com.caixasimples.shared.Money;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * As datas do ciclo mensal do plano pago e o valor proporcional do upgrade.
 *
 * <p>Só data e aritmética, sem relógio: quem chama informa o dia de hoje no fuso do balcão, o que
 * deixa cada regra verificável com uma data fixa.
 *
 * <p>O plano vence todo mês no dia da adesão. Num mês que não tem esse dia, vence no último, e o
 * mês seguinte volta ao dia original: com adesão em 31 de janeiro, vence em 28 de fevereiro e em
 * 31 de março. Cada período vai de um vencimento, inclusive, ao seguinte, exclusive. Pagamento
 * atrasado não desloca o ciclo: a renovação paga o período que venceu, não um mês a partir dela.
 */
public final class CicloDeVencimento {

    /** Dias antes do vencimento em que o aviso ao administrador começa. */
    public static final int DIAS_DE_AVISO = 7;

    /** Dias depois do vencimento em que os recursos pagos continuam ativos sem renovação. */
    public static final int DIAS_DE_TOLERANCIA = 7;

    private CicloDeVencimento() {
        // so datas
    }

    /** O vencimento de um mês: o dia do ciclo, ou o último dia do mês, se o mês for mais curto. */
    public static LocalDate vencimentoNoMes(YearMonth mes, int diaDoCiclo) {
        Objects.requireNonNull(mes, "mes nao pode ser nulo");
        if (diaDoCiclo < 1 || diaDoCiclo > 31) {
            throw new IllegalArgumentException("dia do ciclo fora do mes: " + diaDoCiclo);
        }
        return mes.atDay(Math.min(diaDoCiclo, mes.lengthOfMonth()));
    }

    /**
     * O vencimento do mês seguinte ao de uma data do ciclo. Da adesão, dá o primeiro vencimento; de
     * um vencimento, o fim do período que começa nele.
     */
    public static LocalDate seguinte(LocalDate dataDoCiclo, int diaDoCiclo) {
        return vencimentoNoMes(YearMonth.from(dataDoCiclo).plusMonths(1), diaDoCiclo);
    }

    /** O vencimento do mês anterior: o início do período que termina na data dada. */
    public static LocalDate anterior(LocalDate dataDoCiclo, int diaDoCiclo) {
        return vencimentoNoMes(YearMonth.from(dataDoCiclo).minusMonths(1), diaDoCiclo);
    }

    /** O primeiro dia sem os recursos pagos, se não houver renovação: o oitavo depois do vencimento. */
    public static LocalDate inicioDaSuspensao(LocalDate vencimento) {
        return vencimento.plusDays(DIAS_DE_TOLERANCIA + 1);
    }

    /**
     * Onde o plano pago está no dia de hoje.
     *
     * @param vencimento o fim, exclusive, do último período pago
     */
    public static SituacaoDoPlano situacao(LocalDate vencimento, LocalDate hoje) {
        Objects.requireNonNull(vencimento, "vencimento nao pode ser nulo");
        Objects.requireNonNull(hoje, "hoje nao pode ser nulo");
        if (hoje.isBefore(vencimento.minusDays(DIAS_DE_AVISO))) {
            return SituacaoDoPlano.EM_DIA;
        }
        if (hoje.isBefore(vencimento)) {
            return SituacaoDoPlano.A_VENCER;
        }
        if (hoje.isBefore(inicioDaSuspensao(vencimento))) {
            return SituacaoDoPlano.VENCIDO;
        }
        return SituacaoDoPlano.SUSPENSO;
    }

    /**
     * O período que uma renovação pedida hoje paga.
     *
     * <p>É o que começa no vencimento ainda não pago, mesmo antes dele, para quem renova adiantado.
     * Com mais de um período vencido, é o corrente, o que contém hoje: pagar o mês corrente basta
     * para voltar a ter os recursos, e os meses anteriores ficam sem pagar.
     *
     * @param vencimento o fim, exclusive, do último período pago
     */
    public static Periodo periodoDaRenovacao(LocalDate vencimento, int diaDoCiclo, LocalDate hoje) {
        Objects.requireNonNull(hoje, "hoje nao pode ser nulo");
        LocalDate inicio = vencimento;
        LocalDate fim = seguinte(inicio, diaDoCiclo);
        while (!hoje.isBefore(fim)) {
            inicio = fim;
            fim = seguinte(inicio, diaDoCiclo);
        }
        return new Periodo(inicio, fim);
    }

    /**
     * O valor do upgrade no meio do período: a diferença das mensalidades vezes os dias de hoje,
     * inclusive, até o vencimento, exclusive, sobre os dias do período que termina no vencimento.
     *
     * <p>O crédito do Pix é presumido no dia do pedido. Com renovação adiantada, os dias até o
     * vencimento passam dos dias do período, e o valor cresce na mesma proporção, porque a
     * diferença é devida por todos os dias já pagos no plano menor.
     *
     * @throws IllegalArgumentException se hoje não é anterior ao vencimento: sem período pago, não
     *         há dias restantes para a proporção
     */
    public static Money valorDoUpgrade(Money diferencaMensal, LocalDate vencimento, int diaDoCiclo,
            LocalDate hoje) {
        Objects.requireNonNull(diferencaMensal, "diferenca nao pode ser nula");
        if (!hoje.isBefore(vencimento)) {
            throw new IllegalArgumentException(
                    "upgrade so com o periodo pago: hoje " + hoje + ", vencimento " + vencimento);
        }
        long diasRestantes = ChronoUnit.DAYS.between(hoje, vencimento);
        long diasDoPeriodo = ChronoUnit.DAYS.between(anterior(vencimento, diaDoCiclo), vencimento);
        return diferencaMensal.proporcionalArredondando(diasRestantes, diasDoPeriodo);
    }

    /**
     * Um período do ciclo.
     *
     * @param inicio o primeiro dia
     * @param fim    o dia seguinte ao último, que é o vencimento
     */
    public record Periodo(LocalDate inicio, LocalDate fim) {

        public Periodo {
            Objects.requireNonNull(inicio, "inicio nao pode ser nulo");
            Objects.requireNonNull(fim, "fim nao pode ser nulo");
            if (!inicio.isBefore(fim)) {
                throw new IllegalArgumentException("periodo vazio: " + inicio + " a " + fim);
            }
        }
    }
}
