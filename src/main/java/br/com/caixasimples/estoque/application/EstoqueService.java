package br.com.caixasimples.estoque.application;

import br.com.caixasimples.cadastro.application.ProdutoNaoEncontradoException;
import br.com.caixasimples.cadastro.application.ProdutoService;
import br.com.caixasimples.cadastro.application.ProdutoService.EstoqueDoProduto;
import br.com.caixasimples.contas.application.ContaService;
import br.com.caixasimples.shared.TenantContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Casos de uso do estoque que uma pessoa aciona: o ajuste manual (RF19), o estoque mínimo de um
 * produto e a lista de estoque baixo (RF20). A baixa por venda e o estorno por cancelamento não
 * estão aqui: são reação a evento, e moram nos ouvintes em {@code internal}.
 *
 * <p><strong>Este módulo decide; o cadastro executa.</strong> O agregado Produto, com o saldo, o
 * mínimo e os movimentos, é do cadastro, e ninguém de fora abre a entidade dele. O que é do
 * estoque é a política: a conta ligou o controle de estoque (RF17)? Só então cada caso de uso
 * segue, pedindo ao cadastro pela API pública dele. Com o controle desligado, que é como toda
 * conta nasce, os três recusam com {@link ControleDeEstoqueDesligadoException}, inclusive a
 * consulta: um negócio que não controla estoque não tem alerta a mostrar, e a tela que perguntou
 * precisa saber que o caminho é ligar o módulo, não que a lista está vazia.
 *
 * <p><strong>Sem {@code @Transactional} nesta classe, de propósito.</strong> A pergunta à conta e
 * a escrita no cadastro são transações separadas, como no ouvinte da venda concluída: com o
 * controle desligado, não se abre transação de escrita nenhuma. Não há o que manter atômico entre
 * as duas, porque a resposta da conta não muda pelo que o cadastro grava.
 *
 * <p>A conta vem sempre do contexto, nunca de parâmetro (RNF05); é por isso que nenhum método
 * recebe conta, e é a mesma razão pela qual {@code ContaService} não a recebe.
 *
 * <p>O alerta de estoque baixo é uma <strong>consulta</strong>, não um evento: a tela pergunta e
 * mostra a lista. Não há quem ouça um evento de estoque baixo hoje; quando o envio por canal
 * existir, ele consome esta consulta ou nasce com o evento dele, com quem o ouça.
 *
 * <p>O pacote não é exposto aos outros módulos: quem chama daqui é a camada web deste mesmo
 * módulo. Ninguém depende de estoque, e é isso que permite a ele depender de cadastro e contas.
 */
@Service
public class EstoqueService {

    private final ContaService contas;
    private final ProdutoService produtos;

    EstoqueService(ContaService contas, ProdutoService produtos) {
        this.contas = contas;
        this.produtos = produtos;
    }

    /**
     * Ajuste manual do estoque de um produto (RF19): perda, quebra ou contagem.
     *
     * <p>A diferença carrega o sinal: {@code -2} é uma perda de duas unidades, {@code +3} é uma
     * contagem que achou três a mais. Na contagem, quem chama informa o contado menos o
     * registrado, e o operador vê a diferença antes de confirmar.
     *
     * @param produtoId o produto, nesta conta
     * @param diferenca o que soma ou subtrai do saldo, com sinal; nunca zero
     * @param motivo    obrigatório (RF19)
     * @throws ControleDeEstoqueDesligadoException se a conta não ligou o controle de estoque
     * @throws ProdutoNaoEncontradoException       se o id não existe nesta conta
     * @throws IllegalStateException               se o item é SERVICO ou está inativo
     * @throws IllegalArgumentException            se a diferença é zero ou falta o motivo
     */
    public void ajustar(UUID produtoId, BigDecimal diferenca, String motivo) {
        exigirControleLigado();
        produtos.ajustarEstoque(produtoId, diferenca, motivo);
    }

    /**
     * Define a partir de que saldo um produto entra na lista de estoque baixo (RF20). Zero, que é
     * o padrão de todo produto, avisa quando o item acabou; um mínimo maior avisa antes.
     *
     * @throws ControleDeEstoqueDesligadoException se a conta não ligou o controle de estoque
     * @throws ProdutoNaoEncontradoException       se o id não existe nesta conta
     * @throws IllegalStateException               se o item é SERVICO ou está inativo
     * @throws IllegalArgumentException            se o mínimo é negativo ou tem mais de três casas
     */
    public void definirEstoqueMinimo(UUID produtoId, BigDecimal minimo) {
        exigirControleLigado();
        produtos.definirEstoqueMinimo(produtoId, minimo);
    }

    /**
     * Os produtos ativos da conta cujo saldo chegou ao mínimo ou ficou abaixo dele (RF20). É o
     * alerta de estoque baixo: uma lista que a tela mostra, e vazia quando não há o que repor.
     *
     * @throws ControleDeEstoqueDesligadoException se a conta não ligou o controle de estoque
     */
    public List<EstoqueDoProduto> produtosComEstoqueBaixo() {
        exigirControleLigado();
        return produtos.listarComEstoqueBaixo();
    }

    private void exigirControleLigado() {
        if (!contas.estoqueHabilitado()) {
            throw new ControleDeEstoqueDesligadoException(TenantContext.exigirAtual());
        }
    }
}
