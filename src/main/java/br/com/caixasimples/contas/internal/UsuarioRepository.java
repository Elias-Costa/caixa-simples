package br.com.caixasimples.contas.internal;

import br.com.caixasimples.shared.Perfil;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repositório da raiz de agregado {@code Usuario}.
 *
 * <p>Toda consulta aqui é filtrada automaticamente por {@code conta_id} pelo {@code @TenantId},
 * inclusive {@link #findAll()} e {@link #findById(Object)}. Não escreva {@code WHERE conta_id} à
 * mão, e não use query nativa: o filtro do Hibernate não alcança SQL nativo.
 *
 * <p>Busca por e-mail não mora aqui, porque e-mail é dado de {@link CredencialRepository},
 * consultado antes de existir tenant.
 */
public interface UsuarioRepository extends JpaRepository<Usuario, UUID> {

    List<Usuario> findByAtivoTrue();

    /** Quantos administradores ativos a conta ainda tem: é o que impede inativar o último. */
    long countByPerfilAndAtivoTrue(Perfil perfil);
}
