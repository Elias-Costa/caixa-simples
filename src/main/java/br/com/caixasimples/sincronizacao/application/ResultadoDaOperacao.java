package br.com.caixasimples.sincronizacao.application;

import java.util.Objects;
import java.util.UUID;

/**
 * O que aconteceu com uma operação do lote, na forma que o dispositivo usa para decidir o estado
 * do gesto na fila local.
 *
 * <ul>
 *   <li>{@code APLICADA}: o efeito está no servidor, sem pendência.</li>
 *   <li>{@code APLICADA_COM_REVISAO}: o efeito está no servidor, e o administrador precisa conferir
 *       algo que o detalhe descreve.</li>
 *   <li>{@code NAO_APLICADA}: nada foi feito, e o detalhe diz por quê. O dispositivo não repete
 *       sozinho.</li>
 *   <li>{@code ERRO_TRANSITORIO}: nada foi feito nem gravado, e o mesmo gesto pode ser
 *       reenviado.</li>
 * </ul>
 *
 * @param operacaoId o id da operação, como veio no lote
 * @param resultado  o desfecho
 * @param versao     a revisão do registro depois do gesto, quando ele foi aplicado e o registro
 *                   tem revisão
 * @param detalhe    o motivo da revisão, da recusa ou da falha; nulo quando aplicada sem pendência
 */
public record ResultadoDaOperacao(UUID operacaoId, Resultado resultado, Long versao,
        String detalhe) {

    public enum Resultado {
        APLICADA, APLICADA_COM_REVISAO, NAO_APLICADA, ERRO_TRANSITORIO
    }

    public ResultadoDaOperacao {
        Objects.requireNonNull(operacaoId, "operacaoId nao pode ser nulo");
        Objects.requireNonNull(resultado, "resultado nao pode ser nulo");
    }

    static ResultadoDaOperacao naoAplicada(UUID operacaoId, String motivo) {
        return new ResultadoDaOperacao(operacaoId, Resultado.NAO_APLICADA, null, motivo);
    }

    static ResultadoDaOperacao transitorio(UUID operacaoId, String motivo) {
        return new ResultadoDaOperacao(operacaoId, Resultado.ERRO_TRANSITORIO, null, motivo);
    }
}
