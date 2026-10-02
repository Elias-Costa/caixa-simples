package br.com.caixasimples.sincronizacao;

import java.time.Instant;
import java.util.Objects;

/**
 * O que um módulo fez com um gesto que aceitou.
 *
 * @param versao          a revisão do Produto, Cliente ou SessaoCaixa depois do gesto, que o
 *                        dispositivo guarda como a nova versão lida; nula quando o registro não tem
 *                        revisão, como a Venda
 * @param instanteGravado o instante do balcão que o gesto leu do próprio conteúdo e gravou no
 *                        registro, como a conclusão da Venda e a abertura do caixa; o lote o confere
 *                        contra o relógio do servidor, como confere o instante da operação. Nulo
 *                        quando o gesto não grava instante ou grava o da própria operação
 * @param revisao         por que o gesto, aplicado, precisa ser conferido pelo administrador; nulo
 *                        quando não há pendência
 */
public record Aplicacao(Long versao, Instant instanteGravado, String revisao) {

    public static Aplicacao aplicada(Long versao) {
        return new Aplicacao(versao, null, null);
    }

    public static Aplicacao aplicada(Long versao, Instant instanteGravado) {
        Objects.requireNonNull(instanteGravado, "instante gravado nao pode ser nulo");
        return new Aplicacao(versao, instanteGravado, null);
    }

    public static Aplicacao comRevisao(Long versao, String motivo) {
        return new Aplicacao(versao, null, exigirMotivo(motivo));
    }

    public static Aplicacao comRevisao(Long versao, Instant instanteGravado, String motivo) {
        Objects.requireNonNull(instanteGravado, "instante gravado nao pode ser nulo");
        return new Aplicacao(versao, instanteGravado, exigirMotivo(motivo));
    }

    private static String exigirMotivo(String motivo) {
        if (motivo == null || motivo.isBlank()) {
            throw new IllegalArgumentException("revisao sem motivo nao diz o que conferir");
        }
        return motivo;
    }
}
