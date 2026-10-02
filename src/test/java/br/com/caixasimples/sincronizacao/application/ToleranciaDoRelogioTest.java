package br.com.caixasimples.sincronizacao.application;

import static br.com.caixasimples.sincronizacao.application.SincronizacaoService.TOLERANCIA_DO_RELOGIO;
import static br.com.caixasimples.sincronizacao.application.SincronizacaoService.passaDaTolerancia;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * O limite exato da tolerância do relógio, com o recebimento fixo. Os testes do lote provam qual
 * instante cada gesto confere; aqui fica a fronteira, que eles não alcançam porque o recebimento é
 * lido do relógio do servidor durante o envio.
 */
class ToleranciaDoRelogioTest {

    private static final Instant RECEBIDA_EM = Instant.parse("2026-10-02T15:00:00Z");

    @Test
    @DisplayName("exatamente cinco minutos depois do recebimento ainda está dentro da tolerância")
    void noLimite() {
        assertThat(passaDaTolerancia(RECEBIDA_EM.plus(TOLERANCIA_DO_RELOGIO), RECEBIDA_EM))
                .isFalse();
    }

    @Test
    @DisplayName("um nanossegundo além dos cinco minutos já passa")
    void alemDoLimite() {
        assertThat(passaDaTolerancia(RECEBIDA_EM.plus(TOLERANCIA_DO_RELOGIO).plusNanos(1),
                RECEBIDA_EM)).isTrue();
    }

    @Test
    @DisplayName("instante anterior ao recebimento não passa, mesmo muito antigo: o envio atrasado é legítimo")
    void anteriorAoRecebimento() {
        assertThat(passaDaTolerancia(RECEBIDA_EM.minusSeconds(1), RECEBIDA_EM)).isFalse();
        assertThat(passaDaTolerancia(Instant.parse("2025-10-02T15:00:00Z"), RECEBIDA_EM))
                .isFalse();
    }

    @Test
    @DisplayName("gesto que não gravou instante do conteúdo não é conferido")
    void semInstante() {
        assertThat(passaDaTolerancia(null, RECEBIDA_EM)).isFalse();
    }
}
