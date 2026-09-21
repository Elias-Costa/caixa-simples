package br.com.caixasimples.relatorios.internal;

import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.pagamentos.StatusPagamento;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.TenantId;

/**
 * A tabela {@code pagamento} vista pelos relatórios: um segundo mapeamento da mesma tabela, com as
 * colunas que o faturamento por forma de pagamento lê e nada mais.
 *
 * <p>Segue o desenho de {@link VendaParaRelatorio}, pelos mesmos motivos, e, como
 * {@link ProdutoParaRelatorio}, <strong>não tem repositório</strong>: existe para ser alvo de
 * junção na consulta do faturamento por forma, que parte da venda. Pagamento é membro do agregado
 * Venda e nunca teve repositório próprio; este mapeamento não muda isso.
 *
 * <p><strong>É esta tabela, e não a venda, que diz quanto entrou por cada forma.</strong> Uma
 * venda paga metade em dinheiro e metade em Pix são duas parcelas, cada uma com a sua forma e o
 * seu valor, e o filtro por forma soma o valor de cada parcela, não o total da venda: assim o que
 * entrou em dinheiro, em Pix e em cartão, somado, bate com o faturamento sem filtro.
 *
 * <p>{@code contaId} filtra pelo {@link TenantId}; a junção por id de venda alcança só linhas que a
 * chave estrangeira já prende à mesma conta (RNF05).
 */
@Entity
@Immutable
@Table(name = "pagamento")
public class PagamentoParaRelatorio {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "conta_id", nullable = false, updatable = false)
    private UUID contaId;

    @Column(name = "venda_id", nullable = false)
    private UUID vendaId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FormaPagamento forma;

    /** O valor desta parcela, não o total da venda, e já sem o troco, que é coluna à parte. */
    @Column(nullable = false)
    private BigDecimal valor;

    /** Só a parcela CONFIRMADO é dinheiro que entrou; PENDENTE e RECUSADO ficam fora da soma. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StatusPagamento status;

    protected PagamentoParaRelatorio() {
        // exigido pelo JPA, e o unico construtor de proposito: ninguem instancia esta classe
    }
}
