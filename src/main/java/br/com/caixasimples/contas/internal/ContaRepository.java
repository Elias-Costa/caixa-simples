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

    /**
     * Serializa o primeiro acesso, as mudanças de configuração e as inativações de usuário da mesma
     * Conta. A trava não barra a conferência de chave estrangeira de quem insere linhas da Conta.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Conta c where c.id = :id")
    Optional<Conta> buscarParaAtualizar(UUID id);

    /**
     * Se o acesso da Conta está suspenso. Perguntado a cada requisição autenticada, com a Conta do
     * token, e no login, com a Conta da credencial que acabou de conferir a senha: nos dois casos o id
     * é o que a própria aplicação gravou, e não um valor do pedido. A Conta que não existe responde
     * falso, e a recusa dela fica com a leitura do Usuário.
     */
    @Query("select count(c) > 0 from Conta c where c.id = :id and c.suspensaEm is not null")
    boolean estaSuspensa(UUID id);
}
