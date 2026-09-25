package br.com.caixasimples.vendas.web;

import br.com.caixasimples.cadastro.application.ConsultaDeClienteParaVenda.ClienteNaoEncontradoParaVendaException;
import br.com.caixasimples.cadastro.application.ProdutoNaoEncontradoException;
import br.com.caixasimples.vendas.SessaoCaixaNaoEncontradaParaVendaException;
import br.com.caixasimples.vendas.application.VendaNaoEncontradaException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 404 das rotas de vendas: a própria Venda e o que o pedido referencia, a sessão de caixa, o
 * produto e o cliente.
 *
 * <p>Só a primeira exceção nasce em vendas, e as outras viram 404 aqui porque é a rota de vendas
 * que as recebe: o tratador do cadastro vale só para os controllers do cadastro, e a sessão
 * inexistente chega pela exceção que vendas declara para a pergunta ao caixa. Fora desta lista,
 * cairiam no tratador transversal como erro inesperado, 500 sem detalhe.
 *
 * <p>Precedência máxima porque o tratador transversal responde a qualquer exceção, e entre os
 * advices o primeiro que sabe tratar a exceção é o que responde.
 */
@RestControllerAdvice(basePackageClasses = VendaController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class VendaNaoEncontradaHandler {

    @ExceptionHandler({VendaNaoEncontradaException.class,
            SessaoCaixaNaoEncontradaParaVendaException.class,
            ProdutoNaoEncontradoException.class,
            ClienteNaoEncontradoParaVendaException.class})
    ProblemDetail naoEncontrada(RuntimeException excecao) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, excecao.getMessage());
    }
}
