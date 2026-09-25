package br.com.caixasimples.estoque.web;

import br.com.caixasimples.cadastro.application.ProdutoNaoEncontradoException;
import br.com.caixasimples.estoque.application.ControleDeEstoqueDesligadoException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Traduz o id de produto ausente nesta Conta sem expor dados de outra Conta.
 *
 * <p>Precedência máxima porque o tratador transversal responde a qualquer exceção, e entre os
 * advices o primeiro que sabe tratar a exceção é o que responde.
 */
@RestControllerAdvice(basePackageClasses = EstoqueController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class ErrosDoEstoqueHttp {

    @ExceptionHandler(ControleDeEstoqueDesligadoException.class)
    ProblemDetail controleDesligado(ControleDeEstoqueDesligadoException excecao) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, excecao.getMessage());
    }

    @ExceptionHandler(ProdutoNaoEncontradoException.class)
    ProblemDetail produtoNaoEncontrado(ProdutoNaoEncontradoException excecao) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, excecao.getMessage());
    }
}
