package br.com.caixasimples.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Toda resposta 5xx deixa no log uma linha em ERROR com o status, o método e o caminho, e o alerta de
 * erros da máquina conta as linhas em ERROR. O erro inesperado já sai com a pilha pelo tratador da
 * API, mas a indisponibilidade, como a do Pix ou a do registro de remoções, responde 503 sem linha
 * nenhuma, e só o cliente ficaria sabendo dela.
 *
 * <p>Sem Conta nem origem, como a linha do erro inesperado, porque a falha não é evento de segurança;
 * e sem a query, que pode trazer o termo de uma busca. O caminho chega como o cliente o mandou, ainda
 * codificado, então não tem espaço nem quebra de linha para forjar outra linha.
 *
 * <p>A saúde da instância fica de fora: ela tem o sinal de vida da máquina, e o 503 dela durante a
 * troca da aplicação viraria um alerta a cada deploy.
 *
 * <p>Roda por fora dos outros filtros, para ver o status final de qualquer rota. A exceção que escapa
 * de tudo não passa por aqui com status de erro, mas o servidor de aplicação já a registra em ERROR.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
class RespostaComErroFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RespostaComErroFilter.class);

    /** O caminho já decodificado e normalizado, o mesmo que escolhe a rota. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest requisicao) {
        String caminho = requisicao.getServletPath()
                + (requisicao.getPathInfo() == null ? "" : requisicao.getPathInfo());
        return caminho.startsWith("/actuator/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest requisicao, HttpServletResponse resposta,
            FilterChain corrente) throws ServletException, IOException {
        corrente.doFilter(requisicao, resposta);
        if (resposta.getStatus() >= 500) {
            log.error("Resposta {} em {} {}", resposta.getStatus(), requisicao.getMethod(),
                    requisicao.getRequestURI());
        }
    }
}
