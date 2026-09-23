package br.com.caixasimples.vendas.web;

import br.com.caixasimples.vendas.application.VendaNaoEncontradaException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 404 da Venda pertence ao módulo que conhece essa exceção. */
@RestControllerAdvice(basePackageClasses = VendaController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class VendaNaoEncontradaHandler {

    @ExceptionHandler(VendaNaoEncontradaException.class)
    ProblemDetail naoEncontrada(VendaNaoEncontradaException excecao) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, excecao.getMessage());
    }
}
