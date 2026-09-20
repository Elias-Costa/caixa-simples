package br.com.caixasimples.relatorios.internal;

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
 * <p>Toda consulta é filtrada automaticamente por {@code conta_id} pelo tenant declarado na
 * entidade, <strong>inclusive a JPQL abaixo</strong>. Não escreva {@code WHERE conta_id} à mão, e
 * não use query nativa: o filtro do Hibernate não alcança SQL nativo, e é essa a razão de a soma
 * ser feita em JPQL e não em SQL.
 */
public interface VendaParaRelatorioRepository extends Repository<VendaParaRelatorio, UUID> {

    /**
     * A soma e a contagem das vendas de um status cuja conclusão caiu no intervalo (RF21).
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
            """)
    TotaisDeVendas totaisEntre(@Param("status") StatusVenda status, @Param("inicio") Instant inicio,
            @Param("fim") Instant fim);
}
