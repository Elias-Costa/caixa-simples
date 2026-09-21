package br.com.caixasimples.relatorios.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * A consulta do fluxo de caixa sobre {@link MovimentoCaixaParaRelatorio}.
 *
 * <p>Herda de {@link Repository}, o marcador do Spring Data, pelo motivo dado em
 * {@link VendaParaRelatorioRepository}: sem método de escrita, a assinatura prova que o módulo só
 * lê. Não é um repositório de movimento de caixa, que é membro de agregado e nunca teve um; é o
 * lugar da soma que parte da tabela dele.
 *
 * <p>Toda consulta é filtrada por {@code conta_id} pelo tenant da entidade, inclusive a JPQL
 * abaixo. Não escreva {@code WHERE conta_id} à mão, e não use query nativa.
 */
public interface MovimentoCaixaParaRelatorioRepository
        extends Repository<MovimentoCaixaParaRelatorio, UUID> {

    /**
     * A soma dos movimentos de cada tipo lançados no intervalo (RF23).
     *
     * <p>Uma linha por tipo que teve movimento, e nenhuma para os que não tiveram: é o que um
     * agrupamento devolve, e é o serviço quem completa com zero. Os quatro tipos vêm juntos numa
     * consulta só porque o caixa guarda venda, sangria, suprimento e estorno numa tabela única,
     * justamente para o fechamento e este relatório serem uma soma, e não uma junção.
     *
     * <p><strong>O intervalo é semiaberto</strong> e o instante que o delimita é o do lançamento
     * do movimento, não a abertura da sessão: o fluxo de caixa responde em que dia o dinheiro
     * entrou ou saiu da gaveta, e uma sessão que vira a meia-noite reparte os movimentos entre os
     * dois dias. Quem calcula os dois instantes no fuso do balcão é
     * {@code shared.FusoDeReferencia}.
     */
    @Query("""
            select new br.com.caixasimples.relatorios.internal.TotalPorTipoDeMovimento(
                m.tipo, sum(m.valor))
            from MovimentoCaixaParaRelatorio m
            where m.criadoEm >= :inicio
              and m.criadoEm < :fim
            group by m.tipo
            """)
    List<TotalPorTipoDeMovimento> totaisPorTipoEntre(@Param("inicio") Instant inicio,
            @Param("fim") Instant fim);
}
