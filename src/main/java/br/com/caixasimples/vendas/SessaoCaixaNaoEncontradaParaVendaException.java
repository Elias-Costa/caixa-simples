package br.com.caixasimples.vendas;

import java.util.UUID;

/**
 * Não existe sessão de caixa com esse id <strong>nesta conta</strong>, na pergunta que a venda faz
 * ao caixa por {@link CaixaParaVenda}.
 *
 * <p><strong>Declarada aqui, e lançada pelo módulo do caixa.</strong> O caixa tem a própria exceção
 * de sessão inexistente, mas este módulo não pode nomeá-la: vendas não depende do caixa, porque o
 * caixa já depende de vendas para ouvir os eventos da venda. Declarada neste pacote, ela tem um nome
 * que os dois lados escrevem, e a camada web de vendas a responde com 404.
 *
 * <p>Um id de outra conta é indistinguível de um id que nunca existiu: a sessão de outra conta não
 * volta do banco para quem pergunta (RNF05), e esta exceção não diz qual dos dois casos aconteceu.
 */
public class SessaoCaixaNaoEncontradaParaVendaException extends RuntimeException {

    public SessaoCaixaNaoEncontradaParaVendaException(UUID sessaoCaixaId) {
        super("sessao de caixa nao encontrada nesta conta: " + sessaoCaixaId);
    }
}
