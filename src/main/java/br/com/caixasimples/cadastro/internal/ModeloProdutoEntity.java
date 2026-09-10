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
 * Linha do catálogo sugerido por tipo de negócio (RF32).
 *
 * <p><strong>Não tem {@link TenantId}, e a ausência é o ponto desta classe.</strong> É dado de
 * referência da plataforma, não de negócio de nenhuma conta. Junto de {@code Conta}, cujo id
 * <em>é</em> o tenant, e de {@code Credencial}, consultada antes de existir tenant, ela fecha as
 * três, e só três, exceções ao filtro de tenant no sistema. O isolamento continua de pé porque
 * estas linhas são <em>copiadas</em> para {@code produto}, nunca referenciadas ao vivo; depois de
 * copiado, o item pertence à conta como qualquer outro produto.
 *
 * <p>Não há {@code domain/} nem mapper aqui, pelo mesmo julgamento aplicado a {@code Cliente}: uma
 * linha de referência sem invariante nenhuma a proteger não ganha estrutura de agregado. Ela não
 * muda depois de gravada, e por isso não existe setter nem método de alteração.
 *
 * <p>É pública, e não de visibilidade de pacote como {@code ClienteEntity}, porque
 * {@code application.CatalogoInicialService} lê estas linhas de outro pacote do mesmo módulo. Para
 * fora do módulo nada disso é alcançável, já que pelo Modulith todo subpacote é interno.
 */
@Entity
@Table(name = "modelo_produto")
public class ModeloProdutoEntity {

    @Id
    private UUID id;

    /** Casa com {@code Conta.tipo_negocio} ignorando maiúscula e minúscula. */
    @Column(name = "tipo_negocio", nullable = false)
    private String tipoNegocio;

    @Column(nullable = false)
    private String nome;

    private String categoria;

    private String unidade;

    /**
     * Coluna necessária porque {@code produto.tipo} é {@code NOT NULL}: sem ela, um corte de cabelo
     * seria copiado como {@link TipoProduto#PRODUTO} e carregaria estoque que não existe.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TipoProduto tipo;

    /** Copiado para {@code produto.atributos} junto com o resto da linha (RF02). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "atributos_sugeridos", nullable = false)
    private Map<String, Object> atributosSugeridos;

    protected ModeloProdutoEntity() {
        // exigido pelo JPA
    }

    /**
     * Visibilidade de pacote de propósito: <strong>em produção ninguém chama isto</strong>. As
     * linhas de {@code modelo_produto} vêm da migration e só de lá, porque o catálogo é dado de
     * referência versionado junto do schema, não algo que a aplicação escreve.
     *
     * <p>Existe para o teste do módulo montar o catálogo de um tipo de negócio fictício e provar
     * que contas de tipos diferentes recebem catálogos diferentes, sem depender do conteúdo de
     * fábrica, que mudaria a cada catálogo novo.
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
        // unmodifiableMap sobre uma cópia, e não Map.copyOf, pelo mesmo motivo de Produto: o JSONB
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

    /** Cópia imutável: o modelo não muda por quem leu o mapa para copiar dele. */
    public Map<String, Object> getAtributosSugeridos() {
        return copiar(atributosSugeridos);
    }
}
