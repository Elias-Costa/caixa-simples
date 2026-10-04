package br.com.caixasimples.relatorios.internal;

import br.com.caixasimples.pagamentos.FormaPagamento;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.TenantId;

/**
 * A tabela {@code recebimento} vista pelos relatórios: o que a conferência do cartão lê do
 * recebimento de fiado, e nada mais.
 *
 * <p>Mesmo desenho de {@link PagamentoParaRelatorio}: somente leitura, sem repositório, alvo de
 * junção numa consulta que parte da venda. O recebimento é membro do agregado Venda e nunca teve
 * repositório próprio; este mapeamento não muda isso.
 *
 * <p>A sessão de caixa vem junto porque é ela que diz quem recebeu: o fiado pode ser recebido por
 * outra pessoa, no caixa dela, dias depois da venda.
 *
 * <p>{@code contaId} filtra pelo {@link TenantId}, também quando a tabela entra por junção: é ele
 * que isola, e não as chaves estrangeiras, que só garantem que a venda e a sessão existem, não que
 * são da mesma conta (RNF05).
 */
@Entity
@Immutable
@Table(name = "recebimento")
public class RecebimentoParaRelatorio {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "conta_id", nullable = false, updatable = false)
    private UUID contaId;

    @Column(name = "venda_id", nullable = false)
    private UUID vendaId;

    @Column(name = "sessao_caixa_id", nullable = false)
    private UUID sessaoCaixaId;

    @Column(nullable = false)
    private BigDecimal valor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FormaPagamento forma;

    @Column(name = "criado_em", nullable = false)
    private Instant criadoEm;

    /** Só em cartão; nulo quando não foi informado. */
    @Column(length = 40)
    private String nsu;

    protected RecebimentoParaRelatorio() {
        // exigido pelo JPA, e o unico construtor de proposito: ninguem instancia esta classe
    }
}
