package br.com.caixasimples.cadastro.domain;

import br.com.caixasimples.cadastro.TipoProduto;
import br.com.caixasimples.shared.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Produto ou servico do catalogo de uma conta. Raiz do agregado Produto (modelo de dados §4), com
 * {@code MovimentoEstoque} como membro a partir da etapa 1.7.
 *
 * <p><strong>Nao importa framework</strong> — nem {@code jakarta.persistence}, nem
 * {@code org.springframework} (arquitetura §2). O mapeamento para o banco vive em
 * {@code cadastro.internal.ProdutoEntity}.
 *
 * <p><strong>Nao carrega {@code contaId}, e isso e deliberado.</strong> O tenant e preenchido pelo
 * Hibernate na entidade, via {@code @TenantId}, a partir do contexto da requisicao. Deixando a
 * conta fora do dominio, nao existe assinatura em que um chamador possa informa-la — que e
 * literalmente o que RNF05 proibe. Ver {@code .claude/rules/multi-tenancy.md}.
 *
 * <p>Por que estrutura completa aqui e slice simples em {@code Conta}/{@code Usuario}: a P7
 * reservou {@code domain/} a {@code Venda}, {@code SessaoCaixa} e {@code Produto}, que tem
 * invariante de verdade a proteger.
 */
public class Produto {

    private final UUID id;
    private final Instant criadoEm;

    private String nome;
    private Money preco;
    private String codigo;
    private String categoria;
    private String unidade;
    private TipoProduto tipo;

    /**
     * Saldo consolidado, nunca somado do historico a cada leitura (RF20). Quem o move e o
     * {@code MovimentoEstoque} da etapa 1.7, na mesma transacao — ate la ele so nasce em zero.
     *
     * <p>D16b: existe tambem em SERVICO, que simplesmente nunca recebe movimento. Uma coluna sempre
     * preenchida evita null em todo leitor; o custo e que um servico aparece com saldo zero, entao
     * o alerta do RF20 filtra por {@link TipoProduto} — como ja vai filtrar por conta com estoque
     * habilitado (P4).
     */
    private BigDecimal estoqueAtual;

    private Map<String, Object> atributos;
    private boolean ativo;

    /**
     * Cadastro de um item novo (RF01/RF02).
     *
     * @param nome      obrigatorio
     * @param preco     obrigatorio; zero e valido, negativo nao (D16c)
     * @param tipo      obrigatorio
     * @param codigo    opcional (D11); espacos nas pontas somem e texto em branco vira ausencia
     * @param categoria opcional (D16a)
     * @param unidade   opcional (D16a) — ex.: "un", "kg", "hora"
     * @param atributos opcional; nulo vira mapa vazio, porque ausencia de atributo especifico nao
     *                  e um caso a tratar em quem le (RF02)
     */
    public Produto(String nome, Money preco, TipoProduto tipo, String codigo, String categoria,
            String unidade, Map<String, Object> atributos) {
        this.id = UUID.randomUUID();
        this.criadoEm = Instant.now();
        this.nome = exigirTexto(nome, "nome");
        this.preco = exigirPreco(preco);
        this.tipo = Objects.requireNonNull(tipo, "tipo nao pode ser nulo");
        this.codigo = textoOpcional(codigo);
        this.categoria = textoOpcional(categoria);
        this.unidade = textoOpcional(unidade);
        this.atributos = copiar(atributos);
        this.estoqueAtual = BigDecimal.ZERO;
        this.ativo = true;
    }

    private Produto(UUID id, Instant criadoEm, String nome, Money preco, TipoProduto tipo,
            String codigo, String categoria, String unidade, BigDecimal estoqueAtual,
            Map<String, Object> atributos, boolean ativo) {
        this.id = id;
        this.criadoEm = criadoEm;
        this.nome = nome;
        this.preco = preco;
        this.tipo = tipo;
        this.codigo = codigo;
        this.categoria = categoria;
        this.unidade = unidade;
        this.estoqueAtual = estoqueAtual;
        this.atributos = copiar(atributos);
        this.ativo = ativo;
    }

    /**
     * Remonta um produto que ja existe no banco, preservando identidade e estado.
     *
     * <p>Existe so para {@code ProdutoEntity} — nao e caminho de cadastro. Por isso nao revalida:
     * o que esta gravado ja passou pelo construtor publico e pelos CHECK da migration; recusar
     * aqui deixaria uma linha existente impossivel de ler.
     */
    public static Produto reconstituir(UUID id, Instant criadoEm, String nome, Money preco,
            TipoProduto tipo, String codigo, String categoria, String unidade,
            BigDecimal estoqueAtual, Map<String, Object> atributos, boolean ativo) {
        return new Produto(id, criadoEm, nome, preco, tipo, codigo, categoria, unidade,
                estoqueAtual, atributos, ativo);
    }

    /** RF05 — soft delete: o registro fica, para o historico de vendas nao perder a referencia. */
    public void inativar() {
        this.ativo = false;
    }

    private static Money exigirPreco(Money preco) {
        Objects.requireNonNull(preco, "preco nao pode ser nulo");
        if (preco.isNegativo()) {
            // D16c: zero passa (cortesia, brinde, item de acompanhamento); negativo nao e preco.
            throw new IllegalArgumentException("preco nao pode ser negativo: " + preco);
        }
        return preco;
    }

    private static String exigirTexto(String valor, String campo) {
        if (valor == null || valor.isBlank()) {
            throw new IllegalArgumentException(campo + " nao pode ser vazio");
        }
        return valor.trim();
    }

    private static String textoOpcional(String valor) {
        if (valor == null || valor.isBlank()) {
            return null;
        }
        return valor.trim();
    }

    private static Map<String, Object> copiar(Map<String, Object> atributos) {
        if (atributos == null) {
            return Map.of();
        }
        // unmodifiableMap sobre uma copia, e nao Map.copyOf: o JSONB pode ter valor nulo
        // (`{"tamanho": null}`), que Map.copyOf recusa — e uma linha ja gravada assim ficaria
        // impossivel de ler.
        return Collections.unmodifiableMap(new LinkedHashMap<>(atributos));
    }

    public UUID getId() {
        return id;
    }

    public Instant getCriadoEm() {
        return criadoEm;
    }

    public String getNome() {
        return nome;
    }

    public Money getPreco() {
        return preco;
    }

    public TipoProduto getTipo() {
        return tipo;
    }

    /** D11 — pode ser nulo: muitos negocios nao usam codigo nenhum. */
    public String getCodigo() {
        return codigo;
    }

    public String getCategoria() {
        return categoria;
    }

    public String getUnidade() {
        return unidade;
    }

    public BigDecimal getEstoqueAtual() {
        return estoqueAtual;
    }

    /** Copia imutavel: atributo so muda pela raiz, nunca por quem leu o mapa. */
    public Map<String, Object> getAtributos() {
        return atributos;
    }

    public boolean isAtivo() {
        return ativo;
    }
}
