package br.com.caixasimples.relatorios.internal;

import br.com.caixasimples.vendas.StatusVenda;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * A consulta do ranking dos mais vendidos sobre {@link ItemVendaParaRelatorio}.
 *
 * <p>Herda de {@link Repository}, o marcador do Spring Data, e não de {@code JpaRepository}, pelo
 * motivo dado em {@link VendaParaRelatorioRepository}: sem {@code save}, {@code delete} nem
 * {@code findAll}, a assinatura é a primeira prova de que este módulo só lê. Não é um repositório
 * de item de venda, que é membro de agregado e nunca teve um; é o lugar da consulta agregada que
 * parte da tabela dele.
 *
 * <p>Toda consulta é filtrada por {@code conta_id} pelo tenant da entidade raiz, inclusive a
 * JPQL abaixo. Não escreva {@code WHERE conta_id} à mão, e não use query nativa: o filtro do
 * Hibernate não alcança SQL nativo.
 */
public interface ItemVendaParaRelatorioRepository extends Repository<ItemVendaParaRelatorio, UUID> {

    /**
     * Os produtos mais vendidos, por quantidade, entre as vendas de um status cuja conclusão caiu
     * no intervalo (RF22).
     *
     * <p><strong>As junções são por id, escritas na consulta</strong>, e não por associação
     * mapeada na entidade: a venda dá o status e o instante da conclusão, o produto dá o nome e a
     * unidade, e nenhum dos dois precisa ser navegável fora daqui. O filtro de tenant da entidade
     * raiz basta para o isolamento (RNF05): um item só se junta à sua própria venda e ao seu
     * próprio produto, que a chave estrangeira prende à mesma conta.
     *
     * <p><strong>O valor é somado com o mesmo arredondamento do domínio.</strong> Cada item vale
     * quantidade vezes preço, arredondado para centavos <em>naquele item</em>, menos o desconto;
     * é assim que o comprovante fecha linha a linha. O {@code round} de duas casas do Postgres
     * arredonda o meio para longe do zero, que é exatamente o meio para cima do domínio enquanto o
     * valor não é negativo, e quantidade, preço e desconto não são, por restrição da tabela.
     * Somar antes e arredondar depois daria outro número, em centavos, e o relatório deixaria de
     * bater com os comprovantes.
     *
     * <p><strong>O intervalo é semiaberto</strong>, como no faturamento, e o instante que delimita
     * o dia é o da conclusão da venda, pelo mesmo motivo de lá. Quem calcula os dois instantes no
     * fuso do balcão é {@code shared.FusoDeReferencia}.
     *
     * <p>Empate de quantidade sai em ordem de nome e, num empate de nome, de id, para o ranking
     * ser o mesmo a cada consulta. O {@link Limit} corta no banco, porque um ranking de dez não
     * precisa trazer o catálogo inteiro para descartar o resto em memória.
     *
     * <p><strong>O operador é opcional</strong> (RF24): com {@code usuarioId} nulo a condição
     * some, e o ranking é o da conta inteira. É o mesmo parâmetro, pelo mesmo motivo, de
     * {@link VendaParaRelatorioRepository#totaisEntre}. Não há filtro por forma de pagamento
     * aqui: um item não pertence a uma parcela, e o ranking não teria como repartir uma venda
     * dividida entre as formas.
     *
     * <p>O status é parâmetro, e não literal na consulta, para a regra de quais vendas contam
     * ficar escrita no caso de uso.
     */
    @Query("""
            select new br.com.caixasimples.relatorios.internal.ProdutoVendido(
                p.id, p.nome, p.unidade,
                sum(i.quantidade),
                sum(round(i.quantidade * i.precoUnitario, 2) - i.desconto))
            from ItemVendaParaRelatorio i
              join VendaParaRelatorio v on v.id = i.vendaId
              join ProdutoParaRelatorio p on p.id = i.produtoId
            where v.status = :status
              and v.concluidoEm >= :inicio
              and v.concluidoEm < :fim
              and (:usuarioId is null or v.usuarioId = :usuarioId)
            group by p.id, p.nome, p.unidade
            order by sum(i.quantidade) desc, p.nome asc, p.id asc
            """)
    List<ProdutoVendido> maisVendidosEntre(@Param("status") StatusVenda status,
            @Param("inicio") Instant inicio, @Param("fim") Instant fim,
            @Param("usuarioId") UUID usuarioId, Limit limite);
}
