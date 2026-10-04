package br.com.caixasimples.contas.internal;

import br.com.caixasimples.contas.Plano;
import br.com.caixasimples.contas.RecursoDoPlano;
import br.com.caixasimples.contas.SituacaoDoPlano;
import br.com.caixasimples.shared.ContaId;
import br.com.caixasimples.shared.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * O negócio contratante, isto é, o tenant. Agregado de uma entidade só.
 *
 * <p><strong>Não tem {@code @TenantId}, e isso é correto:</strong> o {@code id} desta entidade
 * <em>é</em> o tenant que as outras tabelas carregam em {@code conta_id}, então não há o que
 * filtrar aqui. Em troca, {@link ContaRepository} nunca é consultado com um id vindo de fora da
 * aplicação, e sim sempre a partir do contexto de tenant.
 *
 * <p>O plano pago tem ciclo mensal: o dia do vencimento e o próximo vencimento, os dois nulos no
 * plano gratuito, e o banco recusa um sem o outro. O plano só muda por aqui, pela aplicação de um
 * pedido de plano, e as datas seguem {@link CicloDeVencimento}.
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

    /** O dia do mês da adesão, em que o plano pago vence. */
    @Column(name = "dia_de_vencimento")
    private Integer diaDeVencimento;

    /** O fim, exclusive, do último período pago. */
    @Column(name = "proximo_vencimento")
    private LocalDate proximoVencimento;

    @Column(name = "estoque_habilitado", nullable = false)
    private boolean estoqueHabilitado;

    @Column(name = "catalogo_inicial_aplicado", nullable = false)
    private boolean catalogoInicialAplicado;

    @Column(name = "criado_em", nullable = false, updatable = false)
    private Instant criadoEm;

    protected Conta() {
        // exigido pelo JPA
    }

    /**
     * @param tipoNegocio opcional; casa com {@code ModeloProduto.tipo_negocio} para sugerir o
     *                    catálogo inicial (RF32), e nulo significa cadastro começando em branco
     */
    public Conta(String nomeNegocio, String tipoNegocio, Plano plano) {
        this.id = UUID.randomUUID();
        this.nomeNegocio = exigirTexto(nomeNegocio, "nomeNegocio");
        this.tipoNegocio = tipoNegocio;
        this.plano = Objects.requireNonNull(plano, "plano nao pode ser nulo");
        this.estoqueHabilitado = false;
        this.catalogoInicialAplicado = false;
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

    /** Negócio baseado em serviço opera com o módulo de estoque desligado (RF17). */
    public boolean isEstoqueHabilitado() {
        return estoqueHabilitado;
    }

    public boolean isCatalogoInicialAplicado() {
        return catalogoInicialAplicado;
    }

    public void marcarCatalogoInicialAplicado() {
        if (catalogoInicialAplicado) {
            throw new IllegalStateException("catalogo inicial desta conta ja foi aplicado");
        }
        this.catalogoInicialAplicado = true;
    }

    public Instant getCriadoEm() {
        return criadoEm;
    }

    /** Nulo no plano gratuito. */
    public Integer getDiaDeVencimento() {
        return diaDeVencimento;
    }

    /** Nulo no plano gratuito. */
    public LocalDate getProximoVencimento() {
        return proximoVencimento;
    }

    /** Onde a Conta está no ciclo do plano pago, no dia de hoje do balcão. */
    public SituacaoDoPlano situacao(LocalDate hoje) {
        if (plano == Plano.GRATIS) {
            return SituacaoDoPlano.SEM_MENSALIDADE;
        }
        return CicloDeVencimento.situacao(proximoVencimento, hoje);
    }

    /**
     * Se o recurso vale hoje: o plano o inclui e não está suspenso. Na tolerância depois do
     * vencimento, ainda vale.
     */
    public boolean temRecurso(RecursoDoPlano recurso, LocalDate hoje) {
        return plano.inclui(recurso) && situacao(hoje) != SituacaoDoPlano.SUSPENSO;
    }

    /** Os recursos que valem hoje, para o aplicativo decidir o que mostrar. */
    public Set<RecursoDoPlano> recursos(LocalDate hoje) {
        Set<RecursoDoPlano> ativos = EnumSet.noneOf(RecursoDoPlano.class);
        for (RecursoDoPlano recurso : RecursoDoPlano.values()) {
            if (temRecurso(recurso, hoje)) {
                ativos.add(recurso);
            }
        }
        return ativos;
    }

    /** O primeiro dia sem os recursos pagos se não houver renovação. Nulo no plano gratuito. */
    public LocalDate inicioDaSuspensao() {
        if (plano == Plano.GRATIS) {
            return null;
        }
        return CicloDeVencimento.inicioDaSuspensao(proximoVencimento);
    }

    /** O período que uma renovação pedida hoje paga. */
    public CicloDeVencimento.Periodo periodoDaRenovacao(LocalDate hoje) {
        exigirPlanoPago();
        return CicloDeVencimento.periodoDaRenovacao(proximoVencimento, diaDeVencimento, hoje);
    }

    /** O valor do upgrade pedido hoje, pela diferença mensal entre os planos. */
    public Money valorDoUpgrade(Money diferencaMensal, LocalDate hoje) {
        exigirPlanoPago();
        return CicloDeVencimento.valorDoUpgrade(diferencaMensal, proximoVencimento, diaDeVencimento,
                hoje);
    }

    /**
     * Começa o plano pago, com o primeiro período a partir de hoje: o dia de hoje vira o dia do
     * vencimento de todos os meses.
     *
     * @return o primeiro período pago
     * @throws IllegalStateException se a Conta já tem plano pago
     */
    public CicloDeVencimento.Periodo ativarPlano(Plano novo, LocalDate hoje) {
        Objects.requireNonNull(novo, "plano nao pode ser nulo");
        if (novo == Plano.GRATIS) {
            throw new IllegalArgumentException("o plano gratuito nao se ativa");
        }
        if (plano != Plano.GRATIS) {
            throw new IllegalStateException("a conta ja tem o plano " + plano.nomeExibido()
                    + "; a adesao vale so para o plano gratuito");
        }
        this.plano = novo;
        this.diaDeVencimento = hoje.getDayOfMonth();
        this.proximoVencimento = CicloDeVencimento.seguinte(hoje, diaDeVencimento);
        return new CicloDeVencimento.Periodo(hoje, proximoVencimento);
    }

    /**
     * Passa do plano intermediário ao completo sem mudar o vencimento: a diferença paga cobre os
     * dias que faltavam, e o período seguinte já se renova no plano completo.
     *
     * @throws IllegalStateException se a Conta não está no plano intermediário
     */
    public void subirParaCompleto() {
        if (plano != Plano.CAIXA_SIMPLES) {
            throw new IllegalStateException("o upgrade vale so para o plano Caixa Simples; a conta"
                    + " esta no plano " + plano.nomeExibido());
        }
        this.plano = Plano.COMPLETO;
    }

    /**
     * Registra o período pago pela renovação. O dia do vencimento não muda, nem com atraso: o
     * período pago é o que venceu, e não um mês a partir do pagamento.
     *
     * @param fim o fim, exclusive, do período pago, que passa a ser o próximo vencimento
     * @throws IllegalStateException se a Conta mudou de plano desde o pedido, ou se o período já
     *         estava pago
     */
    public void renovar(Plano renovado, LocalDate fim) {
        exigirPlanoPago();
        if (renovado != plano) {
            throw new IllegalStateException("a renovacao e do plano " + renovado.nomeExibido()
                    + ", e a conta esta no plano " + plano.nomeExibido());
        }
        if (!fim.isAfter(proximoVencimento)) {
            throw new IllegalStateException("o periodo desta renovacao ja esta pago");
        }
        this.proximoVencimento = fim;
    }

    private void exigirPlanoPago() {
        if (plano == Plano.GRATIS) {
            throw new IllegalStateException("o plano gratuito nao tem ciclo de vencimento");
        }
    }

    /** Liga ou desliga o controle de estoque desta conta (RF17). */
    public void definirEstoqueHabilitado(boolean habilitado) {
        this.estoqueHabilitado = habilitado;
    }
}
