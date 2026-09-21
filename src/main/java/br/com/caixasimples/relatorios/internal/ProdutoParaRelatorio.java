package br.com.caixasimples.relatorios.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.TenantId;

/**
 * A tabela {@code produto} vista pelos relatórios: só o que uma linha do ranking dos mais vendidos
 * mostra ao lado dos números, o nome e a unidade.
 *
 * <p><strong>Por que um mapeamento próprio, e não uma pergunta ao cadastro.</strong> O cadastro já
 * responde nome e unidade de uma lista de produtos, para o comprovante. Usar essa resposta aqui
 * custaria uma segunda consulta por relatório, uma junção feita em Java e uma dependência nova
 * deste módulo no cadastro; mapear as duas colunas custa esta classe e deixa o ranking numa
 * consulta só, com a junção onde ela é barata. É o mesmo desenho de {@link VendaParaRelatorio},
 * pelos mesmos motivos.
 *
 * <p><strong>Não tem repositório</strong>: ninguém consulta produto a partir daqui. A classe
 * existe para ser alvo de junção na consulta do ranking, e só. Somente leitura garantido como nas
 * irmãs: {@link Immutable} e nenhum construtor com argumentos.
 *
 * <p>Sem a coluna {@code ativo}, de propósito: produto inativado continua aparecendo no ranking do
 * período em que foi vendido, porque o histórico aponta para ele (RF05). O nome é o atual, não o
 * da época da venda, como no comprovante.
 */
@Entity
@Immutable
@Table(name = "produto")
public class ProdutoParaRelatorio {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "conta_id", nullable = false, updatable = false)
    private UUID contaId;

    @Column(nullable = false)
    private String nome;

    /** Livre e opcional no cadastro; é o que dá sentido à quantidade somada, 3 kg ou 3 un. */
    @Column
    private String unidade;

    protected ProdutoParaRelatorio() {
        // exigido pelo JPA, e o unico construtor de proposito: ninguem instancia esta classe
    }
}
