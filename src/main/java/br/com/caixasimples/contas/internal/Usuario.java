package br.com.caixasimples.contas.internal;

import br.com.caixasimples.contas.Perfil;
import br.com.caixasimples.shared.ContaId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/**
 * Pessoa que acessa o sistema, sempre dentro de uma conta. Agregado de uma entidade só.
 *
 * <p>{@code contaId} é preenchido pelo Hibernate a partir do
 * {@code CurrentTenantIdentifierResolver}, e não há construtor que o receba, de propósito, porque
 * {@code contaId} nunca vem de fora da aplicação (RNF05). O filtro em toda leitura também é
 * automático, via {@link TenantId}.
 *
 * <p><strong>Não guarda dado de autenticação.</strong> E-mail e hash de senha vivem em
 * {@link Credencial}, de modo que a entidade que carrega {@code @TenantId} não carrega segredo, e o
 * e-mail tem uma casa só.
 */
@Entity
@Table(name = "usuario")
public class Usuario {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "conta_id", nullable = false, updatable = false)
    private UUID contaId;

    @Column(nullable = false)
    private String nome;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Perfil perfil;

    /** Soft delete: preserva o histórico de vendas do operador. */
    @Column(nullable = false)
    private boolean ativo;

    @Column(name = "criado_em", nullable = false, updatable = false)
    private Instant criadoEm;

    protected Usuario() {
        // exigido pelo JPA
    }

    public Usuario(String nome, Perfil perfil) {
        this.id = UUID.randomUUID();
        this.nome = exigirTexto(nome, "nome");
        this.perfil = Objects.requireNonNull(perfil, "perfil nao pode ser nulo");
        this.ativo = true;
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

    public ContaId getContaId() {
        return ContaId.de(contaId);
    }

    public String getNome() {
        return nome;
    }

    public Perfil getPerfil() {
        return perfil;
    }

    public boolean isAtivo() {
        return ativo;
    }

    public Instant getCriadoEm() {
        return criadoEm;
    }

    public void inativar() {
        this.ativo = false;
    }
}
