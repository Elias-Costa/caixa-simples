package br.com.caixasimples.estoque.internal;

import br.com.caixasimples.cadastro.application.ProdutoNaoEncontradoException;
import br.com.caixasimples.cadastro.application.ProdutoService;
import br.com.caixasimples.contas.application.ContaService;
import br.com.caixasimples.shared.TenantContext;
import br.com.caixasimples.vendas.VendaConcluida;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ouve a venda concluída e dá baixa no estoque dos produtos vendidos (RF18).
 *
 * <p>É o segundo ouvinte do mesmo fato, ao lado do caixa, e o mesmo desenho: a venda publica, o
 * estoque reage, e vendas não conhece este módulo.
 *
 * <h2>Quem decide e quem executa</h2>
 *
 * <p><strong>Este módulo decide; o cadastro executa.</strong> O agregado Produto, com seu saldo e
 * seus movimentos, é do cadastro, e nenhum outro módulo abre a entidade dele. O que é do estoque
 * é a política: a conta ligou o controle de estoque (RF17)? Então cada produto da venda vira uma
 * baixa, pedida ao cadastro pela API pública dele. Com o controle desligado, que é como toda
 * conta nasce, nada acontece.
 *
 * <p><strong>Um produto por movimento.</strong> O mesmo produto pode aparecer em mais de uma linha
 * da venda, porque as linhas não se mesclam, e a venda baixa cada produto uma vez só: as linhas
 * dele são somadas antes de pedir, e o cadastro recebe o total que a venda levou. Serviço no meio
 * dos itens não é erro: o cadastro reconhece e não baixa.
 *
 * <h2>Dentro da transação da conclusão</h2>
 *
 * <p>Roda na thread e na transação de quem concluiu a venda, e não depois do commit: a venda
 * concluída e a baixa de todos os itens confirmam juntas ou não confirmam. É o que deixa a
 * conclusão recebida do dispositivo sem rede saber, logo depois, se a venda levou mais do que o
 * saldo registrava. Sem reentrega, porque o fato não passa pelo registro de publicação; a mesma
 * venda continua sem baixar duas vezes, porque o cadastro recusa a duplicata.
 *
 * <p>A conta é a de quem concluiu, a mesma que a transação já usa, e o ouvinte confere que o
 * evento é dela. A transação é a de quem publicou; o {@link TransactionTemplate} só a abre quando o
 * evento é publicado fora de uma.
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
     * Roda quando a venda é concluída, antes do commit.
     *
     * @throws ProdutoNaoEncontradoException se um produto do evento não existe nesta conta; a
     *         conclusão falha junto
     * @throws IllegalStateException se a venda já deu baixa num produto ou se o evento é de outra
     *         conta; a conclusão falha junto
     */
    @EventListener
    public void darBaixa(VendaConcluida evento) {
        if (!TenantContext.exigirAtual().equals(evento.contaId())) {
            throw new IllegalStateException(
                    "venda " + evento.vendaId() + " concluida em outra conta; o estoque nao baixa");
        }

        transacao.executeWithoutResult(status -> {
            if (!contas.estoqueHabilitado()) {
                // A conta não controla estoque: nada a baixar.
                log.debug("conta {} sem controle de estoque; venda {} nao gera movimento",
                        evento.contaId(), evento.vendaId());
                return;
            }
            // Pedir uma baixa por linha faria o cadastro recusar a segunda linha do mesmo
            // produto, e a conclusão inteira falharia.
            Map<UUID, BigDecimal> quantidadePorProduto = new LinkedHashMap<>();
            for (VendaConcluida.Item item : evento.itens()) {
                quantidadePorProduto.merge(item.produtoId(), item.quantidade(), BigDecimal::add);
            }
            for (Map.Entry<UUID, BigDecimal> produto : quantidadePorProduto.entrySet()) {
                produtos.darBaixaPorVenda(produto.getKey(), produto.getValue(), evento.vendaId());
            }
        });
    }
}
