package br.com.caixasimples.relatorios.web;

import br.com.caixasimples.relatorios.application.MaisVendidosService;
import br.com.caixasimples.relatorios.application.MaisVendidosService.MaisVendidos;
import br.com.caixasimples.relatorios.application.MaisVendidosService.Posicao;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Entrada HTTP dos mais vendidos de um período (RF22), de todos os operadores ou de um só (RF24); a
 * autorização de ADMIN e o plano ficam no caso de uso.
 *
 * <p>O limite é obrigatório, como no caso de uso: um ranking tem tamanho, e é quem pergunta que diz
 * quantas posições quer. Não há filtro por forma de pagamento, porque um item não pertence a uma
 * parcela.
 */
@RestController
@RequestMapping("/api/relatorios/mais-vendidos")
class MaisVendidosController {

    private final MaisVendidosService maisVendidos;

    MaisVendidosController(MaisVendidosService maisVendidos) {
        this.maisVendidos = maisVendidos;
    }

    @GetMapping
    MaisVendidosNaResposta doPeriodo(@RequestParam LocalDate inicio, @RequestParam LocalDate fim,
            @RequestParam int limite, @RequestParam(required = false) UUID operadorId) {
        // O caso de uso tem uma assinatura para cada pergunta; sem operador, é a da Conta inteira.
        MaisVendidos resultado;
        if (operadorId == null) {
            resultado = maisVendidos.doPeriodo(inicio, fim, limite);
        } else {
            resultado = maisVendidos.doPeriodo(inicio, fim, limite, operadorId);
        }
        return MaisVendidosNaResposta.de(resultado);
    }

    record MaisVendidosNaResposta(LocalDate inicio, LocalDate fim,
            List<PosicaoNaResposta> posicoes) {
        static MaisVendidosNaResposta de(MaisVendidos resultado) {
            return new MaisVendidosNaResposta(resultado.inicio(), resultado.fim(),
                    resultado.posicoes().stream().map(PosicaoNaResposta::de).toList());
        }
    }

    /** A unidade sai da resposta quando o cadastro não a informou. */
    record PosicaoNaResposta(UUID produtoId, String nome, String unidade, BigDecimal quantidade,
            BigDecimal valor) {
        static PosicaoNaResposta de(Posicao posicao) {
            return new PosicaoNaResposta(posicao.produtoId(), posicao.nome(), posicao.unidade(),
                    posicao.quantidade(), posicao.valor().valor());
        }
    }
}
