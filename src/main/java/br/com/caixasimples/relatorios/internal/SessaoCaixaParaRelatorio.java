package br.com.caixasimples.relatorios.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.TenantId;

/**
 * A tabela {@code sessao_caixa} vista pelos relatórios: só o dono da sessão, para a conferência do
 * cartão dizer quem recebeu um fiado.
 *
 * <p>O recebimento de fiado guarda a sessão em que entrou, e não a pessoa; a pessoa é a dona da
 * sessão, porque o fiado só se recebe no próprio caixa aberto. Mesmo desenho de
 * {@link PagamentoParaRelatorio}: somente leitura, sem repositório, alvo de junção.
 *
 * <p>{@code contaId} filtra pelo {@link TenantId} (RNF05).
 */
@Entity
@Immutable
@Table(name = "sessao_caixa")
public class SessaoCaixaParaRelatorio {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "conta_id", nullable = false, updatable = false)
    private UUID contaId;

    /** O operador que abriu a sessão: o caixa é sempre de uma pessoa só. */
    @Column(name = "usuario_id", nullable = false)
    private UUID usuarioId;

    protected SessaoCaixaParaRelatorio() {
        // exigido pelo JPA, e o unico construtor de proposito: ninguem instancia esta classe
    }
}
