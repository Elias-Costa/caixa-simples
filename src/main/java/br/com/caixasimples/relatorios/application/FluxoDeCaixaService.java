package br.com.caixasimples.relatorios.application;

import br.com.caixasimples.caixa.TipoMovimentoCaixa;
import br.com.caixasimples.relatorios.internal.MovimentoCaixaParaRelatorioRepository;
import br.com.caixasimples.relatorios.internal.TotalPorTipoDeMovimento;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.UsuarioContext;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * O fluxo de caixa de um período: entradas, saídas e saldo (RF23).
 *
 * <p><strong>É o fluxo da gaveta.</strong> O caixa só registra dinheiro em espécie: Pix e cartão
 * nunca estiveram na gaveta e não geram movimento. Então entrada aqui é o dinheiro de venda que
 * entrou e o suprimento que reforçou o troco; saída é a sangria e o estorno de uma venda
 * cancelada; e saldo é a diferença. O que entrou por Pix e cartão está no faturamento, que é o
 * relatório do que foi vendido, e não do que passou pela gaveta.
 *
 * <p><strong>A regra de sinal é a do próprio caixa</strong>: VENDA e SUPRIMENTO somam, SANGRIA e
 * ESTORNO subtraem, exatamente como a sessão calcula o seu esperado de fechamento. O switch que a
 * escreve aqui é exaustivo de propósito, como o de lá: um tipo novo no enum vira erro de
 * compilação neste serviço, em vez de cair fora das entradas e das saídas em silêncio. Os quatro
 * totais saem separados na resposta, para a tela mostrar o que quiser sem uma segunda pergunta.
 *
 * <p><strong>O valor de abertura não entra.</strong> Não é movimento: é o troco que já estava na
 * gaveta quando o expediente começou. O saldo deste relatório é quanto o período moveu, não
 * quanto há na gaveta; quem responde isso é o fechamento de cada sessão.
 *
 * <p><strong>O dia é o do lançamento do movimento</strong>, no fuso do balcão, e não o dia da
 * sessão. O histórico do caixa agrupa a sessão inteira pelo dia da abertura, porque uma sessão é
 * um expediente; o fluxo de caixa é um dia do calendário, como o faturamento, e uma sessão que
 * atravessa a meia-noite reparte os movimentos entre os dois dias.
 *
 * <p>O módulo só lê, e a conta vem sempre do contexto, nunca de parâmetro (RNF05).
 *
 * <p><strong>É relatório do administrador (RF30):</strong> o fluxo é o da conta inteira, e o
 * operador não o lê.
 */
@Service
public class FluxoDeCaixaService {

    private final MovimentoCaixaParaRelatorioRepository movimentos;

    FluxoDeCaixaService(MovimentoCaixaParaRelatorioRepository movimentos) {
        this.movimentos = movimentos;
    }

    /**
     * O fluxo de caixa de um período de dias do balcão, <strong>com os dois extremos
     * incluídos</strong>.
     *
     * @param inicio o primeiro dia, obrigatório
     * @param fim    o último dia, obrigatório; pode ser o mesmo que o primeiro
     * @throws br.com.caixasimples.shared.AcessoNegadoException se quem chama não é ADMIN
     * @throws IllegalArgumentException se {@code fim} vem antes de {@code inicio}
     */
    @Transactional(readOnly = true)
    public FluxoDeCaixa doPeriodo(LocalDate inicio, LocalDate fim) {
        UsuarioContext.exigirAdmin();
        Periodo periodo = new Periodo(inicio, fim);

        List<TotalPorTipoDeMovimento> linhas = movimentos.totaisPorTipoEntre(
                periodo.inicioInclusivo(), periodo.fimExclusivo());

        // A consulta devolve só os tipos que tiveram movimento; para quem lê o relatório, tipo
        // sem movimento é zero.
        Map<TipoMovimentoCaixa, Money> porTipo = new EnumMap<>(TipoMovimentoCaixa.class);
        for (TipoMovimentoCaixa tipo : TipoMovimentoCaixa.values()) {
            porTipo.put(tipo, Money.ZERO);
        }
        for (TotalPorTipoDeMovimento linha : linhas) {
            porTipo.put(linha.tipo(), Money.de(linha.total()));
        }

        Money entradas = Money.ZERO;
        Money saidas = Money.ZERO;
        for (Map.Entry<TipoMovimentoCaixa, Money> total : porTipo.entrySet()) {
            // Exaustivo de propósito, espelhando o switch de SessaoCaixa: acrescentar um valor em
            // TipoMovimentoCaixa quebra a compilação aqui, e não a soma.
            boolean entra = switch (total.getKey()) {
                case VENDA, SUPRIMENTO -> true;
                case SANGRIA, ESTORNO -> false;
            };
            if (entra) {
                entradas = entradas.somar(total.getValue());
            } else {
                saidas = saidas.somar(total.getValue());
            }
        }

        return new FluxoDeCaixa(inicio, fim,
                porTipo.get(TipoMovimentoCaixa.VENDA), porTipo.get(TipoMovimentoCaixa.SUPRIMENTO),
                porTipo.get(TipoMovimentoCaixa.SANGRIA), porTipo.get(TipoMovimentoCaixa.ESTORNO),
                entradas, saidas, entradas.subtrair(saidas));
    }

    /**
     * A resposta do caso de uso. Aninhada no serviço, como {@code FaturamentoService.Faturamento},
     * por ser o formato de resposta deste caso de uso e de mais nenhum.
     *
     * <p>Os quatro totais por tipo e os três consolidados saem juntos: os consolidados são o que
     * o requisito pede, e os quatro são o que a tela precisa para explicar de onde eles vieram.
     * Todos zero, nunca nulos, quando o período não teve movimento.
     *
     * @param inicio      o primeiro dia do período, no fuso do balcão
     * @param fim         o último dia do período, incluído
     * @param vendas      o dinheiro em espécie que entrou por vendas concluídas
     * @param suprimentos o reforço de troco que entrou
     * @param sangrias    o dinheiro retirado da gaveta
     * @param estornos    o dinheiro devolvido por vendas canceladas
     * @param entradas    vendas mais suprimentos
     * @param saidas      sangrias mais estornos
     * @param saldo       entradas menos saídas; pode ser negativo, num período em que saiu mais
     *                    do que entrou
     */
    public record FluxoDeCaixa(LocalDate inicio, LocalDate fim, Money vendas, Money suprimentos,
            Money sangrias, Money estornos, Money entradas, Money saidas, Money saldo) {
    }
}
