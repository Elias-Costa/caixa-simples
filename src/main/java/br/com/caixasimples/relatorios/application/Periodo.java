package br.com.caixasimples.relatorios.application;

import br.com.caixasimples.shared.FusoDeReferencia;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Um período de dias do balcão, <strong>com os dois extremos incluídos</strong>, e os dois
 * instantes em UTC que o delimitam numa consulta.
 *
 * <p>Existe porque os três relatórios recebem o mesmo par de dias, recusam o mesmo período
 * invertido e convertem os dois dias do mesmo jeito; escrever isso três vezes seria três lugares
 * para a mesma regra ficar diferente. Visibilidade de pacote: é forma interna dos casos de uso,
 * e as respostas deles seguem devolvendo os dois dias como {@link LocalDate}.
 *
 * <p>Sem limite de tamanho: cada relatório é uma agregação, e devolve o mesmo punhado de linhas
 * seja de um dia ou de um ano.
 *
 * @param inicio o primeiro dia, obrigatório
 * @param fim    o último dia, obrigatório e incluído; pode ser o mesmo que o primeiro
 */
record Periodo(LocalDate inicio, LocalDate fim) {

    Periodo {
        Objects.requireNonNull(inicio, "inicio do periodo nao pode ser nulo");
        Objects.requireNonNull(fim, "fim do periodo nao pode ser nulo");
        if (fim.isBefore(inicio)) {
            throw new IllegalArgumentException(
                    "fim do periodo (" + fim + ") nao pode vir antes do inicio (" + inicio + ")");
        }
    }

    /** O primeiro instante do primeiro dia, no fuso do balcão, para o limite inclusivo. */
    Instant inicioInclusivo() {
        return FusoDeReferencia.inicioDoDia(inicio);
    }

    /**
     * O primeiro instante do dia seguinte ao último, para o limite <strong>exclusivo</strong>: o
     * intervalo é semiaberto, para o que acontecer exatamente à meia-noite entrar num dia só.
     */
    Instant fimExclusivo() {
        return FusoDeReferencia.inicioDoDiaSeguinte(fim);
    }
}
