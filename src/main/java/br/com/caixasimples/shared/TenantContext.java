package br.com.caixasimples.shared;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Conta (tenant) da operacao em curso.
 *
 * <p>Preenchido pelo filtro de autenticacao a partir do claim do JWT — <strong>nunca</strong> a
 * partir de corpo, path, query ou header da requisicao (arquitetura §3, RNF05). O
 * {@code CurrentTenantIdentifierResolver} do Hibernate le daqui para aplicar o filtro de
 * {@code @TenantId} em toda query automaticamente.
 *
 * <p>Implementado com {@link ThreadLocal}, nao com {@code ScopedValue}: {@code ScopedValue} ainda e
 * <em>preview</em> no Java 21 (so ficou final no 25) e exigiria {@code --enable-preview}. Cada
 * virtual thread tem seu proprio valor de {@code ThreadLocal}, entao a semantica por requisicao
 * esta correta com {@code spring.threads.virtual.enabled=true}. Ao migrar para o Java 25, este e o
 * unico arquivo a trocar.
 */
public final class TenantContext {

    private static final ThreadLocal<ContaId> ATUAL = new ThreadLocal<>();

    /**
     * Tenant sentinela usado quando nao ha conta no contexto.
     *
     * <p>Existe por exigencia do Hibernate 7: com um resolver de tenant registrado, ele recusa
     * abrir sessao sem identificador — inclusive na inicializacao, quando o Spring Data valida as
     * derived queries. Devolver {@code null} derruba o contexto.
     *
     * <p>E infraestrutura, <strong>nunca</strong> uma conta de verdade, e o desenho falha fechado
     * nas duas direcoes: na leitura, filtra por um {@code conta_id} que nenhuma linha tem, logo
     * devolve vazio; na escrita, a foreign key {@code conta_id REFERENCES conta (id)} rejeita o
     * insert no banco. A migration V1 ainda proibe por {@code CHECK} que alguma conta real receba
     * este id.
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
     * @throws TenantNaoResolvidoException se nao houver conta no contexto — falhar e correto,
     *         consultar sem filtro nao.
     */
    public static ContaId exigirAtual() {
        ContaId contaId = ATUAL.get();
        if (contaId == null) {
            throw new TenantNaoResolvidoException();
        }
        return contaId;
    }

    /** Obrigatorio ao fim de cada requisicao — thread reaproveitada nao pode herdar tenant. */
    public static void limpar() {
        ATUAL.remove();
    }

    /**
     * Executa uma acao no contexto de uma conta especifica, restaurando o contexto anterior no
     * fim. Use em listener de evento assincrono e em teste; nunca para "emprestar" outra conta
     * dentro do fluxo de uma requisicao.
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
