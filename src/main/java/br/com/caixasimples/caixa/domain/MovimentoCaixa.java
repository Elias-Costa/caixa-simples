package br.com.caixasimples.caixa.domain;

import br.com.caixasimples.caixa.TipoMovimentoCaixa;
import br.com.caixasimples.shared.Money;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Uma entrada ou saida de dinheiro do caixa: venda, sangria ou suprimento.
 *
 * <p><strong>Membro do agregado Caixa</strong>, nunca raiz (modelo de dados §4). Nao tem
 * repositorio e nao se altera sozinho: nasce dentro de {@link SessaoCaixa#registrar} e some com
 * ela. E um {@code record} justamente porque nao ha nada para alterar depois — movimento de caixa
 * lancado nao se edita, o que se faz e lancar o oposto.
 *
 * <p><strong>Nao importa framework</strong> (arquitetura §2), pelo mesmo motivo de
 * {@code cadastro.domain.Produto}: o mapeamento vive em {@code caixa.internal}.
 *
 * <p>O {@link #valor} e <strong>sempre positivo</strong> (D21b) — quem carrega o sinal e o
 * {@link #tipo}. Uma sangria de trinta reais grava trinta, nao menos trinta.
 *
 * @param id       gerado na aplicacao, nunca pelo banco (RNF01)
 * @param tipo     quem carrega o sinal do movimento
 * @param valor    sempre positivo (D21b)
 * @param motivo   obrigatorio em SANGRIA e SUPRIMENTO (RF14) e nulo quando ausente; quem
 *                 <em>exige</em> o motivo e o R07, aqui o campo so aceita o que vier
 * @param vendaId  preenchido so quando o tipo e VENDA. E referencia entre agregados, entao e um
 *                 {@link UUID} e nunca um objeto navegavel (modelo de dados §4)
 * @param criadoEm momento do lancamento, em UTC
 */
public record MovimentoCaixa(UUID id, TipoMovimentoCaixa tipo, Money valor, String motivo,
        UUID vendaId, Instant criadoEm) {

    public MovimentoCaixa {
        Objects.requireNonNull(id, "id nao pode ser nulo");
        Objects.requireNonNull(tipo, "tipo nao pode ser nulo");
        Objects.requireNonNull(criadoEm, "criadoEm nao pode ser nulo");
        Objects.requireNonNull(valor, "valor nao pode ser nulo");
        if (valor.isNegativo()) {
            // D21b — o sinal e do tipo, entao um valor negativo aqui seria sinal duplicado: uma
            // sangria de -30 subtrairia duas vezes. O CHECK da migration diz o mesmo.
            throw new IllegalArgumentException(
                    "valor de movimento de caixa nao pode ser negativo: " + valor
                            + ". Quem indica entrada ou saida e o tipo, nunca o sinal do valor.");
        }
        motivo = textoOpcional(motivo);
    }

    /** Movimento novo: identidade e momento nascem aqui, como em todo registro do sistema (RNF01). */
    static MovimentoCaixa novo(TipoMovimentoCaixa tipo, Money valor, String motivo, UUID vendaId) {
        return new MovimentoCaixa(UUID.randomUUID(), tipo, valor, motivo, vendaId, Instant.now());
    }

    private static String textoOpcional(String valor) {
        if (valor == null || valor.isBlank()) {
            return null;
        }
        return valor.trim();
    }
}
