package br.com.caixasimples.caixa.internal;

import br.com.caixasimples.caixa.StatusSessaoCaixa;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repositório da raiz de agregado {@code SessaoCaixa}.
 *
 * <p>Toda consulta aqui é filtrada automaticamente por {@code conta_id} pelo {@code @TenantId},
 * inclusive {@link #findAll()} e {@link #findById(Object)}. Não escreva {@code WHERE conta_id} à
 * mão, e não use query nativa: o filtro do Hibernate não alcança SQL nativo.
 *
 * <p><strong>Cada método derivado nasce junto do caso de uso que o usa, nunca antes.</strong>
 * Os três abaixo existem porque a abertura e o histórico precisam deles; enquanto o que o módulo
 * fazia cabia em {@code save}, {@code findById} e {@code findAll}, nenhum outro foi declarado.
 *
 * <p>{@code MovimentoCaixa} é membro do agregado e nunca terá repositório próprio: é carregado e
 * alterado pela raiz. A entidade dele nem sequer é visível fora deste pacote, então a regra não
 * depende apenas de disciplina.
 */
public interface SessaoCaixaRepository extends JpaRepository<SessaoCaixaEntity, UUID> {

    /**
     * Sustenta a regra de uma sessão ABERTA por operador, aplicada em
     * {@code SessaoCaixaService.abrir}. A rede embaixo, para duas requisições simultâneas, é o
     * índice único parcial da migration V6.
     *
     * <p>Sem {@code conta_id} na assinatura, como todo o resto: o {@code @TenantId} filtra, então
     * o operador de outra conta nunca entra nesta contagem (RNF05).
     */
    boolean existsByUsuarioIdAndStatus(UUID usuarioId, StatusSessaoCaixa status);

    /**
     * As sessões de um dia, de todos os operadores da conta (RF16).
     *
     * <p><strong>O intervalo é semiaberto</strong>, e por isso {@code GreaterThanEqual} com
     * {@code LessThan} em vez do {@code Between} derivado: {@code Between} inclui os dois extremos,
     * e uma sessão aberta exatamente à meia-noite apareceria no histórico de dois dias. Quem
     * calcula os dois instantes no fuso do balcão é {@code shared.FusoDeReferencia}; este método
     * recebe o intervalo já pronto, em UTC, que é como a coluna está gravada.
     *
     * <p>Devolve {@link LinhaDoHistorico} e não a entidade, o que impede a consulta de arrastar os
     * movimentos {@code EAGER} de toda sessão do dia. O javadoc daquele record explica o custo.
     */
    List<LinhaDoHistorico> findByAbertaEmGreaterThanEqualAndAbertaEmLessThanOrderByAbertaEm(
            Instant inicio, Instant fim);

    /**
     * As sessões de um dia de <strong>um operador</strong> (RF16).
     *
     * <p>Método separado em vez de um parâmetro que aceita nulo: um {@code IS NULL} embutido na
     * consulta exigiria {@code @Query}, que o projeto não usa em lugar nenhum, e é onde o Postgres
     * reclama de parâmetro sem tipo. Duas assinaturas longas custam menos que um mecanismo novo.
     * Quem escolhe entre as duas é o caso de uso.
     */
    List<LinhaDoHistorico> findByUsuarioIdAndAbertaEmGreaterThanEqualAndAbertaEmLessThanOrderByAbertaEm(
            UUID usuarioId, Instant inicio, Instant fim);
}
