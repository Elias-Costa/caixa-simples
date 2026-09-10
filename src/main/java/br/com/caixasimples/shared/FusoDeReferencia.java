package br.com.caixasimples.shared;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

/**
 * O fuso que delimita <em>o dia</em> no sistema, e as duas conversões que dependem dele.
 *
 * <p>{@code America/Bahia} fixo, como <strong>constante única da aplicação</strong>: não é coluna
 * em {@code conta} e não é o fuso do servidor. O armazenamento continua em UTC, via
 * {@code timestamptz} e {@code hibernate.jdbc.time_zone: UTC}; o fuso entra só aqui, na conversão
 * que diz onde um dia começa e onde termina.
 *
 * <p><strong>Por que fixo e não configurável:</strong> todos os negócios atendidos operam no mesmo
 * fuso. Um fuso por conta custaria coluna, tela e, pior, seria mais uma configuração capaz de ficar
 * errada e produzir relatório torto sem avisar ninguém. O fuso do servidor foi descartado de saída:
 * a aplicação roda em infraestrutura tipicamente configurada em UTC, o que faria o dia virar às 21h
 * locais, no meio do expediente.
 *
 * <p><strong>Por que em {@code shared} e não em {@code caixa}:</strong> a constante precisa ser
 * única, e o segundo uso já está previsto, porque os relatórios de faturamento por dia e por
 * período delimitam o mesmo dia. Duas cópias da mesma {@link ZoneId} seriam duas coisas para manter
 * em dia.
 *
 * <p><strong>Reabrir quando</strong> o produto passar a atender região em outro fuso. A migração é
 * esta constante virar coluna em {@code conta}, com o valor atual como padrão de todas as linhas.
 */
public final class FusoDeReferencia {

    /** O fuso do balcão, o único do sistema. */
    public static final ZoneId DO_BALCAO = ZoneId.of("America/Bahia");

    private FusoDeReferencia() {
        // so constante e conversao
    }

    /**
     * O instante em que o dia começa no balcão, para usar como limite <strong>inclusivo</strong> de
     * uma consulta.
     *
     * <p>No fuso do balcão, um dia de setembro começa às 03h em UTC. É por isso que consultar o dia
     * comparando a data crua da coluna daria a resposta errada em toda sessão aberta depois das
     * 21h.
     */
    public static Instant inicioDoDia(LocalDate dia) {
        Objects.requireNonNull(dia, "dia nao pode ser nulo");
        return dia.atStartOfDay(DO_BALCAO).toInstant();
    }

    /**
     * O instante em que o dia seguinte começa, para usar como limite <strong>exclusivo</strong> da
     * mesma consulta.
     *
     * <p>O nome diz o que o valor é, em vez de chamá-lo de fim do dia: quem lê a consulta vê que o
     * limite é <strong>exclusivo</strong>, já que o instante devolvido aqui pertence ao dia
     * seguinte, e não precisa adivinhar se o último instante entra. Um fim do dia calculado como
     * 23:59:59 perderia o que acontecesse na fração de segundo seguinte.
     *
     * <p>Sem notação de intervalo em colchete neste javadoc, e sem aspas duplas: o exportador de
     * documentação do Modulith joga o comentário num JSON lido por um parser ingênuo, e um
     * delimitador sem par embaralha o arquivo inteiro, quebrando o teste de fronteiras com um erro
     * que não menciona javadoc nenhum.
     */
    public static Instant inicioDoDiaSeguinte(LocalDate dia) {
        Objects.requireNonNull(dia, "dia nao pode ser nulo");
        return inicioDoDia(dia.plusDays(1));
    }
}
