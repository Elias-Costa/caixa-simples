package br.com.caixasimples.contas.internal;

import br.com.caixasimples.contas.Plano;
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

/**
 * O negocio contratante — o tenant. Agregado de uma entidade so.
 *
 * <p><strong>Nao tem {@code @TenantId}, e isso e correto:</strong> o {@code id} desta entidade
 * <em>e</em> o tenant que as outras tabelas carregam em {@code conta_id}. Nao ha o que filtrar
 * aqui. Em troca, {@link ContaRepository} nunca e consultado com um id vindo de fora da
 * aplicacao — sempre a partir do contexto de tenant. Ver {@code .claude/rules/multi-tenancy.md}.
 */
@Entity
@Table(name = "conta")
public class Conta {

    @Id
    private UUID id;

    @Column(name = "nome_negocio", nullable = false)
    private String nomeNegocio;

    @Column(name = "tipo_negocio")
    private String tipoNegocio;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Plano plano;

    @Column(name = "estoque_habilitado", nullable = false)
    private boolean estoqueHabilitado;

    @Column(name = "criado_em", nullable = false, updatable = false)
    private Instant criadoEm;

    protected Conta() {
        // exigido pelo JPA
    }

    /**
     * @param tipoNegocio opcional — casa com {@code ModeloProduto.tipo_negocio} para sugerir o
     *                    catalogo inicial (RF32); nulo significa cadastro comecando em branco
     */
    public Conta(String nomeNegocio, String tipoNegocio, Plano plano) {
        this.id = UUID.randomUUID();
        this.nomeNegocio = exigirTexto(nomeNegocio, "nomeNegocio");
        this.tipoNegocio = tipoNegocio;
        this.plano = Objects.requireNonNull(plano, "plano nao pode ser nulo");
        this.estoqueHabilitado = false;
        this.criadoEm = Instant.now();
    }

    private static String exigirTexto(String valor, String campo) {
        if (valor == null || valor.isBlank()) {
            throw new IllegalArgumentException(campo + " nao pode ser vazio");
        }
        return valor.trim();
    }

    public ContaId contaId() {
        return ContaId.de(id);
    }

    public UUID getId() {
        return id;
    }

    public String getNomeNegocio() {
        return nomeNegocio;
    }

    public String getTipoNegocio() {
        return tipoNegocio;
    }

    public Plano getPlano() {
        return plano;
    }

    /** RF17 — negocio baseado em servico opera com o modulo de estoque desligado. */
    public boolean isEstoqueHabilitado() {
        return estoqueHabilitado;
    }

    public Instant getCriadoEm() {
        return criadoEm;
    }

    /** RF31 — a propria conta troca de plano. */
    public void trocarPlano(Plano novo) {
        this.plano = Objects.requireNonNull(novo, "plano nao pode ser nulo");
    }

    /** RF17 — liga/desliga o controle de estoque desta conta. */
    public void definirEstoqueHabilitado(boolean habilitado) {
        this.estoqueHabilitado = habilitado;
    }
}
