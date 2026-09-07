package br.com.caixasimples.contas.internal;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repositorio da raiz de agregado {@code Conta}.
 *
 * <p>Atencao: {@code Conta} nao tem {@code @TenantId} (o {@code id} dela <em>e</em> o tenant),
 * entao <strong>este repositorio nao e protegido por filtro automatico</strong>. Consulte-o
 * sempre com o id vindo de {@code TenantContext.exigirAtual()}, nunca com id recebido em
 * requisicao — caso contrario e IDOR direto.
 */
public interface ContaRepository extends JpaRepository<Conta, UUID> {
}
