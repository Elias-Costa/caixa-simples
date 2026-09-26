package br.com.caixasimples.sincronizacao.internal;

import br.com.caixasimples.sincronizacao.application.ResultadoDaOperacao.Resultado;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repositório do registro de operações sincronizadas.
 *
 * <p>Toda consulta aqui é filtrada automaticamente por {@code conta_id} pelo {@code @TenantId}:
 * o mesmo id de operação em outra conta não é encontrado, e por isso não é devolvido nem
 * confundido com o desta (RNF05). Não escreva {@code WHERE conta_id} à mão, e não use query
 * nativa: o filtro do Hibernate não alcança SQL nativo.
 */
public interface OperacaoSincronizadaRepository
        extends JpaRepository<OperacaoSincronizada, UUID> {

    /** O resultado já gravado desta operação nesta conta, se houver. */
    Optional<OperacaoSincronizada> findByOperacaoId(UUID operacaoId);

    /** Os resultados já gravados das operações de que outra depende. */
    List<OperacaoSincronizada> findByOperacaoIdIn(Collection<UUID> operacaoIds);

    /**
     * As operações com o resultado diferente do dado que ninguém conferiu, da que chegou primeiro
     * para a mais recente. Quem chama passa a aplicada sem pendência, e recebe revisões e recusas.
     */
    List<OperacaoSincronizada> findByResultadoNotAndConferidaEmIsNullOrderByRecebidaEm(
            Resultado excluido);

    /**
     * As conferidas a partir do início, inclusivo, até o fim, exclusivo, pela ordem da
     * conferência.
     */
    List<OperacaoSincronizada> findByConferidaEmGreaterThanEqualAndConferidaEmLessThanOrderByConferidaEm(
            Instant inicio, Instant fim);
}
