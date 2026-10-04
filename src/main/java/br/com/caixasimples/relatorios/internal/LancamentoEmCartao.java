package br.com.caixasimples.relatorios.internal;

import br.com.caixasimples.vendas.StatusVenda;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Um pagamento em cartão, como a conferência contra o extrato da operadora o lê, com os
 * <strong>tipos da coluna</strong>.
 *
 * <p>É o alvo do {@code select new} das duas consultas da conferência em
 * {@link VendaParaRelatorioRepository}, a das parcelas e a dos recebimentos de fiado: as duas
 * devolvem a mesma forma, e o caso de uso junta as duas listas pela hora. Público pelo mesmo motivo
 * de {@link ProdutoVendido}, e sem {@code contaId} pelo mesmo motivo de toda projeção do projeto:
 * quem filtra é o tenant na entidade (RNF05).
 *
 * @param id              a parcela ou o recebimento
 * @param vendaId         a venda a que o pagamento pertence
 * @param lancadoEm       quando foi lançado, em UTC; é o que põe a linha no dia
 * @param valor           o valor cobrado na maquininha
 * @param usuarioId       quem lançou: o operador da venda, na parcela, e o dono da sessão em que o
 *                        fiado entrou, no recebimento
 * @param situacaoDaVenda a situação atual da venda
 * @param nsu             o NSU do comprovante da maquininha; nulo quando não foi informado
 */
public record LancamentoEmCartao(UUID id, UUID vendaId, Instant lancadoEm, BigDecimal valor,
        UUID usuarioId, StatusVenda situacaoDaVenda, String nsu) {
}
