package br.com.caixasimples.contas.internal;

import br.com.caixasimples.shared.ContaId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Ponto de entrada do login: mapeia um e-mail para o usuário e a conta dele.
 *
 * <p><strong>Não tem {@code @TenantId}, e isso é o propósito da entidade.</strong> No login ainda
 * não existe conta no contexto, e uma consulta filtrada por tenant devolveria vazio, de modo que a
 * autenticação nunca funcionaria. Esta não é uma tabela sem isolamento: é a tabela que
 * <em>resolve</em> o isolamento, e por isso não pode estar sujeita a ele.
 *
 * <p>Cuidado ao ler o campo {@code contaId} aqui, porque ele é <strong>dado</strong>, não
 * discriminador de tenant. É exatamente o valor que o login descobre para popular o
 * {@code TenantContext} e só então resolver o resto sob filtro normal.
 *
 * <p>Um e-mail pertence a exatamente uma conta, garantido por índice único global sobre
 * {@code lower(email)}, e há um login por usuário, por índice único em {@code usuario_id}.
 */
@Entity
@Table(name = "credencial")
public class Credencial {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String email;

    /** BCrypt. Nunca a senha em texto puro. */
    @Column(name = "senha_hash", nullable = false)
    private String senhaHash;

    @Column(name = "usuario_id", nullable = false, updatable = false)
    private UUID usuarioId;

    @Column(name = "conta_id", nullable = false, updatable = false)
    private UUID contaId;

    @Column(name = "criado_em", nullable = false, updatable = false)
    private Instant criadoEm;

    protected Credencial() {
        // exigido pelo JPA
    }

    /**
     * @param senhaHash hash BCrypt já calculado; esta classe nunca recebe senha em texto puro
     */
    public Credencial(String email, String senhaHash, UUID usuarioId, ContaId contaId) {
        this.id = UUID.randomUUID();
        this.email = exigirTexto(email, "email").toLowerCase();
        this.senhaHash = exigirTexto(senhaHash, "senhaHash");
        this.usuarioId = Objects.requireNonNull(usuarioId, "usuarioId nao pode ser nulo");
        this.contaId = Objects.requireNonNull(contaId, "contaId nao pode ser nulo").valor();
        this.criadoEm = Instant.now();
    }

    private static String exigirTexto(String valor, String campo) {
        if (valor == null || valor.isBlank()) {
            throw new IllegalArgumentException(campo + " nao pode ser vazio");
        }
        return valor.trim();
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getSenhaHash() {
        return senhaHash;
    }

    public UUID getUsuarioId() {
        return usuarioId;
    }

    public ContaId getContaId() {
        return ContaId.de(contaId);
    }

    public Instant getCriadoEm() {
        return criadoEm;
    }

    public void trocarSenha(String novoHash) {
        this.senhaHash = exigirTexto(novoHash, "senhaHash");
    }
}
