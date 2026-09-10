package br.com.caixasimples.contas.internal;

import java.util.UUID;
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
}
