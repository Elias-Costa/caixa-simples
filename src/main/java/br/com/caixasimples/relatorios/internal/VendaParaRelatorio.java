package br.com.caixasimples.relatorios.internal;

import br.com.caixasimples.vendas.StatusVenda;
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
 * A tabela {@code venda} vista pelos relatórios: um segundo mapeamento da mesma tabela, com as
 * colunas que o faturamento e os seus filtros leem e nada mais.
 *
 * <p><strong>Por que um mapeamento próprio, e não a entidade do módulo de vendas.</strong> A
 * entidade de lá mora no pacote interno daquele módulo, e a fronteira entre módulos proíbe
 * importá-la. Pedir a soma ao módulo de vendas pela API pública dele faria a consulta de
 * relatório viver em vendas, e o mesmo se repetiria em caixa e em cadastro a cada relatório novo.
 * Este módulo lê o dado dos outros direto do banco, sem remontar agregado nenhum: uma consulta de
 * relatório lê colunas e soma, e não precisa das invariantes que a raiz protege. O esquema é o
 * contrato entre os dois mapeamentos, e ele é versionado nas migrations.
 *
 * <p><strong>Somente leitura, garantido em três pontos.</strong> {@link Immutable} faz o Hibernate
 * ignorar qualquer alteração numa instância carregada; o repositório desta classe não oferece
 * {@code save} nem {@code delete}; e não existe construtor com argumentos, então nenhum código
 * consegue instanciá-la para gravar. Este módulo nunca escreve em outro, e isso não depende de
 * disciplina.
 *
 * <p><strong>Custo aceito:</strong> renomear ou remover uma destas colunas no módulo de vendas
 * derruba a subida da aplicação aqui, porque o Hibernate valida o esquema. É o modo certo de
 * falhar: alto, no deploy, e não com um relatório em branco.
 *
 * <p>{@code contaId} filtra toda consulta automaticamente pelo {@link TenantId}, inclusive a
 * agregada em JPQL. Relatório é sempre por conta (RNF05), e não há assinatura por onde informar
 * outra.
 */
@Entity
@Immutable
@Table(name = "venda")
public class VendaParaRelatorio {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "conta_id", nullable = false, updatable = false)
    private UUID contaId;

    /**
     * O operador que fez a venda, para o filtro por operador (RF24). Só o id: o nome é do módulo
     * de contas, e a tela que oferece o filtro já o tem.
     */
    @Column(name = "usuario_id", nullable = false, updatable = false)
    private UUID usuarioId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StatusVenda status;

    /** Já líquido dos descontos de item e de venda: é o que o cliente pagou. */
    @Column(name = "valor_total", nullable = false)
    private BigDecimal valorTotal;

    /**
     * O instante que delimita o dia do faturamento: quando os pagamentos fecharam a conta. Nulo
     * na comanda ABERTA e na CANCELADA que nunca concluiu; gravado uma vez e nunca alterado, nem
     * pelo cancelamento.
     */
    @Column(name = "concluido_em")
    private Instant concluidoEm;

    protected VendaParaRelatorio() {
        // exigido pelo JPA, e o unico construtor de proposito: ninguem instancia esta classe
    }
}
