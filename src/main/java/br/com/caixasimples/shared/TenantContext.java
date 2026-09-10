package br.com.caixasimples.shared;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Conta (tenant) da operação em curso.
 *
 * <p>Preenchido pelo filtro de autenticação a partir do claim do token, <strong>nunca</strong> a
 * partir de corpo, path, query ou header da requisição, porque qualquer um desses seria um valor
 * que o cliente pode forjar para alcançar dado de outra conta (RNF05). O
 * {@code CurrentTenantIdentifierResolver} do Hibernate lê daqui para aplicar o filtro de
 * {@code @TenantId} em toda query automaticamente.
 *
 * <p>Implementado com {@link ThreadLocal} e não com {@code ScopedValue}, porque
 * {@code ScopedValue} ainda é <em>preview</em> no Java 21, tendo se tornado final apenas no 25, e
 * exigiria {@code --enable-preview}. Cada virtual thread tem seu próprio valor de
 * {@link ThreadLocal}, então a semântica por requisição continua correta com
 * {@code spring.threads.virtual.enabled=true}. Ao migrar para o Java 25, este é o único arquivo a
 * trocar.
 */
public final class TenantContext {

    private static final ThreadLocal<ContaId> ATUAL = new ThreadLocal<>();

    /**
     * Tenant sentinela usado quando não há conta no contexto.
     *
     * <p>Existe por exigência do Hibernate 7: com um resolver de tenant registrado, ele recusa
     * abrir sessão sem identificador, inclusive na inicialização, quando o Spring Data valida as
     * derived queries. Devolver {@code null} derruba o contexto.
     *
     * <p>É infraestrutura, <strong>nunca</strong> uma conta de verdade, e o desenho falha fechado
     * nas duas direções: na leitura, filtra por um {@code conta_id} que nenhuma linha tem, logo
     * devolve vazio; na escrita, a foreign key {@code conta_id REFERENCES conta (id)} rejeita o
     * insert no banco. A primeira migration ainda proíbe por {@code CHECK} que alguma conta real
     * receba este id.
     */
    public static final ContaId SEM_TENANT = ContaId.de(new java.util.UUID(0L, 0L));

    private TenantContext() {
    }

    public static void definir(ContaId contaId) {
        ATUAL.set(Objects.requireNonNull(contaId, "contaId nao pode ser nulo"));
    }

    public static Optional<ContaId> atual() {
        return Optional.ofNullable(ATUAL.get());
    }

    /**
     * @throws TenantNaoResolvidoException se não houver conta no contexto. Falhar é o
     *         comportamento correto; consultar sem filtro não é.
     */
    public static ContaId exigirAtual() {
        ContaId contaId = ATUAL.get();
        if (contaId == null) {
            throw new TenantNaoResolvidoException();
        }
        return contaId;
    }

    /**
     * Obrigatório ao fim de cada requisição: thread reaproveitada não pode herdar o tenant da
     * requisição anterior.
     */
    public static void limpar() {
        ATUAL.remove();
    }

    /**
     * Executa uma ação no contexto de uma conta específica, restaurando o contexto anterior no
     * fim. Use em listener de evento assíncrono e em teste. Nunca use para tomar emprestada outra
     * conta dentro do fluxo de uma requisição.
     */
    public static <T> T executarComo(ContaId contaId, Supplier<T> acao) {
        ContaId anterior = ATUAL.get();
        definir(contaId);
        try {
            return acao.get();
        } finally {
            if (anterior == null) {
                limpar();
            } else {
                ATUAL.set(anterior);
            }
        }
    }

    public static void executarComo(ContaId contaId, Runnable acao) {
        executarComo(contaId, () -> {
            acao.run();
            return null;
        });
    }
}
