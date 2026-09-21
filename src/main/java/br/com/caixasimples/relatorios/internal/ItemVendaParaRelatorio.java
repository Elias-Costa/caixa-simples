package br.com.caixasimples.relatorios.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.TenantId;

/**
 * A tabela {@code item_venda} vista pelos relatórios: um segundo mapeamento da mesma tabela, com as
 * colunas que o ranking dos mais vendidos lê e nada mais.
 *
 * <p>Segue o mesmo desenho de {@link VendaParaRelatorio}, e pelos mesmos motivos: a entidade do
 * módulo de vendas mora no pacote interno daquele módulo, e um relatório lê colunas e soma, sem
 * precisar das invariantes que a raiz do agregado protege. Somente leitura garantido em três
 * pontos, {@link Immutable}, repositório sem método de escrita e nenhum construtor com argumentos.
 *
 * <p>Não há referência navegável para a venda nem para o produto: as junções são feitas por id, à
 * mão, na consulta que precisa delas. Item de venda é membro do agregado Venda e nunca teve
 * repositório próprio; o que existe aqui não é um repositório do item, é uma consulta agregada
 * que parte dele.
 *
 * <p>{@code contaId} filtra toda consulta pelo {@link TenantId}. É o filtro desta entidade, a raiz
 * da consulta, que isola o ranking por conta (RNF05); as junções por id de venda e de produto
 * alcançam só linhas que a chave estrangeira já prende à mesma conta.
 */
@Entity
@Immutable
@Table(name = "item_venda")
public class ItemVendaParaRelatorio {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "conta_id", nullable = false, updatable = false)
    private UUID contaId;

    @Column(name = "venda_id", nullable = false)
    private UUID vendaId;

    @Column(name = "produto_id", nullable = false)
    private UUID produtoId;

    /** Sempre positiva, com três casas: é o que se soma para dizer o que mais saiu. */
    @Column(nullable = false)
    private BigDecimal quantidade;

    /** Cópia do preço no momento da venda; reajuste posterior do produto não muda o relatório. */
    @Column(name = "preco_unitario", nullable = false)
    private BigDecimal precoUnitario;

    /** Desconto deste item; zero quando não há, nunca nulo. */
    @Column(nullable = false)
    private BigDecimal desconto;

    protected ItemVendaParaRelatorio() {
        // exigido pelo JPA, e o unico construtor de proposito: ninguem instancia esta classe
    }
}
