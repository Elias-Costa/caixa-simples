package br.com.caixasimples.contas.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repositorio de {@link Credencial}.
 *
 * <p>{@link #findByEmailIgnoreCase(String)} e a <strong>unica consulta do sistema que roda
 * legitimamente sem tenant no contexto</strong>. Isso nao e exceção à regra de isolamento: a
 * tabela {@code credencial} nao pertence a nenhuma conta — ela e o que descobre a conta. Ver
 * {@code .claude/rules/multi-tenancy.md}.
 *
 * <p>Toda consulta que dependa de conta deve usar {@link UsuarioRepository} ou os repositorios dos
 * demais modulos, que sao filtrados automaticamente.
 */
public interface CredencialRepository extends JpaRepository<Credencial, UUID> {

    Optional<Credencial> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);
}
