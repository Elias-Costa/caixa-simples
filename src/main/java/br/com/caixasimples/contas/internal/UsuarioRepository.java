package br.com.caixasimples.contas.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repositorio da raiz de agregado {@code Usuario}.
 *
 * <p>Toda consulta aqui e filtrada automaticamente por {@code conta_id} pelo {@code @TenantId} —
 * inclusive {@link #findAll()} e {@link #findById(Object)}. Nao escreva {@code WHERE conta_id}
 * a mao, e nao use query nativa: o filtro do Hibernate nao alcanca SQL nativo.
 *
 * <p>Busca por e-mail nao mora aqui: e-mail e dado de {@link CredencialRepository}, consultado
 * antes de existir tenant (D14d).
 */
public interface UsuarioRepository extends JpaRepository<Usuario, UUID> {

    List<Usuario> findByAtivoTrue();
}
