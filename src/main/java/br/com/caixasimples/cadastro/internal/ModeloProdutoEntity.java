package br.com.caixasimples.cadastro.internal;

import br.com.caixasimples.cadastro.TipoProduto;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
import org.hibernate.type.SqlTypes;

/**
 * Linha do catalogo sugerido por tipo de negocio (RF32) — passo R05 do roteiro, etapa 1.3 do plano.
 *
 * <p><strong>Nao tem {@link TenantId}, e a ausencia e o ponto desta classe.</strong> E dado de
 * referencia da plataforma, nao de negocio de nenhuma conta: junto de {@code Conta} (cujo id
 * <em>e</em> o tenant) e {@code Credencial} (consultada antes de existir tenant), fecha as tres — e
 * so tres — excecoes ao filtro de tenant descritas em {@code .claude/rules/multi-tenancy.md}. O
 * isolamento continua de pe porque estas linhas sao <em>copiadas</em> para {@code produto}, nunca
 * referenciadas ao vivo; depois de copiado, o item pertence a conta como qualquer outro produto.
 *
 * <p>Nao ha {@code domain/} nem mapper aqui, pelo mesmo julgamento da P7 aplicado a
 * {@code Cliente}: uma linha de referencia sem invariante nenhuma a proteger nao ganha estrutura de
 * agregado. Ela nao muda depois de gravada — nao existe setter, nem metodo de alteracao.
 *
 * <p>Publica, e nao com visibilidade de pacote como {@code ClienteEntity}, porque
 * {@code application.CatalogoInicialService} le estas linhas de outro pacote do mesmo modulo. Para
 * fora do modulo nada disso e alcancavel: pelo Modulith, todo subpacote e interno.
 */
@Entity
@Table(name = "modelo_produto")
public class ModeloProdutoEntity {

    @Id
    private UUID id;

    /** Casa com {@code Conta.tipo_negocio} ignorando maiuscula/minuscula (D20e). */
    @Column(name = "tipo_negocio", nullable = false)
    private String tipoNegocio;

    @Column(nullable = false)
    private String nome;

    private String categoria;

    private String unidade;

    /**
     * D20c — nao estava no modelo de dados §3 e precisou entrar: {@code produto.tipo} e
     * {@code NOT NULL}, e sem esta coluna um corte de cabelo seria copiado como
     * {@link TipoProduto#PRODUTO}, carregando estoque que nao existe (RF17/P4).
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TipoProduto tipo;

    /** RF02 — copiado para {@code produto.atributos} junto com o resto da linha. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "atributos_sugeridos", nullable = false)
    private Map<String, Object> atributosSugeridos;

    protected ModeloProdutoEntity() {
        // exigido pelo JPA
    }

    /**
     * Visibilidade de pacote de proposito: <strong>em producao ninguem chama isto</strong>. As
     * linhas de {@code modelo_produto} vem da migration (D20a) e so de la — o catalogo e dado de
     * referencia versionado junto do schema, nao algo que a aplicacao escreve.
     *
     * <p>Existe para o teste do modulo montar o catalogo de um tipo de negocio ficticio, e provar
     * que contas de tipos diferentes recebem catalogos diferentes sem depender do conteudo de
     * fabrica — que hoje tem um tipo so (D20b) e mudaria a cada catalogo novo.
     */
    ModeloProdutoEntity(String tipoNegocio, String nome, String categoria, String unidade,
            TipoProduto tipo, Map<String, Object> atributosSugeridos) {
        this.id = UUID.randomUUID();
        this.tipoNegocio = tipoNegocio;
        this.nome = nome;
        this.categoria = categoria;
        this.unidade = unidade;
        this.tipo = tipo;
        this.atributosSugeridos = copiar(atributosSugeridos);
    }

    private static Map<String, Object> copiar(Map<String, Object> atributos) {
        if (atributos == null) {
            return Map.of();
        }
        // unmodifiableMap sobre uma copia, e nao Map.copyOf, pelo mesmo motivo de Produto: o JSONB
        // pode ter valor nulo, que Map.copyOf recusa.
        return Collections.unmodifiableMap(new LinkedHashMap<>(atributos));
    }

    public UUID getId() {
        return id;
    }

    public String getNome() {
        return nome;
    }

    public String getCategoria() {
        return categoria;
    }

    public String getUnidade() {
        return unidade;
    }

    public TipoProduto getTipo() {
        return tipo;
    }

    /** Copia imutavel: o modelo nao muda por quem leu o mapa para copiar dele. */
    public Map<String, Object> getAtributosSugeridos() {
        return copiar(atributosSugeridos);
    }
}
