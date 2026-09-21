package br.com.caixasimples.relatorios.application;

import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.pagamentos.StatusPagamento;
import br.com.caixasimples.relatorios.internal.TotaisDeVendas;
import br.com.caixasimples.relatorios.internal.VendaParaRelatorioRepository;
import br.com.caixasimples.shared.FusoDeReferencia;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.UsuarioContext;
import br.com.caixasimples.vendas.StatusVenda;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Faturamento do dia e de um período (RF21): quanto entrou e em quantas vendas; e, a pedido, só o
 * que entrou por uma forma de pagamento, ou pelas mãos de um operador, ou os dois (RF24).
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
 * <p><strong>Por forma de pagamento, o que se soma é a parcela, e não a venda.</strong> Uma venda
 * paga metade em dinheiro e metade em Pix se reparte entre as duas formas pelo valor de cada
 * parcela, e conta como uma venda em cada; assim dinheiro mais Pix mais cartão dá o faturamento
 * sem filtro, e não mais. Só a parcela CONFIRMADO entra: PENDENTE ainda não entrou, RECUSADO nunca
 * entrou. Por operador, contam as vendas que ele fez, seja qual for o caixa em que as fez.
 *
 * <p><strong>É aqui que o fuso de referência entra pela segunda vez.</strong> A coluna está
 * gravada em UTC e o dia que o dono do negócio quer dizer é o do balcão; sem a conversão, toda
 * venda concluída depois das 21h cairia no dia seguinte. A constante é uma só no sistema, em
 * {@link FusoDeReferencia}, e o histórico do caixa usa a mesma.
 *
 * <p>O módulo só lê: o repositório que responde nem tem método de escrita. E a conta vem sempre
 * do contexto, nunca de parâmetro (RNF05), então nenhum método recebe conta.
 *
 * <p><strong>É relatório do administrador (RF30)</strong>, inclusive quando filtrado por um
 * operador: o operador não lê faturamento, nem o próprio. A pergunta é feita uma vez, no método
 * em que todas as sobrecargas desembocam.
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
     * O faturamento de um dia do balcão, do primeiro ao último instante dele, sem filtro além do
     * dia.
     *
     * @param dia o dia local do balcão, obrigatório
     */
    @Transactional(readOnly = true)
    public Faturamento doDia(LocalDate dia) {
        return doDia(dia, Filtros.nenhum());
    }

    /**
     * O faturamento de um dia do balcão, do primeiro ao último instante dele, com os filtros
     * pedidos.
     *
     * @param dia     o dia local do balcão, obrigatório
     * @param filtros obrigatório; {@link Filtros#nenhum()} para o dia inteiro
     */
    @Transactional(readOnly = true)
    public Faturamento doDia(LocalDate dia, Filtros filtros) {
        Objects.requireNonNull(dia, "dia nao pode ser nulo");
        return doPeriodo(dia, dia, filtros);
    }

    /**
     * O faturamento de um período de dias do balcão, <strong>com os dois extremos
     * incluídos</strong>: de {@code inicio} ao fim do dia {@code fim}, sem filtro além do período.
     *
     * @param inicio o primeiro dia, obrigatório
     * @param fim    o último dia, obrigatório; pode ser o mesmo que o primeiro
     * @throws IllegalArgumentException se {@code fim} vem antes de {@code inicio}
     */
    @Transactional(readOnly = true)
    public Faturamento doPeriodo(LocalDate inicio, LocalDate fim) {
        return doPeriodo(inicio, fim, Filtros.nenhum());
    }

    /**
     * O faturamento de um período de dias do balcão, <strong>com os dois extremos
     * incluídos</strong>, com os filtros pedidos.
     *
     * <p>Sem limite de tamanho do período: a consulta é uma agregação e devolve uma linha, seja de
     * um dia ou de um ano. A validação do período e a conversão dos dois dias para o fuso do
     * balcão são as mesmas dos outros relatórios, em {@link Periodo}.
     *
     * <p>Com forma de pagamento, a consulta é outra: parte das parcelas, e não das vendas, porque é
     * a parcela que tem forma. As duas consultas contam as mesmas vendas, e a resposta tem a mesma
     * forma.
     *
     * @param inicio  o primeiro dia, obrigatório
     * @param fim     o último dia, obrigatório; pode ser o mesmo que o primeiro
     * @param filtros obrigatório; {@link Filtros#nenhum()} para o período inteiro
     * @throws IllegalArgumentException se {@code fim} vem antes de {@code inicio}
     */
    @Transactional(readOnly = true)
    public Faturamento doPeriodo(LocalDate inicio, LocalDate fim, Filtros filtros) {
        UsuarioContext.exigirAdmin();
        Periodo periodo = new Periodo(inicio, fim);
        Objects.requireNonNull(filtros, "filtros nao podem ser nulos; sem filtro, use Filtros.nenhum()");

        TotaisDeVendas totais;
        if (filtros.forma() == null) {
            totais = vendas.totaisEntre(StatusVenda.CONCLUIDA,
                    periodo.inicioInclusivo(), periodo.fimExclusivo(), filtros.operadorId());
        } else {
            totais = vendas.totaisPorFormaEntre(StatusVenda.CONCLUIDA, filtros.forma(),
                    StatusPagamento.CONFIRMADO,
                    periodo.inicioInclusivo(), periodo.fimExclusivo(), filtros.operadorId());
        }

        // A soma de zero linhas é nula em SQL; para quem lê o relatório, um dia sem venda é zero.
        Money total = totais.total() == null ? Money.ZERO : Money.de(totais.total());
        return new Faturamento(inicio, fim, total, totais.quantidade());
    }

    /**
     * Os filtros do faturamento além do período (RF24), que se combinam: só uma forma de
     * pagamento, só um operador, ou os dois ao mesmo tempo.
     *
     * <p><strong>Componente nulo é filtro ausente.</strong> É um record com dois campos que
     * aceitam nulo, em vez de uma assinatura por combinação, porque duas combinações hoje seriam
     * quatro assinaturas iguais, e as fábricas estáticas dizem em uma palavra o que cada chamada
     * quer. A resposta não repete os filtros: quem os passou os tem.
     *
     * <p>Operador que não existe, ou que é de outra conta, dá zero, e não erro: o filtro de conta
     * do mapeamento não devolve venda de operador de fora, e este módulo não pergunta ao módulo de
     * contas quem existe.
     *
     * @param forma      a forma de pagamento cujas parcelas contam; nula para todas
     * @param operadorId o operador cujas vendas contam; nulo para todos
     */
    public record Filtros(FormaPagamento forma, UUID operadorId) {

        /** Sem filtro além do período: o faturamento inteiro. */
        public static Filtros nenhum() {
            return new Filtros(null, null);
        }

        /** Só o que entrou por uma forma de pagamento, de todos os operadores. */
        public static Filtros porForma(FormaPagamento forma) {
            Objects.requireNonNull(forma, "forma de pagamento nao pode ser nula");
            return new Filtros(forma, null);
        }

        /** Só o que um operador vendeu, em todas as formas. */
        public static Filtros porOperador(UUID operadorId) {
            Objects.requireNonNull(operadorId, "operadorId nao pode ser nulo");
            return new Filtros(null, operadorId);
        }
    }

    /**
     * A resposta dos casos de uso. Aninhado no serviço, como
     * {@code SessaoCaixaService.ResumoDeSessao}, porque é o formato de resposta deste caso de uso
     * e de mais nenhum.
     *
     * <p>Devolve o período pedido junto com os números, para a tela não precisar guardá-lo à
     * parte: no faturamento de um dia, {@code inicio} e {@code fim} são o mesmo dia.
     *
     * @param inicio             o primeiro dia do período, no fuso do balcão
     * @param fim                o último dia do período, incluído
     * @param total              a soma do que o cliente pagou nas vendas concluídas; com filtro
     *                           por forma, a soma das parcelas dessa forma; zero quando não houve
     *                           nenhuma
     * @param quantidadeDeVendas quantas vendas concluídas entraram na soma; com filtro por forma,
     *                           quantas tiveram ao menos uma parcela nela
     */
    public record Faturamento(LocalDate inicio, LocalDate fim, Money total,
            long quantidadeDeVendas) {
    }
}
