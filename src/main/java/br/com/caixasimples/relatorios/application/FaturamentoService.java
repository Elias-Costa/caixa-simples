package br.com.caixasimples.relatorios.application;

import br.com.caixasimples.relatorios.internal.TotaisDeVendas;
import br.com.caixasimples.relatorios.internal.VendaParaRelatorioRepository;
import br.com.caixasimples.shared.FusoDeReferencia;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.vendas.StatusVenda;
import java.time.LocalDate;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Faturamento do dia e de um período (RF21): quanto entrou e em quantas vendas.
 *
 * <p><strong>O dia de uma venda é o da conclusão</strong>, o instante em que os pagamentos
 * fecharam a conta, e não o da abertura da comanda. É quando o dinheiro entrou, é o único instante
 * que toda venda concluída tem, é a data do comprovante, e não muda com o cancelamento. Uma
 * comanda aberta às 23h50 e paga às 00h10 conta no dia seguinte. O caixa delimita a sessão pela
 * abertura, e as duas escolhas convivem: a sessão é um expediente, que pode atravessar a
 * meia-noite; o faturamento é um dia do calendário.
 *
 * <p><strong>Contam só as vendas CONCLUIDA no momento da consulta.</strong> A comanda ABERTA ainda
 * não é dinheiro que entrou. A venda CANCELADA sai do faturamento do dia em que tinha sido
 * concluída, o que espelha o caixa, onde a entrada da venda e o estorno do cancelamento se
 * anulam; e como uma venda concluída só cancela com a sessão em que nasceu ainda aberta, o
 * cancelamento acontece no mesmo expediente, não semanas depois. Não há bruto e líquido: um
 * número só.
 *
 * <p><strong>É aqui que o fuso de referência entra pela segunda vez.</strong> A coluna está
 * gravada em UTC e o dia que o dono do negócio quer dizer é o do balcão; sem a conversão, toda
 * venda concluída depois das 21h cairia no dia seguinte. A constante é uma só no sistema, em
 * {@link FusoDeReferencia}, e o histórico do caixa usa a mesma.
 *
 * <p>O módulo só lê: o repositório que responde nem tem método de escrita. E a conta vem sempre
 * do contexto, nunca de parâmetro (RNF05), então nenhum método recebe conta.
 *
 * <p>O pacote não é exposto aos outros módulos: quem chama daqui é a camada web deste mesmo
 * módulo, quando nascer. Ninguém depende de relatórios.
 */
@Service
public class FaturamentoService {

    private final VendaParaRelatorioRepository vendas;

    FaturamentoService(VendaParaRelatorioRepository vendas) {
        this.vendas = vendas;
    }

    /**
     * O faturamento de um dia do balcão, do primeiro ao último instante dele.
     *
     * @param dia o dia local do balcão, obrigatório
     */
    @Transactional(readOnly = true)
    public Faturamento doDia(LocalDate dia) {
        Objects.requireNonNull(dia, "dia nao pode ser nulo");
        return doPeriodo(dia, dia);
    }

    /**
     * O faturamento de um período de dias do balcão, <strong>com os dois extremos
     * incluídos</strong>: de {@code inicio} ao fim do dia {@code fim}.
     *
     * <p>Sem limite de tamanho do período: a consulta é uma agregação e devolve uma linha, seja de
     * um dia ou de um ano. A validação do período e a conversão dos dois dias para o fuso do
     * balcão são as mesmas dos outros relatórios, em {@link Periodo}.
     *
     * @param inicio o primeiro dia, obrigatório
     * @param fim    o último dia, obrigatório; pode ser o mesmo que o primeiro
     * @throws IllegalArgumentException se {@code fim} vem antes de {@code inicio}
     */
    @Transactional(readOnly = true)
    public Faturamento doPeriodo(LocalDate inicio, LocalDate fim) {
        Periodo periodo = new Periodo(inicio, fim);

        TotaisDeVendas totais = vendas.totaisEntre(StatusVenda.CONCLUIDA,
                periodo.inicioInclusivo(), periodo.fimExclusivo());

        // A soma de zero linhas é nula em SQL; para quem lê o relatório, um dia sem venda é zero.
        Money total = totais.total() == null ? Money.ZERO : Money.de(totais.total());
        return new Faturamento(inicio, fim, total, totais.quantidade());
    }

    /**
     * A resposta dos dois casos de uso. Aninhado no serviço, como
     * {@code SessaoCaixaService.ResumoDeSessao}, porque é o formato de resposta deste caso de uso
     * e de mais nenhum.
     *
     * <p>Devolve o período pedido junto com os números, para a tela não precisar guardá-lo à
     * parte: no faturamento de um dia, {@code inicio} e {@code fim} são o mesmo dia.
     *
     * @param inicio             o primeiro dia do período, no fuso do balcão
     * @param fim                o último dia do período, incluído
     * @param total              a soma do que o cliente pagou nas vendas concluídas; zero quando
     *                           não houve nenhuma
     * @param quantidadeDeVendas quantas vendas concluídas entraram na soma
     */
    public record Faturamento(LocalDate inicio, LocalDate fim, Money total,
            long quantidadeDeVendas) {
    }
}
