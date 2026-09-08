package br.com.caixasimples.shared;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

/**
 * O fuso que delimita <em>o dia</em> no sistema, e as duas conversoes que dependem dele.
 *
 * <p>Implementa a decisao <strong>P6</strong>: {@code America/Bahia} fixo, <strong>constante unica
 * da aplicacao</strong> — nao coluna em {@code conta}, nao fuso do servidor. O armazenamento
 * continua em UTC ({@code timestamptz} + {@code hibernate.jdbc.time_zone: UTC}); o fuso entra so
 * aqui, na conversao que diz onde um dia comeca e onde termina.
 *
 * <p><strong>Por que fixo e nao configuravel:</strong> todos os clientes do escopo §1 e §3 estao em
 * Paulo Afonso-BA e no Raso da Catarina. Um fuso por conta custaria coluna, tela e — pior — seria
 * mais uma configuracao capaz de ficar errada e produzir relatorio torto sem avisar ninguem. O fuso
 * do servidor foi descartado de saida: a aplicacao vai para a AWS, tipicamente em UTC, o que faria
 * o dia virar as 21h locais, no meio do expediente.
 *
 * <p><strong>Por que em {@code shared} e nao em {@code caixa}:</strong> a P6 pede uma constante so,
 * e o segundo uso ja esta previsto — os relatorios de faturamento por dia e por periodo (R19 e R20)
 * delimitam o mesmo dia. Duas copias da mesma {@link ZoneId} seriam duas coisas para manter em dia.
 *
 * <p><strong>Reabrir quando</strong> o produto sair da Bahia: a migracao e esta constante virar
 * coluna em {@code conta}, com {@code America/Bahia} como valor inicial de todas as linhas.
 */
public final class FusoDeReferencia {

    /** P6 — o fuso do balcao, o unico do sistema. */
    public static final ZoneId DO_BALCAO = ZoneId.of("America/Bahia");

    private FusoDeReferencia() {
        // so constante e conversao
    }

    /**
     * O instante em que o dia comeca no balcao, para usar como limite <strong>inclusivo</strong> de
     * uma consulta.
     *
     * <p>Um dia de setembro em Paulo Afonso comeca as 03h em UTC — e por isso que consultar o dia
     * comparando a data crua da coluna daria a resposta errada em toda sessao aberta depois das
     * 21h.
     */
    public static Instant inicioDoDia(LocalDate dia) {
        Objects.requireNonNull(dia, "dia nao pode ser nulo");
        return dia.atStartOfDay(DO_BALCAO).toInstant();
    }

    /**
     * O instante em que o dia seguinte comeca, para usar como limite <strong>exclusivo</strong> da
     * mesma consulta.
     *
     * <p>O nome diz o que o valor e, em vez de chama-lo de fim do dia: quem le a consulta ve que o
     * limite e <strong>exclusivo</strong> — o instante devolvido aqui ja pertence ao dia seguinte —
     * e nao precisa adivinhar se o ultimo instante entra. Um fim do dia calculado como 23:59:59
     * perderia o que acontecesse na fracao de segundo seguinte.
     *
     * <p>Sem notacao de intervalo em colchete no javadoc: o exportador do Modulith joga o comentario
     * num JSON lido por um parser ingenuo, e um colchete sem par embaralha o arquivo inteiro — mesma
     * armadilha da D18, que ja proibiu aspas duplas por aqui.
     */
    public static Instant inicioDoDiaSeguinte(LocalDate dia) {
        Objects.requireNonNull(dia, "dia nao pode ser nulo");
        return inicioDoDia(dia.plusDays(1));
    }
}
