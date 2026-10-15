package br.com.caixasimples.contas.internal;

import br.com.caixasimples.shared.Perfil;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

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

    /**
     * Quantos administradores ativos a conta ainda tem: é o que impede inativar o último. Só decide
     * com a linha da Conta travada até o commit; sem ela, duas inativações simultâneas contariam os
     * mesmos administradores.
     */
    long countByPerfilAndAtivoTrue(Perfil perfil);

    /**
     * Serializa as revogações do mesmo usuário: sem a trava, duas revogações simultâneas leriam a
     * mesma versão de acesso e gravariam o mesmo número, e o token emitido por uma valeria depois da
     * outra. A consulta passa pelo filtro de tenant como qualquer outra deste repositório.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from Usuario u where u.id = :id")
    Optional<Usuario> buscarParaAtualizar(UUID id);

    /**
     * Todos os usuários da conta, travados, para revogar o acesso de todos de uma vez. A ordem pelo id
     * faz duas transações que travam os mesmos usuários pararem no primeiro, em vez de se esperarem.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from Usuario u order by u.id")
    List<Usuario> buscarTodosParaAtualizar();
}
