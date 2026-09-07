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
 * Ponto de entrada do login: mapeia um e-mail para o usuario e a conta dele (decisao D9).
 *
 * <p><strong>Nao tem {@code @TenantId}, e isso e o proposito da entidade.</strong> No login ainda
 * nao existe conta no contexto; uma consulta filtrada por tenant devolveria vazio e a autenticacao
 * nunca funcionaria. Esta nao e uma tabela sem isolamento — e a tabela que <em>resolve</em> o
 * isolamento, e por isso nao pode estar sujeita a ele.
 *
 * <p>Cuidado ao ler o campo {@code contaId} aqui: e <strong>dado</strong>, nao discriminador de
 * tenant. E exatamente o valor que o login descobre para popular o {@code TenantContext} e so
 * entao resolver o resto sob filtro normal.
 *
 * <p>Um e-mail pertence a exatamente uma conta (decisao P3), garantido por indice unico global
 * sobre {@code lower(email)}. E um login por usuario, por indice unico em {@code usuario_id}.
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
     * @param senhaHash hash BCrypt ja calculado — esta classe nunca recebe senha em texto puro
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
