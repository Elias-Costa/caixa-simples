package br.com.caixasimples.relatorios.internal;

import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.pagamentos.StatusPagamento;
import br.com.caixasimples.vendas.StatusVenda;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * As consultas do faturamento sobre {@link VendaParaRelatorio}.
 *
 * <p><strong>Herda de {@link Repository}, o marcador do Spring Data, e não de
 * {@code JpaRepository}</strong>, de propósito: assim a interface não tem {@code save},
 * {@code delete} nem {@code findAll}. Este módulo só lê, e a assinatura do repositório é a
 * primeira prova disso, antes de qualquer revisão. Todo repositório de raiz de agregado do sistema
 * herda o completo, porque lá se grava; aqui não há o que gravar.
 *
 * <p><strong>Por que os filtros são parâmetros opcionais na JPQL, e não Specification.</strong>
 * O executor de Specification do Spring Data traz {@code update} e {@code delete} na interface,
 * o que desfaria a garantia acima, e não agrega: devolve entidades, não somas. Os filtros
 * combináveis dos relatórios (RF24) são dois, operador e forma de pagamento, sobre consultas que
 * já são agregações; cada um entra como condição que some quando o parâmetro é nulo, e cada
 * relatório continua uma consulta só, legível de ponta a ponta.
 *
 * <p>Toda consulta é filtrada automaticamente por {@code conta_id} pelo tenant declarado na
 * entidade, <strong>inclusive as JPQL abaixo</strong>. Não escreva {@code WHERE conta_id} à mão, e
 * não use query nativa: o filtro do Hibernate não alcança SQL nativo, e é essa a razão de a soma
 * ser feita em JPQL e não em SQL.
 */
public interface VendaParaRelatorioRepository extends Repository<VendaParaRelatorio, UUID> {

    /**
     * A soma e a contagem das vendas de um status cuja conclusão caiu no intervalo, de todos os
     * operadores ou de um só (RF21, RF24).
     *
     * <p><strong>É a primeira consulta escrita à mão do projeto</strong>, e vale dizer por quê.
     * Todo o resto usa método derivado do nome, que basta para filtrar e ordenar; método derivado
     * não expressa uma soma. A alternativa seria trazer uma projeção por venda e somar em Java, o
     * que faria um relatório de um ano carregar todas as vendas do ano para somar duas casas
     * decimais. A soma é do banco.
     *
     * <p><strong>O intervalo é semiaberto</strong>: {@code >=} no início e {@code <} no fim, como
     * no histórico do caixa, para uma venda concluída exatamente à meia-noite entrar num dia só.
     * Quem calcula os dois instantes no fuso do balcão é {@code shared.FusoDeReferencia}; este
     * método recebe o intervalo já pronto, em UTC, que é como a coluna está gravada.
     *
     * <p><strong>O operador é opcional</strong>: com {@code usuarioId} nulo a condição some, e a
     * consulta é a mesma de antes do filtro. É um parâmetro que aceita nulo, em vez de uma segunda
     * assinatura, porque os filtros se combinam e cada combinação viraria mais uma consulta igual
     * às outras. O Hibernate dá tipo ao parâmetro pela comparação com a coluna, na mesma condição,
     * e é isso que evita a reclamação do Postgres sobre parâmetro sem tipo num {@code is null}
     * solto.
     *
     * <p>Sempre devolve uma linha, mesmo sem venda nenhuma no intervalo: uma agregação sem
     * agrupamento é assim. O {@code total} dessa linha é nulo, e o javadoc de
     * {@link TotaisDeVendas} explica.
     *
     * <p>O status é parâmetro, e não literal na consulta, para a regra de quais vendas contam
     * ficar escrita no caso de uso, ao lado da regra de qual instante delimita o dia.
     */
    @Query("""
            select new br.com.caixasimples.relatorios.internal.TotaisDeVendas(
                sum(v.valorTotal), count(v))
            from VendaParaRelatorio v
            where v.status = :status
              and v.concluidoEm >= :inicio
              and v.concluidoEm < :fim
              and (:usuarioId is null or v.usuarioId = :usuarioId)
            """)
    TotaisDeVendas totaisEntre(@Param("status") StatusVenda status, @Param("inicio") Instant inicio,
            @Param("fim") Instant fim, @Param("usuarioId") UUID usuarioId);

    /**
     * A soma das parcelas de uma forma de pagamento, e a contagem das vendas que as têm, entre as
     * vendas de um status cuja conclusão caiu no intervalo, de todos os operadores ou de um só
     * (RF24).
     *
     * <p><strong>O que se soma aqui é a parcela, e não a venda.</strong> Uma venda paga metade em
     * dinheiro e metade em Pix se reparte entre as duas formas pelo valor de cada parcela; somar o
     * total da venda em cada forma que ela usou faria dinheiro mais Pix mais cartão passar do
     * faturamento. Por isso a consulta junta {@link PagamentoParaRelatorio} por id de venda,
     * escrito na consulta e não por associação mapeada, e soma {@code p.valor}. A venda contada
     * é distinta, porque uma venda pode ter duas parcelas na mesma forma.
     *
     * <p>Só a parcela do status pedido entra na soma; o caso de uso passa CONFIRMADO, porque
     * PENDENTE ainda não entrou e RECUSADO nunca entrou. As vendas são as mesmas de
     * {@link #totaisEntre}: mesmo status, mesmo intervalo semiaberto pelo instante da conclusão,
     * mesmo operador opcional, pelos mesmos motivos.
     *
     * <p>O filtro de tenant da entidade raiz basta para o isolamento (RNF05): uma venda só se
     * junta às suas próprias parcelas, que a chave estrangeira prende à mesma conta.
     */
    @Query("""
            select new br.com.caixasimples.relatorios.internal.TotaisDeVendas(
                sum(p.valor), count(distinct v.id))
            from VendaParaRelatorio v
              join PagamentoParaRelatorio p on p.vendaId = v.id
            where v.status = :status
              and v.concluidoEm >= :inicio
              and v.concluidoEm < :fim
              and p.forma = :forma
              and (p.status = :statusDaParcela
                   or (p.forma = :fiado and p.status = :pendente))
              and (:usuarioId is null or v.usuarioId = :usuarioId)
            """)
    TotaisDeVendas totaisPorFormaEntre(@Param("status") StatusVenda status,
            @Param("forma") FormaPagamento forma,
            @Param("statusDaParcela") StatusPagamento statusDaParcela,
            @Param("fiado") FormaPagamento fiado,
            @Param("pendente") StatusPagamento pendente,
            @Param("inicio") Instant inicio, @Param("fim") Instant fim,
            @Param("usuarioId") UUID usuarioId);
}
