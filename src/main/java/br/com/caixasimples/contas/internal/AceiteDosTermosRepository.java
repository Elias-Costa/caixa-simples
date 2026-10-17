package br.com.caixasimples.contas.internal;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repositório do aceite dos termos de uso, entidade única sem membros.
 *
 * <p>Toda consulta aqui é filtrada automaticamente por {@code conta_id} pelo {@code @TenantId}: o
 * aceite de outra Conta não existe para esta. Não escreva {@code WHERE conta_id} à mão, e não use
 * query nativa: o filtro do Hibernate não alcança SQL nativo.
 */
public interface AceiteDosTermosRepository extends JpaRepository<AceiteDosTermos, UUID> {

    /** Se a Conta do contexto já aceitou esta versão. */
    boolean existsByVersao(String versao);
}
