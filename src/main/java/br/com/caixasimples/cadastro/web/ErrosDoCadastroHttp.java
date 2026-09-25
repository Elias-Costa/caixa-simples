package br.com.caixasimples.cadastro.web;

import br.com.caixasimples.cadastro.application.ProdutoNaoEncontradoException;
import br.com.caixasimples.cadastro.internal.ClienteService.ClienteNaoEncontradoException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Erros que só o cadastro conhece; o contrato transversal de Problem Details fica em shared.
 *
 * <p>Precedência máxima porque o tratador transversal responde a qualquer exceção, e entre os
 * advices o primeiro que sabe tratar a exceção é o que responde.
 */
@RestControllerAdvice(basePackageClasses = ProdutoController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class ErrosDoCadastroHttp {

    @ExceptionHandler({ProdutoNaoEncontradoException.class, ClienteNaoEncontradoException.class})
    ProblemDetail naoEncontrado(RuntimeException excecao) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, excecao.getMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail conflitoDeCadastro(DataIntegrityViolationException excecao) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "O cadastro conflita com um registro existente nesta conta");
    }
}
