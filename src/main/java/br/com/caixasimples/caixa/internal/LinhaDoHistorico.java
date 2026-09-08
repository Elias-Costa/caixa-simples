package br.com.caixasimples.caixa.internal;

import br.com.caixasimples.caixa.StatusSessaoCaixa;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Uma linha do historico de caixa como ela sai do banco, com os <strong>tipos da coluna</strong>.
 *
 * <p>Existe por um motivo so, e vale escrever qual: fazer a consulta do historico (RF16)
 * <strong>nao passar pelo {@code EAGER}</strong> dos movimentos de {@link SessaoCaixaEntity}. O
 * javadoc daquele mapeamento avisou que listar sessoes seria o ponto em que a conta muda — carregar
 * o extrato completo de toda sessao do dia para mostrar uma tabela de totais. Uma projecao seleciona
 * so estas colunas, e os movimentos nem sao consultados.
 *
 * <p><strong>Por que {@link BigDecimal} e nao {@code Money}:</strong> e o que o JPA sabe construir
 * numa projecao — ele copia a coluna para o componente do record, e nao tem como chamar
 * {@code Money.de(...)} no meio do caminho. A traducao para {@code Money}, que e a linguagem do
 * resto do sistema, acontece uma vez so, em
 * {@code SessaoCaixaService.ResumoDeSessao}. Este record nao sai de {@code caixa.internal} por
 * conta propria.
 *
 * <p>Sem {@code contaId}: quem filtra e o {@code @TenantId} na entidade, e uma projecao que
 * devolvesse a conta so daria a quem le a ideia de conferi-la a mao (RNF05).
 *
 * @param diferenca positivo e falta na gaveta, negativo e sobra (dicionario de dados §3); nulo
 *                  enquanto a sessao esta ABERTA, junto com {@code valorFechamentoContado} e
 *                  {@code fechadaEm}
 */
public record LinhaDoHistorico(UUID id, UUID usuarioId, BigDecimal valorAbertura,
        BigDecimal valorFechamentoEsperado, BigDecimal valorFechamentoContado, BigDecimal diferenca,
        Instant abertaEm, Instant fechadaEm, StatusSessaoCaixa status) {
}
