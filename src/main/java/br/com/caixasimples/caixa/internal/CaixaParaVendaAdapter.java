package br.com.caixasimples.caixa.internal;

import br.com.caixasimples.caixa.StatusSessaoCaixa;
import br.com.caixasimples.caixa.application.SessaoCaixaService;
import br.com.caixasimples.vendas.CaixaParaVenda;
import java.util.UUID;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * A resposta do caixa à pergunta que a venda faz: esta sessão está aberta?
 *
 * <p>É o caixa quem implementa a interface declarada em vendas, e não o contrário, porque a
 * dependência entre os dois módulos só pode ter um sentido: o caixa ouve o evento de venda
 * concluída, logo já depende de vendas; se vendas chamasse um caso de uso daqui, a verificação de
 * fronteiras acusaria ciclo. Fica em {@code internal} porque nenhum outro módulo precisa nomear
 * esta classe: vendas fala com a interface, e o Spring injeta a implementação.
 *
 * <p>Só traduz a pergunta. A regra de que venda só começa e só conclui em caixa aberto é da
 * venda, e mora lá.
 */
@Component
class CaixaParaVendaAdapter implements CaixaParaVenda {

    private final SessaoCaixaService sessoes;
    private final SessaoCaixaRepository linhas;

    CaixaParaVendaAdapter(SessaoCaixaService sessoes, SessaoCaixaRepository linhas) {
        this.sessoes = sessoes;
        this.linhas = linhas;
    }

    /**
     * @throws br.com.caixasimples.caixa.application.SessaoCaixaNaoEncontradaException se a sessão
     *         não existe nesta conta; atravessa sem tradução, como a interface anuncia
     */
    @Override
    public boolean estaAberto(UUID sessaoCaixaId) {
        return sessoes.consultar(sessaoCaixaId).status() == StatusSessaoCaixa.ABERTA;
    }

    @Override
    public Optional<UUID> sessaoAbertaDoOperadorAtual() {
        return sessoes.abertaDoOperadorAtual().map(SessaoCaixaService.ResumoDeSessao::id);
    }

    @Override
    public boolean estaAbertoParaConfirmacaoPix(UUID sessaoCaixaId) {
        return linhas.findLinhaById(sessaoCaixaId)
                .map(linha -> linha.status() == StatusSessaoCaixa.ABERTA)
                .orElse(false);
    }
}
