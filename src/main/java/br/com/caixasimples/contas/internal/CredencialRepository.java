package br.com.caixasimples.contas.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repositório de {@link Credencial}.
 *
 * <p>{@link #findByEmailIgnoreCase(String)} é a <strong>única consulta do sistema que roda
 * legitimamente sem tenant no contexto</strong>. Isso não é exceção à regra de isolamento: a tabela
 * {@code credencial} não pertence a nenhuma conta, porque ela é o que descobre a conta.
 *
 * <p>Toda consulta que dependa de conta deve usar {@link UsuarioRepository} ou os repositórios dos
 * demais módulos, que são filtrados automaticamente.
 */
public interface CredencialRepository extends JpaRepository<Credencial, UUID> {

    Optional<Credencial> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);
}
