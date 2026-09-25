package br.com.caixasimples.caixa.internal;

import br.com.caixasimples.caixa.application.OperadorJaTemCaixaAbertoException;
import br.com.caixasimples.caixa.application.SessaoCaixaNaoEncontradaException;
import br.com.caixasimples.caixa.application.SessaoCaixaService;
import br.com.caixasimples.sincronizacao.Aplicacao;
import br.com.caixasimples.sincronizacao.AplicadorDeOperacoes;
import br.com.caixasimples.sincronizacao.OperacaoRecebida;
import br.com.caixasimples.sincronizacao.OperacaoRecusadaException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Os gestos da SessaoCaixa que o dispositivo registrou sem rede, aplicados pelos casos de uso do
 * caixa.
 *
 * <p>Cada gesto é o caso de uso com rede de mesmo nome, com o instante do balcão: a abertura com o
 * id que o dispositivo gerou, a sangria e o suprimento com o motivo obrigatório, o fechamento com
 * o valor contado. As regras são as da raiz e do serviço de sempre, inclusive uma sessão aberta
 * por operador e a sangria que não passa do esperado.
 *
 * <p>São fatos, e não campos a sobrescrever: a versão que o dispositivo leu não decide nada. O fato
 * entra se a raiz aceita, contra o estado que o servidor tem ao recebê-lo, e o fechamento calcula a
 * diferença com os movimentos que o servidor conhece, inclusive os que o dispositivo não viu.
 */
@Component
class GestosDoCaixa implements AplicadorDeOperacoes {

    private final SessaoCaixaService sessoes;
    private final SessaoCaixaRepository linhas;
    private final ObjectMapper json;

    GestosDoCaixa(SessaoCaixaService sessoes, SessaoCaixaRepository linhas, ObjectMapper json) {
        this.sessoes = sessoes;
        this.linhas = linhas;
        this.json = json;
    }

    @Override
    public Set<String> tipos() {
        return Set.of("caixa.abrir", "caixa.sangrar", "caixa.suprir", "caixa.fechar");
    }

    @Override
    public Aplicacao aplicar(OperacaoRecebida operacao) {
        UUID id = operacao.registroId();
        try {
            switch (operacao.tipo()) {
                case "caixa.abrir" -> {
                    Abertura abertura = operacao.payloadComo(json, Abertura.class);
                    sessoes.abrir(id, operacao.dinheiro(abertura.valorAbertura(), "valorAbertura"),
                            operacao.exigir(abertura.abertaEm(), "abertaEm"));
                }
                case "caixa.sangrar" -> {
                    Movimento sangria = operacao.payloadComo(json, Movimento.class);
                    sessoes.registrarSangria(id, operacao.dinheiro(sangria.valor(), "valor"),
                            sangria.motivo(), operacao.exigir(sangria.criadoEm(), "criadoEm"));
                }
                case "caixa.suprir" -> {
                    Movimento suprimento = operacao.payloadComo(json, Movimento.class);
                    sessoes.registrarSuprimento(id, operacao.dinheiro(suprimento.valor(), "valor"),
                            suprimento.motivo(),
                            operacao.exigir(suprimento.criadoEm(), "criadoEm"));
                }
                case "caixa.fechar" -> {
                    Fechamento fechamento = operacao.payloadComo(json, Fechamento.class);
                    sessoes.fechar(id,
                            operacao.dinheiro(fechamento.valorContado(), "valorContado"),
                            operacao.exigir(fechamento.fechadaEm(), "fechadaEm"));
                }
                default -> throw new OperacaoRecusadaException(
                        "gesto de caixa desconhecido: " + operacao.tipo());
            }
        } catch (SessaoCaixaNaoEncontradaException | OperadorJaTemCaixaAbertoException recusa) {
            throw new OperacaoRecusadaException(recusa.getMessage(), recusa);
        }

        // A revisão só muda quando a alteração vai ao banco; sem enviar o pendente antes, a
        // leitura devolveria a revisão de antes do gesto.
        linhas.flush();
        return Aplicacao.aplicada(linhas.findById(id).orElseThrow().getVersao());
    }

    /** O conteúdo de {@code caixa.abrir}, como o dispositivo o grava. */
    record Abertura(BigDecimal valorAbertura, Instant abertaEm) {
    }

    /** O conteúdo de {@code caixa.sangrar} e de {@code caixa.suprir}. */
    record Movimento(BigDecimal valor, String motivo, Instant criadoEm) {
    }

    /** O conteúdo de {@code caixa.fechar}. */
    record Fechamento(BigDecimal valorContado, Instant fechadaEm) {
    }
}
