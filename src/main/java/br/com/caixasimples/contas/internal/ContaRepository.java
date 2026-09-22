package br.com.caixasimples.contas.internal;

import java.util.UUID;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repositório da raiz de agregado {@code Conta}.
 *
 * <p>Atenção: {@code Conta} não tem {@code @TenantId}, porque o {@code id} dela <em>é</em> o
 * tenant, e portanto <strong>este repositório não é protegido por filtro automático</strong>.
 * Consulte-o sempre com o id vindo de {@code TenantContext.exigirAtual()}, nunca com um id recebido
 * na requisição; caso contrário, é acesso direto a objeto de outra conta.
 */
public interface ContaRepository extends JpaRepository<Conta, UUID> {

    /** Serializa dois primeiros logins da mesma Conta para o catálogo não ser copiado duas vezes. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Conta c where c.id = :id")
    Optional<Conta> buscarParaPrimeiroAcesso(UUID id);
}
