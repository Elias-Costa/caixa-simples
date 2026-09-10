package br.com.caixasimples.caixa.internal;

import br.com.caixasimples.caixa.StatusSessaoCaixa;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Uma linha do histórico de caixa como ela sai do banco, com os <strong>tipos da coluna</strong>.
 *
 * <p>Existe por um motivo só, e vale escrever qual: fazer a consulta do histórico (RF16)
 * <strong>não passar pelo {@code EAGER}</strong> dos movimentos de {@link SessaoCaixaEntity}.
 * Listar sessões é justamente o ponto em que aquele carregamento deixa de ser barato, porque traria
 * o extrato completo de toda sessão do dia para montar uma tabela de totais. Uma projeção seleciona
 * só estas colunas, e os movimentos nem chegam a ser consultados.
 *
 * <p><strong>Por que {@link BigDecimal} e não {@code Money}:</strong> é o que o JPA sabe construir
 * numa projeção, já que ele copia a coluna direto para o componente do record e não tem como chamar
 * uma fábrica no meio do caminho. A tradução para {@code Money}, que é a linguagem do resto do
 * sistema, acontece uma vez só, em {@code SessaoCaixaService.ResumoDeSessao}. Este record não sai
 * de {@code caixa.internal} por conta própria.
 *
 * <p>Sem {@code contaId}: quem filtra é o {@code @TenantId} na entidade, e uma projeção que
 * devolvesse a conta só daria a quem lê a ideia de conferi-la à mão (RNF05).
 *
 * @param diferenca positivo é falta na gaveta, negativo é sobra; nulo enquanto a sessão está
 *                  ABERTA, junto com {@code valorFechamentoContado} e {@code fechadaEm}
 */
public record LinhaDoHistorico(UUID id, UUID usuarioId, BigDecimal valorAbertura,
        BigDecimal valorFechamentoEsperado, BigDecimal valorFechamentoContado, BigDecimal diferenca,
        Instant abertaEm, Instant fechadaEm, StatusSessaoCaixa status) {
}
