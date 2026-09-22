package br.com.caixasimples.caixa.web;

import br.com.caixasimples.caixa.application.OperadorJaTemCaixaAbertoException;
import br.com.caixasimples.caixa.application.SessaoCaixaNaoEncontradaException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Erros próprios do caixa, traduzidos no módulo que conhece a causa. */
@RestControllerAdvice(basePackageClasses = SessaoCaixaController.class)
class ErrosDoCaixaHttp {

    @ExceptionHandler(SessaoCaixaNaoEncontradaException.class)
    ProblemDetail naoEncontrada(SessaoCaixaNaoEncontradaException excecao) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, excecao.getMessage());
    }

    @ExceptionHandler(OperadorJaTemCaixaAbertoException.class)
    ProblemDetail caixaJaAberto(OperadorJaTemCaixaAbertoException excecao) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, excecao.getMessage());
    }

    /** O índice também protege duas aberturas simultâneas que passaram pela consulta prévia. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail conflitoDeCaixa(DataIntegrityViolationException excecao) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "O caixa conflita com uma sessão existente nesta conta");
    }
}
