package br.com.caixasimples.caixa.domain;

import br.com.caixasimples.caixa.TipoMovimentoCaixa;
import br.com.caixasimples.shared.Money;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Uma entrada ou saída de dinheiro do caixa: venda, sangria ou suprimento.
 *
 * <p><strong>Membro do agregado Caixa</strong>, nunca raiz. Não tem repositório e não se altera
 * sozinho: nasce dentro de {@link SessaoCaixa}, por {@link SessaoCaixa#sangrar},
 * {@link SessaoCaixa#suprir} ou {@link SessaoCaixa#registrarVenda}, e some com ela. É um
 * {@code record} justamente porque não há nada para alterar depois: movimento de caixa lançado não
 * se edita, o que se faz é lançar o oposto.
 *
 * <p><strong>Não importa framework</strong>, pelo mesmo motivo do agregado Produto. O mapeamento
 * para o banco vive em {@code caixa.internal}, e assim a regra continua legível sem conhecer JPA.
 *
 * <p>O {@link #valor} é <strong>sempre positivo</strong>, e quem carrega o sinal é o {@link #tipo}.
 * Uma sangria de trinta reais grava trinta, não menos trinta. Guardar o sinal em dois lugares
 * abriria espaço para uma linha em que os dois se contradizem.
 *
 * @param id       gerado na aplicação e nunca pelo banco, para que o registro tenha identidade
 *                 definitiva mesmo criado sem conexão (RNF01)
 * @param tipo     quem carrega o sinal do movimento
 * @param valor    sempre positivo
 * @param motivo   obrigatório em SANGRIA e SUPRIMENTO (RF14) e nulo quando ausente; quem
 *                 <em>exige</em> o motivo é a raiz, em {@link SessaoCaixa#sangrar} e
 *                 {@link SessaoCaixa#suprir}, porque aqui o campo apenas aceita o que vier
 * @param vendaId  preenchido só quando o tipo é VENDA. É referência entre agregados, então é um
 *                 {@link UUID} e nunca um objeto navegável
 * @param criadoEm momento do lançamento, em UTC
 */
public record MovimentoCaixa(UUID id, TipoMovimentoCaixa tipo, Money valor, String motivo,
        UUID vendaId, Instant criadoEm) {

    public MovimentoCaixa {
        Objects.requireNonNull(id, "id nao pode ser nulo");
        Objects.requireNonNull(tipo, "tipo nao pode ser nulo");
        Objects.requireNonNull(criadoEm, "criadoEm nao pode ser nulo");
        Objects.requireNonNull(valor, "valor nao pode ser nulo");
        if (valor.isNegativo()) {
            // O sinal é do tipo, então um valor negativo aqui seria sinal duplicado: uma sangria
            // de -30 subtrairia duas vezes. O CHECK da migration diz o mesmo no banco.
            throw new IllegalArgumentException(
                    "valor de movimento de caixa nao pode ser negativo: " + valor
                            + ". Quem indica entrada ou saida e o tipo, nunca o sinal do valor.");
        }
        motivo = textoOpcional(motivo);
    }

    /**
     * Movimento novo: identidade e momento nascem aqui, como em todo registro do sistema (RNF01).
     */
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
