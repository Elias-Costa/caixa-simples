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
 * Produto ou serviço do catálogo de uma conta. Raiz do agregado Produto, que terá
 * {@code MovimentoEstoque} como membro quando o controle de estoque existir.
 *
 * <p><strong>Não importa framework</strong>, nem {@code jakarta.persistence} nem
 * {@code org.springframework}. O mapeamento para o banco vive em
 * {@code cadastro.internal.ProdutoEntity}.
 *
 * <p><strong>Não carrega {@code contaId}, e isso é deliberado.</strong> O tenant é preenchido pelo
 * Hibernate na entidade, via {@code @TenantId}, a partir do contexto da requisição. Deixando a
 * conta fora do domínio, não existe assinatura em que um chamador possa informá-la, que é
 * literalmente o que o isolamento entre contas proíbe (RNF05).
 *
 * <p>Por que estrutura completa aqui e vertical slice em {@code Cliente}: a camada {@code domain/}
 * é reservada às raízes que têm invariante de verdade a proteger. Onde não há invariante, ela seria
 * cerimônia.
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
     * Saldo consolidado, nunca somado do histórico a cada leitura, que é o que mantém barato o
     * alerta de estoque baixo (RF20). Quem o move é o movimento de estoque, na mesma transação.
     * Enquanto esse movimento não existir, ele apenas nasce em zero.
     *
     * <p>Existe também em SERVICO, que simplesmente nunca recebe movimento. Uma coluna sempre
     * preenchida evita nulo em todo leitor; o custo é que um serviço aparece com saldo zero, então
     * o alerta filtra por {@link TipoProduto}, do mesmo modo que já vai filtrar pelas contas com
     * estoque habilitado.
     */
    private BigDecimal estoqueAtual;

    private Map<String, Object> atributos;
    private boolean ativo;

    /**
     * Cadastro de um item novo (RF01, RF02).
     *
     * @param nome      obrigatório
     * @param preco     obrigatório; zero é válido, negativo não
     * @param tipo      obrigatório
     * @param codigo    opcional; espaços nas pontas somem e texto em branco vira ausência
     * @param categoria opcional
     * @param unidade   opcional, por exemplo {@code un}, {@code kg} ou {@code hora}
     * @param atributos opcional; nulo vira mapa vazio, porque ausência de atributo específico não
     *                  é um caso a tratar em quem lê (RF02)
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
     * Remonta um produto que já existe no banco, preservando identidade e estado.
     *
     * <p>Existe apenas para {@code ProdutoEntity}, e não é caminho de cadastro. Por isso não
     * revalida: o que está gravado já passou pelo construtor público e pelos CHECK da migration, e
     * recusar aqui deixaria uma linha existente impossível de ler.
     */
    public static Produto reconstituir(UUID id, Instant criadoEm, String nome, Money preco,
            TipoProduto tipo, String codigo, String categoria, String unidade,
            BigDecimal estoqueAtual, Map<String, Object> atributos, boolean ativo) {
        return new Produto(id, criadoEm, nome, preco, tipo, codigo, categoria, unidade,
                estoqueAtual, atributos, ativo);
    }

    /**
     * Aplica uma edição do cadastro (RF04). As regras são as mesmas do construtor, e de propósito:
     * o que não entra num produto novo também não entra num produto editado.
     *
     * <p>Os atributos são <strong>substituídos por inteiro</strong>, nunca mesclados, porque a
     * edição descreve o produto como ele fica, e não um delta sobre o que estava lá.
     *
     * <p><strong>Não recebe {@code tipo}, e a ausência é que é a decisão.</strong> O tipo decide se
     * o item participa de estoque, então trocá-lo depois deixaria um movimento de estoque órfão num
     * item que virou SERVICO, ou um SERVICO com saldo. Errou o tipo no cadastro? Use
     * {@link #inativar()} e recadastre; o histórico de vendas do item antigo continua de pé.
     *
     * <p>Não recebe {@code estoqueAtual} pelo mesmo tipo de motivo: saldo só se move por movimento
     * de estoque, nunca por edição de cadastro.
     *
     * @throws IllegalStateException se o produto já foi inativado. Como não há reativação, editar
     *                               um registro que ninguém mais enxerga não teria efeito nenhum
     */
    public void alterar(String nome, Money preco, String codigo, String categoria, String unidade,
            Map<String, Object> atributos) {
        if (!ativo) {
            throw new IllegalStateException("produto inativo nao pode ser editado: " + id);
        }
        this.nome = exigirTexto(nome, "nome");
        this.preco = exigirPreco(preco);
        this.codigo = textoOpcional(codigo);
        this.categoria = textoOpcional(categoria);
        this.unidade = textoOpcional(unidade);
        this.atributos = copiar(atributos);
    }

    /**
     * Soft delete (RF05): o registro fica, para o histórico de vendas não perder a referência.
     *
     * <p><strong>É idempotente.</strong> Inativar um produto já inativo não é erro de ninguém, e
     * sim o mesmo estado pedido de novo: dois cliques no balcão, ou a requisição que o cliente
     * offline reenvia ao voltar a rede.
     *
     * <p>Não existe {@code reativar()}. Reativar esbarraria no índice único parcial do código do
     * produto, que só vale entre os ativos, porque o código do item inativado pode já ter sido
     * reaproveitado por outro.
     */
    public void inativar() {
        this.ativo = false;
    }

    private static Money exigirPreco(Money preco) {
        Objects.requireNonNull(preco, "preco nao pode ser nulo");
        if (preco.isNegativo()) {
            // Zero passa, porque cortesia, brinde e item de acompanhamento existem. Negativo não é
            // preço: seria desconto, e desconto é da venda, não do cadastro.
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
        // unmodifiableMap sobre uma cópia, e não Map.copyOf: o JSONB pode ter valor nulo, que
        // Map.copyOf recusa, e uma linha já gravada assim ficaria impossível de ler.
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

    /** Pode ser nulo: muitos negócios não usam código nenhum. */
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

    /** Cópia imutável: atributo só muda pela raiz, nunca por quem leu o mapa. */
    public Map<String, Object> getAtributos() {
        return atributos;
    }

    public boolean isAtivo() {
        return ativo;
    }
}
