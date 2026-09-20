package br.com.caixasimples.cadastro.domain;

import br.com.caixasimples.cadastro.TipoMovimentoEstoque;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Uma entrada, saída ou ajuste no estoque de um produto.
 *
 * <p><strong>Membro do agregado Produto</strong>, nunca raiz. Não tem repositório e não se altera
 * sozinho: nasce dentro de {@link Produto}, por {@link Produto#darBaixaPorVenda},
 * {@link Produto#estornarPorCancelamento} ou {@link Produto#ajustarEstoque}, e o saldo da raiz
 * muda no mesmo ato. É um {@code record} porque não há nada para alterar depois: movimento
 * lançado não se edita, o que se faz é lançar o oposto, e a ENTRADA do cancelamento é exatamente
 * o oposto da SAIDA da venda.
 *
 * <p><strong>A raiz não carrega o histórico.</strong> Ao contrário de {@code SessaoCaixa}, que
 * remonta seus movimentos a cada leitura, {@link Produto} guarda só o saldo consolidado e devolve
 * o movimento novo a quem o chamou, para que a persistência grave os dois juntos. O histórico de
 * um produto cresce a cada venda, sem limite, e o produto é lido em toda venda; carregar tudo a
 * cada leitura seria pagar o preço que o saldo consolidado existe para evitar.
 *
 * <p><strong>Não importa framework</strong>, pelo mesmo motivo da raiz. O mapeamento para o banco
 * vive em {@code cadastro.internal}.
 *
 * <p>A {@link #quantidade} é <strong>positiva em ENTRADA e SAIDA</strong>, e quem carrega o sinal é
 * o {@link #tipo}, como no movimento de caixa. Uma saída de duas unidades grava dois, não menos
 * dois. <strong>Em AJUSTE o sinal está na quantidade</strong>: é a diferença que o ajuste manual
 * aplicou ao saldo, negativa numa perda ou quebra, positiva numa contagem que achou mais do que
 * o registrado. O mesmo tipo serve para os dois sentidos, então é a quantidade que diz qual foi.
 * Zero nunca é movimento, em tipo nenhum.
 *
 * @param id         gerado na aplicação e nunca pelo banco, para que o registro tenha identidade
 *                   definitiva mesmo criado sem conexão (RNF01)
 * @param tipo       quem carrega o sinal do movimento em ENTRADA e SAIDA
 * @param quantidade positiva em ENTRADA e SAIDA; com sinal em AJUSTE; nunca zero
 * @param motivo     obrigatório em AJUSTE (RF19) e nulo quando ausente; quem <em>exige</em> o
 *                   motivo é a raiz, em {@link Produto#ajustarEstoque}, porque aqui o campo apenas
 *                   aceita o que vier
 * @param vendaId    preenchido quando o movimento vem de uma venda. É referência entre agregados,
 *                   então é um {@link UUID} e nunca um objeto navegável
 * @param criadoEm   momento do lançamento, em UTC
 */
public record MovimentoEstoque(UUID id, TipoMovimentoEstoque tipo, BigDecimal quantidade,
        String motivo, UUID vendaId, Instant criadoEm) {

    /** As três casas de {@code numeric(12,3)}, as mesmas do saldo e da quantidade vendida. */
    private static final int CASAS_DA_QUANTIDADE = 3;

    public MovimentoEstoque {
        Objects.requireNonNull(id, "id nao pode ser nulo");
        Objects.requireNonNull(tipo, "tipo nao pode ser nulo");
        Objects.requireNonNull(quantidade, "quantidade nao pode ser nula");
        Objects.requireNonNull(criadoEm, "criadoEm nao pode ser nulo");
        if (quantidade.signum() == 0) {
            throw new IllegalArgumentException(
                    "quantidade de movimento de estoque nao pode ser zero: nada entrou nem saiu");
        }
        if (tipo != TipoMovimentoEstoque.AJUSTE && quantidade.signum() < 0) {
            // O sinal é do tipo, então um valor negativo aqui seria sinal duplicado: uma saída
            // de -2 somaria em vez de subtrair. O CHECK da migration diz o mesmo no banco.
            throw new IllegalArgumentException(
                    "quantidade de " + tipo + " nao pode ser negativa: " + quantidade
                            + ". Quem indica entrada ou saida e o tipo, nunca o sinal.");
        }
        exigirCasasDaQuantidade(quantidade, "quantidade de movimento de estoque");
        motivo = textoOpcional(motivo);
    }

    /**
     * A baixa de uma venda concluída (RF18): identidade e momento nascem aqui, como em todo
     * registro do sistema (RNF01).
     */
    static MovimentoEstoque saidaPorVenda(BigDecimal quantidade, UUID vendaId) {
        Objects.requireNonNull(vendaId, "vendaId nao pode ser nulo");
        return new MovimentoEstoque(UUID.randomUUID(), TipoMovimentoEstoque.SAIDA, quantidade,
                null, vendaId, Instant.now());
    }

    /**
     * O estorno de uma venda cancelada (RF12): a quantidade que a venda tinha levado volta, e o
     * movimento aponta para a mesma venda da SAIDA que ele desfaz. Sem motivo, como a saída: a
     * venda já o explica.
     */
    static MovimentoEstoque entradaPorCancelamento(BigDecimal quantidade, UUID vendaId) {
        Objects.requireNonNull(vendaId, "vendaId nao pode ser nulo");
        return new MovimentoEstoque(UUID.randomUUID(), TipoMovimentoEstoque.ENTRADA, quantidade,
                null, vendaId, Instant.now());
    }

    /**
     * O ajuste manual (RF19): a diferença com sinal e o motivo que a raiz já exigiu. Sem venda,
     * porque o ajuste é decisão de quem conta a prateleira, não consequência de um atendimento.
     */
    static MovimentoEstoque ajuste(BigDecimal diferenca, String motivo) {
        return new MovimentoEstoque(UUID.randomUUID(), TipoMovimentoEstoque.AJUSTE, diferenca,
                motivo, null, Instant.now());
    }

    /**
     * Recusa mais de três casas decimais em qualquer quantidade de estoque: o banco arredondaria a
     * quarta casa em silêncio, e o saldo calculado na raiz deixaria de bater com a soma dos
     * movimentos gravados. Mesma postura do item da venda. Zeros à direita não contam: 2,0000 é
     * 2. Compartilhada com a raiz porque o estoque mínimo é comparado com o saldo e obedece à
     * mesma escala.
     */
    static void exigirCasasDaQuantidade(BigDecimal valor, String campo) {
        if (valor.stripTrailingZeros().scale() > CASAS_DA_QUANTIDADE) {
            throw new IllegalArgumentException(
                    campo + " tem no maximo " + CASAS_DA_QUANTIDADE + " casas decimais: " + valor);
        }
    }

    private static String textoOpcional(String valor) {
        if (valor == null || valor.isBlank()) {
            return null;
        }
        return valor.trim();
    }
}
