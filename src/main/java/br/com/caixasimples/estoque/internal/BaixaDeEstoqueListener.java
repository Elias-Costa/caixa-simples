package br.com.caixasimples.estoque.internal;

import br.com.caixasimples.cadastro.application.ProdutoNaoEncontradoException;
import br.com.caixasimples.cadastro.application.ProdutoService;
import br.com.caixasimples.contas.application.ContaService;
import br.com.caixasimples.shared.TenantContext;
import br.com.caixasimples.vendas.VendaConcluida;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ouve a venda concluída e dá baixa no estoque dos produtos vendidos (RF18).
 *
 * <p>É o segundo ouvinte do mesmo fato, ao lado do caixa, e o mesmo desenho: a venda publica, o
 * estoque reage, e vendas não conhece este módulo. O evento chega pelo registro de publicação,
 * depois do commit da venda; se esta classe falhar, a publicação fica incompleta e pode ser
 * reprocessada, em vez de o estoque ficar errado em silêncio.
 *
 * <h2>Quem decide e quem executa</h2>
 *
 * <p><strong>Este módulo decide; o cadastro executa.</strong> O agregado Produto, com seu saldo e
 * seus movimentos, é do cadastro, e nenhum outro módulo abre a entidade dele. O que é do estoque
 * é a política: a conta ligou o controle de estoque (RF17)? Então cada item da venda vira uma
 * baixa, pedida ao cadastro pela API pública dele. Com o controle desligado, que é como toda
 * conta nasce, nada acontece, e nem se abre transação.
 *
 * <p><strong>Um item por movimento, e a venda inteira numa transação só.</strong> Ou todos os
 * itens baixam, ou nenhum, e a publicação fica incompleta para o reprocessamento. Serviço no meio
 * dos itens não é erro: o cadastro reconhece e não baixa.
 *
 * <p><strong>A mesma venda baixa uma vez.</strong> A entrega é garantida ao menos uma vez, então
 * o evento pode chegar de novo; antes de cada item, o cadastro responde se aquela venda já baixou
 * aquele produto, e a reentrega vira um não fazer nada, registrado em log. O cadastro recusa a
 * duplicata por conta própria também, para nenhum outro chamador baixar em dobro.
 *
 * <h2>Por que não a anotação de listener do Modulith</h2>
 *
 * <p>Mesmo motivo do ouvinte do caixa: a anotação pronta abre uma transação antes do corpo do
 * método, e o Hibernate resolve o tenant na abertura da sessão, então ela nasceria presa ao
 * sentinela sem enxergar a conta do evento. Aqui as duas anotações estão por extenso, a conta do
 * evento entra no contexto primeiro e a transação é aberta depois, à mão. Tenant primeiro,
 * transação depois.
 *
 * <p>O nome não é {@code VendaConcluidaListener}, como no caixa, porque duas classes com o mesmo
 * nome simples colidem no registro de beans do Spring, mesmo em pacotes diferentes. Fica em
 * {@code internal} porque é um adapter de entrada: nenhum outro módulo o nomeia.
 */
@Component
class BaixaDeEstoqueListener {

    private static final Logger log = LoggerFactory.getLogger(BaixaDeEstoqueListener.class);

    private final ContaService contas;
    private final ProdutoService produtos;
    private final TransactionTemplate transacao;

    BaixaDeEstoqueListener(ContaService contas, ProdutoService produtos,
            TransactionTemplate transacao) {
        this.contas = contas;
        this.produtos = produtos;
        this.transacao = transacao;
    }

    /**
     * Roda depois do commit da transação que concluiu a venda, em outra thread.
     *
     * @throws ProdutoNaoEncontradoException se um produto do evento não existe na conta do
     *         evento; a publicação fica incompleta no registro
     */
    @Async
    @TransactionalEventListener
    public void darBaixa(VendaConcluida evento) {
        // Tenant primeiro, transação depois. Ver o javadoc da classe.
        TenantContext.executarComo(evento.contaId(), () -> {
            if (!contas.estoqueHabilitado()) {
                // A conta não controla estoque: nada a baixar, e nem se abre transação.
                log.debug("conta {} sem controle de estoque; venda {} nao gera movimento",
                        evento.contaId(), evento.vendaId());
                return;
            }

            transacao.executeWithoutResult(status -> {
                for (VendaConcluida.Item item : evento.itens()) {
                    if (produtos.jaDeuBaixaPorVenda(item.produtoId(), evento.vendaId())) {
                        // Reentrega do registro de publicação: o estoque já saiu.
                        log.info("venda {} ja deu baixa no produto {}; reentrega ignorada",
                                evento.vendaId(), item.produtoId());
                        continue;
                    }
                    produtos.darBaixaPorVenda(item.produtoId(), item.quantidade(),
                            evento.vendaId());
                }
            });
        });
    }
}
