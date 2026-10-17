package br.com.caixasimples.contas.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/**
 * O aceite de uma versão dos termos de uso por um administrador da Conta. Entidade única, sem
 * membros, que só nasce: o aceite é prova, e prova não se edita.
 *
 * <p>Cada versão aceita é uma linha, para o aceite da versão nova não apagar a prova da anterior.
 * O banco recusa a segunda linha da mesma versão na Conta.
 *
 * <p>{@code contaId} é preenchido pelo Hibernate a partir do tenant do contexto, e não há
 * construtor que o receba (RNF05).
 */
@Entity
@Table(name = "aceite_dos_termos")
public class AceiteDosTermos {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "conta_id", nullable = false, updatable = false)
    private UUID contaId;

    @Column(nullable = false, updatable = false)
    private String versao;

    @Column(name = "aceito_em", nullable = false, updatable = false)
    private Instant aceitoEm;

    @Column(name = "aceito_por", nullable = false, updatable = false)
    private UUID aceitoPor;

    protected AceiteDosTermos() {
        // exigido pelo JPA
    }

    public AceiteDosTermos(String versao, UUID aceitoPor, Instant agora) {
        this.id = UUID.randomUUID();
        this.versao = Objects.requireNonNull(versao, "versao nao pode ser nula");
        this.aceitoPor = Objects.requireNonNull(aceitoPor, "aceitoPor nao pode ser nulo");
        this.aceitoEm = Objects.requireNonNull(agora, "agora nao pode ser nulo");
    }

    public UUID getId() {
        return id;
    }

    public UUID getContaId() {
        return contaId;
    }

    public String getVersao() {
        return versao;
    }

    public Instant getAceitoEm() {
        return aceitoEm;
    }

    public UUID getAceitoPor() {
        return aceitoPor;
    }
}
