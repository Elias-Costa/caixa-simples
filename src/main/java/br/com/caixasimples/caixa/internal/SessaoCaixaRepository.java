package br.com.caixasimples.caixa.internal;

import br.com.caixasimples.caixa.StatusSessaoCaixa;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repositorio da raiz de agregado {@code SessaoCaixa}.
 *
 * <p>Toda consulta aqui e filtrada automaticamente por {@code conta_id} pelo {@code @TenantId} —
 * inclusive {@link #findAll()} e {@link #findById(Object)}. Nao escreva {@code WHERE conta_id} a
 * mao, e nao use query nativa: o filtro do Hibernate nao alcanca SQL nativo.
 *
 * <p><strong>Cada metodo derivado nasce junto do caso de uso que o usa, nunca antes.</strong> O R06
 * nao trouxe nenhum: {@code save}, {@code findById} e {@code findAll} bastavam para schema e
 * persistencia. O R07 trouxe o primeiro, {@link #existsByUsuarioIdAndStatus}. O R08 trouxe as duas
 * consultas do historico.
 *
 * <p>{@code MovimentoCaixa} e membro do agregado e nunca tera repositorio proprio: e carregado e
 * alterado pela raiz (regra 3 do CLAUDE.md). A entidade dele nem sequer e visivel fora deste
 * pacote, entao a regra nao depende so de disciplina.
 */
public interface SessaoCaixaRepository extends JpaRepository<SessaoCaixaEntity, UUID> {

    /**
     * D22a — uma sessao ABERTA por operador. Sustenta a guarda de
     * {@code SessaoCaixaService.abrir}; a rede embaixo, para duas requisicoes simultaneas, e o
     * indice unico parcial da V6.
     *
     * <p>Sem {@code conta_id} na assinatura, como todo o resto: o {@code @TenantId} filtra, entao
     * o operador de outra conta nunca entra nesta contagem (RNF05).
     */
    boolean existsByUsuarioIdAndStatus(UUID usuarioId, StatusSessaoCaixa status);

    /**
     * RF16 — as sessoes de um dia, de todos os operadores da conta.
     *
     * <p><strong>O intervalo e semiaberto</strong>, e por isso {@code GreaterThanEqual} +
     * {@code LessThan} em vez do {@code Between} derivado: {@code Between} inclui os dois extremos,
     * e uma sessao aberta exatamente a meia-noite apareceria no historico de dois dias. Quem calcula
     * os dois instantes no fuso do balcao e {@code shared.FusoDeReferencia} (P6) — este metodo so
     * recebe o intervalo ja pronto, em UTC, que e como a coluna esta gravada.
     *
     * <p>Devolve {@link LinhaDoHistorico} e nao a entidade: e o que impede a consulta de arrastar os
     * movimentos {@code EAGER} de toda sessao do dia. Ver o javadoc daquele record.
     */
    List<LinhaDoHistorico> findByAbertaEmGreaterThanEqualAndAbertaEmLessThanOrderByAbertaEm(
            Instant inicio, Instant fim);

    /**
     * RF16 — as sessoes de um dia de <strong>um operador</strong>.
     *
     * <p>Metodo separado em vez de um parametro que aceita nulo: um {@code IS NULL} embutido na
     * consulta exigiria {@code @Query} — que o projeto ainda nao tem em lugar nenhum — e e onde o
     * Postgres reclama de parametro sem tipo. Duas assinaturas longas custam menos que um mecanismo
     * novo. Quem escolhe entre as duas e o caso de uso.
     */
    List<LinhaDoHistorico> findByUsuarioIdAndAbertaEmGreaterThanEqualAndAbertaEmLessThanOrderByAbertaEm(
            UUID usuarioId, Instant inicio, Instant fim);
}
