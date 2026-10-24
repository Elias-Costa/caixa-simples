package br.com.caixasimples.relatorios.internal;

import br.com.caixasimples.vendas.StatusVenda;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Um Pix, como a conferência contra o extrato do banco o lê, com os <strong>tipos da coluna</strong>.
 *
 * <p>É o alvo do {@code select new} das duas consultas da conferência do Pix em
 * {@link VendaParaRelatorioRepository}, a das parcelas e a dos recebimentos de fiado, no molde de
 * {@link LancamentoEmCartao}. Sem {@code contaId}, como toda projeção do projeto: quem filtra é o
 * tenant na entidade (RNF05).
 *
 * @param id              a parcela ou o recebimento
 * @param vendaId         a venda a que o Pix pertence
 * @param lancadoEm       quando foi lançado, em UTC; é o que põe a linha no dia
 * @param valor           o valor do Pix
 * @param usuarioId       quem lançou: o operador da venda, na parcela, e o dono da sessão em que o
 *                        fiado entrou, no recebimento
 * @param situacaoDaVenda a situação atual da venda
 * @param clienteId       o cliente da venda; nulo quando a venda não tem cliente
 * @param integrado       verdadeiro na cobrança criada no provedor, falso no Pix lançado à mão
 * @param pendente        verdadeiro na cobrança integrada que ainda não teve o pagamento confirmado
 */
public record LancamentoEmPix(UUID id, UUID vendaId, Instant lancadoEm, BigDecimal valor,
        UUID usuarioId, StatusVenda situacaoDaVenda, UUID clienteId, boolean integrado,
        boolean pendente) {
}
