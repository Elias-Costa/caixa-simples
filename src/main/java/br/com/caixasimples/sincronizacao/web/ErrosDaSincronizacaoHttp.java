package br.com.caixasimples.sincronizacao.web;

import br.com.caixasimples.sincronizacao.application.RevisaoNaoEncontradaException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Erros próprios da sincronização, traduzidos no módulo que conhece a causa.
 *
 * <p>Precedência máxima porque o tratador transversal responde a qualquer exceção, e entre os
 * advices o primeiro que sabe tratar a exceção é o que responde.
 */
@RestControllerAdvice(basePackageClasses = RevisaoController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class ErrosDaSincronizacaoHttp {

    @ExceptionHandler(RevisaoNaoEncontradaException.class)
    ProblemDetail naoEncontrada(RevisaoNaoEncontradaException excecao) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, excecao.getMessage());
    }
}
