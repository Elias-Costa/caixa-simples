package br.com.caixasimples.relatorios.internal;

import br.com.caixasimples.caixa.TipoMovimentoCaixa;
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
 * A tabela {@code movimento_caixa} vista pelos relatórios: o que o fluxo de caixa soma, e nada
 * mais.
 *
 * <p>Mesmo desenho de {@link VendaParaRelatorio}: a entidade do módulo do caixa mora no pacote
 * interno daquele módulo, e o fluxo de caixa é uma soma por tipo, que não precisa remontar a
 * sessão. Somente leitura garantido em três pontos, {@link Immutable}, repositório sem método de
 * escrita e nenhum construtor com argumentos.
 *
 * <p>Sem {@code sessao_caixa_id}, de propósito: o período do fluxo é delimitado pelo instante do
 * movimento, não pelo dia da sessão, então a consulta não precisa saber de que expediente cada
 * movimento veio. Uma sessão que atravessa a meia-noite tem os movimentos repartidos entre os dois
 * dias, e isso é o que o relatório quer dizer: o dia em que o dinheiro entrou ou saiu da gaveta.
 *
 * <p>O tipo é o mesmo enum que o módulo do caixa usa, tomado do pacote-base dele, como o status
 * da venda é tomado do pacote-base de vendas. Quem carrega o sinal é o tipo, e o valor é sempre
 * positivo; a regra de qual tipo soma e qual subtrai fica no caso de uso, num switch exaustivo.
 */
@Entity
@Immutable
@Table(name = "movimento_caixa")
public class MovimentoCaixaParaRelatorio {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "conta_id", nullable = false, updatable = false)
    private UUID contaId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TipoMovimentoCaixa tipo;

    /** Sempre positivo; o sinal vem do tipo. */
    @Column(nullable = false)
    private BigDecimal valor;

    /** O instante do lançamento, em UTC: é o que delimita o período do fluxo de caixa. */
    @Column(name = "criado_em", nullable = false)
    private Instant criadoEm;

    protected MovimentoCaixaParaRelatorio() {
        // exigido pelo JPA, e o unico construtor de proposito: ninguem instancia esta classe
    }
}
