package br.com.caixasimples.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Recusa com 413 o corpo de requisição maior que o limite da rota, antes que alguém o leia.
 *
 * <p>Sem limite, o pedido inteiro viraria objeto na memória antes de qualquer recusa: a árvore de
 * um JSON ocupa mais de dez vezes o texto, e um só pedido anônimo grande esgotaria o heap. O login
 * e o aviso de pagamento Pix são públicos e levam pouco, então têm limite pequeno. O resto, o lote
 * de sincronização incluído, aceita até 512 KB, quatro vezes o maior lote que os formulários do
 * aplicativo produzem.
 *
 * <p>O pedido que declara o tamanho é recusado pelo cabeçalho, sem ler nada. O que chega em partes,
 * sem tamanho declarado, não é lido aqui: ler adiantado custaria o limite inteiro até no pedido que
 * a falta de token recusaria. O corpo é só contado enquanto quem consome lê, e a leitura que passa
 * do limite lança {@link CorpoGrandeDemaisException}, que o tratador de erros transforma no mesmo
 * 413.
 *
 * <p>Roda antes de todo outro filtro, inclusive da cadeia de segurança: a recusa pelo cabeçalho não
 * custa nada, e nenhum filtro depois deste alcança mais que o limite. O proxy da máquina corta em
 * 1 MiB, o dobro do maior limite daqui, a transferência que a aplicação ainda não recusou; abaixo
 * disso, quem responde é esta classe, em português.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class LimiteDoCorpoFilter extends OncePerRequestFilter {

    /** O pedido de login mais longo que o formulário produz tem menos de 1 KB. */
    static final int LIMITE_DO_LOGIN = 4 * 1024;

    /** Um aviso com cem pagamentos, cada campo no máximo documentado, tem cerca de 100 KB. */
    static final int LIMITE_DO_AVISO_PIX = 256 * 1024;

    static final int LIMITE_PADRAO = 512 * 1024;

    private final ObjectMapper json;

    LimiteDoCorpoFilter(ObjectMapper json) {
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest requisicao, HttpServletResponse resposta,
            FilterChain corrente) throws ServletException, IOException {

        int limite = limiteDaRota(requisicao);
        long declarado = requisicao.getContentLengthLong();

        if (declarado > limite) {
            resposta.setStatus(HttpStatus.CONTENT_TOO_LARGE.value());
            resposta.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            json.writeValue(resposta.getOutputStream(), recusa(limite));
            return;
        }
        if (declarado < 0) {
            corrente.doFilter(new CorpoContado(requisicao, limite), resposta);
            return;
        }
        corrente.doFilter(requisicao, resposta);
    }

    /**
     * O caminho é o que o servidor já decodificou e normalizou para escolher o controller: escrito de
     * outro jeito, com uma letra codificada ou a barra repetida, o login continua com o limite do
     * login. A comparação pelo começo deixa qualquer variação com sufixo no limite menor.
     */
    static int limiteDaRota(HttpServletRequest requisicao) {
        String caminho = requisicao.getServletPath()
                + (requisicao.getPathInfo() == null ? "" : requisicao.getPathInfo());
        if (caminho.startsWith("/api/auth/login")) {
            return LIMITE_DO_LOGIN;
        }
        if (caminho.startsWith("/api/webhooks/pix")) {
            return LIMITE_DO_AVISO_PIX;
        }
        return LIMITE_PADRAO;
    }

    /** A mesma resposta para o tamanho declarado e para o contado na leitura. */
    static ProblemDetail recusa(int limite) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE,
                "O pedido tem mais de " + limite / 1024 + " KB, o limite desta rota.");
    }

    /**
     * O pedido sem tamanho declarado, com o corpo contado a cada leitura.
     *
     * <p>O fluxo contado só nasce na primeira leitura, como o original: abrir o fluxo antes mudaria o
     * que o servidor faz com o pedido. O leitor de texto também passa pela contagem, senão seria um
     * caminho até o corpo sem limite.
     */
    private static final class CorpoContado extends HttpServletRequestWrapper {

        private final int limite;
        private ServletInputStream contado;

        CorpoContado(HttpServletRequest requisicao, int limite) {
            super(requisicao);
            this.limite = limite;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (contado == null) {
                contado = new FluxoContado(super.getInputStream(), limite);
            }
            return contado;
        }

        /** Sem codificação declarada, vale a do padrão da especificação de servlets. */
        @Override
        public BufferedReader getReader() throws IOException {
            Charset codificacao = getCharacterEncoding() == null
                    ? StandardCharsets.ISO_8859_1
                    : Charset.forName(getCharacterEncoding());
            return new BufferedReader(new InputStreamReader(getInputStream(), codificacao));
        }
    }

    /**
     * Conta os bytes lidos e recusa a leitura que passa do limite. Quem consome fica com no máximo o
     * limite mais um bloco de leitura na memória.
     */
    private static final class FluxoContado extends ServletInputStream {

        private final ServletInputStream original;
        private final int limite;
        private long lidos;

        FluxoContado(ServletInputStream original, int limite) {
            this.original = original;
            this.limite = limite;
        }

        @Override
        public int read() throws IOException {
            int lido = original.read();
            if (lido >= 0) {
                contar(1);
            }
            return lido;
        }

        @Override
        public int read(byte[] destino, int inicio, int tamanho) throws IOException {
            int lidosAgora = original.read(destino, inicio, tamanho);
            if (lidosAgora > 0) {
                contar(lidosAgora);
            }
            return lidosAgora;
        }

        private void contar(int bytes) {
            lidos += bytes;
            if (lidos > limite) {
                throw new CorpoGrandeDemaisException(limite);
            }
        }

        @Override
        public boolean isFinished() {
            return original.isFinished();
        }

        @Override
        public boolean isReady() {
            return original.isReady();
        }

        @Override
        public void setReadListener(ReadListener ouvinte) {
            original.setReadListener(ouvinte);
        }

        @Override
        public void close() throws IOException {
            original.close();
        }
    }
}
